// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:Suppress("DEPRECATION")

package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.sharding.ShardFieldRegistry
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.expressions.impl.IrBranchImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrStringConcatenationImpl
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.nonDispatchParameters
import org.jetbrains.kotlin.ir.util.primaryConstructor

internal class SwitchingProviderGenerator(
  private val context: IrMetroContext,
  private val bindingFieldContext: BindingFieldContext? = null,
  private val shardFieldRegistry: ShardFieldRegistry? = null,
  private val expressionGenerator: IrGraphExpressionGenerator? = null,
) : IrMetroContext by context {

  /**
   * Helper to resolve the owner of a field when it might be in a shard.
   * Returns the appropriate receiver expression (main graph or shard instance).
   */
  context(scope: IrBuilderWithScope)
  private fun resolveOwnerForShard(
    graphExpr: IrExpression,
    graphClass: IrClass,
    shardIndex: Int?
  ): IrExpression = with(scope) {
    if (shardIndex == null || shardIndex == 0) {
      // Field is in main graph
      graphExpr
    } else {
      // Field is in a shard, access via graph.shardN
      val shardFieldOnGraph = graphClass.declarations
        .filterIsInstance<IrField>()
        .firstOrNull { it.name.asString() == "shard$shardIndex" }
        ?: error("Missing shard field: shard$shardIndex")
      irGetField(graphExpr, shardFieldOnGraph)
    }
  }

  @Suppress("DEPRECATION")
  fun populateInvokeBody(
    builder: IrBuilderWithScope,
    graphClass: IrClass,
    switchingProviderClass: IrClass,
    idToBinding: List<IrBinding>, // same order as assigned IDs
    graphExpr: IrExpression,      // The graph instance from SwitchingProvider.graph field
    idExpr: IrExpression,          // The id from SwitchingProvider.id field
    returnType: IrType
  ): List<IrStatement> {
    // Build branches for when(id) expression
    val branches = mutableListOf<IrBranchImpl>()

    // Add a branch for each binding ID
    idToBinding.forEachIndexed { id, binding ->
      val bindingExpr = builder.run {
        // Strategy: Prefer existing provider fields over inline generation to avoid recursion

        // First, check bindingFieldContext for a provider field
        val providerField = bindingFieldContext?.providerField(binding.typeKey)

        if (providerField != null) {
          // Found a provider field in bindingFieldContext - use it with proper owner resolution
          // Check if this field might be in a shard
          val shardInfo = shardFieldRegistry?.findField(binding.typeKey)
          val owner = resolveOwnerForShard(graphExpr, graphClass, shardInfo?.shardIndex)

          // Get the provider field and invoke it to get the instance
          val providerExpr = irGetField(owner, providerField)

          // Invoke the provider to get the instance
          irCall(symbols.providerInvoke).apply {
            dispatchReceiver = providerExpr
          }
        } else {
          // No provider field found - need to handle special cases or generate inline

          when (binding) {
            // BoundInstance must always be read from fields, never constructed inline
            is IrBinding.BoundInstance -> {
              // Try to resolve the field in order of preference:
              // 1. Instance field (most common for BoundInstance)
              // 2. Provider field (if caller expects a provider)
              // 3. Shard registry (if sharding is active)

              val instanceField = bindingFieldContext?.instanceField(binding.typeKey)
              val providerField = bindingFieldContext?.providerField(binding.typeKey)
              val shardInfo = shardFieldRegistry?.findField(binding.typeKey)

              when {
                instanceField != null -> {
                  // Resolve the owner (could be main graph or shard)
                  val owner = resolveOwnerForShard(graphExpr, graphClass, shardInfo?.shardIndex)
                  // Return the instance directly
                  irGetField(owner, instanceField)
                }

                providerField != null -> {
                  // If we have a provider field, use it and invoke
                  val owner = resolveOwnerForShard(graphExpr, graphClass, shardInfo?.shardIndex)
                  // Get the provider and invoke it
                  val providerExpr = irGetField(owner, providerField)
                  irCall(symbols.providerInvoke).apply {
                    dispatchReceiver = providerExpr
                  }
                }

                shardInfo != null -> {
                  // Only shard registry has the field
                  val owner = resolveOwnerForShard(graphExpr, graphClass, shardInfo.shardIndex)
                  irGetField(owner, shardInfo.field)
                }

                else -> {
                  error("BoundInstance must have a field (instance or provider): ${binding.typeKey}")
                }
              }
            }

            is IrBinding.GraphDependency -> {
              // GraphDependency should read from the appropriate field or call getter
              when {
                binding.fieldAccess != null -> {
                  val field = bindingFieldContext?.instanceField(binding.typeKey)
                    ?: error("GraphDependency with fieldAccess must have field: ${binding.typeKey}")

                  // Check if field might be in a shard
                  val shardInfo = shardFieldRegistry?.findField(binding.typeKey)
                  val owner = resolveOwnerForShard(graphExpr, graphClass, shardInfo?.shardIndex)
                  irGetField(owner, field)
                }

                binding.getter != null -> {
                  // Call the getter on the graph (getters are always on main graph)
                  irCall(binding.getter).apply {
                    dispatchReceiver = graphExpr
                  }
                }

                else -> {
                  error("GraphDependency must have either fieldAccess or getter")
                }
              }
            }
            
            else -> {
              // Only allow inline generation for safe binding types
              // All other types must be resolved via fields to avoid unsupported binding errors
              val canGenerateInline = when (binding) {
                is IrBinding.ConstructorInjected -> !binding.assisted // Non-assisted only
                is IrBinding.Provided -> true
                is IrBinding.ObjectClass -> true
                else -> false // Alias, Assisted, MembersInjected, Multibinding, etc. require fields
              }

              if (canGenerateInline) {
                // Safe to generate inline with bypassProviderFor to prevent recursion
                expressionGenerator?.generateBindingCode(
                  binding = binding,
                  contextualTypeKey = binding.contextualTypeKey,
                  accessType = IrGraphExpressionGenerator.AccessType.INSTANCE,
                  fieldInitKey = null,
                  bypassProviderFor = binding.typeKey  // Prevent re-routing through SwitchingProvider
                ) ?: error("ExpressionGenerator is required for inline generation")
              } else {
                // These binding types require field resolution
                // Try to find the field in bindingFieldContext or shardFieldRegistry
                val instanceField = bindingFieldContext?.instanceField(binding.typeKey)
                val shardInfo = shardFieldRegistry?.findField(binding.typeKey)

                when {
                  instanceField != null -> {
                    // Found instance field - use it with proper owner resolution
                    val owner = resolveOwnerForShard(graphExpr, graphClass, shardInfo?.shardIndex)
                    irGetField(owner, instanceField)
                  }

                  shardInfo != null -> {
                    // Field exists in shard registry
                    val owner = resolveOwnerForShard(graphExpr, graphClass, shardInfo.shardIndex)
                    irGetField(owner, shardInfo.field)
                  }

                  else -> {
                    // No field found - this is an error for unsupported inline types
                    error("Binding type ${binding::class.simpleName} requires a field but none found: ${binding.typeKey}")
                  }
                }
              }
            }
          }
        }
      }

      branches += IrBranchImpl(
        UNDEFINED_OFFSET,
        UNDEFINED_OFFSET,
        condition = builder.irEquals(idExpr, builder.irInt(id)),
        result = bindingExpr
      )
    }

    // Default branch: throw error with the unknown id
    val defaultBranch = builder.run {
      val errorMessage = IrStringConcatenationImpl(
        UNDEFINED_OFFSET,
        UNDEFINED_OFFSET,
        symbols.irBuiltIns.stringType
      ).apply {
        arguments.add(irString("Unknown SwitchingProvider id: "))
        arguments.add(idExpr)
      }

      IrBranchImpl(
        UNDEFINED_OFFSET,
        UNDEFINED_OFFSET,
        condition = irTrue(),
        result = irInvoke(
          callee = symbols.stdlibErrorFunction,
          args = listOf(errorMessage)
        )
      )
    }
    branches += defaultBranch

    // Create and return the when expression
    val whenExpr = builder.irWhen(
      type = returnType,
      branches = branches
    )
    
    return listOf(builder.irReturn(whenExpr))
  }
}