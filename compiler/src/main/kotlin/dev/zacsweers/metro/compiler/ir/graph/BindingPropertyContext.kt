// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.ir.IrTypeKey
import org.jetbrains.kotlin.ir.declarations.IrProperty

internal class BindingPropertyContext {
  /** Property owner - either root component or a specific shard. */
  sealed interface Owner {
    data object Root : Owner

    data class Shard(val instanceProperty: IrProperty) : Owner
  }

  data class PropertyEntry(val property: IrProperty, var owner: Owner = Owner.Root)

  // TODO we can end up in awkward situations where we
  //  have the same type keys in both instance and provider fields
  //  this is tricky because depending on the context, it's not valid
  //  to use an instance (for example - you need a provider). How can we
  //  clean this up?

  private val instanceProperties = mutableMapOf<IrTypeKey, PropertyEntry>()
  private val providerProperties = mutableMapOf<IrTypeKey, PropertyEntry>()

  val availableInstanceKeys: Set<IrTypeKey>
    get() = instanceProperties.keys

  val availableProviderKeys: Set<IrTypeKey>
    get() = providerProperties.keys

  fun hasKey(key: IrTypeKey): Boolean = key in instanceProperties || key in providerProperties

  fun putInstanceProperty(key: IrTypeKey, property: IrProperty, owner: Owner = Owner.Root) {
    instanceProperties[key] = PropertyEntry(property, owner)
  }

  fun putProviderProperty(key: IrTypeKey, property: IrProperty, owner: Owner = Owner.Root) {
    providerProperties[key] = PropertyEntry(property, owner)
  }

  fun instanceProperty(key: IrTypeKey): IrProperty? {
    return instanceProperties[key]?.property
  }

  fun providerProperty(key: IrTypeKey): IrProperty? {
    return providerProperties[key]?.property
  }

  fun instancePropertyEntry(key: IrTypeKey): PropertyEntry? {
    return instanceProperties[key]
  }

  fun providerPropertyEntry(key: IrTypeKey): PropertyEntry? {
    return providerProperties[key]
  }

  operator fun contains(key: IrTypeKey): Boolean =
    instanceProperties.containsKey(key) || providerProperties.containsKey(key)
}
