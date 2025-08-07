// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0

import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.Singleton
import dev.zacsweers.metro.createGraph

// Generate many services to test sharding threshold
@SingleIn(TestScope::class) class Service1 @Inject constructor()
@SingleIn(TestScope::class) class Service2 @Inject constructor()
@SingleIn(TestScope::class) class Service3 @Inject constructor()
@SingleIn(TestScope::class) class Service4 @Inject constructor()
@SingleIn(TestScope::class) class Service5 @Inject constructor()
@SingleIn(TestScope::class) class Service6 @Inject constructor()
@SingleIn(TestScope::class) class Service7 @Inject constructor()
@SingleIn(TestScope::class) class Service8 @Inject constructor()
@SingleIn(TestScope::class) class Service9 @Inject constructor()
@SingleIn(TestScope::class) class Service10 @Inject constructor()
@SingleIn(TestScope::class) class Service11 @Inject constructor()
@SingleIn(TestScope::class) class Service12 @Inject constructor()
@SingleIn(TestScope::class) class Service13 @Inject constructor()
@SingleIn(TestScope::class) class Service14 @Inject constructor()
@SingleIn(TestScope::class) class Service15 @Inject constructor()
@SingleIn(TestScope::class) class Service16 @Inject constructor()
@SingleIn(TestScope::class) class Service17 @Inject constructor()
@SingleIn(TestScope::class) class Service18 @Inject constructor()
@SingleIn(TestScope::class) class Service19 @Inject constructor()
@SingleIn(TestScope::class) class Service20 @Inject constructor()

// More services to ensure we hit the sharding threshold
@SingleIn(TestScope::class) class Service21 @Inject constructor()
@SingleIn(TestScope::class) class Service22 @Inject constructor()
@SingleIn(TestScope::class) class Service23 @Inject constructor()
@SingleIn(TestScope::class) class Service24 @Inject constructor()
@SingleIn(TestScope::class) class Service25 @Inject constructor()
@SingleIn(TestScope::class) class Service26 @Inject constructor()
@SingleIn(TestScope::class) class Service27 @Inject constructor()
@SingleIn(TestScope::class) class Service28 @Inject constructor()
@SingleIn(TestScope::class) class Service29 @Inject constructor()
@SingleIn(TestScope::class) class Service30 @Inject constructor()

@Singleton
annotation class TestScope

@DependencyGraph(TestScope::class)
interface LargeTestGraph {
  val service1: Service1
  val service2: Service2
  val service3: Service3
  val service4: Service4
  val service5: Service5
  val service6: Service6
  val service7: Service7
  val service8: Service8
  val service9: Service9
  val service10: Service10
  val service11: Service11
  val service12: Service12
  val service13: Service13
  val service14: Service14
  val service15: Service15
  val service16: Service16
  val service17: Service17
  val service18: Service18
  val service19: Service19
  val service20: Service20
  val service21: Service21
  val service22: Service22
  val service23: Service23
  val service24: Service24
  val service25: Service25
  val service26: Service26
  val service27: Service27
  val service28: Service28
  val service29: Service29
  val service30: Service30
}

fun box(): String {
  val graph = createGraph<LargeTestGraph>()
  
  // Test that all services can be created
  val services = listOf(
    graph.service1, graph.service2, graph.service3, graph.service4, graph.service5,
    graph.service6, graph.service7, graph.service8, graph.service9, graph.service10,
    graph.service11, graph.service12, graph.service13, graph.service14, graph.service15,
    graph.service16, graph.service17, graph.service18, graph.service19, graph.service20,
    graph.service21, graph.service22, graph.service23, graph.service24, graph.service25,
    graph.service26, graph.service27, graph.service28, graph.service29, graph.service30
  )
  
  // Test that scoped services maintain their singleton behavior
  if (graph.service1 !== graph.service1) return "Service1 should be scoped"
  if (graph.service30 !== graph.service30) return "Service30 should be scoped"
  
  // Test that all services were created
  if (services.size != 30) return "Expected 30 services, got ${services.size}"
  
  return "OK"
}