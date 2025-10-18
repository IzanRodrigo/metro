// ENABLE_FAST_INIT
// COMPONENT_SHARDING: true
// KEYS_PER_GRAPH_SHARD: 1

@Scope
annotation class AppScope

@SingleIn(AppScope::class)
class SessionManager @Inject constructor(val store: Store)

class Store @Inject constructor()

class FeedRepository @Inject constructor(val store: Store)

class TimelinePresenter @Inject constructor(
  val sessionManager: SessionManager,
  val feedRepository: FeedRepository,
)

@GraphExtension(AppScope::class)
interface LoggedInGraph {
  val timelinePresenter: TimelinePresenter

  @GraphExtension.Factory
  @ContributesTo(AppScope::class)
  interface Factory {
    fun create(): LoggedInGraph
  }
}

@DependencyGraph(scope = AppScope::class)
interface RootGraph {
  val sessionManager: SessionManager
  val feedRepository: FeedRepository
  val loggedInFactory: LoggedInGraph.Factory
}
