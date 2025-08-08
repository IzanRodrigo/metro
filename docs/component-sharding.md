# Component Sharding

Metro supports component sharding to avoid "class too large" errors that can occur with very large dependency graphs. This feature is inspired by Dagger's component sharding but adapted for Metro's IR-based code generation.

## Overview

When a dependency graph has many bindings, the generated class can become too large, hitting JVM class file size limits (typically 64KB method limit or 65535 constant pool limit). Component sharding distributes bindings across multiple nested shard classes to stay within these limits.

## Configuration

Component sharding can be configured through the Metro Gradle plugin:

### Gradle Plugin Configuration

```kotlin
metro {
  // Maximum number of bindings per shard (default: 100)
  bindingsPerGraphShard = 50
}
```

See [Component Sharding Gradle Configuration](component-sharding-gradle-config.md) for detailed configuration options.

### Command Line

The option can also be set via command line:
```
-P metro.bindings-per-graph-shard=50
```

## How It Works

1. **Detection**: Metro counts the number of bindings in each dependency graph during compilation
2. **Threshold Check**: If the number of bindings exceeds `bindingsPerGraphShard`, sharding is activated
3. **Distribution**: Bindings are distributed across multiple shard classes using a simple chunking strategy
4. **Code Generation**: 
   - Shard classes are generated as nested classes within the main graph
   - Each shard contains a subset of the bindings
   - The main graph holds references to shard instances
   - Delegating fields in the main graph provide transparent access to sharded bindings

## Generated Structure

For a large graph that gets sharded, Metro generates code like this:

```kotlin
@DependencyGraph(AppScope::class)
interface MyGraph {
  // Main graph accessors delegate to shards
  val service1: Service1 // Delegated to shard1.service1Provider
  val service2: Service2 // Delegated to shard1.service2Provider
  val service3: Service3 // Delegated to shard2.service3Provider
  
  // Generated implementation
  class MetroGraph : MyGraph {
    private val graphShard1: GraphShard1
    private val graphShard2: GraphShard2
    
    // Delegating properties
    override val service1: Service1 get() = graphShard1.service1Provider.get()
    override val service2: Service2 get() = graphShard1.service2Provider.get()
    override val service3: Service3 get() = graphShard2.service3Provider.get()
    
    // Nested shard classes
    private class GraphShard1(private val graph: MetroGraph) {
      internal val service1Provider: Provider<Service1> = // ...
      internal val service2Provider: Provider<Service2> = // ...
    }
    
    private class GraphShard2(private val graph: MetroGraph) {
      internal val service3Provider: Provider<Service3> = // ...
    }
  }
}
```

## Performance Considerations

- **Compilation**: Sharding can slightly increase compilation time due to additional class generation
- **Runtime**: Minimal runtime overhead - just an extra field access for sharded bindings
- **Memory**: Each shard is a separate object, but memory overhead is minimal

## When to Use Sharding

Consider enabling sharding with a lower threshold if you:

- Have very large dependency graphs (>100 bindings)
- Encounter "class too large" compilation errors  
- Want to future-proof against growth in graph size

## Troubleshooting

### Class Too Large Errors

If you encounter errors like:
- "The code for the static initializer is exceeding the 65535 bytes limit"
- "Too many constants in constant pool" 
- "Method code too large"

Try these solutions:

1. **Reduce `bindingsPerGraphShard`**: Creates smaller shards with fewer bindings each
2. **Refactor complex bindings**: Consider breaking up bindings that have many dependencies

Example configuration for projects with very complex bindings:

```kotlin
metro {
  bindingsPerGraphShard = 50 // Smaller shards
}
```

Note: Metro now automatically handles extremely large multibindings (maps and sets with many entries) by splitting their initialization across multiple statements, so "Method too large" errors from large ViewModelFactories or similar multibindings should no longer occur.

### Compilation Warnings

Metro will log when sharding is applied:

```
Graph com.example.MyGraph has 150 bindings, which exceeds the sharding threshold of 100. Implementing component sharding.
```

## Implementation Details

### Dependency-Aware Distribution

Metro uses an intelligent sharding algorithm that:

- **Strongly Connected Components (SCC)**: Keeps circular dependencies together in the same shard using Tarjan's algorithm
- **Topological Sorting**: Ensures proper initialization order across shards
- **Dependency Analysis**: Analyzes dependencies between shards to ensure correct initialization order

### Method Size Considerations

Metro automatically handles method size limitations in two ways:

1. **Chunking**: Bindings within shards are distributed across multiple initialization methods to avoid exceeding JVM method size limits
2. **Large Multibinding Handling**: Map and set multibindings with many entries (>50) are automatically split into smaller chunks during initialization to prevent any single expression from becoming too large

## Future Enhancements

Potential future improvements:

- **Size-based optimization**: Consider actual bytecode size rather than just binding count
- **Custom sharding strategies**: Allow users to provide custom distribution logic
- **Adaptive sharding**: Dynamically adjust shard size based on compilation metrics