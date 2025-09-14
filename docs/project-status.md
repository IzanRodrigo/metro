# Metro Project Status

## Last Updated: 2025-01-14

## Overall Status: **BETA**

Metro is functional and can be used in production with some limitations. The core dependency injection features are stable, but some advanced features are still experimental.

## Feature Status

### ✅ Stable Features

- **Core DI**: Constructor injection, providers, scopes
- **Compile-time validation**: Full dependency graph validation at compile time
- **FIR+IR code generation**: Direct code generation into Kotlin compiler IR
- **Kotlin-inject API compatibility**: Top-level functions, native Lazy support
- **Anvil-style aggregation**: @ContributesTo, @ContributesBinding annotations
- **Multiplatform support**: Most major Kotlin multiplatform targets
- **IDE integration**: Error reporting in K2 IDE
- **Dagger interop**: Component-level interop with Dagger

### ⚠️ Experimental Features

- **Graph Sharding**: Partially functional (basic cases work, complex scenarios have issues)
  - ✅ Basic sharding infrastructure
  - ✅ SwitchingProvider integration
  - ❌ Complex module scenarios
  - ❌ Some advanced binding types

### 🚧 In Progress

- Performance optimizations for large graphs
- Complete sharding support for all binding types
- Enhanced IDE integration features

## Recent Updates (2025-01-14)

### Implemented GPT5-Suggested Fixes

1. **@BindsInstance Parameter Handling**
   - Fixed instance field creation for @BindsInstance parameters
   - Ensured proper access to both instance and provider fields
   - Files: `IrGraphGenerator.kt`, `IrGraphExpressionGenerator.kt`

2. **Factory Invocation Fix**
   - Fixed MetroFactory.create() to properly invoke factories
   - Ensures instances are created, not just factory references
   - File: `IrMetroFactory.kt`

3. **SwitchingProvider Field Access**
   - Added special handling for field access from SwitchingProvider context
   - Routes field access through graph for shard dependencies
   - File: `IrGraphExpressionGenerator.kt`

4. **SwitchingProvider Recursion Prevention**
   - Ensured SwitchingProvider uses bypassProviderFor
   - Prevents infinite recursion in provider chains
   - File: `SwitchingProviderGenerator.kt`

### Test Results

| Test Suite | Status | Notes |
|------------|--------|-------|
| Core DI Tests | ✅ Passing | All basic DI functionality working |
| Sharding Minimal | ✅ Passing | Basic sharding case works |
| Debug Sharding | ✅ Passing | Simple sharding scenarios work |
| Complex Sharding | ❌ Failing | Module annotation handling issues |
| Advanced Sharding | ❌ Failing | Complex binding scenarios fail |

## Known Issues

1. **Module Annotation Handling in Sharding**
   - Error: "Illegal annotation class 'Module'"
   - Affects: Complex sharding tests with modules
   - Workaround: Disable sharding for graphs with complex module structures

2. **Performance with Very Large Graphs**
   - Compilation time increases significantly with 1000+ bindings
   - Workaround: Enable sharding (for supported scenarios)

3. **Native Multiplatform Contributions**
   - Cannot contribute dependencies from native targets
   - Blocked by: https://youtrack.jetbrains.com/issue/KT-75865
   - Workaround: Define native dependencies directly in graph

## Recommended Usage

### Production Ready ✅
- Standard dependency injection patterns
- Multiplatform projects (with noted limitations)
- Dagger migration/interop scenarios
- Projects with up to ~500 bindings without sharding

### Use with Caution ⚠️
- Graph sharding for very large projects (test thoroughly)
- Complex module hierarchies with sharding
- Native multiplatform with contributions

### Not Recommended ❌
- Projects requiring 100% Dagger compatibility
- Scenarios requiring runtime dependency resolution

## Migration Guide

For projects migrating from Dagger:
1. Enable Dagger interop in Metro configuration
2. Start with a small component/module
3. Gradually migrate components using @Includes
4. Test sharding on non-critical components first

## Support and Resources

- [Documentation](index.md)
- [Features Overview](features.md)
- [Sharding Guide](sharding.md)
- [Debugging Guide](debugging.md)
- [FAQ](faq.md)

## Roadmap

### Short Term (Q1 2025)
- [ ] Fix module annotation handling in sharding
- [ ] Improve sharding test coverage
- [ ] Performance optimizations

### Medium Term (Q2 2025)
- [ ] Complete sharding support for all scenarios
- [ ] Enhanced IDE features
- [ ] Gradle plugin improvements

### Long Term
- [ ] Full Dagger API compatibility mode
- [ ] Runtime dependency resolution support (if needed)
- [ ] Advanced debugging tools