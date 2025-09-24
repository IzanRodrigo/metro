// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.graph.sharding.ShardingContext
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrField

internal class BindingFieldContext {
  // TODO we can end up in awkward situations where we
  //  have the same type keys in both instance and provider fields
  //  this is tricky because depending on the context, it's not valid
  //  to use an instance (for example - you need a provider). How can we
  //  clean this up?
  // Fields for this graph and other instance params
  private val instanceFields = mutableMapOf<IrTypeKey, IrField>()

  // Fields for providers. May include both scoped and unscoped providers as well as bound
  // instances
  private val providerFields = mutableMapOf<IrTypeKey, IrField>()

  // TODO: Is this the best place to put it?
  var shardingContext: ShardingContext? = null

  // Track which shard class we're currently generating fields for.
  // This is set temporarily during shard field generation to help the expression generator
  // correctly identify when it needs to use outer references for BoundInstance fields.
  var currentShardClass: IrClass? = null

  val availableInstanceKeys: Set<IrTypeKey>
    get() = instanceFields.keys

  fun putInstanceField(key: IrTypeKey, field: IrField) {
    instanceFields[key] = field
  }

  fun putProviderField(key: IrTypeKey, field: IrField) {
    providerFields[key] = field
  }

  fun removeInstanceField(key: IrTypeKey) {
    instanceFields.remove(key)
  }

  fun removeProviderField(key: IrTypeKey) {
    providerFields.remove(key)
  }

  fun instanceField(key: IrTypeKey): IrField? {
    instanceFields[key]?.let { return it }

    shardingContext?.fieldRegistry?.findField(key)?.field?.let { return it }

    return null
  }

  fun providerField(key: IrTypeKey): IrField? {
    providerFields[key]?.let { return it }

    // First check sharding context
    shardingContext?.fieldRegistry?.findField(key)?.let {
      return it.field
    }

    return null
  }

  operator fun contains(key: IrTypeKey): Boolean = instanceFields.containsKey(key) || providerFields.containsKey(key)
}
