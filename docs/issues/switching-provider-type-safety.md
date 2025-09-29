# SwitchingProvider Type Safety Issue

## Current Implementation
Metro's `SwitchingProvider` currently uses `Provider<Any?>` which sacrifices compile-time type safety.

```kotlin
private class SwitchingProvider : Provider<Any?>
```

## Dagger's Implementation
Dagger generates multiple `SwitchingProvider` classes, each properly generic:

```java
private static final class SwitchingProvider<T> implements Provider<T> {
    @Override
    @SuppressWarnings("unchecked")
    public T get() {
        switch (id) {
            case 0:
                return (T) new SomeImpl(...);
            // ...
        }
    }
}
```

## Issues with Current Metro Implementation
1. **Type Safety**: Using `Provider<Any?>` means consumers must cast the result
2. **Runtime Errors**: Potential ClassCastException if wrong type is returned
3. **Poor IDE Support**: No compile-time type checking or autocomplete

## Proposed Solution

### Option 1: Single Generic SwitchingProvider (Complex)
Implement a properly generic `SwitchingProvider<T>` with type parameter support in IR generation.

Challenges:
- IR type parameter handling is complex
- Need to properly map type parameters through the generation pipeline
- Requires creating typed instances with proper type arguments

### Option 2: Multiple SwitchingProviders per Shard (Dagger-like)
Generate separate `SwitchingProvider` classes for different groups of types.

Benefits:
- Better type safety
- Follows Dagger's proven pattern
- Clearer generated code

### Option 3: Keep Provider<Any?> with Documentation
Add `@Suppress("UNCHECKED_CAST")` annotations and improve documentation.

Benefits:
- Simpler implementation
- Works with current IR generation
- Minimal changes required

## Implementation Notes

The main challenge is in `IrSwitchingProviderGenerator.kt`:
1. Creating type parameters requires proper IR type handling
2. The `defaultType` property doesn't work for `IrTypeParameter`
3. Need to properly instantiate generic types when creating provider instances

## Recommended Approach

Short term: Add proper `@Suppress` annotations and documentation
Long term: Implement Option 2 (multiple typed SwitchingProviders) for better type safety

## Related Code
- `/compiler/src/main/kotlin/dev/zacsweers/metro/compiler/ir/IrSwitchingProviderGenerator.kt`
- `/compiler/src/main/kotlin/dev/zacsweers/metro/compiler/ir/IrGraphGenerator.kt` (lines 1840-1847)