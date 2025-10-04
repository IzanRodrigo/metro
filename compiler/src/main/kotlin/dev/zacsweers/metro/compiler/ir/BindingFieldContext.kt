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
  private val instanceFields = mutableMapOf<IrTypeKey, FieldDescriptor>()

  // Fields for providers. May include both scoped and unscoped providers as well as bound
  // instances
  private val providerFields = mutableMapOf<IrTypeKey, FieldDescriptor>()

  // TODO: Is this the best place to put it?
  var shardingContext: ShardingContext? = null

  var fieldOwnershipRegistry: FieldOwnershipRegistry? = null

  // Track which shard class we're currently generating fields for.
  // This is set temporarily during shard field generation to help the expression generator
  // correctly identify when it needs to use outer references for BoundInstance fields.
  var currentShardClass: IrClass? = null

  val availableInstanceKeys: Set<IrTypeKey>
    get() = instanceFields.keys

  fun putInstanceField(
    key: IrTypeKey,
    field: IrField,
    owner: FieldOwner? = null,
  ) {
    val descriptor = descriptorFor(field, owner)
    instanceFields[key] = descriptor
    descriptor.registerOwnership(key)
  }

  fun putProviderField(
    key: IrTypeKey,
    field: IrField,
    owner: FieldOwner? = null,
  ) {
    val descriptor = descriptorFor(field, owner)
    providerFields[key] = descriptor
    descriptor.registerOwnership(key)
  }

  fun removeInstanceField(key: IrTypeKey) {
    instanceFields.remove(key)
  }

  fun removeProviderField(key: IrTypeKey) {
    providerFields.remove(key)
  }

  fun instanceFieldDescriptor(key: IrTypeKey): FieldDescriptor? {
    instanceFields[key]?.let { return it }

    shardingContext?.fieldRegistry?.findField(key)?.let { info ->
      return descriptorFor(info.field, FieldOwner.Shard(info.shardIndex)).also { it.registerOwnership(key) }
    }

    return null
  }

  fun providerFieldDescriptor(key: IrTypeKey): FieldDescriptor? {
    providerFields[key]?.let { return it }

    // First check sharding context
    shardingContext?.fieldRegistry?.findField(key)?.let { info ->
      return descriptorFor(info.field, FieldOwner.Shard(info.shardIndex)).also { it.registerOwnership(key) }
    }

    return null
  }

  fun instanceField(key: IrTypeKey): IrField? = instanceFieldDescriptor(key)?.field

  fun providerField(key: IrTypeKey): IrField? = providerFieldDescriptor(key)?.field

  operator fun contains(key: IrTypeKey): Boolean =
    instanceFields.containsKey(key) || providerFields.containsKey(key)

  fun refreshOwnership() {
    instanceFields.forEach { (key, descriptor) -> descriptor.registerOwnership(key) }
    providerFields.forEach { (key, descriptor) -> descriptor.registerOwnership(key) }
  }

  data class FieldDescriptor(
    val field: IrField,
    val owner: FieldOwner,
    val declaringClass: IrClass? = field.parent as? IrClass,
  )

  sealed class FieldOwner {
    data object MainGraph : FieldOwner()
    data class Shard(val index: Int) : FieldOwner()
    data class Unknown(val declaringClass: IrClass?) : FieldOwner()
  }

  private fun descriptorFor(field: IrField, explicitOwner: FieldOwner?): FieldDescriptor {
    val owner = explicitOwner ?: inferOwner(field)
    return FieldDescriptor(field, owner, field.parent as? IrClass)
  }

  private fun FieldDescriptor.registerOwnership(key: IrTypeKey) {
    val registry = fieldOwnershipRegistry ?: return
    when (val owner = owner) {
      is FieldOwner.MainGraph -> registry.registerMainGraphField(key, field)
      is FieldOwner.Shard -> registry.registerShardField(key, owner.index, field)
      is FieldOwner.Unknown -> {
        val parentClass = declaringClass
        val sharding = shardingContext
        if (parentClass != null && sharding != null) {
          val shardIndex = sharding.shardClasses.indexOf(parentClass)
          if (shardIndex >= 0) {
            fieldOwnershipRegistry?.registerShardField(key, shardIndex, field)
          } else if (parentClass == sharding.mainGraphClass) {
            fieldOwnershipRegistry?.registerMainGraphField(key, field)
          }
        }
      }
    }
  }

  private fun inferOwner(field: IrField): FieldOwner {
    val parentClass = field.parent as? IrClass
    val context = shardingContext
    if (context != null && parentClass != null) {
      if (parentClass == context.mainGraphClass) {
        return FieldOwner.MainGraph
      }
      val shardIndex = context.shardClasses.indexOf(parentClass)
      if (shardIndex >= 0) {
        return FieldOwner.Shard(shardIndex)
      }
    }
    return FieldOwner.Unknown(parentClass)
  }
}
