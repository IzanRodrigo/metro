// COMPILER_OPTIONS: -Xplugin-option=metro:sharding.enabled=true -Xplugin-option=metro:sharding.keysPerShard=5
// TEST_TARGET: jvm

// Large graph test - creates >200 bindings to stress-test sharding with aggressive configuration

// Generate 50 independent services (no dependencies)
@Inject class Service001
@Inject class Service002
@Inject class Service003
@Inject class Service004
@Inject class Service005
@Inject class Service006
@Inject class Service007
@Inject class Service008
@Inject class Service009
@Inject class Service010
@Inject class Service011
@Inject class Service012
@Inject class Service013
@Inject class Service014
@Inject class Service015
@Inject class Service016
@Inject class Service017
@Inject class Service018
@Inject class Service019
@Inject class Service020
@Inject class Service021
@Inject class Service022
@Inject class Service023
@Inject class Service024
@Inject class Service025
@Inject class Service026
@Inject class Service027
@Inject class Service028
@Inject class Service029
@Inject class Service030
@Inject class Service031
@Inject class Service032
@Inject class Service033
@Inject class Service034
@Inject class Service035
@Inject class Service036
@Inject class Service037
@Inject class Service038
@Inject class Service039
@Inject class Service040
@Inject class Service041
@Inject class Service042
@Inject class Service043
@Inject class Service044
@Inject class Service045
@Inject class Service046
@Inject class Service047
@Inject class Service048
@Inject class Service049
@Inject class Service050

// Generate 50 repository classes with dependencies
@Inject class Repository001(val s: Service001)
@Inject class Repository002(val s: Service002)
@Inject class Repository003(val s: Service003)
@Inject class Repository004(val s: Service004)
@Inject class Repository005(val s: Service005)
@Inject class Repository006(val s: Service006)
@Inject class Repository007(val s: Service007)
@Inject class Repository008(val s: Service008)
@Inject class Repository009(val s: Service009)
@Inject class Repository010(val s: Service010)
@Inject class Repository011(val s: Service011)
@Inject class Repository012(val s: Service012)
@Inject class Repository013(val s: Service013)
@Inject class Repository014(val s: Service014)
@Inject class Repository015(val s: Service015)
@Inject class Repository016(val s: Service016)
@Inject class Repository017(val s: Service017)
@Inject class Repository018(val s: Service018)
@Inject class Repository019(val s: Service019)
@Inject class Repository020(val s: Service020)
@Inject class Repository021(val s: Service021)
@Inject class Repository022(val s: Service022)
@Inject class Repository023(val s: Service023)
@Inject class Repository024(val s: Service024)
@Inject class Repository025(val s: Service025)
@Inject class Repository026(val s: Service026)
@Inject class Repository027(val s: Service027)
@Inject class Repository028(val s: Service028)
@Inject class Repository029(val s: Service029)
@Inject class Repository030(val s: Service030)
@Inject class Repository031(val s: Service031)
@Inject class Repository032(val s: Service032)
@Inject class Repository033(val s: Service033)
@Inject class Repository034(val s: Service034)
@Inject class Repository035(val s: Service035)
@Inject class Repository036(val s: Service036)
@Inject class Repository037(val s: Service037)
@Inject class Repository038(val s: Service038)
@Inject class Repository039(val s: Service039)
@Inject class Repository040(val s: Service040)
@Inject class Repository041(val s: Service041)
@Inject class Repository042(val s: Service042)
@Inject class Repository043(val s: Service043)
@Inject class Repository044(val s: Service044)
@Inject class Repository045(val s: Service045)
@Inject class Repository046(val s: Service046)
@Inject class Repository047(val s: Service047)
@Inject class Repository048(val s: Service048)
@Inject class Repository049(val s: Service049)
@Inject class Repository050(val s: Service050)

