/*
 * Copyright (C) 2024 Zac Sweers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.zacsweers.metro.compiler.graph

import java.util.PriorityQueue
import java.util.SortedMap
import java.util.SortedSet

/**
 * Computes graph sharding by partitioning strongly connected components (SCCs) into
 * manageable shards.
 *
 * The algorithm ensures:
 * - No SCC is split across multiple shards (preserves cycle integrity)
 * - Shards are in valid dependency order (prerequisites before dependents)
 * - Shard sizes target the configured keysPerShard threshold
 * - Inter-shard dependencies are tracked for initialization ordering
 *
 * @param TypeKey The type key representation (FirTypeKey or IrTypeKey)
 * @param bindings All bindings in the component graph
 * @param adjacency Dependency graph: key -> set of keys it depends on
 * @param keysPerShard Target maximum bindings per shard (soft limit)
 * @return Complete sharding result with all shards, mappings, and metrics
 */
internal fun <TypeKey : Comparable<TypeKey>> computeSharding(
  bindings: Set<TypeKey>,
  adjacency: SortedMap<TypeKey, SortedSet<TypeKey>>,
  keysPerShard: Int,
): ShardingResult<TypeKey> {
  // Step 1: Compute strongly connected components using Tarjan's algorithm
  val tarjanResult = adjacency.computeStronglyConnectedComponents(
    roots = bindings.toSortedSet()
  )

  // Step 2: Build component-level dependency graph and topologically sort SCCs
  val componentDag = buildComponentDag(
    originalEdges = adjacency,
    componentOf = tarjanResult.componentOf
  )

  val sortedComponentIds = topologicallySortComponentDag(
    dag = componentDag,
    componentCount = tarjanResult.components.size
  )

  // Map sorted IDs back to Component objects
  val sortedComponents = sortedComponentIds.map { componentId ->
    tarjanResult.components.first { it.id == componentId }
  }

  // Step 3: Partition SCCs into shards (bin-packing algorithm)
  val shardsWithoutDeps = partitionSccsIntoShards(
    sortedComponents = sortedComponents,
    keysPerShard = keysPerShard
  )

  // Step 4: Compute inter-shard dependencies
  val shardsWithDeps = computeShardDependencies(
    shards = shardsWithoutDeps,
    adjacency = adjacency,
    tarjanResult = tarjanResult
  )

  // Step 5: Validate shard ordering (sanity check)
  validateShardOrder(shardsWithDeps)

  // Step 6: Build binding-to-shard mapping
  val bindingToShard = buildBindingToShardMap(shardsWithDeps)

  // Step 7: Compute metrics
  val metrics = computeMetrics(
    shards = shardsWithDeps,
    totalBindings = bindings.size,
    totalSccs = tarjanResult.components.size
  )

  return ShardingResult(
    shards = shardsWithDeps,
    bindingToShard = bindingToShard,
    metrics = metrics
  )
}

/**
 * Partitions SCCs into shards using a greedy bin-packing algorithm.
 *
 * SCCs are processed in dependency order and packed into shards, never exceeding
 * the keysPerShard threshold unless a single SCC is larger than the threshold
 * (in which case it gets its own shard).
 *
 * @param TypeKey The type key representation
 * @param sortedComponents SCCs in topological order (prerequisites first)
 * @param keysPerShard Target maximum bindings per shard
 * @return List of shards without inter-shard dependencies computed yet
 */
private fun <TypeKey> partitionSccsIntoShards(
  sortedComponents: List<Component<TypeKey>>,
  keysPerShard: Int,
): List<Shard<TypeKey>> {
  val shards = mutableListOf<Shard<TypeKey>>()
  var currentShardBindings = mutableListOf<TypeKey>()
  var currentScc: Component<TypeKey>? = null

  for (component in sortedComponents) {
    val componentSize = component.vertices.size
    val currentSize = currentShardBindings.size

    // Check if adding this component would exceed the threshold
    val wouldExceed = currentSize + componentSize > keysPerShard
    val hasBindings = currentShardBindings.isNotEmpty()

    if (wouldExceed && hasBindings) {
      // Finalize current shard before adding this component
      shards.add(
        Shard(
          id = shards.size,
          bindings = currentShardBindings.toList(),
          dependencies = emptySet(), // Will be computed later
          scc = currentScc
        )
      )
      currentShardBindings.clear()
      currentScc = null
    }

    // Add component to current shard
    currentShardBindings.addAll(component.vertices)

    // Track the SCC for this shard (use the last/largest one if multiple)
    currentScc = component
  }

  // Finalize last shard if it has any bindings
  if (currentShardBindings.isNotEmpty()) {
    shards.add(
      Shard(
        id = shards.size,
        bindings = currentShardBindings.toList(),
        dependencies = emptySet(),
        scc = currentScc
      )
    )
  }

  return shards
}

/**
 * Computes inter-shard dependencies based on binding-level dependencies.
 *
 * For each shard, determines which other shards it depends on by examining
 * the dependencies of all bindings in the shard.
 *
 * @param TypeKey The type key representation
 * @param shards Shards without dependencies computed
 * @param adjacency Dependency graph from original bindings
 * @param tarjanResult SCC computation result for component tracking
 * @return Shards with dependencies populated
 */
