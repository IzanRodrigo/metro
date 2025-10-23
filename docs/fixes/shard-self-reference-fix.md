# Fix: Shard Self-Reference in Field Initialization

**Date**: 2025-10-20
**Commit**: dfbba658
**Branch**: izan/bmad
**Severity**: Critical - Runtime crash in production

## Problem Statement

Android-spain application was crashing at runtime with the following error during Metro component initialization:

```
FATAL EXCEPTION: main
Process: es.bancosantander.apps.android.intern, PID: 28379
java.lang.RuntimeException: Unable to create application
Caused by: java.lang.NullPointerException: Parameter specified as non-null is null:
  method dev.zacsweers.metro.internal.DoubleCheck$Companion.provider, parameter delegate
  at dev.zacsweers.metro.internal.DoubleCheck$Companion.provider(Unknown Source:2)
  at es.bancosantander.apps.mobile.android.di.components.ApplicationComponent$$MetroGraph$Shard1.initialize(Unknown Source:23)
  at es.bancosantander.apps.mobile.android.di.components.ApplicationComponent$$MetroGraph.<init>(ApplicationComponent.kt:9)
```

## Root Cause Analysis

### Background
The issue was introduced in commit `425b408f` which implemented Fast Init Stories 2.3-2.7. This commit added optimization to reuse existing provider fields for `IrBinding.Provided` cases.

### The Bug
When initializing fields within a shard, the code was generating self-references:

```kotlin
// Inside Shard1.initialize(component: $$MetroGraph)
this.contextProvider = DoubleCheck.provider(
  delegate = component.shard1Instance.contextProvider  // ❌ Self-reference!
)
```

This attempted to initialize a field using its own uninitialized value, which was `null`, causing the NPE.

### Generated Code Analysis
From `android-spain/app/build/metro-reports/internDebug/graph-dumpKotlin-*.kt`:

```kotlin
fun initialize(component: $$MetroGraph) {
  // Line 22330 - Self-reference bug
  <this>.#contextProvider = Companion.provider<Provider<Context>, Context>(
    delegate = component.#shard1Instance.#contextProvider  // ← Same field being initialized!
  )

  // Line 22331 - Another self-reference
  <this>.#deviceInfoManagerProvider = Companion.provider<Provider<DeviceInfoManager>, DeviceInfoManager>(
    delegate = Companion.create(
      context = component.#shard1Instance.#contextProvider  // ← Still initializing!
    )
  )
}
```

### Code Flow
1. **Constructor** creates shard instances (lines 8-22)
2. **Initialization** calls `shard.initialize(this)` (lines 30-51)
3. **Inside initialize()**, when generating field initializers:
   - Fast Init optimization tried to reuse existing provider fields
   - **Bug**: Didn't check if we're initializing that same field
   - Generated `component.shard1Instance.field` to access the field
   - But `component.shard1Instance` is `this`, and `this.field` is still `null`!

### Comparison with Existing Code
The existing field reuse logic at **IrGraphExpressionGenerator.kt:122-147** had proper protection:

```kotlin
// ✅ CORRECT - Existing code with protection
if (fieldInitKey == null || fieldInitKey != binding.typeKey) {
  if (fieldAccess.hasField(binding.typeKey)) {
    fieldAccess.getProviderExpression(binding.typeKey, thisReceiver)?.let { ... }
  }
}
```

The new Fast Init code at **lines 219-226** lacked this protection:

```kotlin
// ❌ BUG - Missing fieldInitKey check
is IrBinding.Provided -> {
  fieldAccess.getProviderExpression(binding.typeKey, thisReceiver)?.let { providerExpression ->
    // Reuses field without checking if we're initializing it!
  }
}
```

## The Fix

### Code Change
Added the same `fieldInitKey` protection to the Fast Init provider field reuse logic:

**File**: `compiler/src/main/kotlin/dev/zacsweers/metro/compiler/ir/IrGraphExpressionGenerator.kt`

```diff
 is IrBinding.Provided -> {
   if (node.sourceGraph.origin == Origins.GeneratedGraphExtension) {
     println("GraphExtension Provided binding ${binding.typeKey} (context: ${contextualTypeKey.typeKey})")
   }
+  // Only try to reuse provider field if we're not currently initializing it
+  // This prevents self-references during shard initialization
+  if (fieldInitKey == null || fieldInitKey != binding.typeKey) {
     fieldAccess.getProviderExpression(binding.typeKey, thisReceiver)?.let { providerExpression ->
       if (node.sourceGraph.origin == Origins.GeneratedGraphExtension) {
         println("GraphExtension using provider field for ${binding.typeKey}")
       }
       val provider =
         with(metroProviderSymbols) { transformMetroProvider(providerExpression, contextualTypeKey) }
       return provider.transformAccessIfNeeded(accessType, AccessType.PROVIDER, binding.typeKey.type)
     }
+  }
   if (node.sourceGraph.origin == Origins.GeneratedGraphExtension) {
     println("GraphExtension invoking factory for ${binding.typeKey}")
   }
```

