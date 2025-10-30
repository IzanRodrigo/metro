// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph.sharding

import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.graph.IrBindingGraph
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.util.kotlinFqName

internal object ShardingDiagnostics {

  /** Generates a diagnostic report showing the sharding plan for a graph. */
  fun generateShardingPlanReport(
    graphClass: IrClass,
    shardInfos: List<ShardInfo>,
    initOrder: List<Int>,
    totalBindings: Int,
    options: MetroOptions,
    bindingGraph: IrBindingGraph,
  ): String = buildString {
    appendLine("=== Metro Graph Sharding Plan ===")
    appendLine()
    appendLine("Component: ${graphClass.kotlinFqName}")
    appendLine("Total bindings: $totalBindings")
    appendLine("Keys per shard limit: ${options.keysPerGraphShard}")
    appendLine("Shard count: ${shardInfos.size}")
    appendLine("Sharding enabled: ${options.enableGraphSharding}")
    appendLine()

    appendLine("Initialization order: ${initOrder.joinToString(" → ") { "Shard${it + 1}" }}")
    appendLine()

    // First compute cross-shard dependencies to get per-shard counts
    val bindingToShard = mutableMapOf<IrTypeKey, Int>()
    shardInfos.forEach { info ->
      info.bindings.forEach { binding -> bindingToShard[binding.typeKey] = info.index }
    }

    // Track outgoing cross-shard edges per shard to identify hotspots
    val crossShardEdgeCounts = IntArray(shardInfos.size)
    shardInfos.forEach { info ->
      info.bindings.forEach { binding ->
        val deps = bindingGraph.requireBinding(binding.typeKey).dependencies
        deps.forEach { dep ->
          val depShard = bindingToShard[dep.typeKey]
          if (depShard != null && depShard != info.index) {
            crossShardEdgeCounts[info.index]++
          }
        }
      }
    }

    shardInfos.forEach { info ->
      appendLine("Shard ${info.index + 1}:")
      appendLine("  Class: ${info.shardClass.name}")
      appendLine("  Bindings: ${info.bindings.size}")
      appendLine("  Outgoing cross-shard edges: ${crossShardEdgeCounts[info.index]}")
      appendLine("  Instance property: ${info.instanceProperty.name}")
      appendLine("  Initialize function: ${info.initializeFunction.name}")

      if (info.bindings.size <= 10) {
        // Show all bindings for small shards
        appendLine("  Binding keys:")
        info.bindings.forEach { binding -> appendLine("    - ${binding.typeKey}") }
      } else {
        // Show first and last for large shards
        appendLine("  Binding keys (first 5):")
        info.bindings.take(5).forEach { binding -> appendLine("    - ${binding.typeKey}") }
        appendLine("    ... (${info.bindings.size - 10} more)")
        appendLine("  Binding keys (last 5):")
        info.bindings.takeLast(5).forEach { binding -> appendLine("    - ${binding.typeKey}") }
      }
      appendLine()
    }

    // Compute and display detailed cross-shard dependencies
    appendLine("Cross-shard dependencies:")
    var crossShardDepCount = 0
    shardInfos.forEach { info ->
      info.bindings.forEach { binding ->
        val deps = bindingGraph.requireBinding(binding.typeKey).dependencies
        deps.forEach { dep ->
          val depShard = bindingToShard[dep.typeKey]
          if (depShard != null && depShard != info.index) {
            appendLine(
              "  Shard${info.index + 1}.${binding.typeKey} → Shard${depShard + 1}.${dep.typeKey}"
            )
            crossShardDepCount++
          }
        }
      }
    }

    if (crossShardDepCount == 0) {
      appendLine("  (none)")
    }
    appendLine()
    appendLine("Total cross-shard dependencies: $crossShardDepCount")
  }
}
