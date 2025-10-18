// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression

/**
 * Default implementation of [BindingFieldAccess] that delegates to [BindingFieldContext].
 *
 * This implementation wraps the existing `BindingFieldContext` and provides a clean abstraction
 * layer. It handles field ownership transparently, whether fields are owned by the root component
 * or nested shard classes.
 *
 * This design allows expression generation code to remain independent of sharding implementation
 * details, enabling future changes to shard layout strategies without refactoring expression
 * generation logic.
 *
 * @param context The binding field context to delegate to
 */
internal class DefaultBindingFieldAccess(
  private val context: BindingFieldContext
) : BindingFieldAccess {

  override fun hasField(key: IrTypeKey): Boolean {
    return context.hasKey(key)
  }

  override fun hasInstanceField(key: IrTypeKey): Boolean {
    return context.instanceFieldEntry(key) != null
  }

  override fun hasProviderField(key: IrTypeKey): Boolean {
    return context.hasProviderEntry(key)
  }

  override fun getProviderExpression(
    scope: IrBuilderWithScope,
    key: IrTypeKey,
    componentReceiver: IrValueParameter,
  ): IrExpression? {
    return context.providerExpression(scope, componentReceiver, key)
  }

  override fun getInstanceExpression(
    scope: IrBuilderWithScope,
    key: IrTypeKey,
    componentReceiver: IrValueParameter,
  ): IrExpression? {
    return context.instanceExpression(scope, componentReceiver, key)
  }
}
