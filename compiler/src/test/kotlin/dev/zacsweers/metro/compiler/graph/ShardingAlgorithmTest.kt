// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import com.google.common.truth.Truth.assertThat
import java.util.SortedSet
import kotlin.test.Test

/**
 * Manual validation tests for the SCC-based sharding algorithm.
 *
 * These tests validate core algorithm properties:
 * - No SCC is split across multiple shards
 * - Shards are in valid dependency order (no forward dependencies)
 * - Shard sizes respect keysPerShard threshold
 * - Metrics are computed correctly
 *
 * Note: These are Phase 1 validation tests. Comprehensive test suite will be
 * developed in Phase 5 after full implementation is proven.
 */
class ShardingAlgorithmTest {

  /**
   * Test Case 1: Simple Linear Dependencies
   *
   * Graph: A → B → C → D (4 bindings, keysPerShard=2)
   *
   * Expected:
   * - Shard 0: [D, C] (prerequisites)
   * - Shard 1: [B, A] (dependents)
   * - Shard 1 depends on Shard 0
   */
  @Test
  fun `simple linear chain is partitioned correctly`() {
    // Build adjacency: A->B, B->C, C->D
    val adjacency = sortedMapOf(
      StringTypeKey("A") to typedSortedSetOf(StringTypeKey("B")),
      StringTypeKey("B") to typedSortedSetOf(StringTypeKey("C")),
      StringTypeKey("C") to typedSortedSetOf(StringTypeKey("D")),
      StringTypeKey("D") to typedSortedSetOf()
    )

    val bindings = adjacency.keys.toSet()

    val result = computeSharding(
      bindings = bindings,
      adjacency = adjacency,
      keysPerShard = 2
    )

    // Should create 2 shards
    assertThat(result.shards).hasSize(2)

    // Shard 0 should contain prerequisites (D, C)
    assertThat(result.shards[0].bindings.map { it.type }).containsExactly("D", "C")

    // Shard 1 should contain dependents (B, A)
    assertThat(result.shards[1].bindings.map { it.type }).containsExactly("B", "A")

    // Shard 1 should depend on Shard 0
    assertThat(result.shards[1].dependencies).containsExactly(0)

    // Verify binding-to-shard mapping
    assertThat(result.bindingToShard[StringTypeKey("A")]).isEqualTo(1)
    assertThat(result.bindingToShard[StringTypeKey("B")]).isEqualTo(1)
    assertThat(result.bindingToShard[StringTypeKey("C")]).isEqualTo(0)
    assertThat(result.bindingToShard[StringTypeKey("D")]).isEqualTo(0)

    // Verify metrics
    assertThat(result.metrics.totalBindings).isEqualTo(4)
    assertThat(result.metrics.shardSizes).containsExactly(2, 2)
    assertThat(result.metrics.avgShardSize).isEqualTo(2.0)
    assertThat(result.metrics.balanceFactor).isEqualTo(1.0) // perfectly balanced
  }

  /**
   * Test Case 2: Diamond Dependencies
   *
   * Graph:
   *     A
   *    / \
   *   B   C
   *    \ /
   *     D
   *
   * (4 bindings, keysPerShard=2)
   *
   * Expected: Greedy packing in dependency order (D before B,C before A)
   * Actual packing depends on SCC topological sort, should be:
   * - Shard 0: [D, B] or [D, C] (2 bindings)
   * - Shard 1: [C, A] or [B, A] (2 bindings)
   */
  @Test
  fun `diamond dependencies are partitioned correctly`() {
    val adjacency = sortedMapOf(
      StringTypeKey("A") to typedSortedSetOf(StringTypeKey("B"), StringTypeKey("C")),
      StringTypeKey("B") to typedSortedSetOf(StringTypeKey("D")),
      StringTypeKey("C") to typedSortedSetOf(StringTypeKey("D")),
      StringTypeKey("D") to typedSortedSetOf()
    )

    val bindings = adjacency.keys.toSet()

    val result = computeSharding(
      bindings = bindings,
      adjacency = adjacency,
      keysPerShard = 2
    )

    // Should create 2 shards (4 bindings / 2 per shard)
    assertThat(result.shards).hasSize(2)

    // D must come in an earlier or same shard as B and C (it's a prerequisite)
    val dShardId = result.bindingToShard[StringTypeKey("D")]!!
    val bShardId = result.bindingToShard[StringTypeKey("B")]!!
    val cShardId = result.bindingToShard[StringTypeKey("C")]!!
    val aShardId = result.bindingToShard[StringTypeKey("A")]!!

    assertThat(dShardId).isAtMost(bShardId)
    assertThat(dShardId).isAtMost(cShardId)
    assertThat(dShardId).isAtMost(aShardId)

    // A must come in a later or same shard as B and C (it depends on them)
    assertThat(aShardId).isAtLeast(bShardId)
    assertThat(aShardId).isAtLeast(cShardId)

    // Verify no forward dependencies (critical property!)
    for (shard in result.shards) {
      for (depId in shard.dependencies) {
        assertThat(depId).isLessThan(shard.id)
      }
    }

    // Verify metrics
    assertThat(result.metrics.totalBindings).isEqualTo(4)
    assertThat(result.metrics.shardSizes).containsExactly(2, 2)
  }