// Generate 50 use case classes with repository dependencies
@Inject class UseCase001(val r: Repository001)
@Inject class UseCase002(val r: Repository002)
@Inject class UseCase003(val r: Repository003)
@Inject class UseCase004(val r: Repository004)
@Inject class UseCase005(val r: Repository005)
@Inject class UseCase006(val r: Repository006)
@Inject class UseCase007(val r: Repository007)
@Inject class UseCase008(val r: Repository008)
@Inject class UseCase009(val r: Repository009)
@Inject class UseCase010(val r: Repository010)
@Inject class UseCase011(val r: Repository011)
@Inject class UseCase012(val r: Repository012)
@Inject class UseCase013(val r: Repository013)
@Inject class UseCase014(val r: Repository014)
@Inject class UseCase015(val r: Repository015)
@Inject class UseCase016(val r: Repository016)
@Inject class UseCase017(val r: Repository017)
@Inject class UseCase018(val r: Repository018)
@Inject class UseCase019(val r: Repository019)
@Inject class UseCase020(val r: Repository020)
@Inject class UseCase021(val r: Repository021)
@Inject class UseCase022(val r: Repository022)
@Inject class UseCase023(val r: Repository023)
@Inject class UseCase024(val r: Repository024)
@Inject class UseCase025(val r: Repository025)
@Inject class UseCase026(val r: Repository026)
@Inject class UseCase027(val r: Repository027)
@Inject class UseCase028(val r: Repository028)
@Inject class UseCase029(val r: Repository029)
@Inject class UseCase030(val r: Repository030)
@Inject class UseCase031(val r: Repository031)
@Inject class UseCase032(val r: Repository032)
@Inject class UseCase033(val r: Repository033)
@Inject class UseCase034(val r: Repository034)
@Inject class UseCase035(val r: Repository035)
@Inject class UseCase036(val r: Repository036)
@Inject class UseCase037(val r: Repository037)
@Inject class UseCase038(val r: Repository038)
@Inject class UseCase039(val r: Repository039)
@Inject class UseCase040(val r: Repository040)
@Inject class UseCase041(val r: Repository041)
@Inject class UseCase042(val r: Repository042)
@Inject class UseCase043(val r: Repository043)
@Inject class UseCase044(val r: Repository044)
@Inject class UseCase045(val r: Repository045)
@Inject class UseCase046(val r: Repository046)
@Inject class UseCase047(val r: Repository047)
@Inject class UseCase048(val r: Repository048)
@Inject class UseCase049(val r: Repository049)
@Inject class UseCase050(val r: Repository050)

// Generate 50 view model classes with use case dependencies
@Inject class ViewModel001(val u: UseCase001)
@Inject class ViewModel002(val u: UseCase002)
@Inject class ViewModel003(val u: UseCase003)
@Inject class ViewModel004(val u: UseCase004)
@Inject class ViewModel005(val u: UseCase005)
@Inject class ViewModel006(val u: UseCase006)
@Inject class ViewModel007(val u: UseCase007)
@Inject class ViewModel008(val u: UseCase008)
@Inject class ViewModel009(val u: UseCase009)
@Inject class ViewModel010(val u: UseCase010)
@Inject class ViewModel011(val u: UseCase011)
@Inject class ViewModel012(val u: UseCase012)
@Inject class ViewModel013(val u: UseCase013)
@Inject class ViewModel014(val u: UseCase014)
@Inject class ViewModel015(val u: UseCase015)
@Inject class ViewModel016(val u: UseCase016)
@Inject class ViewModel017(val u: UseCase017)
@Inject class ViewModel018(val u: UseCase018)
@Inject class ViewModel019(val u: UseCase019)
@Inject class ViewModel020(val u: UseCase020)
@Inject class ViewModel021(val u: UseCase021)
@Inject class ViewModel022(val u: UseCase022)
@Inject class ViewModel023(val u: UseCase023)
@Inject class ViewModel024(val u: UseCase024)
@Inject class ViewModel025(val u: UseCase025)
@Inject class ViewModel026(val u: UseCase026)
@Inject class ViewModel027(val u: UseCase027)
@Inject class ViewModel028(val u: UseCase028)
@Inject class ViewModel029(val u: UseCase029)
@Inject class ViewModel030(val u: UseCase030)
@Inject class ViewModel031(val u: UseCase031)
@Inject class ViewModel032(val u: UseCase032)
@Inject class ViewModel033(val u: UseCase033)
@Inject class ViewModel034(val u: UseCase034)
@Inject class ViewModel035(val u: UseCase035)
@Inject class ViewModel036(val u: UseCase036)
@Inject class ViewModel037(val u: UseCase037)
@Inject class ViewModel038(val u: UseCase038)
@Inject class ViewModel039(val u: UseCase039)
@Inject class ViewModel040(val u: UseCase040)
@Inject class ViewModel041(val u: UseCase041)
@Inject class ViewModel042(val u: UseCase042)
@Inject class ViewModel043(val u: UseCase043)
@Inject class ViewModel044(val u: UseCase044)
@Inject class ViewModel045(val u: UseCase045)
@Inject class ViewModel046(val u: UseCase046)
@Inject class ViewModel047(val u: UseCase047)
@Inject class ViewModel048(val u: UseCase048)
@Inject class ViewModel049(val u: UseCase049)
@Inject class ViewModel050(val u: UseCase050)

