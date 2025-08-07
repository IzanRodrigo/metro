// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
// OPTIONS: -bindings-per-graph-shard=5 -enable-parallel-shard-generation=true -shard-generation-parallelism=2

import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Scope
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.createGraph

@Scope
annotation class ParallelScope

// Generate 30 services to ensure multiple shards
@SingleIn(ParallelScope::class) class Service1 @Inject constructor()
@SingleIn(ParallelScope::class) class Service2 @Inject constructor()
@SingleIn(ParallelScope::class) class Service3 @Inject constructor()
@SingleIn(ParallelScope::class) class Service4 @Inject constructor()
@SingleIn(ParallelScope::class) class Service5 @Inject constructor()
@SingleIn(ParallelScope::class) class Service6 @Inject constructor()
@SingleIn(ParallelScope::class) class Service7 @Inject constructor()
@SingleIn(ParallelScope::class) class Service8 @Inject constructor()
@SingleIn(ParallelScope::class) class Service9 @Inject constructor()
@SingleIn(ParallelScope::class) class Service10 @Inject constructor()
@SingleIn(ParallelScope::class) class Service11 @Inject constructor()
@SingleIn(ParallelScope::class) class Service12 @Inject constructor()
@SingleIn(ParallelScope::class) class Service13 @Inject constructor()
@SingleIn(ParallelScope::class) class Service14 @Inject constructor()
@SingleIn(ParallelScope::class) class Service15 @Inject constructor()
@SingleIn(ParallelScope::class) class Service16 @Inject constructor()
@SingleIn(ParallelScope::class) class Service17 @Inject constructor()
@SingleIn(ParallelScope::class) class Service18 @Inject constructor()
@SingleIn(ParallelScope::class) class Service19 @Inject constructor()
@SingleIn(ParallelScope::class) class Service20 @Inject constructor()
@SingleIn(ParallelScope::class) class Service21 @Inject constructor()
@SingleIn(ParallelScope::class) class Service22 @Inject constructor()
@SingleIn(ParallelScope::class) class Service23 @Inject constructor()
@SingleIn(ParallelScope::class) class Service24 @Inject constructor()
@SingleIn(ParallelScope::class) class Service25 @Inject constructor()
@SingleIn(ParallelScope::class) class Service26 @Inject constructor()
@SingleIn(ParallelScope::class) class Service27 @Inject constructor()
@SingleIn(ParallelScope::class) class Service28 @Inject constructor()
@SingleIn(ParallelScope::class) class Service29 @Inject constructor()
@SingleIn(ParallelScope::class) class Service30 @Inject constructor()

@DependencyGraph(ParallelScope::class)
interface ParallelTestGraph {
  val service1: Service1
  val service15: Service15
  val service30: Service30
}

fun box(): String {
  val graph = createGraph<ParallelTestGraph>()
  
  // Test that services can be created
  val s1 = graph.service1
  val s15 = graph.service15
  val s30 = graph.service30
  
  // Test that scoped services are the same instance
  if (graph.service1 !== graph.service1) return "Service1 should be scoped"
  if (graph.service30 !== graph.service30) return "Service30 should be scoped"
  
  return "OK"
}