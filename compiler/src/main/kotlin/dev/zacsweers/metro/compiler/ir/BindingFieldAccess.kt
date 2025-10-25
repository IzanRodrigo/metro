// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression

/**
 * Abstraction layer for accessing binding fields (instance and provider) in generated dependency
 * graphs.
 *
 * This interface hides the implementation details of where fields are located (root component vs
 * nested shards) and provides a clean API for expression generation. This enables future changes to
 * field ownership and shard layout without impacting expression generation code.
 *
 * ## Usage
 *
 * Expression generators should use this interface instead of directly accessing
 * `BindingPropertyContext`. This allows the same expression generation code to work whether
 * sharding is enabled or not.
 *
 * ```kotlin
 * context(scope: IrBuilderWithScope)
 * fun generateExpression() {
 *   if (fieldAccess.hasProviderField(key)) {
 *     val providerExpr = fieldAccess.getProviderExpression(key, componentReceiver)
 *     // use providerExpr...
 *   }
 * }
 * ```
 *
 * ## Implementation Details
 *
 * When sharding is disabled, all properties live on the root component and access is direct:
 * ```
 * component.field
 * ```
 *
 * When sharding is enabled, properties may live in nested shard classes. The implementation handles
 * cross-shard access automatically:
 * ```
 * component.shardX.field  // For shard-owned properties
 * component.field         // For root-owned properties
 * ```
 *
 * @see dev.zacsweers.metro.compiler.ir.DefaultBindingFieldAccess
 * @see dev.zacsweers.metro.compiler.ir.graph.BindingPropertyContext
 */
internal interface BindingFieldAccess {
  /**
   * Checks if a field exists for the given key (either instance or provider).
   *
   * @param key The type key to check
   * @return true if either an instance field or provider field exists for this key
   */
  fun hasField(key: IrTypeKey): Boolean

  /**
   * Checks if an instance field exists for the given key.
   *
   * Instance fields are used for:
   * - Bound instances (from `@BindsInstance` parameters)
   * - Graph dependencies (from component constructor parameters)
   * - This graph instance (the component itself)
   *
   * @param key The type key to check
   * @return true if an instance field exists for this key
   */
  fun hasInstanceField(key: IrTypeKey): Boolean

  /**
   * Checks if a provider field exists for the given key.
   *
   * Provider fields are used for:
   * - Regular bindings (constructor-injected, provides methods, binds)
   * - Scoped bindings (wrapped in DoubleCheck)
   * - Multibindings (sets and maps)
   *
   * @param key The type key to check
   * @return true if a provider field exists for this key
   */
  fun hasProviderField(key: IrTypeKey): Boolean

  /**
   * Generates an IR expression to access the provider field for the given key.
   *
   * The generated expression handles cross-shard access automatically:
   * - For root-owned fields: `component.field`
   * - For shard-owned fields: `component.shardX.field`
   *
   * ## Example Generated Code
   *
   * ```kotlin
   * // Root-owned provider
   * this.fooProvider
   *
   * // Shard-owned provider
   * this.shard1.barProvider
   * ```
   *
   * @param key The type key for the provider to access
   * @param componentReceiver The IR value parameter representing the component instance (typically
   *   `this`)
   * @return An IR expression accessing the provider field, or null if no provider field exists
   */
  context(scope: IrBuilderWithScope)
  fun getProviderExpression(
    key: IrTypeKey,
    componentReceiver: IrValueParameter,
  ): IrExpression?

  /**
   * Generates an IR expression to access the instance field for the given key.
   *
   * The generated expression handles cross-shard access automatically:
   * - For root-owned fields: `component.field`
   * - For shard-owned fields: `component.shardX.field`
   *
   * ## Example Generated Code
   *
   * ```kotlin
   * // Root-owned instance
   * this.fooInstance
   *
   * // Shard-owned instance
   * this.shard1.barInstance
   * ```
   *
   * @param key The type key for the instance to access
   * @param componentReceiver The IR value parameter representing the component instance (typically
   *   `this`)
   * @return An IR expression accessing the instance field, or null if no instance field exists
   */
  context(scope: IrBuilderWithScope)
  fun getInstanceExpression(
    key: IrTypeKey,
    componentReceiver: IrValueParameter,
  ): IrExpression?
}
