// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.graph.WrappedType
import org.jetbrains.kotlin.ir.declarations.IrClass

/**
 * Fast Init: TRUE deferred initialization optimization for Metro.
 *
 * Unlike Dagger's provider caching approach, Metro implements true deferred initialization:
 * - Analyzes component entry points (interface methods)
 * - Performs reachability analysis to identify eager vs deferred bindings
 * - Only initializes bindings reachable from entry points
 * - Defers everything else until first access
 *
 * This leverages Metro's K2 compile-time analysis advantage for potentially better
 * performance than Dagger's 10-30% improvement.
 */

/**
 * Result of Fast Init reachability analysis.
 */
internal data class FastInitResult(
  val eagerKeys: Set<IrTypeKey>,
  val deferredKeys: Set<IrTypeKey>,
  val entryPointKeys: Set<IrTypeKey>
) {
  val eagerCount: Int get() = eagerKeys.size
  val deferredCount: Int get() = deferredKeys.size
  val totalCount: Int get() = eagerCount + deferredCount
  val deferralRatio: Double =
    if (totalCount > 0) deferredCount.toDouble() / totalCount else 0.0

  fun isAllEager(): Boolean = deferredCount == 0
  fun hasNoEntryPoints(): Boolean = entryPointKeys.isEmpty()
}

/**
 * Analyze component for Fast Init optimization.
 *
 * Extracts entry points from component interface and performs reachability analysis
 * to classify bindings as eager (needed at startup) vs deferred (lazy initialization).
 */
context(context: IrMetroContext)
internal fun analyzeFastInit(
  component: IrClass,
  bindingGraph: IrBindingGraph
): FastInitResult {
  // Extract entry points from component interface
  val entryPointKeys = component.abstractFunctions()
    .map { function ->
      IrContextualTypeKey.from(function).typeKey
    }
    .toSet()

  // Perform reachability analysis
  val eager = mutableSetOf<IrTypeKey>()
  val visited = mutableSetOf<IrTypeKey>()
  val queue = ArrayDeque<IrTypeKey>()

  // Start with entry points
  queue.addAll(entryPointKeys)

  fun collectDependencyKeys(binding: IrBinding): List<IrTypeKey> {
    return when (binding) {
      is IrBinding.CustomWrapper -> {
        when (binding.wrappedContextKey.wrappedType) {
          is WrappedType.Provider, is WrappedType.Lazy -> emptyList()
          else -> binding.dependencies.map { it.typeKey }
        }
      }
      is IrBinding.Multibinding -> {
        binding.sourceBindings.flatMap { sourceKey ->
          val sourceBinding = bindingGraph.findBinding(sourceKey)
          if (sourceBinding != null) {
            buildList {
              add(sourceKey)
              addAll(collectDependencyKeys(sourceBinding))
            }
          } else {
            emptyList()
          }
        }
      }
      else -> binding.dependencies.map { it.typeKey }
    }
  }

  while (queue.isNotEmpty()) {
    val current = queue.removeFirst()
    if (current in visited) continue
    visited.add(current)
    eager.add(current)

    // Find binding
    val binding = bindingGraph.findBinding(current) ?: continue

    // Get dependencies (respecting Provider/Lazy wrappers)
    val deps = when (binding) {
      is IrBinding.Alias -> listOf(binding.aliasedType)
      else -> collectDependencyKeys(binding)
    }

    // Add dependencies to queue
    deps.forEach { dep ->
      if (dep !in visited) {
        queue.add(dep)
      }
    }
  }

  // All other bindings are deferred
  val allKeys = bindingGraph.bindingsSnapshot().keys
  val deferred = allKeys - eager

  return FastInitResult(
    eagerKeys = eager,
    deferredKeys = deferred,
    entryPointKeys = entryPointKeys
  )
}
