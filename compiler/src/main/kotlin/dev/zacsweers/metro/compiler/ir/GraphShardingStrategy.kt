// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import org.jetbrains.kotlin.name.Name

/**
 * Strategy for distributing bindings across multiple shards to avoid class size limits.
 * 
 * Based on Dagger's approach but simplified for Metro's IR-based code generation.
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
  )
  
  /**
   * Distributes bindings across shards based on size constraints.
   * 
   * For now, we use a simple chunking strategy. In the future, this could be enhanced
   * with dependency analysis to ensure proper initialization order.
   */
  fun distributeBindings(
    bindings: Collection<IrBinding>,
  ): List<ShardInfo> {
    if (bindings.size <= bindingsPerShard) {
      // No need to shard
      return emptyList()
    }
    
    // Simple strategy: chunk bindings into groups of bindingsPerShard size
    // Future enhancement: use topological sorting and SCC analysis
    return bindings
      .chunked(bindingsPerShard)
      .mapIndexed { index, bindingChunk ->
        ShardInfo(
          index = index + 1, // Start from 1, 0 is the main graph
          name = Name.identifier("GraphShard${index + 1}"),
          bindings = bindingChunk
        )
      }
  }
}