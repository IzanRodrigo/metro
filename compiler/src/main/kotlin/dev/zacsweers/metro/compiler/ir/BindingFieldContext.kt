// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.name.ClassId

/**
 * Information about where a binding field is located.
 * @property field The IR field reference
 * @property shardField If the field is in a shard, this is the IR field for the shard instance on the component.
 *                      If null, the field is directly on the component.
 */
internal data class FieldLocation(
  val field: IrField,
  val shardField: IrField? = null,
  val ownerGraphClassId: ClassId? = null,
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

  fun putInstanceField(
    key: IrTypeKey,
    field: IrField,
    shardField: IrField? = null,
    ownerGraphClassId: ClassId? = null,
  ) {
    instanceFields[key] = FieldLocation(field, shardField, ownerGraphClassId)
  }

  fun putProviderField(
    key: IrTypeKey,
    field: IrField,
    shardField: IrField? = null,
    ownerGraphClassId: ClassId? = null,
  ) {
    providerFields[key] = FieldLocation(field, shardField, ownerGraphClassId)
  }

  fun instanceField(key: IrTypeKey): FieldLocation? {
    return instanceFields[key]
  }

  fun providerField(key: IrTypeKey): FieldLocation? {
    return providerFields[key]
  }

  /**
   * Attempt a "loose" lookup for a provider field by comparing raw classifier + arity, ignoring
   * nullability and platform type flexibility. This is a defensive fallback specifically for
   * BoundInstance lookups where type normalization mismatches may occur between the binding graph
   * (which may record a not-null Kotlin type) and IR constructor parameters (which can surface
   * as platform/flexible types).
   */
  fun providerFieldLoose(target: IrTypeKey): Pair<IrTypeKey, FieldLocation>? {
    val targetClassifier = target.type.classOrNull
  val targetArgs = (target.type as? IrSimpleType)?.arguments?.size ?: 0
    var match: Pair<IrTypeKey, FieldLocation>? = null
    for ((k, v) in providerFields) {
      val kClassifier = k.type.classOrNull
      if (kClassifier == targetClassifier) {
  val kArgs = (k.type as? IrSimpleType)?.arguments?.size ?: 0
        if (kArgs == targetArgs) {
          if (match != null && match.first != k) {
            // Ambiguous – bail out
            return null
          }
          match = k to v
        }
      }
    }
    return match
  }

  /** For diagnostics: returns all registered provider keys as strings. */
  fun dumpProviderKeys(): String = providerFields.keys.joinToString(", ") { it.toString() }

  operator fun contains(key: IrTypeKey): Boolean = instanceFields.containsKey(key) || providerFields.containsKey(key)
}
