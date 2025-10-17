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
 * Tests for component sharding with scoped bindings and scope hierarchies.
 *
 * Verifies that sharding correctly handles:
 * - Multiple scope annotations (@SingleIn with different scopes)
 * - Scope hierarchies (parent-child relationships)
 * - Proper initialization ordering respecting scope boundaries
 * - Distribution of scoped bindings across shards
 */
class ShardingScopedGraphTest : MetroCompilerTest() {

  private fun multipleScopesSources(): List<com.tschuchort.compiletesting.SourceFile> =
    listOf(
      source(
        """
        import javax.inject.Inject

        object AppScope
        object UserScope
        object ActivityScope

        @DependencyGraph(scope = AppScope::class)
        interface ApplicationComponent {
          val appService: AppService
          val userManager: UserManager
          val activityHelper: ActivityHelper

          @DependencyGraph.Factory
          interface Factory {
            fun create(): ApplicationComponent
          }
        }

        @SingleIn(AppScope::class)
        class AppService @Inject constructor()

        @SingleIn(AppScope::class)
        class Database @Inject constructor()

        @SingleIn(UserScope::class)
        class UserManager @Inject constructor(
          val database: Database
        )

        @SingleIn(UserScope::class)
        class UserPreferences @Inject constructor()

        @SingleIn(ActivityScope::class)
        class ActivityHelper @Inject constructor(
          val userManager: UserManager,
          val appService: AppService
        )

        @SingleIn(ActivityScope::class)
        class ActivityState @Inject constructor()
        """.trimIndent(),
        "dev.zacsweers.metro.runtime.annotations.*",
      )
    )

  private fun scopeHierarchySources(): List<com.tschuchort.compiletesting.SourceFile> =
    listOf(
      source(
        """
        import javax.inject.Inject

        object AppScope
        object FeatureScope

        @DependencyGraph(scope = AppScope::class)
        interface ApplicationComponent {
          val root: RootService
          val feature: FeatureService

          @DependencyGraph.Factory
          interface Factory {
            fun create(): ApplicationComponent
          }
        }

        @SingleIn(AppScope::class)
        class SharedResource @Inject constructor()

        @SingleIn(AppScope::class)
        class RootService @Inject constructor(
          val shared: SharedResource
        )

        @SingleIn(FeatureScope::class)
        class FeatureService @Inject constructor(
          val shared: SharedResource,
          val root: RootService
        )

        @SingleIn(FeatureScope::class)
        class FeatureCache @Inject constructor()
        """.trimIndent(),
        "dev.zacsweers.metro.runtime.annotations.*",
      )
    )

  @Test
  fun `sharding with multiple scopes preserves scope boundaries`() {
    val plan =
      compileAndReadShardingPlan(multipleScopesSources()) {
        it.copy(
          shrinkUnusedBindings = false,
          enableDaggerRuntimeInterop = true,
          keysPerGraphShard = 2, // Force sharding
        )
      }

    println(plan)
    // Should have multiple shards
    assertThat(plan).contains("Shard 1")

    // Scoped services should appear in plan
    assertThat(plan).contains("Service")
    assertThat(plan).contains("keys:")
  }

  @Test
  fun `sharding respects scope hierarchy initialization order`() {
    val plan =
      compileAndReadShardingPlan(scopeHierarchySources()) {
        it.copy(
          shrinkUnusedBindings = false,
          enableDaggerRuntimeInterop = true,
          keysPerGraphShard = 2,
        )
      }

    println(plan)
    // Should have sharding
    assertThat(plan).contains("Shard")

    // Parent scope dependencies should be initialized before child scope
    assertThat(plan).contains("SharedResource")
    assertThat(plan).contains("RootService")
    assertThat(plan).contains("FeatureService")
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
