# Component Sharding Configuration in Gradle

The Metro Gradle plugin provides configuration options to control component sharding behavior:

## Basic Configuration

```kotlin
// build.gradle.kts
metro {
  // Maximum number of bindings per shard (default: 100)
  bindingsPerGraphShard = 50
}
```

## Examples

### Aggressive Sharding
For very large graphs, you might want smaller shards:

```kotlin
metro {
  bindingsPerGraphShard = 25  // Create more, smaller shards
}
```

### Conservative Sharding
For smaller projects or when debugging:

```kotlin
metro {
  bindingsPerGraphShard = 200  // Create fewer, larger shards
}
```

## Performance Considerations

- **Shard Size**: Smaller shards (lower `bindingsPerGraphShard`) avoid JVM class size limits but may increase overhead
- **Dependency Analysis**: Metro analyzes dependencies between bindings to group strongly connected components together
- **Initialization Order**: Bindings within shards are initialized in topological order to ensure dependencies are available

## Troubleshooting

If you encounter "class too large" errors, reduce `bindingsPerGraphShard`:

```kotlin
metro {
  bindingsPerGraphShard = 50  // Or even lower if needed
}
```

The compiler will log when sharding is applied:
```
Graph com.example.MyGraph has 150 bindings, which exceeds the sharding threshold of 100. Implementing component sharding.
```