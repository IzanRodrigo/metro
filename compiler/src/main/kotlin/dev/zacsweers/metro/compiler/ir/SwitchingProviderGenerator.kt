// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:Suppress("DEPRECATION")

package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.sharding.ShardFieldRegistry
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrBranchImpl
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
    graphClass: IrClass,
    switchingProviderClass: IrClass,
    idToBinding: List<IrBinding>, // same order as assigned IDs
    thisGraphParamName: String = "graph",
    idParamName: String = "id",
  ) {
    // Resolve fields safely at the top
    val idField = switchingProviderClass.declarations.filterIsInstance<IrField>()
      .firstOrNull { it.name.asString() == idParamName }
      ?: error("SwitchingProvider must have field: $idParamName")

    val graphField = switchingProviderClass.declarations.filterIsInstance<IrField>()
      .firstOrNull { it.name.asString() == thisGraphParamName }
      ?: error("SwitchingProvider must have field: $thisGraphParamName")

    // Find the invoke function
    val invokeFun = switchingProviderClass.functions
      .firstOrNull { it.name.asString() == "invoke" && it.nonDispatchParameters.isEmpty() }
      ?: error("SwitchingProvider must have invoke() function with no parameters")

    // Build the invoke body
    val builder = createIrBuilder(invokeFun.symbol)
    invokeFun.body = builder.irBlockBody {
      val thisParam = invokeFun.dispatchReceiverParameter
        ?: error("invoke() must have dispatch receiver")

      // Build branches for when(id) expression
      val branches = mutableListOf<IrBranchImpl>()

      // Add a branch for each binding ID
      idToBinding.forEachIndexed { id, binding ->
        // Always use inline generation to avoid provider field lookups
        // which would cause recursion (since the provider field IS this SwitchingProvider)
        val expr = expressionGenerator!!.generateBindingCode(
          binding,
          accessType = IrGraphExpressionGenerator.AccessType.INSTANCE,
          bypassProviderFor = binding.typeKey
        )

        // Create a fresh field access for each branch to avoid duplicate IR nodes
        val idFieldAccess = irGetField(irGet(thisParam), idField)

        branches += IrBranchImpl(
          UNDEFINED_OFFSET,
          UNDEFINED_OFFSET,
          condition = irEquals(idFieldAccess, irInt(id)),
          result = irReturn(expr)
        )
      }

      // Default branch: throw error with the unknown id
      // Use simpler error function instead of AssertionError
      val defaultThrow = irInvoke(
        callee = symbols.stdlibErrorFunction,
        args = listOf(irString("Unknown SwitchingProvider id"))
      )
      branches += IrBranchImpl(
        UNDEFINED_OFFSET,
        UNDEFINED_OFFSET,
        condition = irTrue(),
        result = defaultThrow
      )

      // Create when expression with all branches
      val whenExpr = irWhen(
        type = invokeFun.returnType,
        branches = branches
      )

      +irReturn(whenExpr)
    }
  }

  private fun IrBuilderWithScope.generateReturnExprViaGraph(
    builder: IrBuilderWithScope,
    switchingProviderClass: IrClass,
    graphField: IrField,
    binding: IrBinding,
    invokeFun: IrFunction,
    graphClass: IrClass
  ): IrExpression {
    // Read this.graph
    val dispatchThis = invokeFun.dispatchReceiverParameter
      ?: error("invoke() must have dispatch receiver")
    val graphGet = irGetField(irGet(dispatchThis), graphField)

    // Check if sharding is enabled and find which shard contains this binding
    val shardInfo = shardFieldRegistry?.findField(binding.typeKey)

    val providerAccess = when {
      shardInfo != null -> {
        // Field is in a shard - need to access through shard instance
        val shardIndex = shardInfo.shardIndex
        val field = shardInfo.field

        if (shardIndex == 0) {
          // Main graph - direct access
          irGetField(graphGet, field)
        } else {
          // Access through shard instance: graph.shardN.fieldProvider
          val shardField = graphGet.type.getClass()?.declarations
            ?.filterIsInstance<IrField>()
            ?.firstOrNull { shardField -> shardField.name.asString() == "shard$shardIndex" }

          if (shardField != null) {
            val shardAccess = irGetField(graphGet, shardField)
            irGetField(shardAccess, field)
          } else {
            // Shard field not found, try direct access as fallback
            irGetField(graphGet, field)
          }
        }
      }
      bindingFieldContext != null -> {
        // No sharding, use binding field context
        val field = bindingFieldContext.providerField(binding.typeKey)
        if (field != null) {
          irGetField(graphGet, field)
        } else {
          null
        }
      }
      else -> null
    }

    if (providerAccess != null) {
      // Invoke the provider to get the instance
      return irInvoke(providerAccess, callee = symbols.providerInvoke)
    }

    // Fallback: If we can't find the provider field, generate error
    return irInvoke(
      callee = symbols.stdlibErrorFunction,
      args = listOf(irString("Provider field not found for ${binding.typeKey}"))
    )
  }
}