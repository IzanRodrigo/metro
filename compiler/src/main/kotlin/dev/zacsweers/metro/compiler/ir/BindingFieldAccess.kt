// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.ir.graph.BindingPropertyContext
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression

/**
 * Handles access to binding fields in generated dependency graphs. Manages both direct and
 * cross-shard property access transparently.
 */
internal class BindingFieldAccess(private val bindingPropertyContext: BindingPropertyContext) {

  fun hasField(key: IrTypeKey): Boolean {
    return hasInstanceField(key) || hasProviderField(key)
  }

  fun hasInstanceField(key: IrTypeKey): Boolean {
    return bindingPropertyContext.instanceProperty(key) != null
  }

  fun hasProviderField(key: IrTypeKey): Boolean {
    return bindingPropertyContext.providerProperty(key) != null
  }

  context(scope: IrBuilderWithScope)
  fun getProviderExpression(key: IrTypeKey, componentReceiver: IrValueParameter): IrExpression? {
    val entry = bindingPropertyContext.providerPropertyEntry(key) ?: return null
    return buildPropertyAccess(entry, componentReceiver)
  }

  context(scope: IrBuilderWithScope)
  fun getInstanceExpression(key: IrTypeKey, componentReceiver: IrValueParameter): IrExpression? {
    val entry = bindingPropertyContext.instancePropertyEntry(key) ?: return null
    return buildPropertyAccess(entry, componentReceiver)
  }

  context(scope: IrBuilderWithScope)
  private fun buildPropertyAccess(
    entry: BindingPropertyContext.PropertyEntry,
    componentReceiver: IrValueParameter,
  ): IrExpression {
    val componentExpr = scope.irGet(componentReceiver)

    return when (val owner = entry.owner) {
      is BindingPropertyContext.Owner.Root -> {
        scope.irGetProperty(componentExpr, entry.property)
      }
      is BindingPropertyContext.Owner.Shard -> {
        val shardInstanceExpr = scope.irGetProperty(componentExpr, owner.instanceProperty)
        scope.irGetProperty(shardInstanceExpr, entry.property)
      }
    }
  }
}
