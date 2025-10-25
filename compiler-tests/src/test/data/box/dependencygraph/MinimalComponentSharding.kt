// KEYS_PER_GRAPH_SHARD: 5
// ENABLE_COMPONENT_SHARDING: true
// This test verifies that component sharding works by creating a component
// with 10 bindings when keysPerGraphShard=5, which should trigger sharding
// into 2 shards.

@SingleIn(AppScope::class) @Inject class Service1

@SingleIn(AppScope::class) @Inject class Service2(val s1: Service1)

@SingleIn(AppScope::class) @Inject class Service3(val s2: Service2)

@SingleIn(AppScope::class) @Inject class Service4(val s3: Service3)

@SingleIn(AppScope::class) @Inject class Service5(val s4: Service4)

@SingleIn(AppScope::class) @Inject class Service6(val s5: Service5)

@SingleIn(AppScope::class) @Inject class Service7(val s6: Service6)

@SingleIn(AppScope::class) @Inject class Service8(val s7: Service7)

@SingleIn(AppScope::class) @Inject class Service9(val s8: Service8)

@SingleIn(AppScope::class) @Inject class Service10(val s9: Service9)

@DependencyGraph(scope = AppScope::class)
interface TestComponent {
  val service10: Service10
}

fun box(): String {
  val component = createTestComponent()
  val service = component.service10

  // Verify the dependency chain works through shards
  return when {
    service.s9.s8.s7.s6.s5.s4.s3.s2.s1 == null -> "FAIL: null in chain"
    else -> "OK"
  }
}
