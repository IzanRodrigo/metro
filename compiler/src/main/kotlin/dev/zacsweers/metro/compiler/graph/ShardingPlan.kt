// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

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
 */
internal data class Shard(
  val index: Int,
  val bindings: Set<IrTypeKey>,
  val dependencies: Set<IrTypeKey>,
) {
  val name = "Shard$index"
}

internal fun buildShardingPlan(topo: TopoSortResult<IrTypeKey>, keysPerShard: Int): ShardingPlan {
  require(keysPerShard > 0)
  val shards = mutableListOf<Shard>()
  val bindingToShard = HashMap<IrTypeKey, Int>(topo.sortedKeys.size)

  // deterministic per-SCC ordering
  val verticesByComponent = Array(topo.components.size) { emptyList<IrTypeKey>() }
  for (c in topo.components) verticesByComponent[c.id] = c.vertices.sorted()

  var idx = 0
  val current = mutableListOf<IrTypeKey>()

  fun flush() {
    if (current.isEmpty()) return
    val set = current.toSet()
    val deps = crossShardDeps(set, topo.adjacency, bindingToShard, idx)
    for (k in set) bindingToShard[k] = idx
    shards += Shard(index = idx++, bindings = set, dependencies = deps)
    current.clear()
  }

  for (cid in topo.componentOrder) {
    val comp = verticesByComponent[cid]
    if (current.isEmpty()) {
      if (comp.size >= keysPerShard) {
        current += comp
        flush()
      } else {
        current += comp
      }
      continue
    }

    if (current.size + comp.size <= keysPerShard) {
      current += comp
    } else {
      flush()
      if (comp.size >= keysPerShard) {
        current += comp; flush()
      } else {
        current += comp
      }
    }
  }
  flush()
  return ShardingPlan(shards = shards, bindingToShard = bindingToShard, keysPerShard = keysPerShard)
}

private fun crossShardDeps(
  keys: Set<IrTypeKey>,
  adj: Map<IrTypeKey, Set<IrTypeKey>>,
  which: Map<IrTypeKey, Int>,
  shardIdx: Int,
): Set<IrTypeKey> {
  val out = LinkedHashSet<IrTypeKey>()
  for (u in keys) {
    for (v in adj[u].orEmpty()) {
      val sv = which[v] ?: continue
      if (sv != shardIdx) out += v // by topo invariant sv < shardIdx
    }
  }
  return out
}
