# Multibinding Chunking (Experimental)

Large map or set multibindings can generate very large shard field initialization functions and risk exceeding JVM or Kotlin/IR method size limits (or at least slow down compilation). To mitigate this, Metro can (optionally) split very large multibindings into a series of helper functions and then merge their results with a builder API.

## Status
Experimental and disabled by default. Enable with the compiler option:

```
-Pmetro.chunk-large-multibindings=true
```

## When It Applies
A multibinding qualifies as "large" when its number of individual entry source bindings exceeds a fixed threshold (currently 50). Only pure individual entry set multibindings (no existing collection / `@ElementsIntoSet` providers) are chunked; mixed mode sets are left unchanged for now.

## How It Works
For a large map or set multibinding:

1. Its entry bindings are partitioned into chunks of at most the threshold size.
2. For each chunk a synthetic `Multibinding` binding is fabricated and emitted through the normal binding generation path inside a private helper function on the shard.
   * Map helpers return a `Provider<Map<K, V or Provider<V>>>` depending on whether the value type itself is wrapped in `Provider`.
   * Set helpers return a `Provider<Set<T>>` and are treated as collection providers when merged.
3. The original field initializer is replaced with builder logic:
   * Maps: `MapFactory.Builder` / `MapProviderFactory.Builder` is created with the full (original) size hint; each helper result is merged via `putAll`.
   * Sets: `SetFactory.Builder` is created with zero individual entries and N collection providers (one per helper); each helper is added with `addCollectionProvider`.
4. The builder is built and wrapped (if necessary) into the Metro provider form expected by the shard field.

## Rationale
Splitting very large initializer bodies reduces:
- IR builder statement count per function
- Risk of hitting method-size limits
- Complexity of future per-field optimization passes

## Limitations / Future Work
- Threshold is hard‑coded (50) – could be made configurable later.
- Set chunking currently skips cases where existing collection providers are present (mixed modes) for simplicity.
- No dedicated regression tests yet (to be added before enabling by default).
- Ordering guarantees follow the existing multibinding deterministic ordering since each helper preserves its internal order and merge order is deterministic.

## Enabling / Disabling
Disabled by default. Enable via Gradle:

```
# In module build.gradle(.kts) compiler args or on the command line
-Pmetro.chunk-large-multibindings=true
```

## Tracing
When enabled, detection of large multibindings is traced (logger key `largeMultibindings`) to assist with diagnostics.

## Safety
All helper functions are private to the shard, reducing API surface. Synthetic binding IDs are suffixed with `_chunkN` / `_setChunkN` to avoid collisions.

---
Feedback welcome. This mechanism will likely evolve before being turned on by default.
