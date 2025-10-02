// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0

pluginManagement {
  repositories {
    mavenCentral()
    google()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositories {
    mavenCentral()
    google()
  }
}

rootProject.name = "metro"

include(":compiler", ":compiler-tests", ":gradle-plugin", ":interop-dagger", ":runtime")

val VERSION_NAME: String by extra.properties
