// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrBranchImpl
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.primaryConstructor

internal class SwitchingProviderGenerator(
  private val context: IrMetroContext,
) : IrMetroContext by context {

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
    // For now, we'll delegate to the existing provider field on the graph
    // This is simpler than recreating the entire binding expression
    val dispatchThis = owner.dispatchReceiverParameter!!
    val graphField = switchingProviderClass.declarations.filterIsInstance<IrField>()
      .firstOrNull { it.name.asString() == thisGraphParamName }
      ?: error("SwitchingProvider must have field: $thisGraphParamName")
    val graphGet = irGetField(irGet(dispatchThis), graphField)

    // For simplicity, just return a stub error for now
    // In a full implementation, we'd generate the actual binding expression
    // or delegate to existing provider fields
    return irInvoke(
      callee = symbols.stdlibErrorFunction,
      args = listOf(irString("SwitchingProvider case for ${binding.typeKey} not yet implemented"))
    )
  }
}