private fun <TypeKey : Comparable<TypeKey>> computeShardDependencies(
  shards: List<Shard<TypeKey>>,
  adjacency: Map<TypeKey, Set<TypeKey>>,
  tarjanResult: TarjanResult<TypeKey>,
): List<Shard<TypeKey>> {
  // Build mapping: binding -> shard ID
  val bindingToShardId = mutableMapOf<TypeKey, Int>()
  for (shard in shards) {
    for (binding in shard.bindings) {
      bindingToShardId[binding] = shard.id
    }
  }

  // For each shard, find which other shards it depends on
  return shards.map { shard ->
    val dependencies = mutableSetOf<Int>()

    for (binding in shard.bindings) {
      // Look up dependencies of this binding in the adjacency map
      for (dependency in adjacency[binding].orEmpty()) {
        val depShardId = bindingToShardId[dependency]

        // Only track cross-shard dependencies (not self-dependencies)
        if (depShardId != null && depShardId != shard.id) {
          dependencies.add(depShardId)
        }
      }
    }

    shard.copy(dependencies = dependencies)
  }
}

/**
 * Validates that shards are in correct dependency order.
 *
 * Ensures that no shard depends on a shard with a higher ID (forward dependency),
 * which would violate the initialization order.
 *
 * @throws IllegalStateException if an invalid dependency is found
 */
private fun <TypeKey> validateShardOrder(shards: List<Shard<TypeKey>>) {
  for (shard in shards) {
    for (depShardId in shard.dependencies) {
      check(depShardId < shard.id) {
        """
        Invalid shard order detected!
        Shard ${shard.id} depends on Shard $depShardId, but Shard $depShardId comes after Shard ${shard.id}.
        This indicates a bug in the sharding algorithm's topological ordering.
        """.trimIndent()
      }
    }
  }
}

/**
 * Builds a mapping from each binding to its containing shard ID.
 *
 * @param TypeKey The type key representation
 * @param shards All shards with bindings
 * @return Map from binding key to shard ID
 */
private fun <TypeKey> buildBindingToShardMap(
  shards: List<Shard<TypeKey>>
): Map<TypeKey, Int> {
  return shards.flatMap { shard ->
    shard.bindings.map { binding -> binding to shard.id }
  }.toMap()
}

/**
 * Computes metrics about the sharding quality.
 *
 * Metrics include distribution statistics, balance factors, and SCC information
 * useful for debugging and reporting.
 *
 * @param TypeKey The type key representation
 * @param shards All shards
 * @param totalBindings Total number of bindings across all shards
 * @param totalSccs Total number of SCCs found
 * @return Sharding metrics
 */
private fun <TypeKey> computeMetrics(
  shards: List<Shard<TypeKey>>,
  totalBindings: Int,
  totalSccs: Int,
): ShardingMetrics {
  val shardSizes = shards.map { it.bindings.size }
  val largestScc = shards.mapNotNull { it.scc?.vertices?.size }.maxOrNull() ?: 0
  val avgShardSize = if (shards.isNotEmpty()) {
    totalBindings.toDouble() / shards.size
  } else {
    0.0
  }

  // Balance factor: ratio of largest shard to average shard size
  // 1.0 = perfectly balanced, >2.0 = highly skewed
  val maxShardSize = shardSizes.maxOrNull() ?: 0
  val balanceFactor = if (avgShardSize > 0) {
    maxShardSize / avgShardSize
  } else {
    1.0
  }

  return ShardingMetrics(
    totalBindings = totalBindings,
    totalSccs = totalSccs,
    largestScc = largestScc,
    shardSizes = shardSizes,
    avgShardSize = avgShardSize,
    balanceFactor = balanceFactor
  )
}

/**
 * Builds a component-level dependency DAG from the original binding graph.
 *
 * This is used internally by the sharding algorithm to topologically sort SCCs.
 * Reuses Metro's existing implementation.
 *
 * @see dev.zacsweers.metro.compiler.graph.buildComponentDag
 */
private fun <V> buildComponentDag(
  originalEdges: Map<V, Set<V>>,
  componentOf: Map<V, Int>,
): Map<Int, Set<Int>> {
  val dag = mutableMapOf<Int, MutableSet<Int>>()

  for ((fromVertex, outs) in originalEdges) {
    val prereqComp = componentOf.getValue(fromVertex)
    for (toVertex in outs) {
      val dependentComp = componentOf.getValue(toVertex)
      if (prereqComp != dependentComp) {
        // Reverse arrow for Kahn's algorithm: dependent -> prerequisite
        dag.getOrPut(dependentComp, ::mutableSetOf) += prereqComp
      }
    }
  }
  return dag
}

/**
 * Topologically sorts component IDs using Kahn's algorithm.
 *
 * Uses a priority queue to ensure deterministic ordering when multiple
 * components are ready at the same time.
 *
 * Reuses Metro's existing implementation.
 *
 * @see dev.zacsweers.metro.compiler.graph.topologicallySortComponentDag
 */
private fun topologicallySortComponentDag(
  dag: Map<Int, Set<Int>>,
  componentCount: Int
): List<Int> {
  val inDegree = IntArray(componentCount)
  dag.values.flatten().forEach { inDegree[it]++ }

  val queue = PriorityQueue<Int>().apply {
    for (id in 0 until componentCount) {
      if (inDegree[id] == 0) {
        add(id)
      }
    }
  }

  val order = mutableListOf<Int>()
  while (queue.isNotEmpty()) {
    val c = queue.remove()
    order += c
    for (n in dag[c].orEmpty()) {
      if (--inDegree[n] == 0) {
        queue += n
      }
    }
  }

  check(order.size == componentCount) {
    "Cycle remained after SCC collapse (should be impossible)"
  }

  return order
}
