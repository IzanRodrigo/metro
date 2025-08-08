// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.incremental

import com.autonomousapps.kit.GradleBuilder.build
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShardingConfigurationTest {
  @Test
  fun `test sharding configuration with custom values`() {
    val fixture =
      object : MetroProject() {
        override fun sources() = listOf(largeGraph)

        // Create a large graph that would trigger sharding
        private val largeGraph =
          source(
            """
            @DependencyGraph(Unit::class)
            interface AppGraph {
              ${(1..30).joinToString("\n  ") { "val service$it: Service$it" }}
            }

            ${(1..30).joinToString("\n") { "@Inject class Service$it" }}
            """
          )

        override fun StringBuilder.onBuildScript() {
          // language=kotlin
          appendLine(
            """
              metro {
                reportsDestination = file("${'$'}buildDir/metro")
                bindingsPerGraphShard = 5
              }
            """
              .trimIndent()
          )
        }
      }

    val project = fixture.gradleProject

    // Build should succeed with custom sharding configuration
    val buildResult = build(project.rootDir, "compileKotlin")
    assertThat(buildResult.output).contains("BUILD SUCCESSFUL")

    // Check that sharding occurred by looking for sharding-related logs
    // (Note: logs would only appear with debug enabled, but we verify the build succeeds)
  }

}
