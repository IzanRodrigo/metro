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

- **Parallel Generation**: Enabled by default, can significantly reduce build times for large graphs
- **Thread Count**: Default (0) uses all available processors. Consider limiting on CI servers
- **Shard Size**: Smaller shards (lower `bindingsPerGraphShard`) avoid JVM class size limits but may increase overhead

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