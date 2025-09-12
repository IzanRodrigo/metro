// Test cycle breaking with Provider wrapping
// RUN_ARGS: -Xplugin-option=metro:sharding.enabled=true -Xplugin-option=metro:sharding.keysPerShard=2

package test

import dev.zacsweers.metro.annotations.*
import javax.inject.Inject
import javax.inject.Provider

// Create a circular dependency that spans shards
class CycleServiceA @Inject constructor(
  // Use Provider to break the cycle
  val serviceBProvider: Provider<CycleServiceB>
)

class CycleServiceB @Inject constructor(
  val serviceC: CycleServiceC
)

class CycleServiceC @Inject constructor(
  val serviceA: CycleServiceA
)

@Component
interface CycleTestComponent {
  fun cycleServiceA(): CycleServiceA
  fun cycleServiceB(): CycleServiceB
  fun cycleServiceC(): CycleServiceC
}

fun box(): String {
  val component = MetroCycleTestComponent()
  
  // Get service A which has a Provider<B>
  val serviceA = component.cycleServiceA()
  
  // Get service B through the provider
  val serviceB = serviceA.serviceBProvider.get()
  
  // Verify the cycle is properly connected
  val serviceC = serviceB.serviceC
  val serviceAFromC = serviceC.serviceA
  
  // They should be the same instance (scoped)
  if (serviceA !== serviceAFromC) return "FAIL: ServiceA instance mismatch in cycle"
  if (serviceB !== component.cycleServiceB()) return "FAIL: ServiceB instance mismatch"
  if (serviceC !== component.cycleServiceC()) return "FAIL: ServiceC instance mismatch"
  
  // Verify we can traverse the cycle multiple times
  val serviceBAgain = serviceA.serviceBProvider.get()
  if (serviceB !== serviceBAgain) return "FAIL: Provider should return same instance"
  
  return "OK"
}