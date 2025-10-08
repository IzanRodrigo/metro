// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

/**
 * Computes global binding order information used by sharding.
 *
 * This first iteration simply exposes all reachable bindings in the same order used for the
 * non-sharded graph (via [IrBindingGraph.BindingGraphResult.sortedKeys]). Later phases will attach
 * ownership metadata so bindings can be reassigned across shards and extensions.
 */
internal class GlobalBindingPlanner(
  private val bindingGraph: IrBindingGraph,
  private val sealResult: IrBindingGraph.BindingGraphResult,
) {

  internal data class Result(
    val allBindingsInOrder: List<IrBinding>,
  )

  fun plan(): Result {
    val orderedBindings = buildList {
      for (key in sealResult.sortedKeys) {
        if (key !in sealResult.reachableKeys) continue
        val binding = bindingGraph.findBinding(key) ?: continue
        add(binding)
      }
    }
    return Result(allBindingsInOrder = orderedBindings)
  }
}
