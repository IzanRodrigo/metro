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
 * Tests for component sharding with Dagger annotation interoperability.
 *
 * Verifies that sharding correctly handles:
 * - Standard Dagger annotations (@Module, @Provides, @Component)
 * - javax.inject annotations (@Inject, @Singleton, @Qualifier)
 * - Mixed Metro and Dagger annotations in the same graph
 * - Proper code generation when processing Dagger-annotated code
 */
class ShardingDaggerInteropTest : MetroCompilerTest() {

  private fun daggerModuleSources(): List<com.tschuchort.compiletesting.SourceFile> =
    listOf(
      source(
        """
        import dagger.Module
        import dagger.Provides
        import javax.inject.Inject
        import javax.inject.Singleton

        object AppScope

        @DependencyGraph(scope = AppScope::class, bindingContainers = [NetworkModule::class])
        interface ApplicationComponent {
          val httpClient: HttpClient
          val apiService: ApiService

          @DependencyGraph.Factory
          interface Factory {
            fun create(): ApplicationComponent
          }
        }

        @Module
        object NetworkModule {
          @Provides
          @Singleton
          fun provideHttpClient(): HttpClient = HttpClient()

          @Provides
          @Singleton
          fun provideRetrofit(client: HttpClient): Retrofit = Retrofit(client)
        }

        class HttpClient

        class Retrofit(val client: HttpClient)

        @Singleton
        class ApiService @Inject constructor(
          val retrofit: Retrofit,
          val client: HttpClient
        )
        """.trimIndent(),
        "dev.zacsweers.metro.runtime.annotations.*",
      )
    )

  private fun javaxInjectSources(): List<com.tschuchort.compiletesting.SourceFile> =
    listOf(
      source(
        """
        import javax.inject.Inject
        import javax.inject.Singleton
        import javax.inject.Named

        object AppScope

        @DependencyGraph(scope = AppScope::class)
        interface ApplicationComponent {
          val serviceA: ServiceA
          val serviceB: ServiceB

          @DependencyGraph.Factory
          interface Factory {
            fun create(): ApplicationComponent
          }
        }

        @Singleton
        class ServiceA @Inject constructor()

        @Singleton
        class ServiceB @Inject constructor(
          val serviceA: ServiceA
        )

        @Singleton
        class Repository @Inject constructor(
          @Named("primary") val primaryDb: Database,
          @Named("secondary") val secondaryDb: Database
        )

        class Database
        """.trimIndent(),
        "dev.zacsweers.metro.runtime.annotations.*",
      )
    )

  @Test
  fun `sharding works with Dagger Module annotations`() {
    val plan =
      compileAndReadShardingPlan(daggerModuleSources()) {
        it.copy(
          shrinkUnusedBindings = false,
          enableDaggerRuntimeInterop = true,
          keysPerGraphShard = 2, // Force sharding
        )
      }

    println(plan)
    // Should have sharding
    assertThat(plan).contains("Shard")

    // Dagger-provided bindings should appear
    assertThat(plan).contains("HttpClient")
    assertThat(plan).contains("keys:")
  }

  @Test
  fun `sharding works with javax inject annotations`() {
    val plan =
      compileAndReadShardingPlan(javaxInjectSources()) {
        it.copy(
          shrinkUnusedBindings = false,
          enableDaggerRuntimeInterop = true,
          keysPerGraphShard = 2,
        )
      }

    println(plan)
    // Should have sharding
    assertThat(plan).contains("Shard")

    // Services with javax.inject should appear
    assertThat(plan).contains("Service")
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
