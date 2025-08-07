// Copyright (C) 2025 Zac Sweers  
// SPDX-License-Identifier: Apache-2.0

import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Scope
import dev.zacsweers.metro.SingleIn

// Create many services to trigger sharding
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

@Scope
annotation class TestScope

@DependencyGraph(TestScope::class)
interface LargeGraph {
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
}