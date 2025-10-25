// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.ir.graph.BindingPropertyContext
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression

/**
 * Default implementation of [BindingFieldAccess] that wraps [BindingPropertyContext].
 *
 * This implementation handles both direct property access (when sharding is disabled) and
 * cross-shard property access (when sharding is enabled). The abstraction allows expression
 * generators to access properties without knowing about the underlying shard structure.
 *
 * ## Access Patterns
 *
 * ### Without Sharding (Root-owned properties)
 * ```kotlin
 * // Generated code: component.field
 * irGetProperty(irGet(componentReceiver), property)
 * ```
 *
 * ### With Sharding (Shard-owned properties)
 * ```kotlin
 * // Generated code: component.shardX.field
 * val shardAccess = irGetProperty(irGet(componentReceiver), shardInstanceProperty)
 * irGetProperty(shardAccess, property)
 * ```
 *
 * ## Phase 2 Implementation
 *
 * In Phase 2 (current), this implementation only supports root-owned properties. Cross-shard access
 * will be implemented in Phase 4 when shard classes are actually generated.
 *
 * @property bindingPropertyContext The context tracking property ownership and shard assignments
 */
internal class DefaultBindingFieldAccess(
  private val bindingPropertyContext: BindingPropertyContext,
) : BindingFieldAccess {

  override fun hasField(key: IrTypeKey): Boolean {
    return hasInstanceField(key) || hasProviderField(key)
  }

  override fun hasInstanceField(key: IrTypeKey): Boolean {
    return bindingPropertyContext.instanceProperty(key) != null
  }

  override fun hasProviderField(key: IrTypeKey): Boolean {
    return bindingPropertyContext.providerProperty(key) != null
  }

  context(scope: IrBuilderWithScope)
  override fun getProviderExpression(
    key: IrTypeKey,
    componentReceiver: IrValueParameter,
  ): IrExpression? {
    val entry = bindingPropertyContext.providerPropertyEntry(key) ?: return null
    return buildPropertyAccess(entry, componentReceiver)
  }

  context(scope: IrBuilderWithScope)
  override fun getInstanceExpression(
    key: IrTypeKey,
    componentReceiver: IrValueParameter,
  ): IrExpression? {
    val entry = bindingPropertyContext.instancePropertyEntry(key) ?: return null
    return buildPropertyAccess(entry, componentReceiver)
  }

  /**
   * Builds an IR expression to access a property, handling both root and shard ownership.
   *
   * ## Phase 4 Implementation (Current)
   *
   * This checks the property's owner and generates the appropriate access pattern:
   *
   * ### Root-Owned Property
   * ```kotlin
   * // Generated code: component.property
   * component.property
   * ```
   *
   * ### Shard-Owned Property
   * ```kotlin
   * // Generated code: component.shardX.property
   * val shard = component.shardX
   * shard.property
   * ```
   *
   * ## Implementation Details
   *
   * The owner information is tracked in BindingPropertyContext during shard generation. When
   * properties are moved to shards (via `movePropertyToShard()`), their owner is updated from
   * `Owner.Root` to `Owner.Shard(instanceProperty)`.
   *
   * Expression generators call this method and get the correct access pattern without needing to
   * know whether sharding is enabled or where the property lives.
   *
   * @param entry The property entry containing the property and its owner information
   * @param componentReceiver The component instance (typically `this`)
   * @return IR expression that accesses the property correctly
   */
  context(scope: IrBuilderWithScope)
  private fun buildPropertyAccess(
    entry: BindingPropertyContext.PropertyEntry,
    componentReceiver: IrValueParameter,
  ): IrExpression {
    val componentExpr = scope.irGet(componentReceiver)

    return when (val owner = entry.owner) {
      is BindingPropertyContext.Owner.Root -> {
        // Direct access: component.property
        scope.irGetProperty(componentExpr, entry.property)
      }
      is BindingPropertyContext.Owner.Shard -> {
        // Cross-shard access: component.shardX.property
        // Step 1: Get the shard instance from the component
        val shardInstanceExpr = scope.irGetProperty(componentExpr, owner.instanceProperty)

        // Step 2: Access the property on the shard
        scope.irGetProperty(shardInstanceExpr, entry.property)
      }
    }
  }
}
