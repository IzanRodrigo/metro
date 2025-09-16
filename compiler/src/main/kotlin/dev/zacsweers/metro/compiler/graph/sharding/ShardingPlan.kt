// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph.sharding

import dev.zacsweers.metro.compiler.ir.IrTypeKey

/**
 * Represents a plan for sharding a dependency graph into multiple classes.
 *
 * Following Dagger's approach, this plan partitions bindings across shards while:
 * - Keeping strongly connected components (cycles) together
 * - Maintaining reverse topological order (Shard{i} doesn't depend on Shard{i+j})
 * - Respecting the keysPerShard threshold
 *
 * @property shards List of shards in initialization order.
 * @property bindingToShard Maps each binding key to its assigned shard index.
 * @property keysPerShard The threshold used for partitioning.
 */
internal data class ShardingPlan(
  val shards: List<Shard>,
  val bindingToShard: Map<IrTypeKey, Int>,
  val keysPerShard: Int,
)

/**
 * Represents a single shard containing a subset of bindings.
 *
 * @property index The shard index.
 * @property bindings Set of binding keys assigned to this shard.
 * @property dependencies Set of binding keys this shard depends on from other shards.
 * @property requiredModules Set of module types required by this shard.
 */
internal data class Shard(
  val index: Int,
  val bindings: Set<IrTypeKey>,
  val dependencies: Set<IrTypeKey>,
  val requiredModules: Set<IrTypeKey> = emptySet(),
) {
  val name = "Shard$index"
}
