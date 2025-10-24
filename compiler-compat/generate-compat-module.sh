#!/bin/bash

# Copyright (C) 2025 Zac Sweers
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

# Ensure script is run from project root
if [ ! -f "settings.gradle.kts" ] || [ ! -d "compiler-compat" ]; then
    echo "❌ Error: This script must be run from the project root directory"
    echo "Example: ./compiler-compat/generate-compat-module.sh 2.3.0"
    exit 1
fi

# Parse arguments
VERSION_ONLY=false
KOTLIN_VERSION=""

while [[ $# -gt 0 ]]; do
    case $1 in
        --version-only)
            VERSION_ONLY=true
            shift
            ;;
        -*)
            echo "Unknown option: $1"
            echo "Usage: $0 [--version-only] <kotlin-version>"
            echo ""
            echo "Options:"
            echo "  --version-only    Add version to version-aliases.txt for CI support (no module generation)"
            echo ""
            echo "Examples:"
            echo "  $0 2.3.0-dev-9673              # Generate full module"
            echo "  $0 --version-only 2.3.21       # Add CI-supported version alias only"
            exit 1
            ;;
        *)
            if [ -z "$KOTLIN_VERSION" ]; then
                KOTLIN_VERSION="$1"
            else
                echo "Error: Multiple versions specified"
                exit 1
            fi
            shift
            ;;
    esac
done

if [ -z "$KOTLIN_VERSION" ]; then
    echo "Error: No Kotlin version specified"
    echo "Usage: $0 [--version-only] <kotlin-version>"
    echo ""
    echo "Options:"
    echo "  --version-only    Add version to version-aliases.txt for CI support (no module generation)"
    echo ""
    echo "Examples:"
    echo "  $0 2.3.0-dev-9673              # Generate full module"
    echo "  $0 --version-only 2.3.21       # Add CI-supported version alias only"
    exit 1
fi

# Function to add version to version-aliases.txt
add_to_version_aliases() {
    local version="$1"
    local aliases_file="compiler-compat/version-aliases.txt"

    # Check if version already exists
    if grep -Fxq "$version" "$aliases_file" 2>/dev/null; then
        echo "⚠️  Version $version already exists in $aliases_file"
        return 0
    fi

    # Add version to the file (maintain sorted order)
    echo "$version" >> "$aliases_file"

    # Re-sort the file (keeping header comments at the top)
    local tmpfile=$(mktemp)
    # Extract header (all lines until first non-comment/non-blank line)
    awk '/^[^#]/ && NF {exit} {print}' "$aliases_file" > "$tmpfile"
    # Extract and sort versions
    grep -v '^#' "$aliases_file" | grep -v '^[[:space:]]*$' | sort >> "$tmpfile"
    mv "$tmpfile" "$aliases_file"

    echo "✅ Added $version to $aliases_file"
}

# If --version-only, just add to version-aliases.txt and exit
if [ "$VERSION_ONLY" = true ]; then
    echo "Adding version $KOTLIN_VERSION to version-aliases.txt (--version-only mode)"
    add_to_version_aliases "$KOTLIN_VERSION"
    echo ""
    echo "✅ Done! Version added to version-aliases.txt for CI support"
    echo ""
    echo "Note: This version will use the nearest available module implementation."
    echo "To generate a dedicated module implementation, run without --version-only flag."
    exit 0
fi

# Transform version to valid package name
# 1. Remove dots
# 2. Replace dashes with underscores
PACKAGE_SUFFIX=$(echo "$KOTLIN_VERSION" | sed 's/\.//g' | sed 's/-/_/g')
MODULE_NAME="k$PACKAGE_SUFFIX"

echo "Generating compatibility module for Kotlin $KOTLIN_VERSION"
echo "Module name: $MODULE_NAME"
echo "Package suffix: $PACKAGE_SUFFIX"

# Create module directory structure (relative to compiler-compat/)
MODULE_DIR="compiler-compat/$MODULE_NAME"
mkdir -p "$MODULE_DIR/src/main/kotlin/dev/zacsweers/metro/compiler/compat/$MODULE_NAME"
mkdir -p "$MODULE_DIR/src/main/resources/META-INF/services"

# Generate version.txt
echo "$KOTLIN_VERSION" > "$MODULE_DIR/version.txt"

# Generate build.gradle.kts
cat > "$MODULE_DIR/build.gradle.kts" << EOF
// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins { alias(libs.plugins.kotlin.jvm) }

kotlin {
  compilerOptions {
    freeCompilerArgs.add("-Xcontext-parameters")
    optIn.addAll(
      "org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi",
      "org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI",
    )
  }
}

dependencies {
  val kotlinVersion = providers.fileContents(layout.projectDirectory.file("version.txt")).asText.map { it.trim() }
  compileOnly(kotlinVersion.map { "org.jetbrains.kotlin:kotlin-compiler:\$it" })
  compileOnly(libs.kotlin.stdlib)
  api(project(":compiler-compat"))
}
EOF

# Generate CompatContextImpl.kt
cat > "$MODULE_DIR/src/main/kotlin/dev/zacsweers/metro/compiler/compat/$MODULE_NAME/CompatContextImpl.kt" << EOF
// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.compat.$MODULE_NAME

import dev.zacsweers.metro.compiler.compat.CompatContext

public class CompatContextImpl : CompatContext {
  // TODO Implement

  public class Factory : CompatContext.Factory {
    override val minVersion: String = "$KOTLIN_VERSION"

    override fun create(): CompatContext = CompatContextImpl()
  }
}
EOF

# Generate service loader file
cat > "$MODULE_DIR/src/main/resources/META-INF/services/dev.zacsweers.metro.compiler.compat.CompatContext\$Factory" << EOF
dev.zacsweers.metro.compiler.compat.$MODULE_NAME.CompatContextImpl\$Factory
EOF

# Add version to version-aliases.txt
add_to_version_aliases "$KOTLIN_VERSION"

echo ""
echo "✅ Generated module structure:"
echo "  📁 $MODULE_DIR/"
echo "  📄 $MODULE_DIR/version.txt"
echo "  📄 $MODULE_DIR/build.gradle.kts"
echo "  📄 $MODULE_DIR/gradle.properties"
echo "  📄 $MODULE_DIR/src/main/kotlin/dev/zacsweers/metro/compiler/compat/$MODULE_NAME/CompatContextImpl.kt"
echo "  📄 $MODULE_DIR/src/main/resources/META-INF/services/dev.zacsweers.metro.compiler.compat.CompatContext\$Factory"
echo ""
echo "✅ Updated configuration:"
echo "  📝 Added module to settings.gradle.kts (auto-discovered)"
echo "  📝 Added dependency to compiler/build.gradle.kts (auto-discovered)"
echo "  📝 Added $KOTLIN_VERSION to compiler-compat/version-aliases.txt"
echo ""
echo "Next step: Implement the CompatContextImpl.kt based on Kotlin $KOTLIN_VERSION APIs"
