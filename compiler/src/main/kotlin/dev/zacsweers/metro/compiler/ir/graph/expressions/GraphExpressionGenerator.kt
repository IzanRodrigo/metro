// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph.expressions

import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.ir.BindingFieldAccess
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.ParentContext
import dev.zacsweers.metro.compiler.ir.getAllSuperTypes
import dev.zacsweers.metro.compiler.ir.graph.BindingPropertyContext
import dev.zacsweers.metro.compiler.ir.graph.DependencyGraphNode
import dev.zacsweers.metro.compiler.ir.graph.IrBinding
import dev.zacsweers.metro.compiler.ir.graph.IrBindingGraph
import dev.zacsweers.metro.compiler.ir.graph.IrGraphExtensionGenerator
import dev.zacsweers.metro.compiler.ir.graph.generatedGraphExtensionData
import dev.zacsweers.metro.compiler.ir.instanceFactory
import dev.zacsweers.metro.compiler.ir.irGetProperty
import dev.zacsweers.metro.compiler.ir.irInvoke
import dev.zacsweers.metro.compiler.ir.metroFunctionOf
import dev.zacsweers.metro.compiler.ir.parameters.Parameter
import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.ir.rawType
import dev.zacsweers.metro.compiler.ir.rawTypeOrNull
import dev.zacsweers.metro.compiler.ir.regularParameters
import dev.zacsweers.metro.compiler.ir.requireSimpleFunction
import dev.zacsweers.metro.compiler.ir.transformers.AssistedFactoryTransformer
import dev.zacsweers.metro.compiler.ir.transformers.BindingContainerTransformer
import dev.zacsweers.metro.compiler.ir.transformers.MembersInjectorTransformer
import dev.zacsweers.metro.compiler.ir.typeAsProviderArgument
import dev.zacsweers.metro.compiler.memoize
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.tracing.Tracer
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.util.companionObject
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.isObject
import org.jetbrains.kotlin.ir.util.isStatic
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.name.Name

