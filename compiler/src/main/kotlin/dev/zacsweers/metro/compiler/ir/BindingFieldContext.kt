// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression

internal class BindingFieldContext {
  sealed interface Owner {
    data object Root : Owner
    data class Shard(val instanceField: IrField) : Owner
  }

  data class FieldEntry(
    val field: IrField,
    var owner: Owner,
  )

  private val instanceFields = mutableMapOf<IrTypeKey, FieldEntry>()
  private val providerFields = mutableMapOf<IrTypeKey, FieldEntry>()

  val availableInstanceKeys: Set<IrTypeKey>
    get() = instanceFields.keys

  val availableProviderKeys: Set<IrTypeKey>
    get() = providerFields.keys

  fun hasKey(key: IrTypeKey): Boolean = key in instanceFields || key in providerFields

  fun putInstanceField(
    key: IrTypeKey,
    field: IrField,
    owner: Owner = Owner.Root,
  ) {
    instanceFields[key] = FieldEntry(field, owner)
  }

  fun putProviderField(
    key: IrTypeKey,
    field: IrField,
    owner: Owner = Owner.Root,
  ) {
    providerFields[key] = FieldEntry(field, owner)
  }

  fun updateInstanceFieldOwner(field: IrField, owner: Owner) {
    updateOwner(instanceFields, field, owner)
  }

  fun updateProviderFieldOwner(field: IrField, owner: Owner) {
    updateOwner(providerFields, field, owner)
  }

  private fun updateOwner(
    map: MutableMap<IrTypeKey, FieldEntry>,
    field: IrField,
    owner: Owner,
  ) {
    map.values.forEach { entry ->
      if (entry.field == field) {
        entry.owner = owner
      }
    }
  }

  fun instanceFieldEntry(key: IrTypeKey): FieldEntry? = instanceFields[key]

  fun providerFieldEntry(key: IrTypeKey): FieldEntry? = providerFields[key]

  context(scope: IrBuilderWithScope)
  fun providerExpression(
    componentReceiver: IrValueParameter,
    key: IrTypeKey,
  ): IrExpression? {
    val entry = providerFields[key] ?: return null
    return entry.buildGetExpression(scope, componentReceiver)
  }

  context(scope: IrBuilderWithScope)
  fun instanceExpression(
    componentReceiver: IrValueParameter,
    key: IrTypeKey,
  ): IrExpression? {
    val entry = instanceFields[key] ?: return null
    return entry.buildGetExpression(scope, componentReceiver)
  }

  private fun FieldEntry.buildGetExpression(
    scope: IrBuilderWithScope,
    componentReceiver: IrValueParameter,
  ): IrExpression {
    val component = scope.irGet(componentReceiver)
    val receiver =
      when (val ownerRef = owner) {
        Owner.Root -> component
        is Owner.Shard -> scope.irGetField(component, ownerRef.instanceField)
      }
    return scope.irGetField(receiver, field)
  }

  operator fun contains(key: IrTypeKey): Boolean =
    instanceFields.containsKey(key) || providerFields.containsKey(key)
}
