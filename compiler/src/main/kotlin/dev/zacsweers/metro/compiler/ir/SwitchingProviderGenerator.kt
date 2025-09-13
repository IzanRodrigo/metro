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
import org.jetbrains.kotlin.ir.util.primaryConstructor

internal class SwitchingProviderGenerator(
  private val context: IrMetroContext,
  private val bindingFieldContext: BindingFieldContext? = null,
  private val shardFieldRegistry: ShardFieldRegistry? = null,
) : IrMetroContext by context {

  @Suppress("DEPRECATION")
  fun populateInvokeBody(
    graphClass: IrClass,
    switchingProviderClass: IrClass,
    idToBinding: List<IrBinding>, // same order as assigned IDs
    thisGraphParamName: String = "graph",
    idParamName: String = "id",
  ) {
    val invokeFun = switchingProviderClass.functions
      .first { it.name.asString() == "invoke" && it.valueParameters.isEmpty() }

    invokeFun.body = createIrBuilder(invokeFun.symbol).irBlockBody {
      // when(id) { case -> return <expr> ; else -> throw AssertionError(id) }
      val primaryConstructor = switchingProviderClass.primaryConstructor
        ?: error("SwitchingProvider must have a primary constructor")

      val idParam = primaryConstructor.valueParameters
        .firstOrNull { it.name.asString() == idParamName }
        ?: error("SwitchingProvider constructor must have parameter: $idParamName")

      val dispatchThis = invokeFun.dispatchReceiverParameter!!

      // Read "this.id" field
      val idField = switchingProviderClass.declarations.filterIsInstance<IrField>()
        .firstOrNull { it.name.asString() == idParamName }
        ?: error("SwitchingProvider must have field: $idParamName")
      val idGet = irGetField(irGet(dispatchThis), idField)

      val branches = mutableListOf<IrBranchImpl>()

      idToBinding.forEachIndexed { id, binding ->
        val caseBody = generateBindingReturnExpr(invokeFun, switchingProviderClass, binding, thisGraphParamName)
        branches += IrBranchImpl(
          UNDEFINED_OFFSET, UNDEFINED_OFFSET,
          condition = irEquals(idGet, irInt(id)),
          result = caseBody
        )
      }

      // default -> error("Unknown id: $id")
      val defaultThrow = irInvoke(
        callee = symbols.stdlibErrorFunction,
        args = listOf(irString("Unknown SwitchingProvider id"))
      )
      branches += IrBranchImpl(
        UNDEFINED_OFFSET, UNDEFINED_OFFSET,
        condition = irTrue(),
        result = defaultThrow
      )

      val whenExpr = irWhen(
        type = invokeFun.returnType,
        branches = branches
      )

      +irReturn(whenExpr)
    }
  }

  private fun IrBuilderWithScope.generateBindingReturnExpr(
    owner: IrFunction,
    switchingProviderClass: IrClass,
    binding: IrBinding,
    thisGraphParamName: String
  ): IrExpression {
    // Delegate to the existing provider field on the graph
    val dispatchThis = owner.dispatchReceiverParameter!!
    val graphField = switchingProviderClass.declarations.filterIsInstance<IrField>()
      .firstOrNull { it.name.asString() == thisGraphParamName }
      ?: error("SwitchingProvider must have field: $thisGraphParamName")
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