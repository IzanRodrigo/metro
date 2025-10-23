// COMPONENT_SHARDING: true
// KEYS_PER_GRAPH_SHARD: 5
// Test: Component with threshold+1 bindings - should trigger sharding
// Story: 1.2 - Edge Case Testing

object AppScope

@DependencyGraph(scope = AppScope::class)
interface TriggerShardingComponent {
  val service1: Service1
  val service2: Service2
  val service3: Service3
  val service4: Service4
  val service5: Service5
  val service6: Service6

  @DependencyGraph.Factory
  interface Factory {
    fun create(): TriggerShardingComponent
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

@SingleIn(AppScope::class)
@Inject
class Service6

fun box(): String {
  val graph = createGraphFactory<TriggerShardingComponent.Factory>().create()
  requireNotNull(graph.service1)
  requireNotNull(graph.service2)
  requireNotNull(graph.service3)
  requireNotNull(graph.service4)
  requireNotNull(graph.service5)
  requireNotNull(graph.service6)

  // Verify sharding occurred (6 bindings > threshold of 5)
  val nestedClasses = graph::class.java.declaredClasses
  val shardClasses = nestedClasses.filter { it.simpleName?.contains("Shard") == true }
  require(shardClasses.isNotEmpty()) {
    "Expected shard classes for 6 bindings (threshold=5), but found none"
  }
  require(shardClasses.size == 2) {
    "Expected 2 shard classes, but found: ${shardClasses.size} - ${shardClasses.map { it.simpleName }}"
  }

  return "OK"
}
