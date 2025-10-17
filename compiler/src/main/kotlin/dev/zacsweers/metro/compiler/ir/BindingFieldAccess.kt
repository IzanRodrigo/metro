// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression

/**
 * Abstraction layer for accessing binding fields (instance and provider) in generated
 * dependency graphs.
 *
 * This interface hides the implementation details of where fields are located (root component
 * vs nested shards) and provides a clean API for expression generation. This enables future
 * changes to field ownership and shard layout without impacting expression generation code.
 *
 * Usage example:
 * ```kotlin
 * context(scope: IrBuilderWithScope)
 * fun generateExpression() {
 *   if (fieldAccess.hasProviderField(key)) {
 *     val providerExpr = fieldAccess.getProviderExpression(key, componentReceiver)
 *     // use providerExpr...
 *   }
 * }
 * ```
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
   * @param key The type key to check
   * @return true if an instance field exists for this key
   */
  fun hasInstanceField(key: IrTypeKey): Boolean

  /**
   * Checks if a provider field exists for the given key.
   *
   * @param key The type key to check
   * @return true if a provider field exists for this key
   */
  fun hasProviderField(key: IrTypeKey): Boolean

  /**
   * Generates an IR expression to access the provider field for the given key.
   *
   * The generated expression handles cross-shard access automatically, including:
   * - Direct field access for root-owned fields
   * - Shard instance field access followed by provider field access for shard-owned fields
   *
   * @param key The type key for the provider to access
   * @param componentReceiver The IR value parameter representing the component instance
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
   * The generated expression handles cross-shard access automatically, including:
   * - Direct field access for root-owned fields
   * - Shard instance field access followed by instance field access for shard-owned fields
   *
   * @param key The type key for the instance to access
   * @param componentReceiver The IR value parameter representing the component instance
   * @return An IR expression accessing the instance field, or null if no instance field exists
   */
  context(scope: IrBuilderWithScope)
  fun getInstanceExpression(
    key: IrTypeKey,
    componentReceiver: IrValueParameter,
  ): IrExpression?
}
