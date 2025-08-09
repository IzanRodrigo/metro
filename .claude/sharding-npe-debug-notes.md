# Component Sharding NPE Debug Notes

## Current Issue
NPE when creating `RenewSessionUseCase` factory in GraphShard7:
```
java.lang.NullPointerException: Parameter specified as non-null is null: method com.santander.one.sessionmanager.domain.usecases.RenewSessionUseCase$$MetroFactory$Companion.create, parameter renewSessionRepository
```

## Key Context
- `RenewSessionRepositoryImpl` is bound using Metro's `@ContributesBinding(AppScope::class)`
- The NPE occurs during shard initialization when calling the factory's create method
- Our error handling code doesn't prevent the null from being passed to the factory

## Possible Causes and Fixes

### 1. **Null arguments still being passed to factory create method**
- **Cause**: The error handling we added in `IrGraphGenerator.generateBindingArguments()` throws an error but doesn't prevent the null from being passed to the factory
- **Location**: `IrGraphGenerator.kt:1364-1389`
- **Fix**: Instead of just erroring out, need to prevent the factory invocation entirely or provide a stub/error value

### 2. **Error occurs before our validation code runs**
- **Cause**: The NPE happens during the factory invocation itself (in `irInvoke`), before our null-checking code in `generateBindingArguments` can catch it
- **Location**: Check `IrMetroFactory.invokeCreateExpression()` and `irInvoke()` calls
- **Fix**: Add null checks earlier in the chain, possibly in `invokeCreateExpression` or when building the args list

### 3. **Contributed bindings not properly registered in shards**
- **Cause**: `@ContributesBinding` bindings aren't being included in the shard's binding resolution context
- **Location**: Check how contributed bindings are processed and made available to shards
- **Fix**: Ensure contributed bindings are accessible from shards by properly propagating them through `bindingLocations` or making them available in the parent graph reference

### 4. **Binding resolution returns null silently**
- **Cause**: Some code path in binding resolution returns null without triggering our error handling
- **Location**: `IrGraphGenerator.generateBindingArguments()` lines 1225-1389
- **Fix**: Audit all paths that can return null and ensure they either error or handle it properly

### 5. **Factory expects non-null but IR generation passes nullable**
- **Cause**: The generated factory's `create` method has non-nullable parameters but the IR invocation allows nulls
- **Location**: `InjectConstructorTransformer` factory generation
- **Fix**: Either make factory parameters nullable when bindings might be absent, or ensure all dependencies are resolved before factory generation

### 6. **Shard initialization order issue**
- **Cause**: GraphShard7 tries to create bindings before all dependencies are initialized
- **Location**: `IrGraphShard.kt` initialization logic
- **Fix**: Reorder shard initialization or ensure dependencies are initialized in parent graph before shard access

### 7. **Missing binding location tracking for contributed bindings**
- **Cause**: `@ContributesBinding` bindings aren't properly tracked in `bindingLocations` map
- **Location**: Where `bindingLocations` is populated
- **Fix**: Ensure contributed bindings are added to `bindingLocations` during graph construction

## Most Likely Solution
The most likely fix would be to prevent the factory invocation when any required arguments are null, rather than just logging an error after the fact. This means:

1. Check if `generateBindingArguments()` returns any nulls
2. If it does, throw an error BEFORE calling `irInvoke()` with the factory create method
3. The error should clearly indicate which binding couldn't be resolved

## Code Locations to Check Next Session
1. `IrGraphGenerator.generateBindingCode()` - where factory invocation happens (lines 1476-1495)
2. `IrMetroFactory.invokeCreateExpression()` - where args are passed to create method
3. `IrGraphGenerator.generateBindingArguments()` - where nulls are returned (lines 1154-1389)
4. How `@ContributesBinding` classes are discovered and added to the binding graph

## Test Case to Add
Create a test that reproduces this exact scenario:
- A class with `@Inject` constructor that depends on an interface
- The interface implementation uses `@ContributesBinding`
- The binding is used in a component with sharding enabled
- Verify the error message is clear and helpful, not an NPE