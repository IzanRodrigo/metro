// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import org.jetbrains.kotlin.ir.declarations.IrField

/**
 * Information about where a binding field is located.
 * @property field The IR field reference
 * @property shardField If the field is in a shard, this is the IR field for the shard instance on the component.
 *                      If null, the field is directly on the component.
 */
internal data class FieldLocation(
  val field: IrField,
  val shardField: IrField? = null
)

internal class BindingFieldContext {
  // TODO we can end up in awkward situations where we
  //  have the same type keys in both instance and provider fields
  //  this is tricky because depending on the context, it's not valid
  //  to use an instance (for example - you need a provider). How can we
  //  clean this up?
  // Fields for this graph and other instance params
  private val instanceFields = mutableMapOf<IrTypeKey, FieldLocation>()
  // Fields for providers. May include both scoped and unscoped providers as well as bound
  // instances
  private val providerFields = mutableMapOf<IrTypeKey, FieldLocation>()

  val availableInstanceKeys: Set<IrTypeKey>
    get() = instanceFields.keys

  fun putInstanceField(key: IrTypeKey, field: IrField, shardField: IrField? = null) {
    instanceFields[key] = FieldLocation(field, shardField)
  }

  fun putProviderField(key: IrTypeKey, field: IrField, shardField: IrField? = null) {
    providerFields[key] = FieldLocation(field, shardField)
  }

  fun instanceField(key: IrTypeKey): FieldLocation? {
    return instanceFields[key]
  }

  fun providerField(key: IrTypeKey): FieldLocation? {
    return providerFields[key]
  }

  operator fun contains(key: IrTypeKey): Boolean = instanceFields.containsKey(key) || providerFields.containsKey(key)
}
