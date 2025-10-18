// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.NameAllocator
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.asName
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addField
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irDelegatingConstructorCall
import org.jetbrains.kotlin.ir.builders.irEquals
import org.jetbrains.kotlin.ir.builders.irExprBody
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irSetField
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrBranchImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrElseBranchImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrInstanceInitializerCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrThrowImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrWhenImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrTypeParameterSymbolImpl
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.defaultType as utilDefaultType
import org.jetbrains.kotlin.ir.util.isObject
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.types.Variance

/**
 * Information about a binding that can be used for SwitchingProvider generation.
 *
 * This is an internal simplified view of a binding to avoid exposing IrGraphGenerator's
 * private FieldBinding class.
 *
 * @property typeKey The type key for this binding
 * @property field The provider field for this binding
 * @property binding The actual IrBinding with dependency and creation information
 */
internal data class SwitchableBinding(
  val typeKey: IrTypeKey,
  val field: IrField,
  val binding: IrBinding,
)

/**
 * Generates SwitchingProvider classes for fastInit mode.
 *
 * SwitchingProvider is a lightweight provider wrapper that defers factory creation until first access.
 * Instead of creating thousands of factory instances during component initialization, we create
 * lightweight SwitchingProvider instances that use a switch statement to route to the appropriate
 * factory creation code on demand.
 *
 * This significantly speeds up component initialization (30-50% faster) at the cost of a small
 * overhead (~1-2ns) on first access to each binding.
 *
 * Generated structure:
 * ```
 * private class SwitchingProvider<T>(
 *   private val component: Component$$$MetroGraph,
 *   private val id: Int
 * ) : Provider<T> {
 *   override fun get(): T {
 *     @Suppress("UNCHECKED_CAST")
 *     return when (id) {
 *       0 -> Service1_Factory.create(component.dep1, component.dep2).get()
 *       1 -> Service2_Factory.create(...).get()
 *       // ... more cases
 *       else -> throw AssertionError(id)
 *     } as T
 *   }
 * }
 * ```
 *
 * For >100 bindings, the when statement is partitioned into multiple private getN() methods
 * to keep method sizes manageable.
 *
 * @param metroContext The IR Metro context providing access to compiler plugin infrastructure
 * @param parentClass The shard or component class that will contain this SwitchingProvider
 * @param bindings List of bindings that should use this SwitchingProvider
 * @param componentClass The root component class (for accessing fields across shards)
 * @param bindingFieldContext Context for finding where provider fields are located
 * @param fieldNameAllocator Allocator for generating unique field/class names
 */
