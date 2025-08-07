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
   * Distributes SCCs across shards while respecting size constraints.
   * SCCs are kept together to avoid circular dependencies between shards.
   */
  private fun distributeSccsToShards(
    bindingsByScc: Map<Int, List<IrBinding>>,
    componentOf: Map<IrTypeKey, Int>,
    adjacency: SortedMap<IrTypeKey, SortedSet<IrTypeKey>>,
  ): List<ShardInfo> {
    val shards = mutableListOf<ShardInfo>()
    val sccToShard = mutableMapOf<Int, Int>()
    
    // Sort SCCs by size (largest first) to optimize packing
    val sortedSccs = bindingsByScc.entries.sortedByDescending { it.value.size }
    
    var currentShardBindings = mutableListOf<IrBinding>()
    var currentShardIndex = 1
    
    for ((sccId, sccBindings) in sortedSccs) {
      // If adding this SCC would exceed the limit, create a new shard
      if (currentShardBindings.isNotEmpty() && 
          currentShardBindings.size + sccBindings.size > bindingsPerShard) {
        // Finalize current shard
        shards.add(createShardInfo(currentShardIndex, currentShardBindings))
        currentShardBindings = mutableListOf()
        currentShardIndex++
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