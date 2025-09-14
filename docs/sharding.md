# Graph Sharding

## Overview

Graph sharding is Metro's solution for handling large dependency graphs that exceed JVM limitations (64KB method size, field count limits). It automatically splits large graphs into smaller "shard" classes while maintaining correct dependency relationships.

## Status

**Current Status: PARTIALLY FUNCTIONAL** (as of 2025-01-14)

- ✅ Core sharding infrastructure implemented
- ✅ Basic sharding tests passing (`sharding_minimal`)
- ✅ SwitchingProvider integration working
- ⚠️ Complex module scenarios still have issues
- ⚠️ Some advanced sharding tests failing

## When to Use Sharding

Sharding is recommended when:
- Your component has more than 100-200 bindings
- You encounter "Method too large" compilation errors
- Build times become excessively long due to large generated classes

## Configuration

### Via Gradle DSL (Recommended)

```kotlin
metro {
  jvmSharding {
    enabled = true
    strategy = ShardingStrategy.AGGRESSIVE
    keysPerShard = 50  // Bindings per shard
  }
}
```

### Via Compiler Arguments

```kotlin
kotlinOptions {
  freeCompilerArgs += listOf(
    "-Xplugin-option=metro:sharding.enabled=true",
    "-Xplugin-option=metro:sharding.keysPerShard=50"
  )
}
```

## How It Works

### 1. Analysis Phase
- Analyzes the dependency graph using Tarjan's algorithm for strongly connected components (SCCs)
- Groups related bindings together to minimize cross-shard dependencies
- Filters out bindings that must stay in the main graph:
  - BoundInstance bindings (constructor parameters)
  - Alias bindings (references to other bindings)
  - GraphDependency bindings with field access
  - Module provisions (@Provides methods) - to avoid circular initialization issues
- Creates a sharding plan that respects topological ordering

### 2. Code Generation
- Generates static nested shard classes within the main component
- Each shard receives necessary module instances and a reference to the main graph
- Shard instances are initialized BEFORE main graph binding fields to ensure availability
- All shard fields are registered in the binding context before creating any initializers
- Cross-shard dependencies are accessed via internal fields (following Dagger's pattern)
- Intra-shard dependencies (within the same shard) use direct field access

### 3. Runtime Behavior
- Shards are initialized eagerly in the component constructor before other bindings
- Dependencies flow from higher-numbered shards to lower-numbered ones
- No circular dependencies between shards (cycles stay within the same shard)
- Module provisions stay in the main graph to avoid initialization order issues

## Recent Fixes (2025-01-14)

### 1. @BindsInstance Parameter Handling
- **Issue**: @BindsInstance parameters were not properly creating instance fields
- **Fix**: Modified `IrGraphGenerator` to create both instance and provider fields for @BindsInstance parameters
- **Files Modified**:
  - `IrGraphGenerator.kt` (lines 398-410)
  - `IrGraphExpressionGenerator.kt` (lines 416-450, 1377-1393)

### 2. Factory Invocation
- **Issue**: `MetroFactory.create()` was returning the factory itself instead of invoking it for an instance
- **Fix**: Added logic to detect when `create()` returns a Factory type and invoke it
- **File Modified**: `IrMetroFactory.kt` (lines 85-97)

### 3. SwitchingProvider Field Access in Sharding Context
- **Issue**: SwitchingProvider couldn't access dependency fields in shards when generating inline instances
- **Fix**: Added special handling to route field access through the graph when receiver is a SwitchingProvider
- **File Modified**: `IrGraphExpressionGenerator.kt` (lines 754-786)

### 4. SwitchingProvider Recursion Prevention
- **Issue**: Potential for infinite recursion when SwitchingProvider invokes itself
- **Fix**: Ensured SwitchingProvider uses `bypassProviderFor` to generate inline instances
- **File Modified**: `SwitchingProviderGenerator.kt` (lines 58-64)

## Architecture Details

### Binding Distribution

**Always in Main Graph:**
- BoundInstance bindings (constructor parameters)
- Alias bindings (references to other bindings)
- GraphDependency bindings with field access
- Module provisions (@Provides methods)

**Distributed to Shards:**
- ConstructorInjected bindings
- MembersInjected bindings
- Multibinding contributions
- Assisted bindings
- GraphExtension bindings

### Cross-Shard Access Pattern

Shards use internal visibility for direct field access:

```kotlin
// Shard 1 can directly access Shard 2's fields
internal class ApplicationComponentShard1(
  private val graph: ApplicationComponent,
  private val appModule: ApplicationModule
) {
  internal val service1Provider = ...

  private val service2Provider =
    graph.shard2.service2Provider  // Direct access to shard2's field
}
```

## Limitations

- **Subgraphs are not sharded**: Following Dagger's pattern, only top-level components are sharded
- **Module provisions not sharded**: @Provides methods stay in the main graph to avoid circular initialization issues
- **Minimum overhead**: Sharding adds a small overhead, so it's only beneficial for large graphs
- **Conservative approach**: Current implementation is conservative about what can be sharded to ensure stability

## Known Issues

1. **Module Annotation Handling**: Complex tests fail with "Illegal annotation class 'Module'" errors
2. **Advanced Binding Types**: Some advanced binding scenarios may not work correctly with sharding
3. **Performance**: While functional, sharding performance optimization is ongoing

## Debugging Sharding Issues

1. Enable debug logging: `-Dmetro.log=true -Dmetro.debug=true`
2. Generate sharding reports: `-Dmetro.reports-destination=/tmp/metro-reports`
3. Check report output at: `/tmp/metro-reports/<GraphName>_sharding_report.md`
4. Reports include: shard structure, binding distribution, generated Kotlin code

## Testing

To run sharding tests:

```bash
# Run all sharding tests
./gradlew :compiler-tests:test --tests "*Sharding*"

# Run specific test with debug output
./gradlew :compiler-tests:test --tests "*testSharding_minimal*" -Dmetro.log=true
```

## Future Work

- [ ] Fix module annotation handling in sharded contexts
- [ ] Optimize shard initialization performance
- [ ] Support sharding for subgraphs (if needed)
- [ ] Improve cross-shard dependency resolution
- [ ] Add more comprehensive test coverage