// Test module passing to shards
// RUN_ARGS: -Xplugin-option=metro:sharding.enabled=true -Xplugin-option=metro:sharding.keysPerShard=2

package test

import dev.zacsweers.metro.annotations.*
import javax.inject.Inject

// Module with provides methods
@Module
class TestModule {
  @Provides
  fun provideString(): String = "Hello from module"
  
  @Provides
  fun provideInt(): Int = 42
  
  @Provides
  fun provideLong(): Long = 100L
}

// Services that depend on module-provided values
class ServiceWithString @Inject constructor(
  val value: String
)

class ServiceWithInt @Inject constructor(
  val value: Int
)

class ServiceWithLong @Inject constructor(
  val value: Long
)

// Service that depends on other services (cross-shard)
class AggregateService @Inject constructor(
  val stringService: ServiceWithString,
  val intService: ServiceWithInt,
  val longService: ServiceWithLong
)

@Component(modules = [TestModule::class])
interface ModuleTestComponent {
  fun serviceWithString(): ServiceWithString
  fun serviceWithInt(): ServiceWithInt
  fun serviceWithLong(): ServiceWithLong
  fun aggregateService(): AggregateService
}

fun box(): String {
  val module = TestModule()
  val component = MetroModuleTestComponent(module)
  
  // Verify module provides work across shards
  val stringService = component.serviceWithString()
  if (stringService.value != "Hello from module") return "FAIL: String value mismatch"
  
  val intService = component.serviceWithInt()
  if (intService.value != 42) return "FAIL: Int value mismatch"
  
  val longService = component.serviceWithLong()
  if (longService.value != 100L) return "FAIL: Long value mismatch"
  
  // Verify aggregate service gets all dependencies correctly
  val aggregate = component.aggregateService()
  if (aggregate.stringService.value != "Hello from module") return "FAIL: Aggregate string mismatch"
  if (aggregate.intService.value != 42) return "FAIL: Aggregate int mismatch"
  if (aggregate.longService.value != 100L) return "FAIL: Aggregate long mismatch"
  
  // Verify same instances are returned
  if (stringService !== aggregate.stringService) return "FAIL: StringService instance mismatch"
  if (intService !== aggregate.intService) return "FAIL: IntService instance mismatch"
  if (longService !== aggregate.longService) return "FAIL: LongService instance mismatch"
  
  return "OK"
}