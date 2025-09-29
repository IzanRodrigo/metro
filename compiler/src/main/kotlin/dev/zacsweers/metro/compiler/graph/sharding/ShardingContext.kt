// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph.sharding

import dev.zacsweers.metro.compiler.ir.IrTypeKey
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.types.IrType
import java.util.*

// Shared context to pass between generators
internal class ShardingContext(
  val plan: ShardingPlan,
  val fieldRegistry: ShardFieldRegistry,
  val mainGraphClass: IrClass,
  val shardedTypeKeys: Set<IrTypeKey>,
  val shardClasses: MutableList<IrClass> = mutableListOf(),
  val switchingProviders: MutableMap<Int, IrClass> = mutableMapOf(),
  val shardFields: MutableMap<Int, IrField> = mutableMapOf(),
  val mainGraphFields: MutableMap<IrTypeKey, IrField> = mutableMapOf(),
  val outerFields: MutableMap<Int, IrField> = mutableMapOf(), // Track outer reference fields for static nested shards
  val shardIndexMapping: MutableMap<Int, Int> = mutableMapOf(), // Maps original shard index to actual array index
  val moduleFields: MutableMap<Int, MutableMap<IrTypeKey, IrField>> = mutableMapOf(),
  val requirementFields: MutableMap<Int, MutableMap<IrTypeKey, IrField>> = mutableMapOf(),
  // Ordinals that have provider fields (filtered by ProviderFieldCollector)
  // Bindings not in this set don't need provider fields (e.g., used only once, not scoped)
  var ordinalsWithProviderFields: Set<Int> = emptySet(),
  // Global field name registry to ensure unique field names across all shards
  val globalFieldNameRegistry: GlobalFieldNameRegistry = GlobalFieldNameRegistry(),
  // Track SwitchingProvider IDs for each shard (for fastInit mode)
  val switchingProviderIdCounters: MutableMap<Int, Int> = mutableMapOf(),
  // Track which type keys are accessed from other shards (need PUBLIC visibility)
  val crossShardAccessedTypeKeys: MutableSet<IrTypeKey> = mutableSetOf(),
) {
  // Helper to get shard class by index
  fun getShardClass(index: Int): IrClass = shardClasses[index]

  // Helper to get shard field by index
  fun getShardField(index: Int): IrField? = shardFields[index]

  // Track which bindings have been processed
  private val processedBindings = BitSet()

  fun markProcessed(ordinal: Int) = processedBindings.set(ordinal)
  fun isProcessed(ordinal: Int) = processedBindings[ordinal]
}