internal class GraphExpressionGenerator
private constructor(
  context: IrMetroContext,
  private val node: DependencyGraphNode,
  override val thisReceiver: IrValueParameter,
  private val bindingPropertyContext: BindingPropertyContext,
  private val fieldAccess: BindingFieldAccess,
  override val bindingGraph: IrBindingGraph,
  private val bindingContainerTransformer: BindingContainerTransformer,
  private val membersInjectorTransformer: MembersInjectorTransformer,
  private val assistedFactoryTransformer: AssistedFactoryTransformer,
  private val graphExtensionGenerator: IrGraphExtensionGenerator,
  override val parentTracer: Tracer,
  getterPropertyFor:
    (
      IrBinding, IrContextualTypeKey, IrBuilderWithScope.(GraphExpressionGenerator) -> IrBody,
    ) -> IrProperty,
) : BindingExpressionGenerator<IrBinding>(context) {

  class Factory(
    private val context: IrMetroContext,
    private val node: DependencyGraphNode,
    private val bindingPropertyContext: BindingPropertyContext,
    private val bindingGraph: IrBindingGraph,
    private val bindingContainerTransformer: BindingContainerTransformer,
    private val membersInjectorTransformer: MembersInjectorTransformer,
    private val assistedFactoryTransformer: AssistedFactoryTransformer,
    private val graphExtensionGenerator: IrGraphExtensionGenerator,
    private val parentTracer: Tracer,
    private val getterPropertyFor:
      (
        IrBinding, IrContextualTypeKey, IrBuilderWithScope.(GraphExpressionGenerator) -> IrBody,
      ) -> IrProperty,
  ) {
    private val fieldAccess by lazy {
      dev.zacsweers.metro.compiler.ir.DefaultBindingFieldAccess(bindingPropertyContext)
    }

    fun create(thisReceiver: IrValueParameter): GraphExpressionGenerator {
      return GraphExpressionGenerator(
        context = context,
        node = node,
        thisReceiver = thisReceiver,
        bindingPropertyContext = bindingPropertyContext,
        fieldAccess = fieldAccess,
        bindingGraph = bindingGraph,
        bindingContainerTransformer = bindingContainerTransformer,
        membersInjectorTransformer = membersInjectorTransformer,
        assistedFactoryTransformer = assistedFactoryTransformer,
        graphExtensionGenerator = graphExtensionGenerator,
        parentTracer = parentTracer,
        getterPropertyFor = getterPropertyFor,
      )
    }
  }

  private val wrappedTypeGenerators = listOf(IrOptionalExpressionGenerator).associateBy { it.key }
  private val multibindingExpressionGenerator by memoize {
    MultibindingExpressionGenerator(this) { binding, contextKey, generateCode ->
      getterPropertyFor(binding, contextKey) { parentGenerator ->
        generateCode(parentGenerator.multibindingGetter)
      }
    }
  }

  // Here to defeat type checking
  private val multibindingGetter: MultibindingExpressionGenerator
    get() = multibindingExpressionGenerator

  /**
   * Gets the correct receiver for accessing bindings, handling subcomponent parent access.
   *
   * For nested subcomponents accessing parent bindings, we can't use thisReceiver directly.
   * Instead, we must access the parent via the stored parent field.
   *
   * @param ownerKey The type key of the component that owns the binding
   * @return The receiver to use for accessing the binding
   */
  context(scope: IrBuilderWithScope)
  private fun getReceiverForBindingAccess(ownerKey: IrTypeKey): IrValueParameter {
    // Check if we're accessing a parent binding (owner is different from current component)
    if (ownerKey == node.typeKey) {
      // Same component - use thisReceiver
      return thisReceiver
    }

    // Different component - accessing parent binding
    // Check if current class has a parent field (subcomponent/nested class)
    val currentClass = thisReceiver.parent as? IrClass
    if (currentClass != null) {
      val parentProperty = currentClass.declarations
        .filterIsInstance<IrProperty>()
        .find { it.name.asString() == "parent" }

      if (parentProperty?.backingField != null) {
        // We're in a subcomponent with explicit parent field
        // Create a synthetic value parameter representing the parent
        // This is a workaround since BindingFieldAccess expects IrValueParameter
        // but we need to use the parent field. We'll handle this in the expression.
        // For now, return the parent's thisReceiver but mark that we need transformation

        // Actually, we need to return something that BindingFieldAccess can work with.
        // The issue is that getInstanceExpression expects a receiver parameter.
        // Let's just return thisReceiver for now and handle the transformation differently.
        return thisReceiver
      }
    }

    // Fallback to thisReceiver
    return thisReceiver
  }

  /**
   * Gets receiver for accessing parent bindings, with lazy shard lookup.
   *
   * For nested subcomponents accessing parent bindings, we need to:
   * 1. Get the parent instance via this.parent field
   * 2. Check if the property is in a shard (lazy lookup from parent's BindingPropertyContext)
   * 3. If in shard, access the shard instance first, then the property
   *
   * @param propertyAccess The property access info from ParentContext
   * @return Expression to get the receiver that contains the property
   */
  context(scope: IrBuilderWithScope)
  private fun getReceiverForParentProperty(
    propertyAccess: ParentContext.PropertyAccess
  ): IrExpression {
    // Check if the property belongs to a parent graph (not the current node)
    if (propertyAccess.parentKey == node.typeKey) {
      // Same graph - use the receiver as-is
      return scope.irGet(propertyAccess.receiverParameter)
    }

    // Different graph - this is a parent binding
    // Get parent instance expression (via this.parent field for nested subcomponents)
    val parentReceiver = run {
      val currentClass = thisReceiver.parent as? IrClass
      if (currentClass != null) {
        val parentProperty = currentClass.declarations
          .filterIsInstance<IrProperty>()
          .find { it.name.asString() == "parent" }

        if (parentProperty?.backingField != null) {
          // We're in a subcomponent - access parent via the stored parent field
          scope.irGetField(scope.irGet(thisReceiver), parentProperty.backingField!!)
        } else {
          // Not a subcomponent, use original receiver
          scope.irGet(propertyAccess.receiverParameter)
        }
      } else {
        scope.irGet(propertyAccess.receiverParameter)
      }
    }

    // Lazy shard lookup: check if property is in a shard of the parent
    if (propertyAccess.parentBindingPropertyContext != null &&
        propertyAccess.propertyTypeKey != null) {
      val propertyEntry = propertyAccess.parentBindingPropertyContext.providerPropertyEntry(
        propertyAccess.propertyTypeKey
      )
      when (val owner = propertyEntry?.owner) {
        is BindingPropertyContext.Owner.Shard -> {
          // Property is in parent's shard, access through shard instance
          return scope.irGetField(parentReceiver, owner.instanceProperty.backingField!!)
        }
        else -> {
          // Property is in parent's root, use parent receiver as-is
          return parentReceiver
        }
      }
    }

    // No binding context, property must be in root
    return parentReceiver
  }

  /**
   * Transforms a graph instance expression to use parent field for subcomponents.
   *
   * When fieldAccess.getInstanceExpression returns an expression using thisReceiver,
   * but we're actually in a subcomponent accessing parent, we need to transform
   * the expression to use this.parent instead of this.
   *
   * This recursively walks the IR tree and replaces all GET_VAR references to the parent's
   * thisReceiver with GET_FIELD access to this.parent.
   */
  context(scope: IrBuilderWithScope)
  private fun transformGraphInstanceForParentAccess(
    graphInstanceExpr: IrExpression,
    ownerKey: IrTypeKey
  ): IrExpression {
    // Only transform if accessing parent from subcomponent
    if (ownerKey == node.typeKey) {
      return graphInstanceExpr
    }

    // Check if we're in a subcomponent
    val currentClass = thisReceiver.parent as? IrClass
    if (currentClass != null) {
      val parentProperty = currentClass.declarations
        .filterIsInstance<IrProperty>()
        .find { it.name.asString() == "parent" }

      if (parentProperty?.backingField != null) {
        // We're in a subcomponent - need to transform the entire expression tree
        // Replace all references to parent's thisReceiver with this.parent field access
        return transformReceiverReferences(
          graphInstanceExpr,
          parentProperty.backingField!!,
          scope
        )
      }
    }

    return graphInstanceExpr
  }

  /**
   * Recursively transforms an IR expression tree, replacing GET_VAR references to parent's
   * thisReceiver with GET_FIELD this.parent.
   */
  context(scope: IrBuilderWithScope)
  private fun transformReceiverReferences(
    expression: IrExpression,
    parentField: org.jetbrains.kotlin.ir.declarations.IrField,
    builder: IrBuilderWithScope
  ): IrExpression {
    // Get the parent graph class (the class that contains our subcomponent)
    val parentGraphClass = (thisReceiver.parent as? IrClass)?.parent as? IrClass

    return expression.transform(object : IrElementTransformerVoid() {
      override fun visitGetValue(expression: IrGetValue): IrExpression {
        // Check if this is a GET_VAR of the parent graph's thisReceiver
        val symbol = expression.symbol
        if (symbol.owner is IrValueParameter) {
          val param = symbol.owner as IrValueParameter

          // Check if it belongs to the parent graph class and is a dispatch receiver
          val paramOwner = param.parent as? IrClass
          if (paramOwner == parentGraphClass && param.name.asString() == "<this>") {
            // This is the parent graph's thisReceiver - replace with this.parent
            return builder.irGetField(builder.irGet(thisReceiver), parentField)
          }
        }
        return super.visitGetValue(expression)
      }
    }, null) as IrExpression
  }

  context(scope: IrBuilderWithScope)
  override fun generateBindingCode(
    binding: IrBinding,
    contextualTypeKey: IrContextualTypeKey,
    accessType: AccessType,
    fieldInitKey: IrTypeKey?,
  ): IrExpression =
    with(scope) {
      if (binding is IrBinding.Absent) {
        reportCompilerBug(
          "Absent bindings need to be checked prior to generateBindingCode(). ${binding.typeKey} missing."
        )
      }

      if (
        accessType != AccessType.INSTANCE &&
          binding is IrBinding.ConstructorInjected &&
          binding.isAssisted
      ) {
        // Should be caught in FIR
        reportCompilerBug("Assisted inject factories should only be accessed as instances")
      }

      val metroProviderSymbols = metroSymbols.providerSymbolsFor(contextualTypeKey)

      // If we're initializing the field for this key, don't ever try to reach for an existing
      // provider for it.
      // This is important for cases like DelegateFactory and breaking cycles.
      if (fieldInitKey == null || fieldInitKey != binding.typeKey) {
        if (fieldAccess.hasField(binding.typeKey)) {
          // Try provider field first
          fieldAccess.getProviderExpression(binding.typeKey, thisReceiver)?.let { providerExpr ->
            val providerInstance =
              providerExpr.let {
                with(metroProviderSymbols) { transformMetroProvider(it, contextualTypeKey) }
              }
            return if (accessType == AccessType.INSTANCE) {
              irInvoke(providerInstance, callee = metroSymbols.providerInvoke)
            } else {
              providerInstance
            }
          }
          // Fall back to instance field
          fieldAccess.getInstanceExpression(binding.typeKey, thisReceiver)?.let { instanceExpr ->
            return if (accessType == AccessType.INSTANCE) {
              instanceExpr
            } else {
              with(metroProviderSymbols) {
                transformMetroProvider(
                  instanceFactory(binding.typeKey.type, instanceExpr),
                  contextualTypeKey,
                )
              }
            }
          }
          // Should never get here
          reportCompilerBug("Unable to find instance or provider field for ${binding.typeKey}")
        }
      }

      return when (binding) {
        is IrBinding.ConstructorInjected -> {
          // Example_Factory.create(...)
          binding.classFactory
            .invokeCreateExpression(binding.typeKey) { createFunction, parameters ->
              generateBindingArguments(
                targetParams = parameters,
                function = createFunction,
                binding = binding,
                fieldInitKey = null,
              )
            }
            .let { factoryInstance ->
              val isAssistedInject = binding.classFactory.isAssistedInject
              if (isAssistedInject) {
                return@let factoryInstance
              }

              factoryInstance.transformAccessIfNeeded(
                accessType,
                AccessType.PROVIDER,
                binding.typeKey.type,
              )
            }
        }

        is IrBinding.CustomWrapper -> {
          val generator =
            wrappedTypeGenerators[binding.wrapperKey]
              ?: reportCompilerBug("No generator found for wrapper key: ${binding.wrapperKey}")

          val delegateBinding = bindingGraph.findBinding(binding.wrappedContextKey.typeKey)
          val isAbsentInGraph = delegateBinding == null
          val wrappedInstance =
            if (!isAbsentInGraph) {
              generateBindingCode(
                delegateBinding,
                binding.wrappedContextKey,
                accessType = AccessType.INSTANCE,
                fieldInitKey = fieldInitKey,
              )
            } else if (binding.allowsAbsent) {
              null
            } else {
              reportCompilerBug("No delegate binding for wrapped type ${binding.typeKey}!")
            }
          generator
            .generate(binding, wrappedInstance)
            .transformAccessIfNeeded(
              accessType,
              AccessType.INSTANCE,
              binding.typeKey.type,
              useInstanceFactory = false,
            )
        }

        is IrBinding.ObjectClass -> {
          irGetObject(binding.type.symbol)
            .transformAccessIfNeeded(accessType, AccessType.INSTANCE, binding.typeKey.type)
        }

        is IrBinding.Alias -> {
          // For binds functions, just use the backing type
          val aliasedBinding = binding.aliasedBinding(bindingGraph)
          check(aliasedBinding != binding) { "Aliased binding aliases itself" }
          return generateBindingCode(
            aliasedBinding,
            accessType = accessType,
            fieldInitKey = fieldInitKey,
          )
        }

        is IrBinding.Provided -> {
          val providerFactory =
            bindingContainerTransformer.getOrLookupProviderFactory(binding)
              ?: reportCompilerBug(
                "No factory found for Provided binding ${binding.typeKey}. This is likely a bug in the Metro compiler, please report it to the issue tracker."
              )

          // Invoke its factory's create() function
          providerFactory
            .invokeCreateExpression(binding.typeKey) { createFunction, params ->
              generateBindingArguments(
                targetParams = params,
                function = createFunction,
                binding = binding,
                fieldInitKey = fieldInitKey,
              )
            }
            .transformAccessIfNeeded(accessType, AccessType.PROVIDER, binding.typeKey.type)
        }

        is IrBinding.Assisted -> {
          // Example9_Factory_Impl.create(example9Provider);
          val factoryImpl = assistedFactoryTransformer.getOrGenerateImplClass(binding.type)

          val targetBinding = bindingGraph.requireBinding(binding.target.typeKey)

          // Assisted-inject factories don't implement Provider
          val delegateFactory =
            generateBindingCode(
              targetBinding,
              accessType = AccessType.INSTANCE,
              fieldInitKey = fieldInitKey,
            )

          val factoryProvider = with(factoryImpl) { invokeCreate(delegateFactory) }

          factoryProvider.transformAccessIfNeeded(
            accessType,
            AccessType.PROVIDER,
            targetBinding.typeKey.type,
          )
        }

        is IrBinding.Multibinding -> {
          multibindingExpressionGenerator
            .generateBindingCode(binding, contextualTypeKey, accessType, fieldInitKey)
            .let {
              if (accessType == AccessType.INSTANCE) {
                it
              } else {
                with(metroProviderSymbols) { transformMetroProvider(it, contextualTypeKey) }
              }
            }
        }

        is IrBinding.MembersInjected -> {
          val injectedClass = referenceClass(binding.targetClassId)!!.owner
          val injectedType = injectedClass.defaultType
          val injectorClass =
            membersInjectorTransformer.getOrGenerateInjector(injectedClass)?.injectorClass

          if (injectorClass == null) {
            // Return a noop
            val noopInjector =
              irInvoke(
                dispatchReceiver = irGetObject(metroSymbols.metroMembersInjectors),
                callee = metroSymbols.metroMembersInjectorsNoOp,
                typeArgs = listOf(injectedType),
              )
            val noopProvider =
              noopInjector.transformAccessIfNeeded(
                requested = AccessType.PROVIDER,
                actual = AccessType.INSTANCE,
                type = injectedType,
              )
            with(metroProviderSymbols) { transformMetroProvider(noopProvider, contextualTypeKey) }
          } else {
            val injectorCreatorClass =
              if (injectorClass.isObject) injectorClass else injectorClass.companionObject()!!
            val createFunction =
              injectorCreatorClass.requireSimpleFunction(Symbols.StringNames.CREATE)
            val args =
              generateBindingArguments(
                targetParams = binding.parameters,
                function = createFunction.owner,
                binding = binding,
                fieldInitKey = fieldInitKey,
              )

            // InjectableClass_MembersInjector.create(stringValueProvider,
            // exampleComponentProvider)
            val provider =
              irInvoke(
                  dispatchReceiver =
                    if (injectorCreatorClass.isObject) {
                      irGetObject(injectorCreatorClass.symbol)
                    } else {
                      // It's static from java, dagger interop
                      check(createFunction.owner.isStatic)
                      null
                    },
                  callee = createFunction,
                  args = args,
                )
                .transformAccessIfNeeded(
                  requested = AccessType.PROVIDER,
                  actual = AccessType.INSTANCE,
                  type = injectedType,
                )

            with(metroProviderSymbols) { transformMetroProvider(provider, contextualTypeKey) }
          }
        }

        is IrBinding.Absent -> {
          // Should never happen, this should be checked before function/constructor injections.
          reportCompilerBug("Unable to generate code for unexpected Absent binding: $binding")
        }

        is IrBinding.BoundInstance -> {
          if (binding.classReceiverParameter != null) {
            when (accessType) {
              AccessType.INSTANCE -> {
                // Get it directly
                irGet(binding.classReceiverParameter)
              }
              AccessType.PROVIDER -> {
                // We need the provider
                irGetProperty(
                  irGet(binding.classReceiverParameter),
                  binding.providerPropertyAccess!!.property,
                )
              }
            }
          } else {
            // Should never happen, this should get handled in the provider/instance fields logic
            // above.
            reportCompilerBug(
              "Unable to generate code for unexpected BoundInstance binding: $binding"
            )
          }
        }

        is IrBinding.GraphExtension -> {
          // Generate graph extension instance
          val extensionImpl =
            graphExtensionGenerator.getOrBuildGraphExtensionImpl(
              binding.typeKey,
              node.sourceGraph,
              // The reportableDeclaration should be the accessor function
              metroFunctionOf(binding.reportableDeclaration as IrSimpleFunction),
              parentTracer,
            )

          if (options.enableGraphImplClassAsReturnType) {
            // This is probably not the right spot to change the return type, but the IrClass
            // implementation is not exposed otherwise.
            binding.accessor.returnType = extensionImpl.defaultType
          }

          val ctor = extensionImpl.primaryConstructor!!
          irCallConstructor(ctor.symbol, node.sourceGraph.typeParameters.map { it.defaultType })
            .apply {
              // If this function has parameters, they're factory instance params and need to be
              // passed on
              val functionParams = binding.accessor.regularParameters

              // First param (dispatch receiver) is always the parent graph
              arguments[0] = irGet(thisReceiver)
              for (i in 0 until functionParams.size) {
                arguments[i + 1] = irGet(functionParams[i])
              }
            }
            .transformAccessIfNeeded(accessType, AccessType.INSTANCE, binding.typeKey.type)
        }

        is IrBinding.GraphExtensionFactory -> {
          // Get the pre-generated extension implementation that should contain the factory
          val extensionImpl =
            graphExtensionGenerator.getOrBuildGraphExtensionImpl(
              binding.extensionTypeKey,
              node.sourceGraph,
              metroFunctionOf(binding.reportableDeclaration as IrSimpleFunction),
              parentTracer,
            )

          // Get the factory implementation that was generated alongside the extension
          val factoryImpl =
            extensionImpl.generatedGraphExtensionData?.factoryImpl
              ?: reportCompilerBug(
                "Expected factory implementation to be generated for graph extension factory binding"
              )

          val constructor = factoryImpl.primaryConstructor!!
          val parameters = constructor.parameters()
          irCallConstructor(
              constructor.symbol,
              binding.accessor.typeParameters.map { it.defaultType },
            )
            .apply {
              // Pass the parent graph instance
              arguments[0] =
                generateBindingCode(
                  bindingGraph.requireBinding(parameters.regularParameters.single().typeKey),
                  accessType = AccessType.INSTANCE,
                )
            }
            .transformAccessIfNeeded(accessType, AccessType.INSTANCE, binding.typeKey.type)
        }

        is IrBinding.GraphDependency -> {
          val ownerKey = binding.ownerKey
          if (binding.propertyAccess != null) {
            // Get the receiver (parent root or shard) via lazy shard lookup
            val actualReceiver = getReceiverForParentProperty(binding.propertyAccess)

            // Access the property from the actual receiver
            irGetProperty(actualReceiver, binding.propertyAccess.property)
          } else if (binding.getter != null) {
            val rawGraphInstanceExpr =
              fieldAccess.getInstanceExpression(ownerKey, thisReceiver)
                ?: reportCompilerBug(
                  "No matching included type instance found for type $ownerKey while processing ${node.typeKey}. Available instance fields ${bindingPropertyContext.availableInstanceKeys}"
                )

            // Transform the expression if we're in a subcomponent accessing parent
            val graphInstanceExpr = transformGraphInstanceForParentAccess(rawGraphInstanceExpr, ownerKey)

            val getterContextKey = IrContextualTypeKey.from(binding.getter)

            val invokeGetter =
              irInvoke(
                dispatchReceiver = graphInstanceExpr,
                callee = binding.getter.symbol,
                typeHint = binding.typeKey.type,
              )

            if (getterContextKey.isWrappedInProvider) {
              // It's already a provider
              invokeGetter
            } else {
              wrapInProviderFunction(binding.typeKey.type) {
                  if (getterContextKey.isWrappedInProvider) {
                    irInvoke(invokeGetter, callee = metroSymbols.providerInvoke)
                  } else if (getterContextKey.isWrappedInLazy) {
                    irInvoke(invokeGetter, callee = metroSymbols.lazyGetValue)
                  } else {
                    invokeGetter
                  }
                }
                .transformAccessIfNeeded(accessType, AccessType.PROVIDER, binding.typeKey.type)
            }
          } else {
            reportCompilerBug("Unknown graph dependency type")
          }
        }
      }
    }

  context(scope: IrBuilderWithScope)
  private fun generateBindingArguments(
    targetParams: Parameters,
    function: IrFunction,
    binding: IrBinding,
    fieldInitKey: IrTypeKey?,
  ): List<IrExpression?> =
    with(scope) {
      val params = function.parameters()
      var paramsToMap = buildList {
        if (
          binding is IrBinding.Provided &&
            targetParams.dispatchReceiverParameter?.type?.rawTypeOrNull()?.isObject != true
        ) {
          targetParams.dispatchReceiverParameter?.let(::add)
        }
        addAll(targetParams.contextParameters.filterNot { it.isAssisted })
        targetParams.extensionReceiverParameter?.let(::add)
        addAll(targetParams.regularParameters.filterNot { it.isAssisted })
      }

      // Handle case where function has more parameters than the binding
      // This can happen when parameters are inherited from ancestor classes
      if (
        binding is IrBinding.MembersInjected && function.regularParameters.size > paramsToMap.size
      ) {
        // For MembersInjected, we need to look at the target class and its ancestors
        val nameToParam = mutableMapOf<Name, Parameter>()
        val targetClass = pluginContext.referenceClass(binding.targetClassId)?.owner
        targetClass // Look for inject methods in the target class and its ancestors
          ?.getAllSuperTypes(excludeSelf = false, excludeAny = true)
          ?.forEach { type ->
            val clazz = type.rawType()
            membersInjectorTransformer
              .getOrGenerateInjector(clazz)
              ?.declaredInjectFunctions
              ?.forEach { (_, params) ->
                for (param in params.regularParameters) {
                  nameToParam.putIfAbsent(param.name, param)
                }
              }
          }
        // Construct the list of parameters in order determined by the function
        paramsToMap =
          function.regularParameters.mapNotNull { functionParam -> nameToParam[functionParam.name] }
        // If we still have a mismatch, log a detailed error
        check(function.regularParameters.size == paramsToMap.size) {
          """
          Inconsistent parameter types for type ${binding.typeKey}!
          Input type keys:
            - ${paramsToMap.map { it.typeKey }.joinToString()}
          Binding parameters (${function.kotlinFqName}):
            - ${function.regularParameters.map { IrContextualTypeKey.from(it).typeKey }.joinToString()}
          """
            .trimIndent()
        }
      }

      if (
        binding is IrBinding.Provided &&
          binding.providerFactory.function.correspondingPropertySymbol == null
      ) {
        check(params.regularParameters.size == paramsToMap.size) {
          """
          Inconsistent parameter types for type ${binding.typeKey}!
          Input type keys:
            - ${paramsToMap.map { it.typeKey }.joinToString()}
          Binding parameters (${function.kotlinFqName}):
            - ${function.regularParameters.map { IrContextualTypeKey.from(it).typeKey }.joinToString()}
          """
            .trimIndent()
        }
      }

      return params.nonDispatchParameters.mapIndexed { i, param ->
        val contextualTypeKey = paramsToMap[i].contextualTypeKey
        val typeKey = contextualTypeKey.typeKey

        val metroProviderSymbols = metroSymbols.providerSymbolsFor(contextualTypeKey)

        val accessType =
          if (param.contextualTypeKey.requiresProviderInstance) {
            AccessType.PROVIDER
          } else {
            AccessType.INSTANCE
          }

        // TODO consolidate this logic with generateBindingCode
        if (accessType == AccessType.INSTANCE) {
          // IFF the parameter can take a direct instance, try our instance fields
          fieldAccess.getInstanceExpression(typeKey, thisReceiver)?.let { instanceExpr ->
            return@mapIndexed instanceExpr.let {
              with(metroProviderSymbols) { transformMetroProvider(it, contextualTypeKey) }
            }
          }
        }

        val providerInstance =
          fieldAccess.getProviderExpression(typeKey, thisReceiver)
            ?: run {
              // Generate binding code for each param
              val paramBinding = bindingGraph.requireBinding(contextualTypeKey)

              if (paramBinding is IrBinding.Absent) {
                // Null argument expressions get treated as absent in the final call
                return@mapIndexed null
              }

              generateBindingCode(
                paramBinding,
                fieldInitKey = fieldInitKey,
                accessType = accessType,
                contextualTypeKey = param.contextualTypeKey,
              )
            }

        typeAsProviderArgument(
          param.contextualTypeKey,
          providerInstance,
          isAssisted = param.isAssisted,
          isGraphInstance = param.isGraphInstance,
        )
      }
    }
}
