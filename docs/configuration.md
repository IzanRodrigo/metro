# Configuration

Metro's Gradle plugin provides extensive configuration options to customize the compiler's behavior. All options are configured through the `metro` extension block in your `build.gradle.kts` file.

## Basic Configuration

```kotlin
plugins {
  kotlin("jvm") // or multiplatform, android, etc.
  id("dev.zacsweers.metro")
}

metro {
  // Configuration options go here
}
```

## Common Options

### Graph Sharding

**New in 0.7.0**

For very large dependency graphs that might hit JVM class size limits, Metro can automatically split the generated code into multiple helper classes.

```kotlin
metro {
  // Enable sharding with max 1000 fields per class
  maxFieldsPerShard = 1000
  
  // Default: Int.MAX_VALUE (sharding disabled)
}
```

When enabled, Metro will:
- Automatically detect when a graph exceeds the field limit
- Generate nested helper classes to store provider fields
- Transparently delegate field access through the main graph class
- Maintain all DI semantics (scoping, circular dependencies, etc.)

This feature is completely transparent to your code - no changes required!

### Debug Logging

Enable verbose debug logging to see generated Kotlin pseudocode:

```kotlin
metro {
  debug.set(true)
}
```

### Reports Destination

Generate diagnostic reports about resolved dependency graphs:

```kotlin
metro {
  reportsDestination.set(layout.buildDirectory.dir("metro/reports"))
}
```

### Performance Options

```kotlin
metro {
  // Shrink unused bindings (default: true)
  shrinkUnusedBindings.set(true)
  
  // Chunk field initializers for large graphs (default: true)
  chunkFieldInits.set(true)
  
  // Transform providers to private visibility (default: true)
  transformProvidersToPrivate.set(true)
}
```

### Validation Options

```kotlin
metro {
  // Enable full binding graph validation (default: false)
  // Validates ALL bindings even if unused
  enableFullBindingGraphValidation.set(true)
  
  // Warn about @Inject placement on constructors (default: true)
  warnOnInjectAnnotationPlacement.set(true)
  
  // Control severity for public provider callables
  publicProviderSeverity.set(DiagnosticSeverity.WARNING)
}
```

### Advanced Features

```kotlin
metro {
  // Generate assisted factories automatically (default: false)
  generateAssistedFactories.set(true)
  
  // Enable top-level function injection (default: false)
  // WARNING: Not compatible with incremental compilation!
  enableTopLevelFunctionInjection.set(false)
  
  // Control contribution hint generation (platform-dependent default)
  generateContributionHints.set(true)
  
  // Generate JVM contribution hints in FIR (default: false)
  generateJvmContributionHintsInFir.set(false)
}
```

## Interop Configuration

Metro can interoperate with other DI frameworks through custom annotations and runtime types:

### Dagger Interop

```kotlin
metro {
  interop {
    // Enable Dagger runtime interop
    enableDaggerRuntimeInterop.set(true)
    
    // Include Dagger annotations (includes javax/jakarta by default)
    includeDagger(
      includeJavax = true,
      includeJakarta = true
    )
  }
}
```

### Kotlin-Inject Interop

```kotlin
metro {
  interop {
    includeKotlinInject()
  }
}
```

### Anvil Interop

```kotlin
metro {
  interop {
    includeAnvil(
      includeDaggerAnvil = true,
      includeKotlinInjectAnvil = true
    )
  }
}
```

### Custom Annotations

You can register custom annotations for Metro to recognize:

```kotlin
metro {
  interop {
    // Custom provider types
    provider.add("com/example/CustomProvider")
    
    // Custom lazy types
    lazy.add("com/example/CustomLazy")
    
    // Custom annotations
    inject.add("com/example/CustomInject")
    scope.add("com/example/CustomScope")
    qualifier.add("com/example/CustomQualifier")
    provides.add("com/example/CustomProvides")
    binds.add("com/example/CustomBinds")
    bindingContainer.add("com/example/CustomModule")
    
    // And many more...
  }
}
```

## Complete Example

Here's a comprehensive configuration example:

```kotlin
metro {
  // Performance optimizations
  maxFieldsPerShard = 10000 // Enable sharding for large graphs
  shrinkUnusedBindings.set(true)
  chunkFieldInits.set(true)
  
  // Debugging
  debug.set(false)
  reportsDestination.set(layout.buildDirectory.dir("metro/reports"))
  
  // Validation
  enableFullBindingGraphValidation.set(false)
  publicProviderSeverity.set(DiagnosticSeverity.NONE)
  
  // Features
  generateAssistedFactories.set(true)
  
  // Interop with Dagger
  interop {
    includeDagger()
  }
}
```

## Gradle Properties

Some options can also be set globally via Gradle properties:

```properties
# gradle.properties
metro.debug=true
metro.reportsDestination=reports/metro
metro.version.check=true
```

## Platform-Specific Defaults

Some options have platform-specific defaults:
- `generateContributionHints`: Defaults vary by target platform (JVM, JS, Native)
- The plugin automatically configures appropriate settings based on your Kotlin target

## Migration Guide

If you're migrating from another DI framework, see the [Adoption Guide](adoption.md) for specific configuration recommendations.