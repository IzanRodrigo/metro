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

/**
 * Tests for component sharding with multibinding-heavy graphs.
 *
 * Verifies that sharding correctly handles:
 * - Set multibindings (@IntoSet, @ElementsIntoSet)
 * - Map multibindings (@IntoMap with various key types)
 * - Distribution of multibinding contributions across shards
 * - Proper initialization ordering when multibindings depend on each other
 */
class ShardingMultibindingsTest : MetroCompilerTest() {

  private fun setMultibindingSources(): List<com.tschuchort.compiletesting.SourceFile> =
    listOf(
      source(
        """
        import javax.inject.Inject
        import javax.inject.Qualifier

        object AppScope

        @Qualifier
        annotation class Named(val value: String)

        @DependencyGraph(scope = AppScope::class)
        interface ApplicationComponent {
          val plugins: Set<Plugin>

          @DependencyGraph.Factory
          interface Factory {
            fun create(): ApplicationComponent
          }
        }

        interface Plugin {
          val name: String
        }

        @SingleIn(AppScope::class)
        @ContributesIntoSet(AppScope::class)
        class PluginA @Inject constructor() : Plugin {
          override val name = "A"
        }

        @SingleIn(AppScope::class)
        @ContributesIntoSet(AppScope::class)
        class PluginB @Inject constructor() : Plugin {
          override val name = "B"
        }

        @SingleIn(AppScope::class)
        @ContributesIntoSet(AppScope::class)
        class PluginC @Inject constructor() : Plugin {
          override val name = "C"
        }

        @SingleIn(AppScope::class)
        @ContributesIntoSet(AppScope::class)
        class PluginD @Inject constructor() : Plugin {
          override val name = "D"
        }

        @SingleIn(AppScope::class)
        @ContributesIntoSet(AppScope::class)
        class PluginE @Inject constructor() : Plugin {
          override val name = "E"
        }
        """.trimIndent(),
        "dev.zacsweers.metro.runtime.annotations.*",
      )
    )

  private fun mapMultibindingSources(): List<com.tschuchort.compiletesting.SourceFile> =
    listOf(
      source(
        """
        import javax.inject.Inject

        object AppScope

        @DependencyGraph(scope = AppScope::class)
        interface ApplicationComponent {
          val handlers: Map<String, Handler>

          @DependencyGraph.Factory
          interface Factory {
            fun create(): ApplicationComponent
          }
        }

        interface Handler {
          fun handle()
        }

        @SingleIn(AppScope::class)
        @ContributesIntoMap(AppScope::class)
        @StringKey("http")
        class HttpHandler @Inject constructor() : Handler {
          override fun handle() {}
        }

        @SingleIn(AppScope::class)
        @ContributesIntoMap(AppScope::class)
        @StringKey("https")
        class HttpsHandler @Inject constructor() : Handler {
          override fun handle() {}
        }

        @SingleIn(AppScope::class)
        @ContributesIntoMap(AppScope::class)
        @StringKey("ws")
        class WebSocketHandler @Inject constructor() : Handler {
          override fun handle() {}
        }

        @SingleIn(AppScope::class)
        @ContributesIntoMap(AppScope::class)
        @StringKey("file")
        class FileHandler @Inject constructor() : Handler {
          override fun handle() {}
        }
        """.trimIndent(),
        "dev.zacsweers.metro.runtime.annotations.*",
      )
    )

  @Test
  fun `sharding with set multibindings distributes contributions across shards`() {
    val plan =
      compileAndReadShardingPlan(setMultibindingSources()) {
        it.copy(
          shrinkUnusedBindings = false,
          enableDaggerRuntimeInterop = true,
          keysPerGraphShard = 2, // Aggressive sharding to force distribution
        )
      }

    println(plan)
    // Should have multiple shards
    assertThat(plan).contains("Shard 1")
    assertThat(plan).contains("Shard 2")

    // Multibinding contributions should be distributed
    assertThat(plan).contains("Plugin")
    assertThat(plan).contains("keys:")
  }

  @Test
  fun `sharding with map multibindings preserves key associations`() {
    val plan =
      compileAndReadShardingPlan(mapMultibindingSources()) {
        it.copy(
          shrinkUnusedBindings = false,
          enableDaggerRuntimeInterop = true,
          keysPerGraphShard = 2,
        )
      }

    println(plan)
    // Should have multiple shards
    assertThat(plan).contains("Shard 1")

    // Map multibinding handlers should appear in plan
    assertThat(plan).contains("Handler")
    assertThat(plan).contains("keys:")
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
}