  /**
   * Test Case 3: Circular Dependencies (SCC)
   *
   * Graph: A → B → C → A (3-node cycle)
   *
   * Expected:
   * - All three bindings must be in the same shard (SCC cannot be split)
   * - Should create 1 shard with 3 bindings
   */
  @Test
  fun `circular dependencies are kept in single shard`() {
    val adjacency = sortedMapOf(
      StringTypeKey("A") to typedSortedSetOf(StringTypeKey("B")),
      StringTypeKey("B") to typedSortedSetOf(StringTypeKey("C")),
      StringTypeKey("C") to typedSortedSetOf(StringTypeKey("A"))
    )

    val bindings = adjacency.keys.toSet()

    val result = computeSharding(
      bindings = bindings,
      adjacency = adjacency,
      keysPerShard = 2  // Threshold is 2, but SCC has 3 nodes
    )

    // Should create 1 shard (cannot split the SCC)
    assertThat(result.shards).hasSize(1)

    // All bindings should be in shard 0
    assertThat(result.shards[0].bindings).hasSize(3)
    assertThat(result.shards[0].bindings.map { it.type })
      .containsExactly("A", "B", "C")

    // No dependencies (only one shard)
    assertThat(result.shards[0].dependencies).isEmpty()

    // All bindings map to shard 0
    assertThat(result.bindingToShard[StringTypeKey("A")]).isEqualTo(0)
    assertThat(result.bindingToShard[StringTypeKey("B")]).isEqualTo(0)
    assertThat(result.bindingToShard[StringTypeKey("C")]).isEqualTo(0)

    // Verify SCC information
    assertThat(result.metrics.totalSccs).isEqualTo(1)
    assertThat(result.metrics.largestScc).isEqualTo(3)
  }

  /**
   * Test Case 4: Large SCC Exceeding keysPerShard
   *
   * Graph: 10-node cycle (keysPerShard=5)
   *
   * Expected:
   * - All 10 bindings in one shard (SCC integrity preserved)
   * - Metrics should show balance factor > 1.0 (unbalanced due to large SCC)
   */
  @Test
  fun `large SCC exceeding threshold is not split`() {
    // Build a 10-node cycle: A->B->C->...->J->A
    val keys = ('A'..'J').map { StringTypeKey(it.toString()) }
    val adjacency = sortedMapOf<StringTypeKey, SortedSet<StringTypeKey>>()

    for (i in keys.indices) {
      val current = keys[i]
      val next = keys[(i + 1) % keys.size]
      adjacency[current] = typedSortedSetOf(next)
    }

    val bindings = adjacency.keys.toSet()

    val result = computeSharding(
      bindings = bindings,
      adjacency = adjacency,
      keysPerShard = 5  // Threshold is 5, but SCC has 10 nodes
    )

    // Should create 1 shard (cannot split the SCC)
    assertThat(result.shards).hasSize(1)
    assertThat(result.shards[0].bindings).hasSize(10)

    // Verify metrics
    assertThat(result.metrics.totalBindings).isEqualTo(10)
    assertThat(result.metrics.totalSccs).isEqualTo(1)
    assertThat(result.metrics.largestScc).isEqualTo(10)
    assertThat(result.metrics.shardSizes).containsExactly(10)
    assertThat(result.metrics.avgShardSize).isEqualTo(10.0)

    // Balance factor should be 1.0 (only one shard, so max == avg)
    assertThat(result.metrics.balanceFactor).isEqualTo(1.0)
  }

