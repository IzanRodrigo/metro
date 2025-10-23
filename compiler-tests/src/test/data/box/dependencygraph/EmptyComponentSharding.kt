// COMPONENT_SHARDING: true
// KEYS_PER_GRAPH_SHARD: 400
// Test: Empty component (0 bindings) - edge case validation
// Story: 1.2 - Edge Case Testing

object AppScope

@DependencyGraph(scope = AppScope::class)
interface EmptyComponent {
  // No entry points - empty component

  @DependencyGraph.Factory
  interface Factory {
    fun create(): EmptyComponent
  }
}

// No bindings - empty component edge case

fun box(): String {
  // Should not crash with 0 bindings
  val graph = createGraphFactory<EmptyComponent.Factory>().create()
  requireNotNull(graph)
  return "OK"
}
