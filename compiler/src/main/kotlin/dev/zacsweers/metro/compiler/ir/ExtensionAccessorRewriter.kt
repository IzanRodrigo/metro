// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid

/**
 * Shared helper that post-processes generated graph extension classes and rewrites synthetic
 * accessor reads back to the desired field expressions supplied by [resolver].
 */
internal class ExtensionAccessorRewriter(
  private val pluginContext: IrPluginContext,
  private val writeDiagnostic: (String, () -> String) -> Unit,
) {

  data class Resolution(
    val expression: IrExpression,
    val description: String,
  )

  fun rewrite(
    extensions: List<IrClass>,
    resolver: (
      extension: IrClass,
      field: IrField,
      dispatchReceiver: IrValueParameter?,
      builder: DeclarationIrBuilder,
      currentFunctionSymbol: IrSymbol,
      diagPrefix: String,
    ) -> Resolution?,
  ) {
    if (extensions.isEmpty()) return

    for (extension in extensions) {
      var currentFunctionSymbol: IrSymbol = extension.symbol
      var currentDispatchReceiver: IrValueParameter? = null

      extension.transform(object : IrElementTransformerVoid() {
        override fun visitFunction(declaration: IrFunction): IrStatement {
          val previousSymbol = currentFunctionSymbol
          val previousDispatch = currentDispatchReceiver

          currentFunctionSymbol = declaration.symbol
          currentDispatchReceiver = declaration.dispatchReceiverParameter ?: previousDispatch

          val result = super.visitFunction(declaration)

          currentFunctionSymbol = previousSymbol
          currentDispatchReceiver = previousDispatch
          return result
        }

        override fun visitGetField(expression: IrGetField): IrExpression {
          val transformed = super.visitGetField(expression) as IrGetField
          val field = transformed.symbol.owner

          val diagPrefix =
            "EXT-ACCESSOR extension=${extension.name.asString()} field=${field.name.asString()} function=${currentFunctionSymbol.owner.render()}"

          val builder = DeclarationIrBuilder(
            pluginContext,
            currentFunctionSymbol,
            transformed.startOffset,
            transformed.endOffset,
          )

          val resolution =
            resolver(
              extension,
              field,
              currentDispatchReceiver,
              builder,
              currentFunctionSymbol,
              diagPrefix,
            )
              ?: return transformed

          writeDiagnostic("extension-accessor-rewrites.txt") {
            "$diagPrefix ${resolution.description}"
          }
          return resolution.expression
        }
      }, null)
    }
  }
}