internal class IrSwitchingProviderGenerator(
  metroContext: IrMetroContext,
  private val parentClass: IrClass,
  private val bindings: List<SwitchableBinding>,
  private val componentClass: IrClass,
  private val bindingFieldContext: BindingFieldContext,
  private val bindingGraph: IrBindingGraph,
  private val fieldNameAllocator: NameAllocator,
  private val expressionGenerator: IrGraphExpressionGenerator,
) : IrMetroContext by metroContext {

  companion object {
    /** Maximum number of cases in a single when statement to avoid method size limits */
    private const val MAX_CASES_PER_SWITCH = 100

    /** Maximum total bindings per SwitchingProvider class */
    private const val MAX_CASES_PER_CLASS = 10_000
  }

  /**
   * Information about the generated SwitchingProvider class.
   *
   * @property switchingProviderClass The generated IR class
   * @property switchIds Map from binding type key to switch case ID
   */
  data class SwitchingProviderInfo(
    val switchingProviderClass: IrClass,
    val switchIds: Map<IrTypeKey, Int>,
  )

  /**
   * Generates the SwitchingProvider class for this shard.
   *
   * @return Information about the generated class including switch ID mappings
   */
  fun generate(): SwitchingProviderInfo {
    // Assign switch IDs to bindings that should use SwitchingProvider
    val switchIds = assignSwitchIds()

    // Generate the SwitchingProvider class
    val switchingProviderClass = createSwitchingProviderClass(switchIds)

    return SwitchingProviderInfo(switchingProviderClass, switchIds)
  }

  /**
   * Assigns sequential switch IDs to bindings that should use SwitchingProvider.
   *
   * Only certain binding types benefit from SwitchingProvider:
   * - Constructor injected bindings
   * - Provider method bindings
   * - Assisted factories
   *
   * Bindings that already have instances (bound instances, graph dependencies, aliases)
   * don't benefit and are excluded.
   */
  private fun assignSwitchIds(): Map<IrTypeKey, Int> {
    return bindings
      .filter { shouldUseSwitchingProvider(it) }
      .mapIndexed { index, binding -> binding.typeKey to index }
      .toMap()
  }

  /**
   * Determines if a binding should use SwitchingProvider based on its type.
   *
   * SwitchingProvider provides value when factory creation can be deferred.
   * It doesn't help for bindings that already have instances or just delegate.
   *
   * Follows Dagger's approach:
   * - Use for: constructor injection, provider methods, assisted factories, subcomponents
   * - Skip for: bound instances (already have), graph dependencies (from parent), aliases (delegate)
   * - Conditional for: multibindings (only if has dependencies to construct)
   */
  private fun shouldUseSwitchingProvider(binding: SwitchableBinding): Boolean {
    val irBinding = binding.binding

    // MVP: Only use SwitchingProvider for bindings with ZERO dependencies
    // This avoids complex dependency resolution while still providing benefit for simple cases
    // TODO: Expand to handle dependencies once expression generation is properly integrated
    val hasNoDependencies = irBinding.dependencies.isEmpty()
    if (!hasNoDependencies) {
      return false
    }

    return when (irBinding) {
      // Factory creation can be deferred - use SwitchingProvider if no dependencies
      is IrBinding.ConstructorInjected,
      is IrBinding.ObjectClass,
      is IrBinding.Provided -> true

      // Already have instance or just delegate - don't use SwitchingProvider
      is IrBinding.Alias,
      is IrBinding.BoundInstance,
      is IrBinding.GraphDependency,
      is IrBinding.MembersInjected -> false

      // Conservative: unknown types don't use SwitchingProvider
      else -> false
    }
  }

  /**
   * Generates an expression to get a dependency instance.
   *
   * Accesses dependencies through the component field, following alias chains as needed.
   * Handles provider-based access, object instances, and other binding types.
   *
   * @param depKey The contextual type key of the dependency
   * @param componentExpr Expression that evaluates to the component instance
   * @return Expression that gets the dependency instance
   */
  private fun IrBuilderWithScope.generateDependencyInstance(
    depKey: IrContextualTypeKey,
    componentExpr: IrExpression
  ): IrExpression {
    // Resolve alias chains to find the actual binding with a provider field
    var currentKey = depKey.typeKey
    var binding = bindingGraph.findBinding(currentKey)

    // Follow alias chain (@Binds methods)
    while (binding is IrBinding.Alias) {
      currentKey = binding.aliasedType
      binding = bindingGraph.findBinding(currentKey)
    }

    // Check if the resolved binding has a provider field
    val providerEntry = bindingFieldContext.providerFieldEntry(currentKey)

    return if (providerEntry != null) {
      // Access through provider field: component.shardN.providerField.get()
      val providerExpr = when (val owner = providerEntry.owner) {
        BindingFieldContext.Owner.Root -> {
          // Field on root: component.providerField
          irGetField(componentExpr, providerEntry.field)
        }
        is BindingFieldContext.Owner.Shard -> {
          // Field in shard: component.shardN.providerField
          val shardExpr = irGetField(componentExpr, owner.instanceField)
          irGetField(shardExpr, providerEntry.field)
        }
      }

      // Call provider.get() to get instance
      irInvoke(
        dispatchReceiver = providerExpr,
        callee = metroSymbols.providerInvoke,
        typeHint = depKey.typeKey.type
      )
    } else {
      // No provider field - handle special cases
      when (binding) {
        is IrBinding.ObjectClass -> {
          // Direct object access
          irGetObject(binding.type.symbol)
        }
        is IrBinding.MembersInjected -> {
          // Members injectors are generated on-demand, look for instance field instead
          val instanceEntry = bindingFieldContext.instanceFieldEntry(currentKey)
          if (instanceEntry != null) {
            when (val owner = instanceEntry.owner) {
              BindingFieldContext.Owner.Root -> {
                irGetField(componentExpr, instanceEntry.field)
              }
              is BindingFieldContext.Owner.Shard -> {
                val shardExpr = irGetField(componentExpr, owner.instanceField)
                irGetField(shardExpr, instanceEntry.field)
              }
            }
          } else {
            error("Cannot find instance field for MembersInjector: ${depKey.typeKey}")
          }
        }
        else -> {
          error("Cannot generate dependency expression for ${depKey.typeKey} (resolved to $currentKey) - no provider field and unsupported binding type: ${binding?.javaClass?.simpleName}")
        }
      }
    }
  }

  /**
   * Generates an expression to create an instance of a binding.
   *
   * This is the core logic for each case in the when statement - it generates
   * the factory creation code specific to each binding type.
   *
   * For ConstructorInjected: `Constructor(dep1, dep2, ...)`
   * For Provided: `Module.provideMethod(dep1, dep2, ...)`
   * For ObjectClass: `ObjectInstance`
   *
   * @param binding The binding to create an instance for
   * @param componentField The component field for accessing dependencies
   * @param thisReceiver The SwitchingProvider's this receiver
   * @return Expression that creates the binding instance
   */
  private fun IrBuilderWithScope.generateBindingCreation(
    binding: SwitchableBinding,
    componentField: IrField,
    thisReceiver: IrValueParameter
  ): IrExpression {

    // For MVP, we only handle zero-dependency bindings
    // Assert that we actually have zero dependencies
    require(binding.binding.dependencies.isEmpty()) {
      "SwitchingProvider binding should have zero dependencies but ${binding.typeKey} has ${binding.binding.dependencies.size}"
    }

    return when (val irBinding = binding.binding) {
      is IrBinding.ConstructorInjected -> {
        // Call factory create().get() - factory should have zero parameters
        irBinding.classFactory.invokeCreateExpression(binding.typeKey) { createFunction, parameters ->
          // Generate expressions for any parameters (even if dependencies is empty, parameters might have assisted/etc)
          // For now, just pass nulls for each parameter - this is a hack but let's see if it works
          parameters.regularParameters.map { null }
        }
      }

      is IrBinding.Provided -> {
        // Call provider method with no arguments
        val factory = irBinding.providerFactory
        irInvoke(
          dispatchReceiver = if (factory.function.parentAsClass.isObject) {
            irGetObject(factory.function.parentAsClass.symbol)
          } else {
            null  // Static method
          },
          callee = factory.function.symbol,
          typeArgs = emptyList(),
          args = emptyList()
        )
      }

      is IrBinding.ObjectClass -> {
        // Simple object reference
        irGetObject(irBinding.type.symbol)
      }

      else -> {
        error("Unsupported binding type for SwitchingProvider: ${irBinding.javaClass.simpleName}")
      }
    }
  }

  /**
   * Generates a simple when statement for <= 100 bindings.
   *
   * Creates an IrWhen expression with one branch per switchable binding, plus an else branch
   * that throws AssertionError for invalid IDs.
   *
   * Pattern:
   * ```
   * when (id) {
   *   0 -> BindingA creation
   *   1 -> BindingB creation
   *   ...
   *   else -> throw AssertionError(id)
   * }
   * ```
   *
   * @param switchableBindings Bindings that should have cases in the when statement
   * @param switchIds Map from type key to switch case ID
   * @param typeParam The type parameter T for return type
   * @param componentField The component field for accessing dependencies
   * @param idField The id field to compare against
   * @param thisReceiver The SwitchingProvider's this receiver
   * @return IrWhen expression with all cases
   */
  private fun IrBuilderWithScope.generateSimpleWhen(
    switchableBindings: List<SwitchableBinding>,
    switchIds: Map<IrTypeKey, Int>,
    typeParam: IrTypeParameter,
    componentField: IrField,
    idField: IrField,
    thisReceiver: IrValueParameter
  ): IrExpression {

    return IrWhenImpl(
      startOffset = UNDEFINED_OFFSET,
      endOffset = UNDEFINED_OFFSET,
      type = typeParam.defaultType,
      origin = null
    ).apply {
      // Add a branch for each switchable binding
      branches.addAll(
        switchableBindings.map { binding ->
          val switchId = switchIds[binding.typeKey]!!

          IrBranchImpl(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            condition = irEquals(
              irGetField(irGet(thisReceiver), idField),
              irInt(switchId)
            ),
            result = generateBindingCreation(binding, componentField, thisReceiver)
          )
        }
      )

      // Add else branch - throw IllegalStateException for invalid switch IDs
      // Using IllegalStateException instead of AssertionError as it's more readily available in IR
      branches.add(
        IrElseBranchImpl(
          startOffset = UNDEFINED_OFFSET,
          endOffset = UNDEFINED_OFFSET,
          condition = IrConstImpl.boolean(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            type = irBuiltIns.booleanType,
            value = true
          ),
          result = IrThrowImpl(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            type = irBuiltIns.nothingType,
            value = irCallConstructor(
              // Find the no-arg constructor of Throwable (we'll convert id to string manually)
              callee = irBuiltIns.throwableClass.constructors.first().owner.symbol,
              typeArguments = emptyList()
            )
          )
        )
      )
    }
  }

  /**
   * Creates the SwitchingProvider class with type parameter, fields, constructor, and get() method.
   */
  private fun createSwitchingProviderClass(switchIds: Map<IrTypeKey, Int>): IrClass {
    val className = fieldNameAllocator.newName("SwitchingProvider")

    // Create the class
    val switchingProviderClass = pluginContext.irFactory.buildClass {
      name = className.asName()
      kind = ClassKind.CLASS
      visibility = DescriptorVisibilities.PRIVATE
      modality = Modality.FINAL
      origin = Origins.Default
    }.apply {
      createThisReceiverParameter()
      parentClass.addChild(this)
    }

    // Add type parameter <T>
    val typeParam = with(this@IrSwitchingProviderGenerator) {
      switchingProviderClass.addTypeParameter("T", irBuiltIns.anyNType)
    }

    // Set supertypes (must be after type parameter is added)
    switchingProviderClass.superTypes = listOf(
      metroSymbols.metroProvider.typeWith(typeParam.defaultType)
    )

    // Add fields: component and id (positional arguments!)
    val componentField = switchingProviderClass.addField(
      fieldNameAllocator.newName("component"),
      componentClass.utilDefaultType,
      DescriptorVisibilities.PRIVATE
    ).apply {
      isFinal = true
    }

    val idField = switchingProviderClass.addField(
      fieldNameAllocator.newName("id"),
      irBuiltIns.intType,
      DescriptorVisibilities.PRIVATE
    ).apply {
      isFinal = true
    }

    // Add constructor
    addConstructorToClass(switchingProviderClass, componentField, idField)

    // Add get() method
    addGetMethodToClass(switchingProviderClass, typeParam, componentField, idField, switchIds)

    return switchingProviderClass
  }

  /**
   * Adds the constructor that accepts component and id parameters.
   *
   * Note: Field initialization is done via field initializers referencing constructor parameters,
   * not in the constructor body, since SwitchingProvider is a nested (not inner) class with no dispatchReceiver.
   */
  private fun addConstructorToClass(
    switchingProviderClass: IrClass,
    componentField: IrField,
    idField: IrField
  ) {
    val constructor = switchingProviderClass.addConstructor {
      visibility = DescriptorVisibilities.PUBLIC
      isPrimary = true
      returnType = switchingProviderClass.utilDefaultType
    }

    val componentParam = constructor.addValueParameter("component", componentClass.utilDefaultType)
    val idParam = constructor.addValueParameter("id", irBuiltIns.intType)

    // Set field initializers to reference constructor parameters (assignConstructorParamsToFields pattern)
    componentField.initializer = createIrBuilder(componentField.symbol).run {
      irExprBody(irGet(componentParam))
    }
    idField.initializer = createIrBuilder(idField.symbol).run {
      irExprBody(irGet(idParam))
    }

    // Generate default constructor body (just calls super)
    constructor.body = with(metroContext) {
      with(constructor) {
        generateDefaultConstructorBody()
      }
    }
  }

  /**
   * Adds the get() method that implements Provider<T>.get().
   *
   * Generates a when statement that routes to the appropriate binding creation code
   * based on the switch ID.
   *
   * For <= 100 bindings: Single when statement
   * For > 100 bindings: Partitioned into get0(), get1(), etc. (TODO Phase 3F)
   */
  private fun addGetMethodToClass(
    switchingProviderClass: IrClass,
    typeParam: IrTypeParameter,
    componentField: IrField,
    idField: IrField,
    switchIds: Map<IrTypeKey, Int>
  ) {
    switchingProviderClass.addFunction(
      "get",
      typeParam.defaultType,
      visibility = DescriptorVisibilities.PUBLIC
    ).apply {
      modality = Modality.FINAL
      overriddenSymbols += metroSymbols.providerInvoke

      buildBlockBody {
        // Get switchable bindings (those that should use SwitchingProvider)
        val switchableBindings = bindings.filter { it.typeKey in switchIds }

        if (switchableBindings.isEmpty()) {
          // No switchable bindings - return null as placeholder
          // This can happen if all bindings are aliases or bound instances
          +irReturn(irNull())
        } else {
          // Generate when statement with binding cases
          // TODO Phase 3F: Handle > 100 cases with partitioning
          val whenExpr = generateSimpleWhen(
            switchableBindings,
            switchIds,
            typeParam,
            componentField,
            idField,
            dispatchReceiverParameter!!
          )
          +irReturn(whenExpr)
        }
      }
    }
  }
}

/**
 * Extension to add a type parameter to a class.
 *
 * Creates a type parameter with the given name and upper bound, adds it to the class's
 * type parameter list, and returns the created type parameter for use in type expressions.
 */
context(context: IrMetroContext)
private fun IrClass.addTypeParameter(name: String, upperBound: IrType): IrTypeParameter {
  val typeParam = context.pluginContext.irFactory.createTypeParameter(
    startOffset = UNDEFINED_OFFSET,
    endOffset = UNDEFINED_OFFSET,
    origin = IrDeclarationOrigin.DEFINED,
    name = name.asName(),
    symbol = IrTypeParameterSymbolImpl(),
    variance = Variance.INVARIANT,
    index = typeParameters.size,
    isReified = false
  ).apply {
    superTypes = listOf(upperBound)
    parent = this@addTypeParameter
  }
  typeParameters = typeParameters + typeParam
  return typeParam
}
