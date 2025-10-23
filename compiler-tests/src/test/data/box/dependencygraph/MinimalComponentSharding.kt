// COMPONENT_SHARDING: true
// KEYS_PER_GRAPH_SHARD: 400
// Test: Minimal component (1 binding, below threshold) - should not shard
// Story: 1.2 - Edge Case Testing

object AppScope

@DependencyGraph(scope = AppScope::class)
interface MinimalComponent {
  val service: Service

  @DependencyGraph.Factory
  interface Factory {
    fun create(): MinimalComponent
  }
}

@SingleIn(AppScope::class)
@Inject
class Service

fun box(): String {
  val graph = createGraphFactory<MinimalComponent.Factory>().create()
  requireNotNull(graph.service)

  // Verify no sharding occurred (only 1 binding, below threshold)
  val nestedClasses = graph::class.java.declaredClasses
  val shardClasses = nestedClasses.filter { it.simpleName?.contains("Shard") == true }
  require(shardClasses.isEmpty()) {
    "Expected no shard classes for 1 binding, but found: ${shardClasses.map { it.simpleName }}"
  }

  return "OK"
}
