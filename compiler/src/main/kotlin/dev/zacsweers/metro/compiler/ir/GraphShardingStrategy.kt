// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.graph.TopoSortResult
import dev.zacsweers.metro.compiler.graph.buildFullAdjacency
import dev.zacsweers.metro.compiler.graph.computeStronglyConnectedComponents
import dev.zacsweers.metro.compiler.graph.topologicalSort
import org.jetbrains.kotlin.name.Name
import java.util.SortedMap
import java.util.SortedSet
import java.util.TreeSet

/**
 * Strategy for distributing bindings across multiple shards to avoid class size limits.
 * 
 * This implementation uses dependency analysis to:
 * 1. Group strongly connected components (SCCs) together to avoid circular dependencies between shards
 * 2. Distribute bindings in topological order to ensure proper initialization
 * 3. Enable parallel shard generation by identifying independent shard groups
 */
internal class GraphShardingStrategy(
  private val bindingsPerShard: Int,
) {
  
  /**
   * Information about a graph shard
   */
  data class ShardInfo(
    val index: Int,
    val name: Name,
    val bindings: List<IrBinding>,
    val dependencies: Set<Int> = emptySet(), // Dependencies on other shard indices
  )
  
  /**
   * Result of the sharding analysis containing both the shards and parallelization information
   */
  data class ShardingResult(
    val shards: List<ShardInfo>,
    val parallelGroups: List<Set<Int>>, // Groups of shard indices that can be generated in parallel
  )
  
  /**
   * Distributes bindings across shards using dependency analysis to avoid circular references
   * and identify opportunities for parallel generation.
   * 
   * Algorithm:
   * 1. Build dependency graph between bindings
   * 2. Find strongly connected components (SCCs) - these must stay together
   * 3. Distribute SCCs across shards respecting size constraints
   * 4. Analyze shard dependencies to identify parallel generation groups
   */
  fun distributeBindings(
    bindings: Map<IrTypeKey, IrBinding>,
    bindingGraph: TopoSortResult<IrTypeKey>? = null,
  ): ShardingResult {
    if (bindings.size <= bindingsPerShard) {
      // No need to shard
      return ShardingResult(emptyList(), emptyList())
    }
    
    // Build adjacency list for dependency analysis if not provided
    val adjacency = if (bindingGraph == null) {
      buildFullAdjacency(
        bindings = bindings,
        dependenciesOf = { binding -> binding.dependencies.map { it.typeKey } },
        onMissing = { _, _ -> /* ignore missing for sharding */ },
      )
    } else {
      // Use the already computed adjacency from binding graph
      buildFullAdjacency(
        bindings = bindings,
        dependenciesOf = { binding -> binding.dependencies.map { it.typeKey } },
        onMissing = { _, _ -> /* ignore missing for sharding */ },
      )
    }
    
    // Find strongly connected components - these must stay in the same shard
    val (components, componentOf) = adjacency.computeStronglyConnectedComponents()
    
    // Group bindings by their SCC
    val bindingsByScc = mutableMapOf<Int, MutableList<IrBinding>>()
    for ((key, binding) in bindings) {
      val sccId = componentOf[key] ?: continue // Skip unreachable bindings
      bindingsByScc.getOrPut(sccId) { mutableListOf() }.add(binding)
    }
    
    // Distribute SCCs across shards
    val shards = distributeSccsToShards(bindingsByScc, componentOf, adjacency)
    
    // Analyze shard dependencies for parallel generation
    val parallelGroups = analyzeParallelGroups(shards)
    
    return ShardingResult(shards, parallelGroups)
  }
  
  /**
   * Distributes SCCs across shards while respecting size constraints AND dependency order.
   * SCCs are kept together to avoid circular dependencies between shards.
   * 
   * CRITICAL: Dependencies must be placed in EARLIER shards (lower index) than their dependents.
   * This ensures that when a shard initializes, all its dependencies in other shards already exist.
   */
  private fun distributeSccsToShards(
    bindingsByScc: Map<Int, List<IrBinding>>,
    componentOf: Map<IrTypeKey, Int>,
    adjacency: SortedMap<IrTypeKey, SortedSet<IrTypeKey>>,
  ): List<ShardInfo> {
    val shards = mutableListOf<ShardInfo>()
    val sccToShard = mutableMapOf<Int, Int>()
    
    // Build SCC dependency graph
    val sccAdjacency = mutableMapOf<Int, MutableSet<Int>>()
    for ((sccId, bindings) in bindingsByScc) {
      sccAdjacency[sccId] = mutableSetOf()
      for (binding in bindings) {
        for (depKey in adjacency[binding.typeKey] ?: emptySet()) {
          val depSccId = componentOf[depKey] ?: continue
          if (depSccId != sccId) {
            sccAdjacency[sccId]!!.add(depSccId)
          }
        }
      }
    }
    
    // Perform topological sort on SCCs
    val sortedSccs = topologicalSortSccs(bindingsByScc.keys, sccAdjacency)
    
    var currentShardBindings = mutableListOf<IrBinding>()
    var currentShardIndex = 1
    
    // Process SCCs in REVERSE topological order (dependencies first)
    for (sccId in sortedSccs.reversed()) {
      val sccBindings = bindingsByScc[sccId] ?: continue
      
      // Check if this SCC must go in a new shard due to dependencies
      val mustCreateNewShard = needsNewShardForDependencies(sccId, sccAdjacency, sccToShard, currentShardIndex)
      
      // If adding this SCC would exceed the limit OR dependencies require it, create a new shard
      if (mustCreateNewShard || 
          (currentShardBindings.isNotEmpty() && 
           currentShardBindings.size + sccBindings.size > bindingsPerShard)) {
        // Finalize current shard if it has bindings
        if (currentShardBindings.isNotEmpty()) {
          shards.add(createShardInfo(currentShardIndex, currentShardBindings))
          currentShardBindings = mutableListOf()
          currentShardIndex++
        }
      }
      
      // Add all bindings from this SCC to current shard
      currentShardBindings.addAll(sccBindings)
      sccToShard[sccId] = currentShardIndex
    }
    
    // Don't forget the last shard
    if (currentShardBindings.isNotEmpty()) {
      shards.add(createShardInfo(currentShardIndex, currentShardBindings))
    }
    
    // Calculate dependencies between shards
    return shards.map { shard ->
      val dependencies = calculateShardDependencies(shard, shards, componentOf, adjacency, sccToShard)
      shard.copy(dependencies = dependencies)
    }
  }
  
  /**
   * Performs topological sort on SCCs to ensure proper ordering
   * Uses iterative approach to avoid stack overflow with deep recursion
   */
  private fun topologicalSortSccs(
    sccIds: Set<Int>,
    sccAdjacency: Map<Int, Set<Int>>
  ): List<Int> {
    // Use Kahn's algorithm (iterative) instead of DFS to avoid stack overflow
    val inDegree = mutableMapOf<Int, Int>()
    val result = mutableListOf<Int>()
    
    // Initialize in-degrees
    for (sccId in sccIds) {
      inDegree[sccId] = 0
    }
    
    // Calculate in-degrees
    for (sccId in sccIds) {
      for (dep in sccAdjacency[sccId] ?: emptySet()) {
        if (dep in sccIds) {
          inDegree[dep] = (inDegree[dep] ?: 0) + 1
        }
      }
    }
    
    // Find all nodes with no incoming edges
    val queue = ArrayDeque<Int>()
    for ((sccId, degree) in inDegree) {
      if (degree == 0) {
        queue.add(sccId)
      }
    }
    
    // Build reverse adjacency for efficient lookup
    val reverseAdjacency = mutableMapOf<Int, MutableSet<Int>>()
    for (sccId in sccIds) {
      for (dep in sccAdjacency[sccId] ?: emptySet()) {
        if (dep in sccIds) {
          reverseAdjacency.getOrPut(dep) { mutableSetOf() }.add(sccId)
        }
      }
    }
    
    // Process nodes in topological order
    while (queue.isNotEmpty()) {
      val current = queue.removeFirst()
      result.add(current)
      
      // For each node that depends on current (using reverse adjacency for O(1) lookup)
      for (dependent in reverseAdjacency[current] ?: emptySet()) {
        inDegree[dependent] = inDegree[dependent]!! - 1
        if (inDegree[dependent] == 0) {
          queue.add(dependent)
        }
      }
    }
    
    // If we couldn't process all SCCs, there's a cycle (shouldn't happen with proper SCC detection)
    // Return remaining SCCs in arbitrary order
    if (result.size < sccIds.size) {
      for (sccId in sccIds) {
        if (sccId !in result) {
          result.add(sccId)
        }
      }
    }
    
    return result
  }
  
  /**
   * Checks if an SCC needs to be in a new shard due to its dependencies
   */
  private fun needsNewShardForDependencies(
    sccId: Int,
    sccAdjacency: Map<Int, Set<Int>>,
    sccToShard: Map<Int, Int>,
    currentShardIndex: Int
  ): Boolean {
    // Check if any of this SCC's dependencies are in the current or later shards
    for (dep in sccAdjacency[sccId] ?: emptySet()) {
      val depShardIndex = sccToShard[dep] ?: continue
      if (depShardIndex >= currentShardIndex) {
        // Dependency is in current or later shard - we need a new shard
        return true
      }
    }
    return false
  }
  
  /**
   * Creates a ShardInfo with proper naming
   */
  private fun createShardInfo(index: Int, bindings: List<IrBinding>): ShardInfo {
    return ShardInfo(
      index = index,
      name = Name.identifier("GraphShard$index"),
      bindings = bindings,
    )
  }
  
  /**
   * Calculates which other shards this shard depends on
   */
  private fun calculateShardDependencies(
    shard: ShardInfo,
    allShards: List<ShardInfo>,
    componentOf: Map<IrTypeKey, Int>,
    adjacency: SortedMap<IrTypeKey, SortedSet<IrTypeKey>>,
    sccToShard: Map<Int, Int>,
  ): Set<Int> {
    val dependencies = mutableSetOf<Int>()
    val shardBindingKeys = shard.bindings.map { it.typeKey }.toSet()
    
    // Check each binding in this shard
    for (binding in shard.bindings) {
      // Look at its dependencies
      for (depKey in adjacency[binding.typeKey] ?: emptySet()) {
        // If the dependency is not in this shard
        if (depKey !in shardBindingKeys) {
          // Find which shard contains it
          val depSccId = componentOf[depKey] ?: continue
          val depShardIndex = sccToShard[depSccId] ?: continue
          if (depShardIndex != shard.index) {
            dependencies.add(depShardIndex)
          }
        }
      }
    }
    
    return dependencies
  }
  
  /**
   * Analyzes shard dependencies to identify groups that can be generated in parallel.
   * Returns a list of sets, where each set contains shard indices that can be generated in parallel.
   */
  private fun analyzeParallelGroups(shards: List<ShardInfo>): List<Set<Int>> {
    if (shards.isEmpty()) return emptyList()
    
    // Build adjacency for shards
    val shardAdjacency = shards.associate { shard ->
      shard.index to shard.dependencies.toSortedSet()
    }.toSortedMap()
    
    // Perform topological sort on shards
    val roots = TreeSet(shards.filter { it.dependencies.isEmpty() }.map { it.index })
    val topoResult = topologicalSort(
      fullAdjacency = shardAdjacency,
      isDeferrable = { _, _ -> false }, // No deferrable dependencies between shards
      onCycle = { cycle -> 
        error("Circular dependency between shards should not happen: $cycle")
      },
      roots = roots,
    )
    
    // Group shards by their level in the dependency graph
    val levels = mutableListOf<Set<Int>>()
    val shardLevel = mutableMapOf<Int, Int>()
    
    for (shardIndex in topoResult.sortedKeys) {
      val deps = shardAdjacency[shardIndex] ?: emptySet()
      val level = if (deps.isEmpty()) {
        0
      } else {
        deps.maxOf { shardLevel[it]!! } + 1
      }
      
      shardLevel[shardIndex] = level
      
      if (level >= levels.size) {
        levels.add(mutableSetOf())
      }
      (levels[level] as MutableSet).add(shardIndex)
    }
    
    return levels
  }
}