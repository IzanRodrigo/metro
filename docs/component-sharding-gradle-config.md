# Component Sharding Configuration in Gradle

The Metro Gradle plugin provides configuration options to control component sharding behavior:

## Basic Configuration

```kotlin
// build.gradle.kts
metro {
  // Maximum number of bindings per shard (default: 100)
  bindingsPerGraphShard = 50
  
  // Enable/disable parallel shard generation (default: true)
  enableParallelShardGeneration = true
  
  // Number of threads for parallel generation (default: 0 = auto)
  // 0 means use all available processors
  shardGenerationParallelism = 4
}
```

## Examples

### Aggressive Sharding
For very large graphs, you might want smaller shards:

```kotlin
metro {
  bindingsPerGraphShard = 25  // Create more, smaller shards
  enableParallelShardGeneration = true
  shardGenerationParallelism = 0  // Use all available cores
}
```

### Conservative Sharding
For smaller projects or when debugging:

```kotlin
metro {
  bindingsPerGraphShard = 200  // Create fewer, larger shards
  enableParallelShardGeneration = false  // Disable parallel generation
}
```

### CI/CD Configuration
For build servers with limited resources:

```kotlin
metro {
  bindingsPerGraphShard = 100  // Default size
  enableParallelShardGeneration = true
  shardGenerationParallelism = 2  // Limit to 2 threads
}
```

## Performance Considerations

- **Parallel Generation**: Currently limited by Kotlin compiler thread-safety constraints. The option prepares for future optimization when the compiler supports it.
- **Thread Count**: Reserved for future use when true parallel generation is possible
- **Shard Size**: Smaller shards (lower `bindingsPerGraphShard`) avoid JVM class size limits but may increase overhead

## Current Limitations

Due to Kotlin compiler internals not being thread-safe (specifically the `PerformanceManager` and symbol resolution), actual shard generation must be performed sequentially. However, the parallel infrastructure:
- Identifies independent shard groups for future optimization
- Provides better logging and diagnostics
- Prepares the codebase for true parallel generation when compiler support improves

## Troubleshooting

If you encounter "class too large" errors, reduce `bindingsPerGraphShard`:

```kotlin
metro {
  bindingsPerGraphShard = 50  // Or even lower if needed
}
```

If builds are slow on CI, limit parallelism:

```kotlin
metro {
  shardGenerationParallelism = 2  // Adjust based on CI resources
}
```