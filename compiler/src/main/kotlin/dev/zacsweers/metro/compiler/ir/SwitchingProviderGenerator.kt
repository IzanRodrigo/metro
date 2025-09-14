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
        // Strategy: Prefer existing provider fields over inline generation
        
        // First, check if we have a provider field for this binding
        val providerField = bindingFieldContext?.providerField(binding.typeKey)
          ?: shardFieldRegistry?.findField(binding.typeKey)?.field
        
        if (providerField != null) {
          // Found a provider field - use it and invoke to get the instance
          val owner = when {
            // Check if field is in a shard
            shardFieldRegistry != null -> {
              val shardInfo = shardFieldRegistry.findField(binding.typeKey)
              val shardIndex = shardInfo?.shardIndex
              if (shardIndex != null && shardIndex != 0) {
                // Field is in a shard, access via graph.shardN
                val shardField = graphClass.declarations
                  .filterIsInstance<IrField>()
                  .firstOrNull { it.name.asString() == "shard$shardIndex" }
                  ?: error("Missing shard field: shard$shardIndex")
                irGetField(graphExpr, shardField)
              } else {
                // Field is in main graph
                graphExpr
              }
            }
            else -> graphExpr // No sharding, use graph directly
          }
          
          // Get the provider field and invoke it
          val providerExpr = irGetField(owner, providerField)
          
          // Invoke the provider to get the instance
          // Use symbols.providerInvoke directly since that's what Metro uses
          irCall(symbols.providerInvoke).apply {
            dispatchReceiver = providerExpr
          }
        } else {
          // No provider field found - need to handle special cases or generate inline
          
          when (binding) {
            // BoundInstance and GraphDependency should never inline - they must have fields
            is IrBinding.BoundInstance -> {
              // For BoundInstance, we should have an instance field
              val instanceField = bindingFieldContext?.instanceField(binding.typeKey)
                ?: error("BoundInstance must have an instance field: ${binding.typeKey}")

              // BoundInstance fields are always on the main graph
              irGetField(graphExpr, instanceField)
            }

            is IrBinding.GraphDependency -> {
              // GraphDependency should read from the appropriate field
              if (binding.fieldAccess != null) {
                val field = bindingFieldContext?.instanceField(binding.typeKey)
                  ?: error("GraphDependency with fieldAccess must have field: ${binding.typeKey}")
                irGetField(graphExpr, field)
              } else if (binding.getter != null) {
                // Call the getter on the graph
                irCall(binding.getter).apply {
                  dispatchReceiver = graphExpr
                }
              } else {
                error("GraphDependency must have either fieldAccess or getter")
              }
            }
            
            else -> {
              // For other bindings, generate inline but with bypassProviderFor to prevent recursion
              expressionGenerator?.generateBindingCode(
                binding = binding,
                contextualTypeKey = binding.contextualTypeKey,
                accessType = IrGraphExpressionGenerator.AccessType.INSTANCE,
                fieldInitKey = null,
                bypassProviderFor = binding.typeKey  // Prevent re-routing through SwitchingProvider
              ) ?: error("ExpressionGenerator is required for inline generation")
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