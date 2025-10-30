// KEYS_PER_GRAPH_SHARD: 2
// ENABLE_GRAPH_SHARDING: true

@SingleIn(AppScope::class) @Inject class Service1

@SingleIn(AppScope::class) @Inject class Service2

@SingleIn(AppScope::class) @Inject class Service3(val s1: Service1, val s2: Service2)

@DependencyGraph(scope = AppScope::class)
interface TestGraph {
  val service1: Service1
  val service2: Service2
  val service3: Service3
}

fun box(): String {
  val graph = createGraph<TestGraph>()
  return when {
    graph.service3.s1 == null -> "FAIL: s1 null"
    graph.service3.s2 == null -> "FAIL: s2 null"
    graph.service3.s1 !== graph.service1 -> "FAIL: s1 not same instance"
    graph.service3.s2 !== graph.service2 -> "FAIL: s2 not same instance"
    else -> "OK"
  }
}
