// COMPONENT_SHARDING: true
// KEYS_PER_GRAPH_SHARD: 5
// Test: Boundary component (exactly threshold) - should NOT shard (> not >=)
// Story: 1.2 - Edge Case Testing

object AppScope

@DependencyGraph(scope = AppScope::class)
interface BoundaryComponent {
  val service1: Service1
  val service2: Service2
  val service3: Service3
  val service4: Service4
  val service5: Service5

  @DependencyGraph.Factory
  interface Factory {
    fun create(): BoundaryComponent
  }
}

@SingleIn(AppScope::class)
@Inject
class Service1

@SingleIn(AppScope::class)
@Inject
class Service2

@SingleIn(AppScope::class)
@Inject
class Service3

@SingleIn(AppScope::class)
@Inject
class Service4

@SingleIn(AppScope::class)
@Inject
class Service5

fun box(): String {
  val graph = createGraphFactory<BoundaryComponent.Factory>().create()
  requireNotNull(graph.service1)
  requireNotNull(graph.service2)
  requireNotNull(graph.service3)
  requireNotNull(graph.service4)
  requireNotNull(graph.service5)

  // Verify no sharding occurred (exactly at threshold, not over)
  val nestedClasses = graph::class.java.declaredClasses
  val shardClasses = nestedClasses.filter { it.simpleName?.contains("Shard") == true }
  require(shardClasses.isEmpty()) {
    "Expected no shard classes for exactly 5 bindings (threshold=5), but found: ${shardClasses.map { it.simpleName }}"
  }

  return "OK"
}