  /**
   * Test Case 5: Multiple SCCs
   *
   * Graph:
   * - SCC1: A ↔ B ↔ C (3-node cycle)
   * - SCC2: D ↔ E (2-node cycle)
   * - SCC3: F (singleton)
   *
   * (6 bindings, keysPerShard=3)
   *
   * Expected:
   * - Shard 0: [A, B, C] (SCC1 - exactly at threshold)
   * - Shard 1: [D, E, F] (SCC2 + SCC3 - packed together)
   */
  @Test
  fun `multiple SCCs are distributed across shards`() {
    val adjacency = sortedMapOf(
      // SCC1: A ↔ B ↔ C (3-node cycle)
      StringTypeKey("A") to typedSortedSetOf(StringTypeKey("B")),
      StringTypeKey("B") to typedSortedSetOf(StringTypeKey("C")),
      StringTypeKey("C") to typedSortedSetOf(StringTypeKey("A")),
      // SCC2: D ↔ E (2-node cycle)
      StringTypeKey("D") to typedSortedSetOf(StringTypeKey("E")),
      StringTypeKey("E") to typedSortedSetOf(StringTypeKey("D")),
      // SCC3: F (singleton, no dependencies)
      StringTypeKey("F") to typedSortedSetOf()
    )

    val bindings = adjacency.keys.toSet()

    val result = computeSharding(
      bindings = bindings,
      adjacency = adjacency,
      keysPerShard = 3
    )

    // Should create 2 shards
    assertThat(result.shards).hasSize(2)

    // Verify each SCC is in exactly one shard (not split)
    val sccA = result.bindingToShard[StringTypeKey("A")]!!
    val sccB = result.bindingToShard[StringTypeKey("B")]!!
    val sccC = result.bindingToShard[StringTypeKey("C")]!!
    assertThat(sccA).isEqualTo(sccB)
    assertThat(sccB).isEqualTo(sccC)

    val sccD = result.bindingToShard[StringTypeKey("D")]!!
    val sccE = result.bindingToShard[StringTypeKey("E")]!!
    assertThat(sccD).isEqualTo(sccE)

    // Verify metrics
    assertThat(result.metrics.totalBindings).isEqualTo(6)
    assertThat(result.metrics.totalSccs).isEqualTo(3)
    assertThat(result.metrics.largestScc).isEqualTo(3)
  }

  /**
   * Test Case 6: Empty Graph
   *
   * Expected: Should handle gracefully with zero shards
   */
  @Test
  fun `empty graph produces empty result`() {
    val adjacency = sortedMapOf<StringTypeKey, SortedSet<StringTypeKey>>()
    val bindings = emptySet<StringTypeKey>()

    val result = computeSharding(
      bindings = bindings,
      adjacency = adjacency,
      keysPerShard = 10
    )

    assertThat(result.shards).isEmpty()
    assertThat(result.bindingToShard).isEmpty()
    assertThat(result.metrics.totalBindings).isEqualTo(0)
    assertThat(result.metrics.totalSccs).isEqualTo(0)
  }

  /**
   * Test Case 7: Single Binding
   *
   * Expected: One shard with one binding, no dependencies
   */
  @Test
  fun `single binding creates single shard`() {
    val adjacency = sortedMapOf(
      StringTypeKey("A") to typedSortedSetOf<StringTypeKey>()
    )
    val bindings = adjacency.keys.toSet()

    val result = computeSharding(
      bindings = bindings,
      adjacency = adjacency,
      keysPerShard = 10
    )

    assertThat(result.shards).hasSize(1)
    assertThat(result.shards[0].bindings).hasSize(1)
    assertThat(result.shards[0].bindings[0].type).isEqualTo("A")
    assertThat(result.shards[0].dependencies).isEmpty()

    assertThat(result.metrics.totalBindings).isEqualTo(1)
    assertThat(result.metrics.totalSccs).isEqualTo(1)
    assertThat(result.metrics.largestScc).isEqualTo(1)
  }

  // Helper function to create typed sorted sets
  private fun <T : Comparable<T>> typedSortedSetOf(vararg elements: T): SortedSet<T> {
    return sortedSetOf(*elements)
  }
}
