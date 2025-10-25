// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.ir.IrTypeKey
import org.jetbrains.kotlin.ir.declarations.IrProperty

internal class BindingPropertyContext {
  /**
   * Represents the owner of a property - either the root component or a specific shard.
   *
   * This is used for component sharding to track which properties belong to which shard, enabling
   * cross-shard field access code generation.
   */
  sealed interface Owner {
    /** Property is owned by the root component (direct access) */
    data object Root : Owner

    /**
     * Property is owned by a nested shard class.
     *
     * @property instanceProperty The property on the root component that holds the shard instance
     *   (e.g., `val shard1: Shard1`)
     */
    data class Shard(val instanceProperty: IrProperty) : Owner
  }

  /**
   * Tracks a property and its owner (root or shard).
   *
   * @property property The IR property itself
   * @property owner The owner of this property (defaults to Root)
   */
  data class PropertyEntry(
    val property: IrProperty,
    var owner: Owner = Owner.Root,
  )

  // TODO we can end up in awkward situations where we
  //  have the same type keys in both instance and provider fields
  //  this is tricky because depending on the context, it's not valid
  //  to use an instance (for example - you need a provider). How can we
  //  clean this up?

  // Properties for this graph and other instance params
  // Maps type key -> property entry (property + owner info)
  private val instanceProperties = mutableMapOf<IrTypeKey, PropertyEntry>()

  // Properties for providers. May include both scoped and unscoped providers as well as bound
  // instances
  // Maps type key -> property entry (property + owner info)
  private val providerProperties = mutableMapOf<IrTypeKey, PropertyEntry>()

  val availableInstanceKeys: Set<IrTypeKey>
    get() = instanceProperties.keys

  val availableProviderKeys: Set<IrTypeKey>
    get() = providerProperties.keys

  fun hasKey(key: IrTypeKey): Boolean = key in instanceProperties || key in providerProperties

  /**
   * Registers an instance property with the given owner.
   *
   * @param key The type key for this property
   * @param property The IR property to register
   * @param owner The owner of this property (defaults to Root)
   */
  fun putInstanceProperty(key: IrTypeKey, property: IrProperty, owner: Owner = Owner.Root) {
    instanceProperties[key] = PropertyEntry(property, owner)
  }

  /**
   * Registers a provider property with the given owner.
   *
   * @param key The type key for this property
   * @param property The IR property to register
   * @param owner The owner of this property (defaults to Root)
   */
  fun putProviderProperty(key: IrTypeKey, property: IrProperty, owner: Owner = Owner.Root) {
    providerProperties[key] = PropertyEntry(property, owner)
  }

  /**
   * Updates the owner of an instance property.
   *
   * This is used when moving properties to shards during shard class generation.
   *
   * @param property The property whose owner should be updated
   * @param owner The new owner
   */
  fun updateInstancePropertyOwner(property: IrProperty, owner: Owner) {
    updateOwner(instanceProperties, property, owner)
  }

  /**
   * Updates the owner of a provider property.
   *
   * This is used when moving properties to shards during shard class generation.
   *
   * @param property The property whose owner should be updated
   * @param owner The new owner
   */
  fun updateProviderPropertyOwner(property: IrProperty, owner: Owner) {
    updateOwner(providerProperties, property, owner)
  }

  private fun updateOwner(
    map: MutableMap<IrTypeKey, PropertyEntry>,
    property: IrProperty,
    owner: Owner,
  ) {
    map.values.forEach { entry ->
      if (entry.property == property) {
        entry.owner = owner
      }
    }
  }

  /** Returns the IR property for an instance key, or null if not found. */
  fun instanceProperty(key: IrTypeKey): IrProperty? {
    return instanceProperties[key]?.property
  }

  /** Returns the IR property for a provider key, or null if not found. */
  fun providerProperty(key: IrTypeKey): IrProperty? {
    return providerProperties[key]?.property
  }

  /** Returns the property entry for an instance key, including owner info. */
  fun instancePropertyEntry(key: IrTypeKey): PropertyEntry? {
    return instanceProperties[key]
  }

  /** Returns the property entry for a provider key, including owner info. */
  fun providerPropertyEntry(key: IrTypeKey): PropertyEntry? {
    return providerProperties[key]
  }

  operator fun contains(key: IrTypeKey): Boolean =
    instanceProperties.containsKey(key) || providerProperties.containsKey(key)
}
