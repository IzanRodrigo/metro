// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.MetroLogger
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.exitProcessing
import dev.zacsweers.metro.compiler.expectAs
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import dev.zacsweers.metro.compiler.graph.WrappedType
import dev.zacsweers.metro.compiler.graph.sharding.ShardingContext
import dev.zacsweers.metro.compiler.ir.parameters.Parameter
import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.ir.parameters.wrapInProvider
import dev.zacsweers.metro.compiler.ir.setDispatchReceiver
import dev.zacsweers.metro.compiler.ir.thisReceiverOrFail
import dev.zacsweers.metro.compiler.ir.transformers.AssistedFactoryTransformer
import dev.zacsweers.metro.compiler.ir.transformers.BindingContainerTransformer
import dev.zacsweers.metro.compiler.ir.transformers.MembersInjectorTransformer
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.tracing.Tracer
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationBase
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irExprBody
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.parent
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.typeOrFail
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.companionObject
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.isObject
import org.jetbrains.kotlin.ir.util.isStatic
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.util.remapTypes
import org.jetbrains.kotlin.name.Name
import kotlin.io.path.appendText
import kotlin.io.path.createFile
import kotlin.io.path.exists

internal class IrGraphExpressionGenerator
private constructor(
  context: IrMetroContext,
  private val node: DependencyGraphNode,
  private val explicitThisReceiver: IrValueParameter?,  // Optional for non-scope contexts
  private val explicitShardIndex: Int?,  // Optional explicit shard index for chunked contexts
  private val bindingFieldContext: BindingFieldContext,
  private val bindingGraph: IrBindingGraph,
  private val bindingContainerTransformer: BindingContainerTransformer,
  private val membersInjectorTransformer: MembersInjectorTransformer,
  private val assistedFactoryTransformer: AssistedFactoryTransformer,
  private val graphExtensionGenerator: IrGraphExtensionGenerator,
  private val fieldOwnershipRegistry: FieldOwnershipRegistry,
  private val parentTracer: Tracer,
) : IrMetroContext by context {

  private companion object {
    internal const val MULTIBINDING_CHUNK_SIZE = 50
  }

  // Extension property to get the current thisReceiver from the builder scope or explicit receiver
  context(scope: IrBuilderWithScope)
  private val currentThisReceiver: IrValueParameter
    get() = explicitThisReceiver
      ?: (scope.parent as? IrFunction)?.dispatchReceiverParameter
      ?: error("No dispatch receiver in current scope or explicit receiver")


  class Factory(
    private val context: IrMetroContext,
    private val node: DependencyGraphNode,
    private val bindingFieldContext: BindingFieldContext,
    private val bindingGraph: IrBindingGraph,
    private val bindingContainerTransformer: BindingContainerTransformer,
    private val membersInjectorTransformer: MembersInjectorTransformer,
    private val assistedFactoryTransformer: AssistedFactoryTransformer,
    private val graphExtensionGenerator: IrGraphExtensionGenerator,
    private val fieldOwnershipRegistry: FieldOwnershipRegistry,
    private val parentTracer: Tracer,
  ) {
    fun create(
      explicitReceiver: IrValueParameter? = null,
      explicitShardIndex: Int? = null
    ): IrGraphExpressionGenerator {
      return IrGraphExpressionGenerator(
        context = context,
        node = node,
        explicitThisReceiver = explicitReceiver,
        explicitShardIndex = explicitShardIndex,
        bindingFieldContext = bindingFieldContext,
        bindingGraph = bindingGraph,
        bindingContainerTransformer = bindingContainerTransformer,
        membersInjectorTransformer = membersInjectorTransformer,
        assistedFactoryTransformer = assistedFactoryTransformer,
        graphExtensionGenerator = graphExtensionGenerator,
        fieldOwnershipRegistry = fieldOwnershipRegistry,
        parentTracer = parentTracer,
      )
    }
  }

  enum class AccessType {
    INSTANCE,
    PROVIDER,
  }

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

      val metroProviderSymbols = symbols.providerSymbolsFor(contextualTypeKey)

        val shardingContext = bindingFieldContext.shardingContext
        val currentShardClass = bindingFieldContext.currentShardClass
        val currentShardIndex = when {
          // First check for explicit shard index (used in chunked init contexts)
          explicitShardIndex != null -> explicitShardIndex
          // Otherwise try to derive from current shard class
          shardingContext != null && currentShardClass != null -> {
            val index = shardingContext.shardClasses.indexOf(currentShardClass)
            if (index >= 0) index else null
          }
          else -> null
        }
      if (fieldInitKey == null || fieldInitKey != binding.typeKey) {
        bindingFieldContext.providerFieldDescriptor(binding.typeKey)?.let { descriptor ->
          accessProviderField(descriptor, binding, currentShardIndex, shardingContext)?.let { expression ->
            val transformedProvider = expression.let {
              with(metroProviderSymbols) { transformMetroProvider(it, contextualTypeKey) }
            }
            return if (accessType == AccessType.INSTANCE) {
              irInvoke(transformedProvider, callee = symbols.providerInvoke)
            } else {
              transformedProvider
            }
          }
        }
      }

      // CRITICAL: For large multibindings that use chunking, we MUST use a provider field
      // The chunked irBlock expression cannot be executed from a different context (e.g., shards)
      // Check if this is a multibinding that would use chunking
      if (binding is IrBinding.Multibinding && !binding.isSet) {
        val size = binding.sourceBindings.size
        if (size >= MULTIBINDING_CHUNK_SIZE) {
          // This multibinding will use chunking - it MUST have a provider field
          val descriptor = bindingFieldContext.providerFieldDescriptor(binding.typeKey)
          if (descriptor == null && fieldInitKey == null) {
            // Write diagnostic instead of failing to see what's happening
            val typeStr = binding.typeKey.type.render(short = true).replace('.', '-').replace('<', '_').replace('>', '_').replace(',', '_')
            writeDiagnostic("multibinding-missing-field-${typeStr.take(100)}.txt") {
              buildString {
                appendLine("=== CRITICAL: Large Multibinding Missing Provider Field ===")
                appendLine("Type: ${binding.typeKey}")
                appendLine("Size: ${size} elements")
                appendLine("Field init key: $fieldInitKey")
                appendLine("Access type: $accessType")
                appendLine("Current class: ${currentThisReceiver.type.render(short = true)}")
                appendLine()
                appendLine("This large multibinding requires a provider field but none was found.")
                appendLine("This will cause runtime failures when accessed from shards.")
                appendLine()
                appendLine("Binding field context state:")
                appendLine("  Has provider field: ${bindingFieldContext.providerFieldDescriptor(binding.typeKey) != null}")
                appendLine("  Has instance field: ${bindingFieldContext.instanceFieldDescriptor(binding.typeKey) != null}")
                appendLine()
                // Note: Can't access private fields, so we'll just check if fields exist
                appendLine("  Is type key in context: ${binding.typeKey in bindingFieldContext}")
              }
            }
            // Don't fail compilation - let it continue so we can see the generated code
          }
        }
      }

      return when (binding) {
        is IrBinding.ConstructorInjected -> {
          // Example_Factory.create(...)
          val factory = binding.classFactory

          with(factory) {
            invokeCreateExpression { createFunction ->
              val remapper = createFunction.typeRemapperFor(binding.typeKey.type)
              generateBindingArguments(
                targetParams = createFunction.parameters(remapper = remapper),
                function =
                  createFunction.deepCopyWithSymbols(initialParent = createFunction.parent).also {
                    it.parent = createFunction.parent
                    it.remapTypes(remapper)
                  },
                binding = binding,
                fieldInitKey = null,
              )
            }
          }
        }

        is IrBinding.ObjectClass -> {
          instanceFactory(binding.typeKey.type, irGetObject(binding.type.symbol))
        }

        is IrBinding.Alias -> {
          // For binds functions, just use the backing type
          val aliasedBinding = binding.aliasedBinding(bindingGraph, IrBindingStack.empty())
          check(aliasedBinding != binding) { "Aliased binding aliases itself" }
          return generateBindingCode(
            aliasedBinding,
            accessType = accessType,
            fieldInitKey = fieldInitKey,
          )
        }

        is IrBinding.Provided -> {
          val factoryClass =
            bindingContainerTransformer.getOrLookupProviderFactory(binding)?.clazz
              ?: reportCompilerBug(
                "No factory found for Provided binding ${binding.typeKey}. This is likely a bug in the Metro compiler, please report it to the issue tracker."
              )

          // Invoke its factory's create() function
          val creatorClass =
            if (factoryClass.isObject) {
              factoryClass
            } else {
              factoryClass.companionObject()!!
            }
          val createFunction = creatorClass.requireSimpleFunction(Symbols.StringNames.CREATE)
          // Must use the provider's params for IrTypeKey as that has qualifier
          // annotations
          val args =
            generateBindingArguments(
              targetParams = binding.parameters,
              function = createFunction.owner,
              binding = binding,
              fieldInitKey = fieldInitKey,
            )
          irInvoke(
            dispatchReceiver = irGetObject(creatorClass.symbol),
            callee = createFunction,
            args = args,
          )
        }

        is IrBinding.Assisted -> {
          // Example9_Factory_Impl.create(example9Provider);
          val factoryImpl = assistedFactoryTransformer.getOrGenerateImplClass(binding.type)

          val targetBinding =
            bindingGraph.requireBinding(binding.target.typeKey, IrBindingStack.empty())
          val delegateFactoryProvider = generateBindingCode(targetBinding, accessType = accessType)

          with(factoryImpl) { invokeCreate(delegateFactoryProvider) }
        }

        is IrBinding.Multibinding -> {
          generateMultibindingExpression(binding, contextualTypeKey, fieldInitKey)
        }

        is IrBinding.MembersInjected -> {
          val injectedClass = referenceClass(binding.targetClassId)!!.owner
          val injectedType = injectedClass.defaultType
          val injectorClass = membersInjectorTransformer.getOrGenerateInjector(injectedClass)?.ir

          if (injectorClass == null) {
            // Return a noop
            val noopInjector =
              irInvoke(
                dispatchReceiver = irGetObject(symbols.metroMembersInjectors),
                callee = symbols.metroMembersInjectorsNoOp,
                typeArgs = listOf(injectedType),
              )
            instanceFactory(noopInjector.type, noopInjector).let {
              with(metroProviderSymbols) { transformMetroProvider(it, contextualTypeKey) }
            }
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
            instanceFactory(
                injectedType,
                // InjectableClass_MembersInjector.create(stringValueProvider,
                // exampleComponentProvider)
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
                ),
              )
              .let { with(metroProviderSymbols) { transformMetroProvider(it, contextualTypeKey) } }
          }
        }

        is IrBinding.Absent -> {
          // Should never happen, this should be checked before function/constructor injections.
          reportCompilerBug("Unable to generate code for unexpected Absent binding: $binding")
        }

        is IrBinding.BoundInstance -> {
          binding.classReceiverParameter?.let { receiver ->
            return when (accessType) {
              AccessType.INSTANCE -> irGet(receiver)
              AccessType.PROVIDER ->
                irGetField(irGet(receiver), binding.providerFieldAccess!!.field)
            }
          }

          val shardingContext = bindingFieldContext.shardingContext
          val currentShardClass = bindingFieldContext.currentShardClass
          val currentShardIndex = if (shardingContext != null && currentShardClass != null) {
            val index = shardingContext.shardClasses.indexOf(currentShardClass)
            if (index >= 0) index else null
          } else {
            null
          }

          val descriptor = (
            bindingFieldContext.providerFieldDescriptor(binding.typeKey)
              ?: bindingFieldContext.instanceFieldDescriptor(binding.typeKey)
          ) ?: reportCompilerBug(
            "Unable to locate field for BoundInstance ${binding.typeKey}.",
          )

          val fieldAccess = accessProviderField(
            descriptor,
            binding,
            currentShardIndex,
            shardingContext,
          ) ?: reportCompilerBug(
            "Unable to access field for BoundInstance ${binding.typeKey} (owner=${descriptor.owner}).",
          )

          val isProviderField = descriptor.field.type.classOrNull == symbols.metroProvider.owner

          return when (accessType) {
            AccessType.INSTANCE ->
              if (isProviderField) {
                val providerExpression =
                  with(metroProviderSymbols) { transformMetroProvider(fieldAccess, contextualTypeKey) }
                irInvoke(providerExpression, callee = symbols.providerInvoke)
              } else {
                fieldAccess
              }

            AccessType.PROVIDER ->
              if (isProviderField) {
                with(metroProviderSymbols) { transformMetroProvider(fieldAccess, contextualTypeKey) }
              } else {
                instanceFactory(binding.typeKey.type, fieldAccess)
              }
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

          val ctor = extensionImpl.primaryConstructor!!
          val instanceExpression =
            irCallConstructor(ctor.symbol, node.sourceGraph.typeParameters.map { it.defaultType })
              .apply {
                // If this function has parameters, they're factory instance params and need to be
                // passed on
                val functionParams = binding.accessor.regularParameters

                // First param (dispatch receiver) is always the parent graph
                arguments[0] = irGet(currentThisReceiver)
                for (i in 0 until functionParams.size) {
                  arguments[i + 1] = irGet(functionParams[i])
                }
              }
          when (accessType) {
            AccessType.INSTANCE -> {
              // Already not a provider
              instanceExpression
            }
            AccessType.PROVIDER -> {
              instanceFactory(binding.typeKey.type, instanceExpression)
            }
          }
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
          val factoryInstance =
            irCallConstructor(
                constructor.symbol,
                binding.accessor.typeParameters.map { it.defaultType },
              )
              .apply {
                // Pass the parent graph instance
                arguments[0] =
                  generateBindingCode(
                    bindingGraph.requireBinding(
                      parameters.regularParameters.single().typeKey,
                      IrBindingStack.empty(),
                    ),
                    accessType = AccessType.INSTANCE,
                  )
              }

          when (accessType) {
            AccessType.INSTANCE -> {
              // Factories are not providers, return directly
              factoryInstance
            }
            AccessType.PROVIDER -> {
              // Wrap in an instance factory
              instanceFactory(binding.typeKey.type, factoryInstance)
            }
          }
        }

        is IrBinding.GraphDependency -> {
          val ownerKey = binding.ownerKey
          if (binding.fieldAccess != null) {
            // Just get the field
            irGetField(irGet(binding.fieldAccess.receiverParameter), binding.fieldAccess.field)
          } else if (binding.getter != null) {
            val graphInstanceField =
              bindingFieldContext.instanceField(ownerKey)
                ?: reportCompilerBug(
                  "No matching included type instance found for type $ownerKey while processing ${node.typeKey}. Available instance fields ${bindingFieldContext.availableInstanceKeys}"
                )

            val getterContextKey = IrContextualTypeKey.from(binding.getter)

            val invokeGetter =
              irInvoke(
                dispatchReceiver = irGetField(irGet(currentThisReceiver), graphInstanceField),
                callee = binding.getter.symbol,
                typeHint = binding.typeKey.type,
              )

            if (getterContextKey.isLazyWrappedInProvider) {
              // TODO FIR this
              reportCompat(
                binding.getter,
                MetroDiagnostics.METRO_ERROR,
                "Provider<Lazy<T>> accessors are not supported.",
              )
              exitProcessing()
            } else if (getterContextKey.isWrappedInProvider) {
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
                      irInvoke(invokeGetter, callee = symbols.providerInvoke)
                    } else if (getterContextKey.isWrappedInLazy) {
                      irInvoke(invokeGetter, callee = symbols.lazyGetValue)
                    } else {
                      invokeGetter
                    }
                  +irReturn(returnExpression)
                }
              irInvoke(
                dispatchReceiver = null,
                callee = symbols.metroProviderFunction,
                typeHint = binding.typeKey.type.wrapInProvider(symbols.metroProvider),
                typeArgs = listOf(binding.typeKey.type),
                args = listOf(lambda),
              )
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
      // Diagnostic removed - binding-arguments reports no longer generated
      val params = function.parameters()
      // TODO only value args are supported atm
      var paramsToMap = buildList {
        if (
          binding is IrBinding.Provided &&
            targetParams.dispatchReceiverParameter?.type?.rawTypeOrNull()?.isObject != true
        ) {
          targetParams.dispatchReceiverParameter?.let(::add)
        }
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

      return params.regularParameters.mapIndexed { i, param ->
        val contextualTypeKey = paramsToMap[i].contextualTypeKey
        val typeKey = contextualTypeKey.typeKey

        val accessType =
          if (param.contextualTypeKey.requiresProviderInstance) {
            AccessType.PROVIDER
          } else {
            AccessType.INSTANCE
          }

        val shardingContext = bindingFieldContext.shardingContext
        val currentShardClass = bindingFieldContext.currentShardClass
        val currentShardIndex = if (shardingContext != null && currentShardClass != null) {
          val index = shardingContext.shardClasses.indexOf(currentShardClass)
          if (index >= 0) index else null
        } else {
          null
        }

        val instanceField =
          if (accessType == AccessType.INSTANCE) bindingFieldContext.instanceField(typeKey) else null

        val providerExpression: IrExpression = when {
          instanceField != null -> irGetField(irGet(currentThisReceiver), instanceField)
          else -> {
            val paramBinding = bindingGraph.requireBinding(contextualTypeKey, IrBindingStack.empty())

            if (paramBinding is IrBinding.Absent) {
              return@mapIndexed null
            }

            // For multibindings, always try to use the field if it exists
            // This ensures proper initialization and avoids null values
            val descriptor = bindingFieldContext.providerFieldDescriptor(typeKey)
            val viaDescriptor = descriptor?.let {
              accessProviderField(it, paramBinding, currentShardIndex, shardingContext)
            }

            viaDescriptor ?: run {
              // Only fall back to inline generation if there's truly no field
              // This should be rare for multibindings after our ProviderFieldCollector fix
              generateBindingCode(
                paramBinding,
                fieldInitKey = fieldInitKey,
                accessType = accessType,
                contextualTypeKey = param.contextualTypeKey,
              )
            }
          }
        }

        typeAsProviderArgument(
          param.contextualTypeKey,
          providerExpression,
          isAssisted = param.isAssisted,
          isGraphInstance = param.isGraphInstance,
        )
      }
    }

  private fun generateMapKeyLiteral(binding: IrBinding): IrExpression {
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
        .map {
          bindingGraph
            .requireBinding(it, IrBindingStack.empty())
            .expectAs<IrBinding.BindingWithAnnotations>()
        }
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
          callee = symbols.emptySet
          args = emptyList()
        }

        1 -> {
          // setOf(<one>)
          callee = symbols.setOfSingleton
          val provider =
            binding.sourceBindings.first().let {
              bindingGraph.requireBinding(it, IrBindingStack.empty())
            }
          args = listOf(generateMultibindingArgument(provider, fieldInitKey))
        }

        else -> {
          // buildSet(<size>) { ... }
          callee = symbols.buildSetWithCapacity
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
                  .map { bindingGraph.requireBinding(it, IrBindingStack.empty()) }
                  .forEach { provider ->
                    +irInvoke(
                      dispatchReceiver = irGet(functionReceiver),
                      callee = symbols.mutableSetAdd.symbol,
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
      val valueProviderSymbols = symbols.providerSymbolsFor(elementType)

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
          typeHint = irBuiltIns.setClass.typeWith(elementType).wrapInProvider(symbols.metroProvider),
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
      // MapFactory.<Integer, Integer>builder(2)
      //   .put(1, FileSystemModule_Companion_ProvideMapInt1Factory.create())
      //   .put(2, provideMapInt2Provider)
      //   .build()
      // MapProviderFactory.<Integer, Integer>builder(2)
      //   .put(1, FileSystemModule_Companion_ProvideMapInt1Factory.create())
      //   .put(2, provideMapInt2Provider)
      //   .build()
      val valueWrappedType = contextualTypeKey.wrappedType.findMapValueType()!!

      val mapTypeArgs = (contextualTypeKey.typeKey.type as IrSimpleType).arguments
      check(mapTypeArgs.size == 2) { "Unexpected map type args: ${mapTypeArgs.joinToString()}" }
      val keyType: IrType = mapTypeArgs[0].typeOrFail
      val rawValueType = mapTypeArgs[1].typeOrFail
      val rawValueTypeMetadata =
        rawValueType.typeOrFail.asContextualTypeKey(null, hasDefault = false)

      // TODO what about Map<String, Provider<Lazy<String>>>?
      //  isDeferrable() but we need to be able to convert back to the middle type
      val useProviderFactory: Boolean = valueWrappedType is WrappedType.Provider

      // Used to unpack the right provider type
      val originalType = contextualTypeKey.toIrType()
      val originalValueType = valueWrappedType.toIrType()
      val originalValueContextKey = originalValueType.asContextualTypeKey(null, hasDefault = false)
      val valueProviderSymbols = symbols.providerSymbolsFor(originalValueType)

      val valueType: IrType = rawValueTypeMetadata.typeKey.type

      val size = binding.sourceBindings.size
      val mapProviderType =
        irBuiltIns.mapClass
          .typeWith(
            keyType,
            if (useProviderFactory) {
              rawValueType.wrapInProvider(symbols.metroProvider)
            } else {
              rawValueType
            },
          )
          .wrapInProvider(symbols.metroProvider)

      if (size == 0) {
        // If it's empty then short-circuit here
        return if (useProviderFactory) {
          // MapProviderFactory.empty()
          val emptyCallee = valueProviderSymbols.mapProviderFactoryEmptyFunction
          if (emptyCallee != null) {
            irInvoke(callee = emptyCallee, typeHint = mapProviderType)
          } else {
            // Call builder().build()
            // build()
            irInvoke(
              callee = valueProviderSymbols.mapProviderFactoryBuilderBuildFunction,
              typeHint = mapProviderType,
              // builder()
              dispatchReceiver =
                irInvoke(
                  callee = valueProviderSymbols.mapProviderFactoryBuilderFunction,
                  typeHint = mapProviderType,
                  args = listOf(irInt(0)),
                ),
            )
          }
        } else {
          // MapFactory.empty()
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

      // MapFactory.<Integer, Integer>builder(2)
      // MapProviderFactory.<Integer, Integer>builder(2)
      val putFunction =
        if (useProviderFactory) {
          valueProviderSymbols.mapProviderFactoryBuilderPutFunction
        } else {
          valueProviderSymbols.mapFactoryBuilderPutFunction
        }
      val putAllFunction =
        if (useProviderFactory) {
          valueProviderSymbols.mapProviderFactoryBuilderPutAllFunction
        } else {
          valueProviderSymbols.mapFactoryBuilderPutAllFunction
        }

      val sourceBindings =
        binding.sourceBindings.map { bindingGraph.requireBinding(it, IrBindingStack.empty()) }

      if (sourceBindings.size < MULTIBINDING_CHUNK_SIZE) {
        val builder: IrExpression =
          irInvoke(
            callee = builderFunction,
            typeArgs = listOf(keyType, valueType),
            typeHint = builderType.typeWith(keyType, valueType),
            args = listOf(irInt(size)),
          )

        val withProviders =
          sourceBindings.fold(builder) { receiver, sourceBinding ->
            val providerTypeMetadata = sourceBinding.contextualTypeKey
            val isMap = providerTypeMetadata.typeKey.type.rawType().symbol == irBuiltIns.mapClass

            if (isMap) {
              val mapArgument =
                generateBindingCode(
                  sourceBinding,
                  accessType = AccessType.PROVIDER,
                  fieldInitKey = fieldInitKey,
                ).let {
                  with(valueProviderSymbols) { transformMetroProvider(it, providerTypeMetadata) }
                }
              irInvoke(
                dispatchReceiver = receiver,
                callee = putAllFunction,
                typeHint = builder.type,
                args = listOf(mapArgument),
              )
            } else {
              irInvoke(
                dispatchReceiver = receiver,
                callee = putFunction,
                typeHint = builder.type,
                args =
                  listOf(
                    generateMapKeyLiteral(sourceBinding),
                    generateBindingCode(
                        sourceBinding,
                        accessType = AccessType.PROVIDER,
                        fieldInitKey = fieldInitKey,
                      )
                      .let {
                        with(valueProviderSymbols) {
                          transformMetroProvider(it, originalValueContextKey)
                        }
                      },
                  ),
              )
            }
          }

        // .build()
        val buildFunction =
          if (useProviderFactory) {
            valueProviderSymbols.mapProviderFactoryBuilderBuildFunction
          } else {
            valueProviderSymbols.mapFactoryBuilderBuildFunction
          }

        val instance =
          irInvoke(
            dispatchReceiver = withProviders,
            callee = buildFunction,
            typeHint = mapProviderType,
          )
        return with(valueProviderSymbols) { transformToMetroProvider(instance, originalType) }
      }

      // Chunked path to keep methods small
      val targetClass =
        currentThisReceiver.enclosingClassOrNull()
          ?: error("Expected this receiver to belong to a class context for ${binding.nameHint}")

      // CRITICAL FIX: For chunked multibindings accessed from shards, we need to ensure
      // the field is properly initialized rather than returning an inline block
      // Check if we're in a context where this might be accessed from a shard
      val shardingContext = bindingFieldContext.shardingContext
      val currentShardClass = bindingFieldContext.currentShardClass
      val currentShardIndex = when {
        // First check for explicit shard index
        explicitShardIndex != null -> explicitShardIndex
        // Otherwise try to derive from current shard class
        shardingContext != null && currentShardClass != null -> {
          val index = shardingContext.shardClasses.indexOf(currentShardClass)
          if (index >= 0) index else null
        }
        else -> null
      }

      val isAccessibleFromShard = shardingContext != null &&
                                   fieldInitKey == null // Not initializing the field itself

      if (isAccessibleFromShard) {
        // If this chunked multibinding might be accessed from a shard,
        // we should use the provider field instead of generating inline
        val descriptor = bindingFieldContext.providerFieldDescriptor(binding.typeKey)
        if (descriptor != null) {
          // Access the field instead of generating inline
          val fieldAccess = accessProviderField(descriptor, binding,
            currentShardIndex, shardingContext)
          if (fieldAccess != null) {
            return with(valueProviderSymbols) {
              transformToMetroProvider(fieldAccess, originalType)
            }
          }
        }
      }

      val builderTypeWithArgs = builderType.typeWith(keyType, valueType)
      val chunkFunctions = sourceBindings
        .chunked(MULTIBINDING_CHUNK_SIZE)
        .mapIndexed { chunkIndex, chunkBindings ->
          createMapMultibindingChunkFunction(
            targetClass = targetClass,
            binding = binding,
            chunkIndex = chunkIndex,
            chunkBindings = chunkBindings,
            builderType = builderTypeWithArgs,
            keyType = keyType,
            valueType = valueType,
            putFunction = putFunction,
            putAllFunction = putAllFunction,
            fieldInitKey = fieldInitKey,
            valueProviderSymbols = valueProviderSymbols,
            originalValueContextKey = originalValueContextKey,
          )
        }

      // .build()
      val buildFunction =
        if (useProviderFactory) {
          valueProviderSymbols.mapProviderFactoryBuilderBuildFunction
        } else {
          valueProviderSymbols.mapFactoryBuilderBuildFunction
        }

      return irBlock(resultType = mapProviderType) {
        val initialBuilder =
          irTemporary(
            irInvoke(
              callee = builderFunction,
              typeArgs = listOf(keyType, valueType),
              typeHint = builderTypeWithArgs,
              args = listOf(irInt(size)),
            ),
            nameHint = "${binding.nameHint.decapitalizeUS()}Builder",
          )

        var currentBuilder: IrExpression = irGet(initialBuilder)
        chunkFunctions.forEachIndexed { chunkIndex, chunkFunction ->
          val chunkResult =
            irTemporary(
              irInvoke(
                dispatchReceiver = irGet(currentThisReceiver),
                callee = chunkFunction.symbol,
                typeHint = builderTypeWithArgs,
                args = listOf(currentBuilder),
              ),
              nameHint = "${binding.nameHint.decapitalizeUS()}Chunk${chunkIndex}Builder",
            )
          currentBuilder = irGet(chunkResult)
        }

        val built =
          irInvoke(
            dispatchReceiver = currentBuilder,
            callee = buildFunction,
            typeHint = mapProviderType,
          )

        with(valueProviderSymbols) { transformToMetroProvider(built, originalType) }
      }
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

  private fun createMapMultibindingChunkFunction(
    targetClass: IrClass,
    binding: IrBinding.Multibinding,
    chunkIndex: Int,
    chunkBindings: List<IrBinding>,
    builderType: IrType,
    keyType: IrType,
    valueType: IrType,
    putFunction: IrSimpleFunctionSymbol,
    putAllFunction: IrSimpleFunctionSymbol,
    fieldInitKey: IrTypeKey?,
    valueProviderSymbols: Symbols.ProviderSymbols,
    originalValueContextKey: IrContextualTypeKey,
  ): IrSimpleFunction {
    val functionName =
      "${binding.nameHint.decapitalizeUS()}Chunk$chunkIndex".asName()

    val existing =
      targetClass.declarations
        .filterIsInstance<IrSimpleFunction>()
        .firstOrNull { it.name == functionName }
    if (existing != null) {
      return existing
    }

    val function = targetClass.addFunction {
      name = functionName
      returnType = builderType
      visibility = DescriptorVisibilities.PRIVATE
    }

    val builderParam = function.addValueParameter("builder".asName(), builderType)

    val dispatchReceiver = targetClass.thisReceiverOrFail.copyTo(function)
    function.setDispatchReceiver(dispatchReceiver)

    val chunkGenerator =
      IrGraphExpressionGenerator(
        context = this,
        node = node,
        explicitThisReceiver = null,  // Will use scope's receiver in chunk function
        explicitShardIndex = explicitShardIndex,  // Preserve shard index for chunked context
        bindingFieldContext = bindingFieldContext,
        bindingGraph = bindingGraph,
        bindingContainerTransformer = bindingContainerTransformer,
        membersInjectorTransformer = membersInjectorTransformer,
        assistedFactoryTransformer = assistedFactoryTransformer,
        graphExtensionGenerator = graphExtensionGenerator,
        fieldOwnershipRegistry = fieldOwnershipRegistry,
        parentTracer = parentTracer,
      )

    val previousShardClass = bindingFieldContext.currentShardClass
    bindingFieldContext.currentShardClass = targetClass
    try {
      function.body = createIrBuilder(function.symbol).run {
        val builderScope = this
        var currentBuilder: IrExpression = irGet(builderParam)
        for (sourceBinding in chunkBindings) {
          val providerTypeMetadata = sourceBinding.contextualTypeKey
          val isMap = providerTypeMetadata.typeKey.type.rawType().symbol == irBuiltIns.mapClass

          val receiver = currentBuilder
          currentBuilder = chunkGenerator.run {
            if (isMap) {
              val mapArgument =
                generateBindingCode(
                  sourceBinding,
                  accessType = AccessType.PROVIDER,
                  fieldInitKey = fieldInitKey,
                ).let {
                  with(valueProviderSymbols) { transformMetroProvider(it, providerTypeMetadata) }
                }
              builderScope.irInvoke(
                dispatchReceiver = receiver,
                callee = putAllFunction,
                typeHint = builderType,
                args = listOf(mapArgument),
              )
            } else {
              builderScope.irInvoke(
                dispatchReceiver = receiver,
                callee = putFunction,
                typeHint = builderType,
                args =
                  listOf(
                    generateMapKeyLiteral(sourceBinding),
                    generateBindingCode(
                        sourceBinding,
                        accessType = AccessType.PROVIDER,
                        fieldInitKey = fieldInitKey,
                      )
                      .let {
                        with(valueProviderSymbols) {
                          transformMetroProvider(it, originalValueContextKey)
                        }
                      },
                  ),
              )
            }
          }
        }

        irExprBody(currentBuilder)
      }
    } finally {
      bindingFieldContext.currentShardClass = previousShardClass
    }

    return function
  }


  private fun IrBuilderWithScope.accessProviderField(
    descriptor: BindingFieldContext.FieldDescriptor,
    binding: IrBinding,
    currentShardIndex: Int?,
    shardingContext: ShardingContext?,
  ): IrExpression? {
    val field = descriptor.field
    return when (val owner = descriptor.owner) {
      BindingFieldContext.FieldOwner.MainGraph -> {
        if (currentShardIndex != null && shardingContext != null) {
          val outerField = shardingContext.outerFields[currentShardIndex] ?: return null
          val outerRef = irGetField(irGet(currentThisReceiver), outerField)
          irGetField(outerRef, field)
        } else {
          irGetField(irGet(currentThisReceiver), field)
        }
      }
      is BindingFieldContext.FieldOwner.Shard -> {
        val context = shardingContext ?: return null
        val actualIndex = context.shardIndexMapping[owner.index] ?: owner.index
        return when {
          currentShardIndex != null && owner.index == currentShardIndex -> {
            // Same shard - direct field access
            irGetField(irGet(currentThisReceiver), field)
          }
          currentShardIndex != null -> {
            // CRITICAL FIX: Check if this is a requirement field first
            // If the current shard has this dependency as a requirement field
            // (passed through constructor), use that instead of accessing other shards
            val requirementField = context.requirementFields[currentShardIndex]?.get(binding.typeKey)
            if (requirementField != null) {
              // Use the local requirement field that was passed through constructor
              return irGetField(irGet(currentThisReceiver), requirementField)
            }

            // Fallback to accessing through graph.shardX pattern
            // This should only happen for bindings that weren't identified as requirements
            val outerField = context.outerFields[currentShardIndex] ?: return null
            val outerRef = irGetField(irGet(currentThisReceiver), outerField)
            val shardField = context.shardFields[actualIndex] ?: return null
            val shardInstance = irGetField(outerRef, shardField)
            irGetField(shardInstance, field)
          }
          else -> {
            // Main graph accessing shard
            val shardField = context.shardFields[actualIndex] ?: return null
            val shardInstance = irGetField(irGet(currentThisReceiver), shardField)
            irGetField(shardInstance, field)
          }
        }
      }
      is BindingFieldContext.FieldOwner.Unknown -> null
    }
  }

  private fun IrValueParameter.enclosingClassOrNull(): IrClass? {
    val parent = parent
    return when (parent) {
      is IrClass -> parent
      is IrFunction -> parent.parent as? IrClass
      else -> null
    }
  }

  private fun String.decapitalizeUS(): String {
    return if (isNotEmpty()) {
      this[0].lowercaseChar() + substring(1)
    } else {
      this
    }
  }
}
