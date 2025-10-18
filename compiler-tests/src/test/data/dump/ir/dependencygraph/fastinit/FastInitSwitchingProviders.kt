// ENABLE_FAST_INIT

@Scope
annotation class AppScope

@Inject class Engine
@Inject class Wheel

class Vehicle @Inject constructor(val engine: Engine, val wheel: Wheel)

@SingleIn(AppScope::class)
class ScopedDashboard @Inject constructor(val vehicle: Vehicle)

@DependencyGraph(scope = AppScope::class)
interface FastInitGraph {
  val vehicle: Vehicle
  val vehicleProvider: Provider<Vehicle>
  val scopedDashboard: ScopedDashboard

  @DependencyGraph.Factory
  interface Factory {
    fun create(): FastInitGraph
  }
}
