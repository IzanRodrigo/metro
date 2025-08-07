// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0

import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Scope
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.createGraph

// Create more services than the default threshold
@SingleIn(TestScope::class)
class Service1 @Inject constructor()

@SingleIn(TestScope::class) 
class Service2 @Inject constructor()

@SingleIn(TestScope::class)
class Service3 @Inject constructor()

@SingleIn(TestScope::class)
class Service4 @Inject constructor()

@SingleIn(TestScope::class)
class Service5 @Inject constructor()

@SingleIn(TestScope::class)
class Service6 @Inject constructor()

@SingleIn(TestScope::class)
class Service7 @Inject constructor()

@Scope
annotation class TestScope

@DependencyGraph(TestScope::class)
interface ShardedGraph {
  val service1: Service1
  val service2: Service2  
  val service3: Service3
  val service4: Service4
  val service5: Service5
  val service6: Service6
  val service7: Service7
}

fun box(): String {
  val graph = createGraph<ShardedGraph>()
  
  // Test that all services can be accessed
  val s1 = graph.service1
  val s2 = graph.service2
  val s3 = graph.service3
  val s4 = graph.service4
  val s5 = graph.service5
  val s6 = graph.service6
  val s7 = graph.service7
  
  // Test scoping works
  if (graph.service1 !== graph.service1) return "Service1 not scoped properly"
  if (graph.service7 !== graph.service7) return "Service7 not scoped properly"
  
  return "OK"
}