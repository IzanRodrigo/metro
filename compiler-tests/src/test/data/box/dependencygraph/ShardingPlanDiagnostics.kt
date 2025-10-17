// COMPONENT_SHARDING: true
// KEYS_PER_GRAPH_SHARD: 2
import kotlin.io.path.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.pathString
import java.nio.file.Files
import java.nio.file.Paths

object AppScope

@DependencyGraph(scope = AppScope::class)
interface ApplicationComponent {
  val foo: Foo
  val bar: Bar
  val baz: Baz

  @DependencyGraph.Factory
  interface Factory {
    fun create(): ApplicationComponent
  }
}

@SingleIn(AppScope::class)
class Foo @Inject constructor()

@SingleIn(AppScope::class)
class Bar @Inject constructor(val foo: Foo)

@SingleIn(AppScope::class)
class Baz @Inject constructor(val foo: Foo, val bar: Bar)

fun box(): String {
  val graph = createGraphFactory<ApplicationComponent.Factory>().create()
  requireNotNull(graph.foo)
  requireNotNull(graph.bar)
  requireNotNull(graph.baz)

  val workingDir = Paths.get("").toAbsolutePath()
  val candidates =
    listOf(
      workingDir.resolve("metro/build/test-metro-reports/main/sharding-plan-ApplicationComponent.txt"),
      workingDir.resolve("build/test-metro-reports/main/sharding-plan-ApplicationComponent.txt"),
    )
  val report =
    candidates.firstOrNull { Files.exists(it) }
      ?: error("Expected sharding report at ${candidates.joinToString { it.pathString }}")

  val content = report.readText()
  require("Shard count: 2" in content) { "Missing shard count.\n$content" }
  require("Init function count: 2" in content) { "Missing init function count.\n$content" }

  val totalFieldInitializers =
    Regex("Total field initializers: (\\d+)")
      .find(content)
      ?.groupValues
      ?.get(1)
      ?.toInt()
      ?: error("Total field initializer count missing.\n$content")
  val fieldCounts =
    Regex("fieldCount: (\\d+)").findAll(content).map { it.groupValues[1].toInt() }.toList()
  require(fieldCounts == listOf(2, 1)) { "Unexpected field counts: $fieldCounts\n$content" }
  require(totalFieldInitializers == fieldCounts.sum()) {
    "Total field initializer count mismatch: $totalFieldInitializers vs ${fieldCounts.sum()}\n$content"
  }

  val deferredCounts =
    Regex("deferredStatementCount: (\\d+)").findAll(content).map { it.groupValues[1].toInt() }.toList()
  require(deferredCounts == listOf(0, 0)) { "Unexpected deferred counts: $deferredCounts\n$content" }

  val totalStatements =
    Regex("totalStatements: (\\d+)").findAll(content).map { it.groupValues[1].toInt() }.toList()
  val expectedTotalStatements =
    fieldCounts.zip(deferredCounts) { fields, deferred -> fields + deferred }
  require(totalStatements == expectedTotalStatements) {
    "Unexpected total statements: $totalStatements vs $expectedTotalStatements\n$content"
  }

  val shardHeaders = Regex("Shard \\d \\((.+)\\)").findAll(content).toList()
  require(shardHeaders.size == 2) { "Expected 2 shard sections but found ${shardHeaders.size}\n$content" }

  val nestedShardNames =
    graph::class.java.declaredClasses.mapNotNull { it.simpleName }.filter { it.contains("Shard") }
  require(nestedShardNames.isNotEmpty()) {
    "Expected nested shard classes on ${graph::class.qualifiedName}"
  }

  return "OK"
}
