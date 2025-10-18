// ENABLE_FAST_INIT

@DependencyGraph
interface CounterGraph {
  val count: Int

  @Provides fun provideCount(): Int = 0
}

@BindingContainer
class CounterProvider(private val base: Int) {
  @Provides fun overrideCount(): Int = base
}

fun snapshot(base: Int): Int {
  val dynamicGraph = createDynamicGraph<CounterGraph>(CounterProvider(base))
  return dynamicGraph.count
}

fun eager(): Int {
  val graph = createGraph<CounterGraph>()
  return graph.count
}