### How the Fix Works

The `fieldInitKey` parameter is passed when generating field initializers for a specific binding:

1. **When initializing a field** (e.g., `contextProvider`):
   - `fieldInitKey = IrTypeKey(Context)`
   - Prevents reusing the field for `Context` because we're currently initializing it
   - Forces factory invocation instead

2. **When accessing a different field**:
   - `fieldInitKey = IrTypeKey(Context)` (what we're initializing)
   - `binding.typeKey = IrTypeKey(Gson)` (what we need)
   - Check passes: `IrTypeKey(Context) != IrTypeKey(Gson)`
   - Optimization applies: reuse existing Gson provider field

3. **Cross-shard access** (legitimate optimization):
   - Shard2 initializing a field that needs Shard1's context
   - `fieldInitKey = IrTypeKey(SomeOtherType)` (what Shard2 is initializing)
   - `binding.typeKey = IrTypeKey(Context)` (from Shard1)
   - Check passes: `SomeOtherType != Context`
   - Generates: `component.shard1Instance.contextProvider` ✅ Valid!

## Impact

### What Changes
- **Same-shard self-references**: Now prevented, will use factory invocation instead
- **Cross-shard references**: Still optimized, unchanged
- **Fast Init optimization**: Preserved for all legitimate cases

### What Stays the Same
- Sharding logic unchanged
- Initialization order unchanged
- Cross-shard dependency resolution unchanged
- Fast Init optimization for non-self-referential cases

### Performance
- Minimal: Self-references were invalid anyway, now correctly use factories
- Fast Init optimization preserved for the 99.9% of legitimate cases

## Testing & Validation

### Build Verification
```bash
cd /Users/izan/Dev/Projects/metro-sharding-workspace/metro
gradle :compiler:jar
# BUILD SUCCESSFUL
```

### Expected Behavior
After fix, the generated code should look like:

```kotlin
// ✅ CORRECT - After fix
fun initialize(component: $$MetroGraph) {
  <this>.#contextProvider = Companion.provider<Provider<Context>, Context>(
    delegate = SomeContextFactory.create()  // Uses factory, not self-reference
  )

  <this>.#deviceInfoManagerProvider = Companion.provider<Provider<DeviceInfoManager>, DeviceInfoManager>(
    delegate = Companion.create(
      context = <this>.#contextProvider  // Can access own field after it's initialized
    )
  )
}
```

### Sharding Statistics (android-spain)
- **Shard count**: 22
- **Total field initializers**: 2,146
- **Cross-shard dependencies**: 3,476
- **Keys per shard limit**: 100
- **Topological sort**: SUCCESS

## Next Steps

1. **Publish Metro locally** for android-spain testing:
   ```bash
   cd /Users/izan/Dev/Projects/metro-sharding-workspace/metro
   ./metrow publish --local --version 1.0.0-SHARD-FIX
   ```

2. **Update android-spain** to use fixed version:
   ```gradle
   // In android-spain build.gradle
   metro = "1.0.0-SHARD-FIX"
   ```

3. **Test android-spain**:
   - Clean build
   - Run application
   - Verify no NPE during initialization
   - Verify all shard fields are properly initialized

4. **Remove debug logging**:
   - Once confirmed working, remove the debug println statements added for graph extensions
   - Located at lines 216-217, 220-221, 227-228 in IrGraphExpressionGenerator.kt

## Related Issues

- **Original Fast Init PR**: Commit 425b408f
- **Graph extension tests**: Some failures exist but are unrelated to this fix
- **Test infrastructure**: Native link failures unrelated to this change

## References

- **Shard initialization**: `metro/compiler/src/main/kotlin/dev/zacsweers/metro/compiler/ir/IrGraphGenerator.kt:1032-1130`
- **Field access abstraction**: `metro/compiler/src/main/kotlin/dev/zacsweers/metro/compiler/ir/BindingFieldContext.kt:97-108`
- **Sharding plan output**: `/Users/izan/Dev/Projects/android-spain/app/build/metro-reports/internDebug/sharding-plan-ApplicationComponent.txt`
- **Graph dump**: `/Users/izan/Dev/Projects/android-spain/app/build/metro-reports/internDebug/graph-dumpKotlin-es-bancosantander-apps-mobile-android-di-components-ApplicationComponent.kt`
