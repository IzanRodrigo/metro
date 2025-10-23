# Fix: Parameter Index Offset Bug in BindingContainerTransformer

**Date:** 2025-10-20
**Affected Version:** 0.7.0-IZAN (commit 425b408f)
**Fixed In:** This commit
**Severity:** Critical - Caused NoSuchElementException crashes during compilation

## Problem

After the Fast Init implementation (commit 425b408f), Metro compiler crashed with `NoSuchElementException` errors when compiling provider factories with dependencies:

```
java.util.NoSuchElementException: Key gson: Gson is missing in the map.
  at dev.zacsweers.metro.compiler.ir.IrKt.parameterAsProviderArgument(ir.kt:551)
  at dev.zacsweers.metro.compiler.ir.transformers.BindingContainerTransformer.getOrLookupProviderFactory(BindingContainerTransformer.kt:361)
```

Multiple parameters were affected:
- `gson: Gson`
- `context: Context`
- `daoDescription: DAODescription`

## Root Cause

The Fast Init refactoring changed how the graph instance parameter is handled in provider factory generation:

**Before (working):**
```kotlin
sourceParameters = Parameters(
  instance = instanceParam,        // Graph instance as dispatch receiver
  regularParameters = [param1, param2, param3]  // Regular params only
)
```

**After (broken):**
```kotlin
sourceParameters = Parameters(
  instance = null,
  regularParameters = [graphParameter, param1, param2, param3]  // Graph param PREPENDED
)
```

However, the parameter mapping logic in `BindingContainerTransformer.kt` (lines 330-342) wasn't updated to account for this structural change. When mapping constructor parameters to source parameters, the code looked up parameters by index **without accounting for the graphParameter offset**:

```kotlin
val regularParams = [param1, param2, param3]  // From original function signature
val paramIndex = 0  // Looking for param1
sourceParameters.regularParameters.getOrNull(0)  // Returns graphParameter (WRONG!)
```

This caused all parameter lookups to be off by 1 when `graphParameter` was present, resulting in:
- Index 0 → Got `graphParameter` instead of `param1`
- Index 1 → Got `param1` instead of `param2`
- Index 2 → Got `param2` instead of `param3`
- Index 3 → Out of bounds, returned null, crashed with NoSuchElementException

## Solution

Added index offset calculation to account for the prepended `graphParameter`:

```kotlin
// File: compiler/src/main/kotlin/dev/zacsweers/metro/compiler/ir/transformers/BindingContainerTransformer.kt
// Lines: 336-339

// Account for graphParameter offset if it exists (it's inserted at the beginning)
val indexOffset = if (graphParameter != null) 1 else 0
val paramIndex = regularParams.indexOfFirst { it.name == irParam.name }
sourceParameters.regularParameters.getOrNull(paramIndex + indexOffset)
```

This ensures correct parameter mapping:
- Index 0 + offset(1) = 1 → Gets `param1` ✓
- Index 1 + offset(1) = 2 → Gets `param2` ✓
- Index 2 + offset(1) = 3 → Gets `param3` ✓

## Testing

**Verified Fix With:**
- Published Metro 0.7.0-IZAN to mavenLocal
- Built android-spain project (large real-world codebase)
- All Metro compiler errors eliminated
- No `.kotlin/errors/` logs generated

**Before Fix:**
- 4 NoSuchElementException crashes
- Build failed immediately during Metro IR generation

**After Fix:**
- 0 Metro compiler errors
- Build progresses successfully through Metro compilation
- All provider factories generate correctly

## Related Changes

Also reverted experimental Fast Init accessor logic in `IrGraphGenerator.kt` that was causing issues with graph extensions. The core Fast Init implementation remains intact in other files.

## Files Changed

1. `compiler/src/main/kotlin/dev/zacsweers/metro/compiler/ir/transformers/BindingContainerTransformer.kt`
   - Added index offset calculation for parameter mapping

2. `compiler/src/main/kotlin/dev/zacsweers/metro/compiler/ir/IrGraphGenerator.kt`
   - Removed experimental Fast Init accessor logic
   - Reverted to standard accessor generation

## Lessons Learned

When refactoring parameter handling:
1. **Update all lookup logic** - Parameter structure changes must be reflected everywhere parameters are accessed
2. **Consider offsets carefully** - Prepending elements to lists requires offset adjustments in index-based lookups
3. **Name-based lookups aren't enough** - Even with name matching, index calculations must account for structural changes
4. **Test with real codebases** - Complex real-world projects expose edge cases that small tests might miss
