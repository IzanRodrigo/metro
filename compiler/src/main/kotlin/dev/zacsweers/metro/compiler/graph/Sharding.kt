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

/**
 * Represents a single shard in the component graph.
 *
 * A shard is a partition of the dependency graph that will be generated as a nested class
 * within the component. Shards are created by partitioning strongly connected components (SCCs)
 * to keep individual methods under size limits and improve compilation performance.
 *
 * @param TypeKey The type key representation (FirTypeKey or IrTypeKey)
 * @property id Unique identifier for this shard (0-indexed)
 * @property bindings List of type keys contained in this shard
 * @property dependencies Set of shard IDs that this shard depends on (for initialization ordering)
 * @property scc The strongly connected component this shard was created from (null for non-SCC shards)
 */
internal data class Shard<TypeKey>(
  val id: Int,
  val bindings: List<TypeKey>,
  val dependencies: Set<Int>,
  val scc: Component<TypeKey>?,
)

/**
 * Result of the sharding algorithm.
 *
 * Contains all shards created from the dependency graph, along with mapping information
 * and metrics for debugging and reporting.
 *
 * @param TypeKey The type key representation (FirTypeKey or IrTypeKey)
 * @property shards List of all shards in initialization order
 * @property bindingToShard Map from each binding's type key to its shard ID
 * @property metrics Performance and distribution metrics about the sharding
 */
internal data class ShardingResult<TypeKey>(
  val shards: List<Shard<TypeKey>>,
  val bindingToShard: Map<TypeKey, Int>,
  val metrics: ShardingMetrics,
)

/**
 * Metrics about the sharding algorithm's results.
 *
 * Used for debugging, reporting, and validating the quality of the sharding.
 * These metrics help identify potential issues like severely unbalanced shards
 * or degenerate cases.
 *
 * @property totalBindings Total number of bindings across all shards
 * @property totalSccs Total number of strongly connected components found
 * @property largestScc Size of the largest SCC (useful for detecting large cycles)
 * @property shardSizes List of binding counts per shard
 * @property avgShardSize Average number of bindings per shard
 * @property balanceFactor Measure of shard size distribution (1.0 = perfectly balanced, >2.0 = highly skewed)
 */
internal data class ShardingMetrics(
  val totalBindings: Int,
  val totalSccs: Int,
  val largestScc: Int,
  val shardSizes: List<Int>,
  val avgShardSize: Double,
  val balanceFactor: Double,
)
