// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0

package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.graph.computeShardsFromBindings
import dev.zacsweers.metro.compiler.ir.IrBindingGraph

/**
 * Support class for generating sharded graph implementations.
 *
 * This class is a work-in-progress. It will compute shards from the current binding graph
 * and in the future will emit multiple nested classes to avoid JVM class and method size limits.
 */
internal class IrGraphShardingGenerator(
  private val bindingGraph: IrBindingGraph
) {
  // Compute shards using the extension property defined in ShardingExtensions.kt
  private val shards = bindingGraph.shards

  fun generate() {
    // TODO: Iterate over shards and delegate generation to IrGraphExpressionGenerator
    // or a future sharding-aware generator. For now, this just logs shard sizes.
    shards.forEachIndexed { index, shard ->
      // Placeholder: Print or log information about each shard for debugging.
      // In the actual implementation, this will create an IrClass for each shard
      // and populate it with provider fields and methods.
    }
  }
}
