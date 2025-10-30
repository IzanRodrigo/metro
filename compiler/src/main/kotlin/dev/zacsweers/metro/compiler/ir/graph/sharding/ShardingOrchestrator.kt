// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph.sharding

import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.graph.Component
import dev.zacsweers.metro.compiler.graph.TopoSortResult
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.graph.DependencyGraphNode
import dev.zacsweers.metro.compiler.ir.graph.IrBinding
import dev.zacsweers.metro.compiler.ir.graph.IrBindingGraph
import dev.zacsweers.metro.compiler.ir.graph.PropertyInitializer
import java.util.SortedMap
import java.util.SortedSet
import org.jetbrains.kotlin.ir.declarations.IrProperty

/**
 * Optimized orchestrator for sharding dependency graph bindings.
 *
 * Key improvements over the baseline version:
 * - O(1) dedupe of cross‑shard edges via a reusable BooleanArray "bitset"
 * - Path compression in alias resolution to avoid repeated chain walks
 * - Deterministic O(S + D) shard topological sort (no PriorityQueue boxing)
 * - Component-first iteration in partitioning (lower branching, fewer lookups)
 * - Optional tail-merge heuristic to avoid tiny last shards when under the cap
 */
internal class ShardingOrchestrator(
  private val bindingGraph: IrBindingGraph,
  private val node: DependencyGraphNode,
  private val options: MetroOptions,
  private val log: (String) -> Unit,
) {
  // Memo for alias resolution. Filled with path compression (see resolveAndCompress).
  private val resolvedAliasCache = LinkedHashMap<IrTypeKey, IrTypeKey>(/* initialCapacity= */ 64)

  /** Partitions property initializers into shard groups using cached topology data. */
  fun partitionProperties(
    propertyInitializers: List<Pair<IrProperty, PropertyInitializer>>,
    propertiesToTypeKeys: Map<IrProperty, IrTypeKey>,
    topologyData: TopoSortResult<IrTypeKey>?,
  ): List<List<PropertyBinding>> {
    if (propertyInitializers.isEmpty()) return emptyList()

    val maxBindingsPerShard = options.keysPerGraphShard
    if (!shouldShard(propertyInitializers.size, maxBindingsPerShard) || topologyData == null) {
      return listOf(createPropertyBindings(propertyInitializers, propertiesToTypeKeys))
    }

    // Build a quick lookup from type key -> property binding
    val propertyMap = HashMap<IrTypeKey, PropertyBinding>(propertyInitializers.size * 2)
    for ((property, initializer) in propertyInitializers) {
      val typeKey = propertiesToTypeKeys.getValue(property)
      propertyMap[typeKey] = PropertyBinding(property, typeKey, initializer)
    }

    // Partition by strongly-connected components in the order of sorted keys,
    // then apply a small tail-merge heuristic to reduce tiny last shards.
    val shards =
      partitionUsingSCCs(
          propertyMap = propertyMap,
          sortedKeys = topologyData.sortedKeys,
          components = topologyData.components,
          componentOf = topologyData.componentOf,
          maxBindingsPerShard = maxBindingsPerShard,
        )
        .toMutableList()

    // Heuristic E: merge the tail shard into the previous if it fits the cap.
    if (shards.size >= 2) {
      val last = shards.last()
      val prev = shards[shards.size - 2]
      if (prev.size + last.size <= maxBindingsPerShard) {
        shards[shards.size - 2] = (prev + last).toMutableList()
        shards.removeAt(shards.lastIndex)
      }
    }

    // If we ended up with only one shard, just return the flat list for clarity.
    return if (shards.size <= 1) listOf(propertyMap.values.toList()) else shards
  }

  /** Computes initialization order for shards using a shard-level DAG. */
  fun computeInitializationOrder(
    shardGroups: List<List<PropertyBinding>>,
    topologyData: TopoSortResult<IrTypeKey>?,
  ): List<Int> {
    if (shardGroups.size <= 1) return emptyList()
    if (topologyData == null) return shardGroups.indices.toList()

    val bindingToShard = buildMap {
      shardGroups.forEachIndexed { shardIndex, bindings ->
        bindings.forEach { binding -> put(binding.typeKey, shardIndex) }
      }
    }

    val shardDependencies =
      computeShardDependencies(
        shardGroups = shardGroups,
        bindingToShard = bindingToShard,
        adjacency = topologyData.adjacency,
      )

    return topologicalSortShards(shardGroups.size, shardDependencies)
  }

  private fun shouldShard(propertyCount: Int, maxBindingsPerShard: Int): Boolean {
    if (!options.enableGraphSharding) return false
    if (propertyCount <= maxBindingsPerShard) return false
    // Don't shard graph extensions - they access parent properties and sharding
    // them creates complex cross-graph receiver scoping issues
    if (node.extendedGraphNodes.isNotEmpty()) return false
    return true
  }

  private fun createPropertyBindings(
    propertyInitializers: List<Pair<IrProperty, PropertyInitializer>>,
    propertiesToTypeKeys: Map<IrProperty, IrTypeKey>,
  ): List<PropertyBinding> {
    return propertyInitializers.map { (property, initializer) ->
      PropertyBinding(
        property = property,
        typeKey = propertiesToTypeKeys.getValue(property),
        initializer = initializer,
      )
    }
  }

  private fun shouldStartNewShard(
    currentShardSize: Int,
    additionalItems: Int,
    maxBindingsPerShard: Int,
  ): Boolean {
    return currentShardSize > 0 && currentShardSize + additionalItems > maxBindingsPerShard
  }

  /**
   * Partition by SCCs with a component-first iteration.
   *
   * We traverse components in the order they first appear in 'sortedKeys' to preserve the
   * high‑level topological intent, then append whole components to shards respecting the cap.
   */
  private fun partitionUsingSCCs(
    propertyMap: Map<IrTypeKey, PropertyBinding>,
    sortedKeys: List<IrTypeKey>,
    components: List<Component<IrTypeKey>>,
    componentOf: Map<IrTypeKey, Int>,
    maxBindingsPerShard: Int,
  ): List<List<PropertyBinding>> {
    val shards = mutableListOf<MutableList<PropertyBinding>>()
    var currentShard = mutableListOf<PropertyBinding>()

    // 1) Build ordered unique component ids from sortedKeys.
    val orderedComponents = ArrayList<Int>(components.size)
    val seenComponent = BooleanArray(components.size)
    // Also collect keys that do not belong to any component.
    val isolatedKeys = mutableListOf<IrTypeKey>()

    for (key in sortedKeys) {
      val cid = componentOf[key]
      if (cid == null) {
        // Some keys may not be part of the computed SCCs (isolated)
        if (propertyMap.containsKey(key)) isolatedKeys += key
      } else if (!seenComponent[cid]) {
        seenComponent[cid] = true
        orderedComponents += cid
      }
    }

    // 2) Append full components in order.
    for (cid in orderedComponents) {
      val component = components[cid]
      // Only include vertices that correspond to actual properties (some graph nodes are not
      // properties).
      val group = component.vertices.mapNotNull(propertyMap::get)
      if (group.isEmpty()) continue

      if (shouldStartNewShard(currentShard.size, group.size, maxBindingsPerShard)) {
        shards += currentShard
        currentShard = mutableListOf()
      }
      currentShard.addAll(group)
    }

    // 3) Append isolated keys last, preserving their order in sortedKeys.
    for (key in isolatedKeys) {
      val binding = propertyMap[key] ?: continue
      if (shouldStartNewShard(currentShard.size, 1, maxBindingsPerShard)) {
        shards += currentShard
        currentShard = mutableListOf()
      }
      currentShard += binding
    }

    if (currentShard.isNotEmpty()) shards += currentShard
    return shards
  }

  /**
   * Compute shard-level dependencies with O(1) dedupe using a reusable BooleanArray.
   *
   * Returns an adjacency array where `deps[a]` contains all shard indices that 'a' depends on.
   */
  private fun computeShardDependencies(
    shardGroups: List<List<PropertyBinding>>,
    bindingToShard: Map<IrTypeKey, Int>,
    adjacency: SortedMap<IrTypeKey, SortedSet<IrTypeKey>>,
  ): Array<IntArray> {
    val s = shardGroups.size
    val seen = BooleanArray(s) // scratch bitset reused per source shard
    val touched = ArrayList<Int>(8) // list of shards touched to reset 'seen' cheaply
    val out = Array(s) { IntArray(0) }

    shardGroups.forEachIndexed { shardIndex, bindings ->
      // mark phase
      for (binding in bindings) {
        val deps = adjacency[binding.typeKey] ?: emptySet()
        for (depKey in deps) {
          val resolved = resolveActualKeyCached(depKey)
          val depShard = bindingToShard[resolved] ?: continue
          if (depShard != shardIndex && !seen[depShard]) {
            seen[depShard] = true
            touched.add(depShard)
          }
        }
      }
      // materialize + reset
      if (touched.isNotEmpty()) {
        touched.sort() // deterministic ordering
        out[shardIndex] = touched.toIntArray()
        for (idx in touched) seen[idx] = false
        touched.clear()
      } else {
        out[shardIndex] = IntArray(0)
      }
    }

    return out
  }

  /**
   * Deterministic O(S + D) topological sort at the shard level.
   *
   * We pre-sort each dependents list, seed zero in-degree nodes in ascending order, and use a FIFO
   * queue to avoid PriorityQueue boxing and O(log S) overhead.
   */
  private fun topologicalSortShards(
    shardCount: Int,
    shardDependencies: Array<IntArray>,
  ): List<Int> {
    val inDegree = IntArray(shardCount)
    val dependents = Array(shardCount) { ArrayList<Int>(4) }

    // Build reverse edges + in-degrees.
    for (a in 0 until shardCount) {
      val deps = shardDependencies[a]
      for (b in deps) {
        inDegree[a]++
        dependents[b].add(a)
      }
    }
    // Sort dependents once to make traversal deterministic.
    for (i in 0 until shardCount) dependents[i].sort()

    // Seed queue with zero in-degree nodes in ascending order.
    val q = ArrayDeque<Int>(shardCount)
    for (i in 0 until shardCount) if (inDegree[i] == 0) q.addLast(i)

    val order = ArrayList<Int>(shardCount)
    while (q.isNotEmpty()) {
      val u = q.removeFirst()
      order.add(u)
      for (v in dependents[u]) {
        if (--inDegree[v] == 0) q.addLast(v)
      }
    }

    return if (order.size != shardCount) {
      log("WARNING: Cycle detected in shard dependencies, using sequential initialization order")
      (0 until shardCount).toList()
    } else {
      order
    }
  }

  private fun resolveActualKeyCached(key: IrTypeKey): IrTypeKey {
    // Fast path
    val cached = resolvedAliasCache[key]
    if (cached != null) return cached
    return resolveAndCompress(key)
  }

  /**
   * Resolve alias chains with path compression: every node visited on the walk is memoized to the
   * terminal implementation key.
   */
  private fun resolveAndCompress(start: IrTypeKey): IrTypeKey {
    var current = start
    // Keep a small path buffer to store the chain we traverse.
    val path = ArrayList<IrTypeKey>(4)

    // Follow alias links while the key exists; stop on non-alias binding or cycle.
    while (current in bindingGraph) {
      path += current
      val binding = bindingGraph.requireBinding(current)
      if (binding is IrBinding.Alias) {
        val next = binding.aliasedType
        // Cycle guard: if the next key is already in the path, bail out.
        if (next in path) {
          log("WARNING: Circular alias chain detected for $start, stopping at $current")
          break
        }
        current = next
      } else {
        break
      }
    }

    // Path compression: memoize all visited keys to the terminal 'current'.
    for (k in path) resolvedAliasCache[k] = current
    return current
  }
}
