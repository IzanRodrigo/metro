// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.compiler.MetroCompilerTest
import dev.zacsweers.metro.compiler.MetroOptions
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Test

class ShardingPlanTest : MetroCompilerTest() {

  private fun shardingSources(): List<com.tschuchort.compiletesting.SourceFile> =
    listOf(
      source(
        """
        import javax.inject.Inject

        object AppScope

        @DependencyGraph(scope = AppScope::class)
        interface ApplicationComponent {
          val root: Root

          @DependencyGraph.Factory
          interface Factory {
            fun create(): ApplicationComponent
          }
        }

        @SingleIn(AppScope::class)
        class Alpha @Inject constructor()

        @SingleIn(AppScope::class)
        class Beta @Inject constructor(val alpha: Alpha)

        @SingleIn(AppScope::class)
        class Gamma @Inject constructor(val beta: Beta)

        @SingleIn(AppScope::class)
        class Baz @Inject constructor()

        @SingleIn(AppScope::class)
        class Bar @Inject constructor(val baz: Baz)

        @SingleIn(AppScope::class)
        class Foo @Inject constructor(val bar: Bar)

        @SingleIn(AppScope::class)
        class Root @Inject constructor(val foo: Foo, val gamma: Gamma)
        """.trimIndent(),
        "dev.zacsweers.metro.runtime.annotations.*",
      )
    )

  @Test
  fun `writes sharding plan diagnostics`() {
    val plan =
      compileAndReadShardingPlan(shardingSources()) {
        it.copy(
          shrinkUnusedBindings = false,
          enableDaggerRuntimeInterop = true,
          keysPerGraphShard = 2,
        )
      }

    println(plan)
    assertThat(plan).contains("Shard 1")
    assertThat(plan).contains("Shard 2")
    assertThat(plan).contains("Keys per shard limit:")
    assertThat(plan).contains("keys:")
  }

  @Test
  fun `shards initialize dependencies before dependents`() {
    val plan =
      compileAndReadShardingPlan(shardingSources()) {
        it.copy(
          shrinkUnusedBindings = false,
          keysPerGraphShard = 1,
        )
      }

    println(plan)

    val shard1Section = shardSection(plan, 1)
    val shard2Section = shardSection(plan, 2)
    val shard3Section = shardSection(plan, 3)
    val shard4Section = shardSection(plan, 4)
    val shard5Section = shardSection(plan, 5)

    assertThat(shard1Section).contains("Baz")
    assertThat(shard2Section).contains("Bar")
    assertThat(shard3Section).contains("Foo")
    assertThat(shard4Section).contains("Gamma")
    assertThat(shard5Section).contains("Root")
    assertThat(plan.indexOf("Baz")).isLessThan(plan.indexOf("Bar"))
    assertThat(plan.indexOf("Bar")).isLessThan(plan.indexOf("Foo"))
    assertThat(plan.indexOf("Foo")).isLessThan(plan.indexOf("Gamma"))
    assertThat(plan.indexOf("Gamma")).isLessThan(plan.indexOf("Root"))
  }

  private fun compileAndReadShardingPlan(
    sources: List<com.tschuchort.compiletesting.SourceFile>,
    configure: (MetroOptions) -> MetroOptions = { it },
  ): String {
    val reportsDir = temporaryFolder.newFolder("reports").toPath()
    var plan = ""
    compile(
      *sources.toTypedArray(),
      options = configure(metroOptions).copy(reportsDestination = reportsDir),
    ) {
      plan = readShardingPlan(reportsDir)
    }
    return plan
  }

  private fun readShardingPlan(reportsDir: Path): String {
    val planPath =
      Files.walk(reportsDir).use { stream ->
        stream.filter { it.fileName.toString().startsWith("sharding-plan-") }
          .findFirst()
          .orElseThrow {
            val existing =
              Files.walk(reportsDir).use { all -> all.map { it.toString() }.toList() }
            error("No sharding plan file found in $reportsDir. Existing files: $existing")
          }
      }
    return planPath.readText().trim()
  }

  private fun shardSection(plan: String, shardNumber: Int): String {
    val marker = "Shard $shardNumber"
    val startIndex = plan.indexOf(marker)
    require(startIndex != -1) { "Shard $shardNumber not found in plan:\n$plan" }
    val nextMarker = "Shard ${shardNumber + 1}"
    val endIndex = plan.indexOf(nextMarker, startIndex).takeIf { it != -1 } ?: plan.length
    return plan.substring(startIndex, endIndex)
  }
}
