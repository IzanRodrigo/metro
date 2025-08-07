// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0

import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Scope
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.createGraph

@SingleIn(TestScope::class)
class TestService1 @Inject constructor()

@SingleIn(TestScope::class)
class TestService2 @Inject constructor()

@SingleIn(TestScope::class)
class TestService3 @Inject constructor()

@SingleIn(TestScope::class)
class TestService4 @Inject constructor()

@SingleIn(TestScope::class)
class TestService5 @Inject constructor()

@SingleIn(TestScope::class)
class TestService6 @Inject constructor()

@SingleIn(TestScope::class) 
class TestService7 @Inject constructor()

@SingleIn(TestScope::class)
class TestService8 @Inject constructor()

@SingleIn(TestScope::class)
class TestService9 @Inject constructor()

@SingleIn(TestScope::class)
class TestService10 @Inject constructor()

@SingleIn(TestScope::class)
class TestService11 @Inject constructor()

@SingleIn(TestScope::class)
class TestService12 @Inject constructor()

@Scope
annotation class TestScope

@DependencyGraph(TestScope::class)
interface TestGraph {
  val service1: TestService1
  val service2: TestService2
  val service3: TestService3
  val service4: TestService4
  val service5: TestService5
  val service6: TestService6
  val service7: TestService7
  val service8: TestService8
  val service9: TestService9
  val service10: TestService10
  val service11: TestService11
  val service12: TestService12
}

fun box(): String {
  val graph = createGraph<TestGraph>()
  
  // Test that all services can be created
  val service1 = graph.service1
  val service2 = graph.service2
  val service3 = graph.service3
  val service4 = graph.service4
  val service5 = graph.service5
  val service6 = graph.service6
  val service7 = graph.service7
  val service8 = graph.service8
  val service9 = graph.service9
  val service10 = graph.service10
  val service11 = graph.service11
  val service12 = graph.service12
  
  // Test that scoped services are the same instance
  if (graph.service1 !== graph.service1) return "Service1 should be scoped"
  if (graph.service12 !== graph.service12) return "Service12 should be scoped"
  
  return "OK"
}