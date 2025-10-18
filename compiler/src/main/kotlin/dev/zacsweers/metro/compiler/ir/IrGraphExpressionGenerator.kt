// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.expectAs
import dev.zacsweers.metro.compiler.graph.WrappedType
import dev.zacsweers.metro.compiler.ir.parameters.Parameter
import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.ir.parameters.wrapInProvider
import dev.zacsweers.metro.compiler.ir.transformers.AssistedFactoryTransformer
import dev.zacsweers.metro.compiler.ir.transformers.BindingContainerTransformer
import dev.zacsweers.metro.compiler.ir.transformers.MembersInjectorTransformer
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.tracing.Tracer
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.parent
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.typeOrFail
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.companionObject
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.isObject
import org.jetbrains.kotlin.ir.util.isStatic
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.name.Name

internal class IrGraphExpressionGenerator
private constructor(
  context: IrMetroContext,
  private val node: DependencyGraphNode,
  private val thisReceiver: IrValueParameter,
  private val fieldAccess: BindingFieldAccess,
  private val bindingGraph: IrBindingGraph,
  private val bindingContainerTransformer: BindingContainerTransformer,
  private val membersInjectorTransformer: MembersInjectorTransformer,
  private val assistedFactoryTransformer: AssistedFactoryTransformer,
  private val graphExtensionGenerator: IrGraphExtensionGenerator,
  private val parentTracer: Tracer,
) : IrMetroContext by context {

  class Factory(
    private val context: IrMetroContext,
    private val node: DependencyGraphNode,
    private val bindingFieldContext: BindingFieldContext,
    private val bindingGraph: IrBindingGraph,
    private val bindingContainerTransformer: BindingContainerTransformer,
    private val membersInjectorTransformer: MembersInjectorTransformer,
    private val assistedFactoryTransformer: AssistedFactoryTransformer,
    private val graphExtensionGenerator: IrGraphExtensionGenerator,
    private val parentTracer: Tracer,
  ) {
    fun create(thisReceiver: IrValueParameter): IrGraphExpressionGenerator {
      // Wrap BindingFieldContext with the abstraction layer
      val fieldAccess: BindingFieldAccess = DefaultBindingFieldAccess(bindingFieldContext)
      return IrGraphExpressionGenerator(
        context = context,
        node = node,
        thisReceiver = thisReceiver,
        fieldAccess = fieldAccess,
        bindingGraph = bindingGraph,
        bindingContainerTransformer = bindingContainerTransformer,
        membersInjectorTransformer = membersInjectorTransformer,
        assistedFactoryTransformer = assistedFactoryTransformer,
        graphExtensionGenerator = graphExtensionGenerator,
        parentTracer = parentTracer,
      )
    }
  }

  enum class AccessType {
    INSTANCE,
    PROVIDER,
  }

  private val wrappedTypeGenerators = listOf(IrOptionalExpressionGenerator).associateBy { it.key }

  context(scope: IrBuilderWithScope)
  fun generateBindingCode(
    binding: IrBinding,
    contextualTypeKey: IrContextualTypeKey = binding.contextualTypeKey,
    accessType: AccessType =
      if (contextualTypeKey.requiresProviderInstance) {
        AccessType.PROVIDER
      } else {
        AccessType.INSTANCE
      },
    fieldInitKey: IrTypeKey? = null,
  ): IrExpression =
    with(scope) {
      if (binding is IrBinding.Absent) {
        reportCompilerBug(
          "Absent bindings need to be checked prior to generateBindingCode(). ${binding.typeKey} missing."
        )
      }

      val metroProviderSymbols = metroSymbols.providerSymbolsFor(contextualTypeKey)

      // If we're initializing the field for this key, don't ever try to reach for an existing
      // provider for it.
      // This is important for cases like DelegateFactory and breaking cycles.
      if (fieldInitKey == null || fieldInitKey != binding.typeKey) {
        if (fieldAccess.hasField(binding.typeKey)) {
          fieldAccess.getProviderExpression(this, binding.typeKey, thisReceiver)?.let { expression ->
            val providerInstance =
              with(metroProviderSymbols) { transformMetroProvider(expression, contextualTypeKey) }
            return if (accessType == AccessType.INSTANCE) {
              irInvoke(providerInstance, callee = metroSymbols.providerInvoke)
            } else {
              providerInstance
            }
          }
          fieldAccess.getInstanceExpression(this, binding.typeKey, thisReceiver)?.let { instance ->
            return if (accessType == AccessType.INSTANCE) {
              instance
            } else {
              with(metroProviderSymbols) {
                transformMetroProvider(
                  instanceFactory(binding.typeKey.type, instance),
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
            .transformAccessIfNeeded(
              accessType,
              // Assisted inject types don't implement Provider
              if (binding.classFactory.isAssistedInject) {
                AccessType.INSTANCE
              } else {
                AccessType.PROVIDER
              },
              binding.typeKey.type,
            )
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
            .transformAccessIfNeeded(accessType, AccessType.INSTANCE, binding.typeKey.type)
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
          val delegateFactoryProvider =
            generateBindingCode(targetBinding, accessType = AccessType.INSTANCE)

          with(factoryImpl) { invokeCreate(delegateFactoryProvider) }
            .transformAccessIfNeeded(accessType, AccessType.PROVIDER, targetBinding.typeKey.type)
        }

        is IrBinding.Multibinding -> {
          generateMultibindingExpression(binding, contextualTypeKey, fieldInitKey)
            .transformAccessIfNeeded(
              requested = accessType,
              actual =
                if (contextualTypeKey.requiresProviderInstance) {
                  AccessType.PROVIDER
                } else {
                  AccessType.INSTANCE
                },
              type = binding.typeKey.type,
            )
        }

        is IrBinding.MembersInjected -> {
          val injectedClass = referenceClass(binding.targetClassId)!!.owner
          val injectedType = injectedClass.defaultType
          val injectorClass = membersInjectorTransformer.getOrGenerateInjector(injectedClass)?.ir

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
                irGetField(
                  irGet(binding.classReceiverParameter),
                  binding.providerFieldAccess!!.field,
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
          val extensionData = extensionImpl.generatedGraphExtensionData

          irCallConstructor(ctor.symbol, node.sourceGraph.typeParameters.map { it.defaultType })
            .apply {
              // If this function has parameters, they're factory instance params and need to be
              // passed on
              val functionParams = binding.accessor.regularParameters

              var argIndex = 0

              // Pass all ancestor arguments
              // Extension expects ancestors in order: parent, intermediates..., root
              if (extensionData?.ancestors?.isNotEmpty() == true) {
                val currentExtensionData = node.sourceGraph.generatedGraphExtensionData

                for (requiredAncestor in extensionData.ancestors) {
                  // Find how to access this ancestor from the current component
                  val ancestorValue = if (requiredAncestor.componentClass == node.sourceGraph.metroGraphOrFail) {
                    // The required ancestor is THIS component - pass thisReceiver
                    irGet(thisReceiver)
                  } else {
                    // Look for this ancestor in our own ancestors list
                    val ourAncestor = currentExtensionData?.ancestors?.find {
                      it.componentClass == requiredAncestor.componentClass
                    }

                    if (ourAncestor != null) {
                      // We have this ancestor - pass our field
                      irGetField(irGet(thisReceiver), ourAncestor.field)
                    } else {
                      // We don't have it - this is the root and we ARE the root
                      irGet(thisReceiver)
                    }
                  }

                  arguments[argIndex++] = ancestorValue
                }
              }

              // Pass creator function parameters
              for (i in 0 until functionParams.size) {
                arguments[argIndex++] = irGet(functionParams[i])
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
          if (binding.fieldAccess != null) {
            // Get the correct ancestor receiver based on which component owns the field
            // For graph extensions with multiple ancestors (Dagger's approach):
            // - Find which ancestor owns this field by matching parentKey
            // - Access that ancestor's field directly
            val extensionData = node.sourceGraph.generatedGraphExtensionData

            val parentReceiver = if (extensionData?.ancestors?.isNotEmpty() == true) {
              // This is a graph extension - find the correct ancestor that owns the field
              // The field owner is stored in binding.fieldAccess.parentKey (node's typeKey)
              val fieldOwnerTypeKey = binding.fieldAccess.parentKey

              // Find matching ancestor by comparing SOURCE type keys
              // CRITICAL: parentKey is the node's typeKey (source interface), not the metroGraph class
              // So we must match against ancestor.sourceTypeKey, not IrTypeKey(ancestor.componentClass)!
              val owningAncestor = extensionData.ancestors.find { ancestor ->
                ancestor.sourceTypeKey == fieldOwnerTypeKey
              }

              if (owningAncestor != null) {
                // Access the correct ancestor directly
                irGetField(irGet(thisReceiver), owningAncestor.field)
              } else {
                // Could not find ancestor in our list
                // NEVER use binding.fieldAccess.receiverParameter - it references parent's <this>
                // which has wrong type in child's constructor context!
                // Fallback: use rootGraph (last ancestor) or parent (first ancestor)
                extensionData.rootGraphField?.let {
                  irGetField(irGet(thisReceiver), it)
                } ?: extensionData.parentField?.let {
                  irGetField(irGet(thisReceiver), it)
                } ?: reportCompilerBug(
                  "No ancestor field available for accessing ${fieldOwnerTypeKey} from ${node.sourceGraph.kotlinFqName}"
                )
              }
            } else {
              // Regular component - direct access to parent's this receiver
              irGet(binding.fieldAccess.receiverParameter)
            }

            // Check if field is in a shard - lazy lookup from parent's BindingFieldContext
            val actualReceiver = if (
              binding.fieldAccess.parentBindingFieldContext != null &&
              binding.fieldAccess.fieldTypeKey != null
            ) {
              val fieldEntry = binding.fieldAccess.parentBindingFieldContext.providerFieldEntry(
                binding.fieldAccess.fieldTypeKey
              )
              when (val owner = fieldEntry?.owner) {
                is BindingFieldContext.Owner.Shard -> {
                  // Field is in parent's shard, access through shard instance
                  irGetField(parentReceiver, owner.instanceField)
                }
                else -> {
                  // Field is in parent's root
                  parentReceiver
                }
              }
            } else {
              // No binding context, field must be in root
              parentReceiver
            }

            // Access the field from the actual receiver (root or shard)
            irGetField(actualReceiver, binding.fieldAccess.field)
          } else if (binding.getter != null) {
            val graphInstance =
              fieldAccess.getInstanceExpression(this, ownerKey, thisReceiver)
                ?: reportCompilerBug(
                  "No matching included type instance found for type $ownerKey while processing ${node.typeKey}."
                )

            val getterContextKey = IrContextualTypeKey.from(binding.getter)

            val invokeGetter =
              irInvoke(
                dispatchReceiver = graphInstance,
                callee = binding.getter.symbol,
                typeHint = binding.typeKey.type,
              )

            if (getterContextKey.isWrappedInProvider) {
              // It's already a provider
              invokeGetter
            } else {
              val lambda =
                irLambda(
                  parent = this.parent,
                  receiverParameter = null,
                  emptyList(),
                  binding.typeKey.type,
                  suspend = false,
                ) {
                  val returnExpression =
                    if (getterContextKey.isWrappedInProvider) {
                      irInvoke(invokeGetter, callee = metroSymbols.providerInvoke)
                    } else if (getterContextKey.isWrappedInLazy) {
                      irInvoke(invokeGetter, callee = metroSymbols.lazyGetValue)
                    } else {
                      invokeGetter
                    }
                  +irReturn(returnExpression)
                }
              irInvoke(
                  dispatchReceiver = null,
                  callee = metroSymbols.metroProviderFunction,
                  typeHint = binding.typeKey.type.wrapInProvider(metroSymbols.metroProvider),
                  typeArgs = listOf(binding.typeKey.type),
                  args = listOf(lambda),
                )
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
          fieldAccess.getInstanceExpression(this, typeKey, thisReceiver)?.let { instance ->
            return@mapIndexed with(metroProviderSymbols) {
              transformMetroProvider(instance, contextualTypeKey)
            }
          }
        }

        val providerInstance =
          fieldAccess.getProviderExpression(this, typeKey, thisReceiver)
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

  internal fun generateMapKeyLiteral(binding: IrBinding): IrExpression {
    val mapKey =
      when (binding) {
        is IrBinding.Alias -> binding.annotations.mapKeys.first().ir
        is IrBinding.Provided -> binding.annotations.mapKeys.first().ir
        is IrBinding.ConstructorInjected -> binding.annotations.mapKeys.first().ir
        else -> reportCompilerBug("Unsupported multibinding source: $binding")
      }

    val unwrapValue = shouldUnwrapMapKeyValues(mapKey)
    val expression =
      if (!unwrapValue) {
        mapKey
      } else {
        // We can just copy the expression!
        mapKey.arguments[0]!!.deepCopyWithSymbols()
      }

    return expression
  }

  context(scope: IrBuilderWithScope)
  private fun generateMultibindingExpression(
    binding: IrBinding.Multibinding,
    contextualTypeKey: IrContextualTypeKey,
    fieldInitKey: IrTypeKey?,
  ): IrExpression {
    return if (binding.isSet) {
      generateSetMultibindingExpression(binding, contextualTypeKey, fieldInitKey)
    } else {
      // It's a map
      generateMapMultibindingExpression(binding, contextualTypeKey, fieldInitKey)
    }
  }

  context(scope: IrBuilderWithScope)
  private fun generateSetMultibindingExpression(
    binding: IrBinding.Multibinding,
    contextualTypeKey: IrContextualTypeKey,
    fieldInitKey: IrTypeKey?,
  ): IrExpression {
    val elementType = (binding.typeKey.type as IrSimpleType).arguments.single().typeOrFail
    val (collectionProviders, individualProviders) =
      binding.sourceBindings
        .map { bindingGraph.requireBinding(it).expectAs<IrBinding.BindingWithAnnotations>() }
        .partition { it.annotations.isElementsIntoSet }
    // If we have any @ElementsIntoSet, we need to use SetFactory
    return if (collectionProviders.isNotEmpty() || contextualTypeKey.requiresProviderInstance) {
      generateSetFactoryExpression(
        elementType,
        collectionProviders,
        individualProviders,
        fieldInitKey,
      )
    } else {
      generateSetBuilderExpression(binding, elementType, fieldInitKey)
    }
  }

  context(scope: IrBuilderWithScope)
  private fun generateSetBuilderExpression(
    binding: IrBinding.Multibinding,
    elementType: IrType,
    fieldInitKey: IrTypeKey?,
  ): IrExpression =
    with(scope) {
      val callee: IrSimpleFunctionSymbol
      val args: List<IrExpression>
      when (val size = binding.sourceBindings.size) {
        0 -> {
          // emptySet()
          callee = metroSymbols.emptySet
          args = emptyList()
        }

        1 -> {
          // setOf(<one>)
          callee = metroSymbols.setOfSingleton
          val provider = binding.sourceBindings.first().let { bindingGraph.requireBinding(it) }
          args = listOf(generateMultibindingArgument(provider, fieldInitKey))
        }

        else -> {
          // buildSet(<size>) { ... }
          callee = metroSymbols.buildSetWithCapacity
          args = buildList {
            add(irInt(size))
            add(
              irLambda(
                parent = parent,
                receiverParameter = irBuiltIns.mutableSetClass.typeWith(elementType),
                valueParameters = emptyList(),
                returnType = irBuiltIns.unitType,
                suspend = false,
              ) { function ->
                // This is the mutable set receiver
                val functionReceiver = function.extensionReceiverParameterCompat!!
                binding.sourceBindings
                  .map { bindingGraph.requireBinding(it) }
                  .forEach { provider ->
                    +irInvoke(
                      dispatchReceiver = irGet(functionReceiver),
                      callee = metroSymbols.mutableSetAdd.symbol,
                      args = listOf(generateMultibindingArgument(provider, fieldInitKey)),
                    )
                  }
              }
            )
          }
        }
      }

      return irCall(
          callee = callee,
          type = binding.typeKey.type,
          typeArguments = listOf(elementType),
        )
        .apply {
          for ((i, arg) in args.withIndex()) {
            arguments[i] = arg
          }
        }
    }

  context(scope: IrBuilderWithScope)
  private fun generateSetFactoryExpression(
    elementType: IrType,
    collectionProviders: List<IrBinding>,
    individualProviders: List<IrBinding>,
    fieldInitKey: IrTypeKey?,
  ): IrExpression =
    with(scope) {
      // SetFactory.<String>builder(1, 1)
      //   .addProvider(FileSystemModule_Companion_ProvideString1Factory.create())
      //   .addCollectionProvider(provideString2Provider)
      //   .build()

      // Used to unpack the right provider type
      val valueProviderSymbols = metroSymbols.providerSymbolsFor(elementType)

      // SetFactory.<String>builder(1, 1)
      val builder: IrExpression =
        irInvoke(
          callee = valueProviderSymbols.setFactoryBuilderFunction,
          typeHint = valueProviderSymbols.setFactoryBuilder.typeWith(elementType),
          typeArgs = listOf(elementType),
          args = listOf(irInt(individualProviders.size), irInt(collectionProviders.size)),
        )

      val withProviders =
        individualProviders.fold(builder) { receiver, provider ->
          irInvoke(
            dispatchReceiver = receiver,
            callee = valueProviderSymbols.setFactoryBuilderAddProviderFunction,
            typeHint = builder.type,
            args =
              listOf(
                generateBindingCode(
                  provider,
                  accessType = AccessType.PROVIDER,
                  fieldInitKey = fieldInitKey,
                )
              ),
          )
        }

      // .addProvider(FileSystemModule_Companion_ProvideString1Factory.create())
      val withCollectionProviders =
        collectionProviders.fold(withProviders) { receiver, provider ->
          irInvoke(
            dispatchReceiver = receiver,
            callee = valueProviderSymbols.setFactoryBuilderAddCollectionProviderFunction,
            typeHint = builder.type,
            args =
              listOf(
                generateBindingCode(
                  provider,
                  accessType = AccessType.PROVIDER,
                  fieldInitKey = fieldInitKey,
                )
              ),
          )
        }

      // .build()
      val instance =
        irInvoke(
          dispatchReceiver = withCollectionProviders,
          callee = valueProviderSymbols.setFactoryBuilderBuildFunction,
          typeHint =
            irBuiltIns.setClass.typeWith(elementType).wrapInProvider(metroSymbols.metroProvider),
        )
      return with(valueProviderSymbols) {
        transformToMetroProvider(instance, irBuiltIns.setClass.typeWith(elementType))
      }
    }

  context(scope: IrBuilderWithScope)
  private fun generateMapMultibindingExpression(
    binding: IrBinding.Multibinding,
    contextualTypeKey: IrContextualTypeKey,
    fieldInitKey: IrTypeKey?,
  ): IrExpression =
    with(scope) {
      // Inline (no chunk) generation; chunk helpers emitted in IrGraphGenerator via field initialization when needed.
      val valueWrappedType = contextualTypeKey.wrappedType.findMapValueType()!!
      val mapTypeArgs = (contextualTypeKey.typeKey.type as IrSimpleType).arguments
      check(mapTypeArgs.size == 2) { "Unexpected map type args: ${mapTypeArgs.joinToString()}" }
      val keyType: IrType = mapTypeArgs[0].typeOrFail
      val rawValueType = mapTypeArgs[1].typeOrFail
      val rawValueTypeMetadata =
        rawValueType.typeOrFail.asContextualTypeKey(
          null,
          hasDefault = false,
          patchMutableCollections = false,
          declaration = binding.declaration,
        )
      val useProviderFactory: Boolean = valueWrappedType is WrappedType.Provider
      val originalType = contextualTypeKey.toIrType()
      val originalValueType = valueWrappedType.toIrType()
      val originalValueContextKey =
        originalValueType.asContextualTypeKey(
          null,
          hasDefault = false,
          patchMutableCollections = false,
          declaration = binding.declaration,
        )
      val valueProviderSymbols = metroSymbols.providerSymbolsFor(originalValueType)
      val valueType: IrType = rawValueTypeMetadata.typeKey.type
      val size = binding.sourceBindings.size
      val mapProviderType =
        irBuiltIns.mapClass
          .typeWith(
            keyType,
            if (useProviderFactory) {
              rawValueType.wrapInProvider(metroSymbols.metroProvider)
            } else {
              rawValueType
            },
          )
          .wrapInProvider(metroSymbols.metroProvider)

      if (size == 0) {
        return if (useProviderFactory) {
          val emptyCallee = valueProviderSymbols.mapProviderFactoryEmptyFunction
          if (emptyCallee != null) {
            irInvoke(callee = emptyCallee, typeHint = mapProviderType)
          } else {
            irInvoke(
              callee = valueProviderSymbols.mapProviderFactoryBuilderBuildFunction,
              typeHint = mapProviderType,
              dispatchReceiver =
                irInvoke(
                  callee = valueProviderSymbols.mapProviderFactoryBuilderFunction,
                  typeHint = mapProviderType,
                  args = listOf(irInt(0)),
                ),
            )
          }
        } else {
          irInvoke(
            callee = valueProviderSymbols.mapFactoryEmptyFunction,
            typeHint = mapProviderType,
          )
        }
      }

      val builderFunction =
        if (useProviderFactory) {
          valueProviderSymbols.mapProviderFactoryBuilderFunction
        } else {
          valueProviderSymbols.mapFactoryBuilderFunction
        }
      val builderType =
        if (useProviderFactory) {
          valueProviderSymbols.mapProviderFactoryBuilder
        } else {
          valueProviderSymbols.mapFactoryBuilder
        }

      val putFunction =
        if (useProviderFactory) {
          valueProviderSymbols.mapProviderFactoryBuilderPutFunction
        } else {
          valueProviderSymbols.mapFactoryBuilderPutFunction
        }

      val buildFunction =
        if (useProviderFactory) {
          valueProviderSymbols.mapProviderFactoryBuilderBuildFunction
        } else {
          valueProviderSymbols.mapFactoryBuilderBuildFunction
        }

      val sourceBindingObjects = binding.sourceBindings.map { bindingGraph.requireBinding(it) }

      fun newBuilder(initialCapacity: Int): IrExpression =
        irInvoke(
          callee = builderFunction,
          typeArgs = listOf(keyType, valueType),
          typeHint = builderType.typeWith(keyType, valueType),
          args = listOf(irInt(initialCapacity)),
        )

      fun addEntries(base: IrExpression, entries: List<IrBinding>): IrExpression =
        entries.fold(base) { receiver, sourceBinding ->
          irInvoke(
            dispatchReceiver = receiver,
            callee = putFunction,
            typeHint = base.type,
            args = listOf(
              generateMapKeyLiteral(sourceBinding),
              generateBindingCode(
                  sourceBinding,
                  accessType = AccessType.PROVIDER,
                  fieldInitKey = fieldInitKey,
                )
                .let {
                  with(valueProviderSymbols) { transformMetroProvider(it, originalValueContextKey) }
                },
            ),
          )
        }

      val builder = newBuilder(size)
      val withEntries = addEntries(builder, sourceBindingObjects)
      val instance =
        irInvoke(
          dispatchReceiver = withEntries,
          callee = buildFunction,
          typeHint = mapProviderType,
        )
      return with(valueProviderSymbols) { transformToMetroProvider(instance, originalType) }
    }

  context(scope: IrBuilderWithScope)
  private fun generateMultibindingArgument(
    provider: IrBinding,
    fieldInitKey: IrTypeKey?,
  ): IrExpression =
    with(scope) {
      val bindingCode =
        generateBindingCode(provider, accessType = AccessType.PROVIDER, fieldInitKey = fieldInitKey)
      return typeAsProviderArgument(
        contextKey = IrContextualTypeKey.create(provider.typeKey),
        bindingCode = bindingCode,
        isAssisted = false,
        isGraphInstance = false,
      )
    }

  // TODO move transformMetroProvider into this too
  context(scope: IrBuilderWithScope)
  private fun IrExpression.transformAccessIfNeeded(
    requested: AccessType,
    actual: AccessType,
    type: IrType,
  ): IrExpression {
    return when (requested) {
      actual -> this
      AccessType.PROVIDER -> {
        // actual is an instance, wrap it
        wrapInInstanceFactory(type)
      }
      AccessType.INSTANCE -> {
        // actual is a provider but we want instance
        unwrapProvider(type)
      }
    }
  }

  context(scope: IrBuilderWithScope)
  private fun IrExpression.wrapInInstanceFactory(type: IrType): IrExpression {
    return with(scope) { instanceFactory(type, this@wrapInInstanceFactory) }
  }

  context(scope: IrBuilderWithScope)
  private fun IrExpression.unwrapProvider(type: IrType): IrExpression {
    return with(scope) {
      irInvoke(this@unwrapProvider, callee = metroSymbols.providerInvoke, typeHint = type)
    }
  }
}
