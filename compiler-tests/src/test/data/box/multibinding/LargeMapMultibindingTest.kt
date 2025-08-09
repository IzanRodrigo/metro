// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0

import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.IntoMap
import dev.zacsweers.metro.Module
import dev.zacsweers.metro.Multibindings
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.Scope
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.StringKey
import dev.zacsweers.metro.createGraph

@Scope
annotation class TestScope

// Service that will be in the map values
class Service(val name: String)

// Module with many map entries to test chunking
@Module
@Multibindings
interface LargeMapModule {
  companion object {
    // Generate 50 map entries to trigger chunking
    @Provides @IntoMap @StringKey("key1") @SingleIn(TestScope::class)
    fun provideService1(): Service = Service("service1")
    
    @Provides @IntoMap @StringKey("key2") @SingleIn(TestScope::class)
    fun provideService2(): Service = Service("service2")
    
    @Provides @IntoMap @StringKey("key3") @SingleIn(TestScope::class)
    fun provideService3(): Service = Service("service3")
    
    @Provides @IntoMap @StringKey("key4") @SingleIn(TestScope::class)
    fun provideService4(): Service = Service("service4")
    
    @Provides @IntoMap @StringKey("key5") @SingleIn(TestScope::class)
    fun provideService5(): Service = Service("service5")
    
    @Provides @IntoMap @StringKey("key6") @SingleIn(TestScope::class)
    fun provideService6(): Service = Service("service6")
    
    @Provides @IntoMap @StringKey("key7") @SingleIn(TestScope::class)
    fun provideService7(): Service = Service("service7")
    
    @Provides @IntoMap @StringKey("key8") @SingleIn(TestScope::class)
    fun provideService8(): Service = Service("service8")
    
    @Provides @IntoMap @StringKey("key9") @SingleIn(TestScope::class)
    fun provideService9(): Service = Service("service9")
    
    @Provides @IntoMap @StringKey("key10") @SingleIn(TestScope::class)
    fun provideService10(): Service = Service("service10")
    
    @Provides @IntoMap @StringKey("key11") @SingleIn(TestScope::class)
    fun provideService11(): Service = Service("service11")
    
    @Provides @IntoMap @StringKey("key12") @SingleIn(TestScope::class)
    fun provideService12(): Service = Service("service12")
    
    @Provides @IntoMap @StringKey("key13") @SingleIn(TestScope::class)
    fun provideService13(): Service = Service("service13")
    
    @Provides @IntoMap @StringKey("key14") @SingleIn(TestScope::class)
    fun provideService14(): Service = Service("service14")
    
    @Provides @IntoMap @StringKey("key15") @SingleIn(TestScope::class)
    fun provideService15(): Service = Service("service15")
    
    @Provides @IntoMap @StringKey("key16") @SingleIn(TestScope::class)
    fun provideService16(): Service = Service("service16")
    
    @Provides @IntoMap @StringKey("key17") @SingleIn(TestScope::class)
    fun provideService17(): Service = Service("service17")
    
    @Provides @IntoMap @StringKey("key18") @SingleIn(TestScope::class)
    fun provideService18(): Service = Service("service18")
    
    @Provides @IntoMap @StringKey("key19") @SingleIn(TestScope::class)
    fun provideService19(): Service = Service("service19")
    
    @Provides @IntoMap @StringKey("key20") @SingleIn(TestScope::class)
    fun provideService20(): Service = Service("service20")
    
    @Provides @IntoMap @StringKey("key21") @SingleIn(TestScope::class)
    fun provideService21(): Service = Service("service21")
    
    @Provides @IntoMap @StringKey("key22") @SingleIn(TestScope::class)
    fun provideService22(): Service = Service("service22")
    
    @Provides @IntoMap @StringKey("key23") @SingleIn(TestScope::class)
    fun provideService23(): Service = Service("service23")
    
    @Provides @IntoMap @StringKey("key24") @SingleIn(TestScope::class)
    fun provideService24(): Service = Service("service24")
    
    @Provides @IntoMap @StringKey("key25") @SingleIn(TestScope::class)
    fun provideService25(): Service = Service("service25")
  }
}

@DependencyGraph(TestScope::class)
@Module(includes = [LargeMapModule::class])
interface TestGraph {
  val serviceMap: Map<String, Service>
}

fun box(): String {
  val graph = createGraph<TestGraph>()
  
  // Test that all services are in the map
  val map = graph.serviceMap
  if (map.size != 25) return "Expected 25 entries, got ${map.size}"
  
  // Test a few specific entries
  if (map["key1"]?.name != "service1") return "key1 entry incorrect"
  if (map["key10"]?.name != "service10") return "key10 entry incorrect"
  if (map["key25"]?.name != "service25") return "key25 entry incorrect"
  
  // Test that they're all scoped (same instance)
  if (graph.serviceMap !== graph.serviceMap) return "Map should be scoped"
  if (graph.serviceMap["key1"] !== graph.serviceMap["key1"]) return "Service1 should be scoped"
  
  return "OK"
}