// Additional module provisions to increase binding count
@Module
class LargeModule {
  @Provides fun provideConfig1(): String = "config1"
  @Provides fun provideConfig2(): String = "config2"
  @Provides fun provideConfig3(): String = "config3"
  @Provides fun provideConfig4(): String = "config4"
  @Provides fun provideConfig5(): String = "config5"
  @Provides fun provideInt1(): Int = 1
  @Provides fun provideInt2(): Int = 2
  @Provides fun provideInt3(): Int = 3
  @Provides fun provideInt4(): Int = 4
  @Provides fun provideInt5(): Int = 5
}

// Aggregate service that depends on multiple view models
@Inject class AggregateService(
  val vm1: ViewModel001,
  val vm10: ViewModel010,
  val vm20: ViewModel020,
  val vm30: ViewModel030,
  val vm40: ViewModel040,
  val vm50: ViewModel050
)

@DependencyGraph
interface LargeShardedGraph {
  // Sample of services (not all listed for brevity)
  val service001: Service001
  val service025: Service025
  val service050: Service050
  
  // Sample of repositories
  val repository001: Repository001
  val repository025: Repository025
  val repository050: Repository050
  
  // Sample of use cases
  val useCase001: UseCase001
  val useCase025: UseCase025
  val useCase050: UseCase050
  
  // Sample of view models
  val viewModel001: ViewModel001
  val viewModel025: ViewModel025
  val viewModel050: ViewModel050
  
  // Aggregate service
  val aggregateService: AggregateService
  
  // Module provisions
  val config1: String
  val config5: String
  val int1: Int
  val int5: Int
  
  @Module
  val largeModule: LargeModule = LargeModule()
}

fun box(): String {
  val graph = createGraph<LargeShardedGraph>()
  
  // Verify sample services are created
  assertNotNull(graph.service001)
  assertNotNull(graph.service025)
  assertNotNull(graph.service050)
  
  // Verify sample repositories and their dependencies
  assertNotNull(graph.repository001)
  assertEquals(graph.service001, graph.repository001.s)
  assertNotNull(graph.repository025)
  assertEquals(graph.service025, graph.repository025.s)
  assertNotNull(graph.repository050)
  assertEquals(graph.service050, graph.repository050.s)
  
  // Verify sample use cases and their dependencies
  assertNotNull(graph.useCase001)
  assertEquals(graph.repository001, graph.useCase001.r)
  assertNotNull(graph.useCase025)
  assertEquals(graph.repository025, graph.useCase025.r)
  assertNotNull(graph.useCase050)
  assertEquals(graph.repository050, graph.useCase050.r)
  
  // Verify sample view models and their dependencies
  assertNotNull(graph.viewModel001)
  assertEquals(graph.useCase001, graph.viewModel001.u)
  assertNotNull(graph.viewModel025)
  assertEquals(graph.useCase025, graph.viewModel025.u)
  assertNotNull(graph.viewModel050)
  assertEquals(graph.useCase050, graph.viewModel050.u)
  
  // Verify aggregate service has correct dependencies
  assertNotNull(graph.aggregateService)
  assertEquals(graph.viewModel001, graph.aggregateService.vm1)
  assertEquals(graph.viewModel010, graph.aggregateService.vm10)
  assertEquals(graph.viewModel020, graph.aggregateService.vm20)
  assertEquals(graph.viewModel030, graph.aggregateService.vm30)
  assertEquals(graph.viewModel040, graph.aggregateService.vm40)
  assertEquals(graph.viewModel050, graph.aggregateService.vm50)
  
  // Verify module provisions
  assertEquals("config1", graph.config1)
  assertEquals("config5", graph.config5)
  assertEquals(1, graph.int1)
  assertEquals(5, graph.int5)
  
  // This test with 210+ bindings and keysPerShard=5 should generate 40+ shards
  // The test passes if the graph compiles and all dependencies are correctly wired
  
  return "OK"
}