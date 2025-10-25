# CLAUDE.md

See @README.md, @docs, and @.github/CONTRIBUTING.md for project overview.

## Common Commands

### Building and Testing
- `./gradlew :compiler:test` - Run legacy compiler tests
- `./gradlew :compiler-tests:test` - Run new compiler tests
- `./gradlew :gradle-plugin:functionalTest` - Run Gradle integration tests
- `./gradlew -p samples check` - Run sample project tests
- `./metrow check` - Runs _all_ validation and tests in the project (tests, linting, API validation). This is expensive.

### Code Quality
Don't bother running code formatting, I'll handle that in commits.

### Documentation
- `./gradlew dokkaHtml` - Generate API documentation
- `docs/` - Contains all markdown documentation

### Benchmarks
- `cd benchmark && ./run_benchmarks.sh metro` - Run performance benchmarks

## Project Architecture

Metro is a compile-time dependency injection framework implemented as a Kotlin compiler plugin with multiplatform support.

### Core Modules

**compiler/** - Kotlin compiler plugin implementation
- Uses two-phase compilation: FIR (analysis) → IR (code generation)
- `fir/` - Frontend IR extensions for K2 compiler analysis and validation
- `ir/` - IR transformers for code generation
- `graph/` - Dependency graph analysis, validation, and cycle detection
- Entry point: `MetroCompilerPluginRegistrar.kt`

**runtime/** - Multiplatform annotations and runtime support
- Public annotation APIs: `@DependencyGraph`, `@Inject`, `@Provides`, `@Binds`, `@Scope`
- `internal/` - Runtime support classes (factories, providers, double-check)
- Supports JVM, JS, Native, and WASM targets

**gradle-plugin/** - Gradle integration
- `MetroGradleSubplugin.kt` - Main plugin implementation
- Provides `metro` DSL for configuration
- Automatically wires compiler plugin and runtime dependencies

**interop-dagger/** - Dagger interoperability
- Bridge functions between Metro and Dagger provider types
- Allows gradual migration from Dagger to Metro

### Testing Strategy

**compiler/src/test** - Legacy compiler tests

**compiler-tests/** - Modern JetBrains compiler testing infrastructure
- Box tests (`data/box/`) - Full compilation and execution validation
- Diagnostic tests (`data/diagnostic/`) - Error reporting and validation
- Dump tests (`data/dump/`) - FIR/IR tree inspection and verification

To create a new test, add a source file under the appropriate directory and then run `./gradlew :compiler-tests:generateTests` to regenerate tests. This will then add a generated junit test that can be run via the standard `./gradlew :compiler-tests:test` task.

**samples/** - Real-world integration examples
- `weather-app/` - Basic multiplatform usage
- `android-app/` - Android-specific integration
- `multi-module-test/` - Complex multi-module dependency graph

### Key Files for Development

**Compiler Plugin Development:**
- `compiler/src/main/kotlin/dev/zacsweers/metro/compiler/fir/` - FIR analysis extensions
- `compiler/src/main/kotlin/dev/zacsweers/metro/compiler/ir/` - Code generation transformers
- `compiler/src/main/kotlin/dev/zacsweers/metro/compiler/graph/` - Dependency graph logic

**API Changes:**
- `runtime/src/commonMain/kotlin/dev/zacsweers/metro/` - Public annotation APIs
- Update both runtime and samples when changing public APIs

**Build Configuration:**
- `gradle/libs.versions.toml` - Centralized dependency versions
- Each module has `gradle.properties` for module-specific configuration
- Root `build.gradle.kts` contains shared build logic and conventions

### Development Patterns

- **Code Generation**: Uses KotlinPoet for generating factory classes and injection code
- **Graph Analysis**: Topological sorting with cycle detection for dependency resolution
- **Multiplatform**: Maximize shared common code, platform-specific only when necessary
- **Binary Compatibility**: API validation enabled for public modules (excluding compiler internals)
- **Shadow JAR**: Compiler uses shadow JAR to avoid dependency conflicts at runtime

### Testing New Features

1. Add compiler tests in `compiler-tests/src/test/data/` using the appropriate test type
2. Test existing tests with `./gradlew :compiler:test`.
3. Test integration with samples in `samples/` directory
4. Run `./metrow check` to validate all tests and API compatibility

### Important Notes

- Kotlin compiler plugins are not stable APIs - Metro tracks Kotlin releases closely
- FIR is for analysis/validation, IR is for code generation - don't mix concerns
- Always run API validation (`apiCheck`) when changing public APIs
- Use existing test infrastructure patterns rather than creating new test types
- Don't run gradle commands with unnecessary flags like `--info`, `--no-daemon`, etc.
- Don't cd into a module directory and run gradle commands - use `./gradlew` instead from the directory that wrapper is in.
- Do not run tests automatically, prompt first.

## Component Sharding Implementation

**Status:** ✅ COMPLETE AND WORKING (as of 2025-10-25)

**What It Does:**
Metro now automatically shards large components (1000+ bindings) into multiple nested shard classes to avoid JVM method size limits.

### Key Files

**Algorithm & Generation:**
- `compiler/src/main/kotlin/dev/zacsweers/metro/compiler/ir/graph/IrGraphGenerator.kt`
  - `partitionPropertyInitializers()` - SCC-aware partitioning
  - `generateShardClass()` - Shard class generation
  - `computeShardInitializationOrder()` - Topological sort
  - `resolveActualKey()` - Alias resolution for @Binds

**Abstraction:**
- `compiler/src/main/kotlin/dev/zacsweers/metro/compiler/ir/BindingFieldAccess.kt`
- `compiler/src/main/kotlin/dev/zacsweers/metro/compiler/ir/DefaultBindingFieldAccess.kt`

**Ownership Tracking:**
- `compiler/src/main/kotlin/dev/zacsweers/metro/compiler/ir/graph/BindingPropertyContext.kt`

### Critical K2 Patterns for Shard Generation

**✅ CORRECT Class Creation Sequence:**
```kotlin
val cls = irFactory.buildClass { ... }.apply {
  superTypes = listOf(irBuiltIns.anyType)
  createThisReceiverParameter()  // MUST be first!
  parent = graphClass
  graphClass.addChild(this)
}

// Add constructor AFTER apply completes
val ctor = cls.addConstructor {
  returnType = cls.defaultType  // NOW safe!
}
```

**❌ WRONG - Causes defaultType NPE:**
```kotlin
val cls = graphClass.factory.buildClass { ... }  // DON'T use class.factory!
val cls = irFactory.buildClass { ... }.apply {
  // NO createThisReceiverParameter()!  // WRONG!
  addConstructor { }  // WRONG - too early!
}
```

**✅ defaultType Safety Rules:**
- Call ONLY after `createThisReceiverParameter()` called
- Call ONLY after class added to parent
- Call ONLY after constructor added (for constructor returnType)
- Use `irBuiltIns.anyType` as placeholder if unsure

**✅ Function Dispatch Receivers:**
```kotlin
val func = cls.addFunction { ... }.apply {
  val receiver = cls.thisReceiver!!.copyTo(this)
  setDispatchReceiver(receiver)  // REQUIRED!
  // Now dispatchReceiverParameter!! works
}
```

### Testing Sharding

**Box Test:**
```kotlin
// In compiler-tests/src/test/data/box/dependencygraph/*.kt
// KEYS_PER_GRAPH_SHARD: 5
// ENABLE_COMPONENT_SHARDING: true
```

**Run:**
```bash
./gradlew :compiler-tests:generateTests
./gradlew :compiler-tests:test --tests "*Sharding*"
```

**Check Reports:**
```bash
cat app/build/metro-reports/*/sharding-plan-*.txt
```

### Troubleshooting Sharding

**If you see defaultType NPE:**
1. Ensure `createThisReceiverParameter()` called first
2. Check constructor added after buildClass apply
3. Verify using `irFactory` not `graphClass.factory`
4. Check dispatch receiver set for functions

**If you see cycle errors:**
1. Check `resolveActualKey()` handles @Binds correctly
2. Review cross-shard dependency tracking
3. Fallback to sequential order is safe

### Documentation

Complete sharding documentation in workspace: `docs/metro/00-INDEX.md`

**Quick links:**
- Success summary: `docs/metro/GRAPH-SHARDING-SUCCESS.md`
- Implementation guide: `docs/metro/02-sharding-implementation.md`
- Configuration: `docs/metro/05-configuration.md`
- Troubleshooting: `docs/metro/06-troubleshooting.md`

### Android-Spain Integration

**Verified working:**
- Android-spain: 2,127 bindings → 22 shards
- Build: SUCCESS in 3m 16s
- Config: `keysPerGraphShard = 100`
- Reports: Generated in `app/build/metro-reports/internDebug/`
