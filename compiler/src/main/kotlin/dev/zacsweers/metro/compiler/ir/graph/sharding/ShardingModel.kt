// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph.sharding

import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.graph.PropertyInitializer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction

/** Tracks a property binding with its associated metadata for sharding. */
internal data class PropertyBinding(
  val property: IrProperty,
  val typeKey: IrTypeKey,
  val initializer: PropertyInitializer,
)

/** Contains all metadata for a generated shard. */
internal data class ShardInfo(
  val index: Int,
  val shardClass: IrClass,
  val instanceProperty: IrProperty,
  val initializeFunction: IrSimpleFunction,
  val bindings: List<PropertyBinding>,
)
