// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.METRO_VERSION
import dev.zacsweers.metro.compiler.NameAllocator
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.decapitalizeUS
import dev.zacsweers.metro.compiler.expectAs
import dev.zacsweers.metro.compiler.ir.generateDefaultConstructorBody
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.ir.parameters.wrapInProvider
import dev.zacsweers.metro.compiler.ir.transformers.AssistedFactoryTransformer
import dev.zacsweers.metro.compiler.ir.transformers.BindingContainerTransformer
import dev.zacsweers.metro.compiler.ir.transformers.MembersInjectorTransformer
import dev.zacsweers.metro.compiler.isGeneratedGraph
import dev.zacsweers.metro.compiler.letIf
import dev.zacsweers.metro.compiler.proto.MetroMetadata
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.suffixIfNot
import dev.zacsweers.metro.compiler.tracing.Tracer
import dev.zacsweers.metro.compiler.tracing.traceNested
import dev.zacsweers.metro.compiler.graph.computeStronglyConnectedComponents
import java.util.LinkedHashMap
import java.util.SortedMap
import java.util.SortedSet
import java.util.TreeMap
import java.util.TreeSet
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.DescriptorVisibility
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addField
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irExprBody
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irSetField
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrOverridableDeclaration
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.typeOrFail
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.util.propertyIfAccessor
import org.jetbrains.kotlin.ir.util.statements
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import kotlin.collections.ArrayDeque

internal typealias BasicFieldInitializer =
  IrBuilderWithScope.(thisReceiver: IrValueParameter, key: IrTypeKey) -> IrExpression

internal typealias FieldInitializer =
  IrBuilderWithScope.(
    componentReceiver: IrValueParameter,
    ownerReceiver: IrValueParameter,
    key: IrTypeKey,
  ) -> IrExpression

internal class IrGraphGenerator(
  metroContext: IrMetroContext,
  private val dependencyGraphNodesByClass: (ClassId) -> DependencyGraphNode?,
  private val node: DependencyGraphNode,
  private val graphClass: IrClass,
  private val bindingGraph: IrBindingGraph,
  private val sealResult: IrBindingGraph.BindingGraphResult,
  private val fieldNameAllocator: NameAllocator,
  private val parentTracer: Tracer,
  // TODO move these accesses to irAttributes
  bindingContainerTransformer: BindingContainerTransformer,
  private val membersInjectorTransformer: MembersInjectorTransformer,
  assistedFactoryTransformer: AssistedFactoryTransformer,
  graphExtensionGenerator: IrGraphExtensionGenerator,
  // Optional pre-created binding field context for use by graph extensions
  private val bindingFieldContext: BindingFieldContext = BindingFieldContext(),
) : IrMetroContext by metroContext {

  private sealed interface FieldOwner {
    data object Root : FieldOwner
    data class Shard(val index: Int) : FieldOwner
  }

  private data class FieldBinding(
    val field: IrField,
    val typeKey: IrTypeKey,
    var owner: FieldOwner = FieldOwner.Root,
    val initializer: FieldInitializer,
  )

  private data class ShardInfo(
    val index: Int,
    val shardClass: IrClass,
    val instanceField: IrField,
    val initializeFunction: IrSimpleFunction,
    val bindings: List<FieldBinding>,
    val instantiateStatement: IrBuilderWithScope.(thisReceiver: IrValueParameter) -> IrStatement,
    val initializeStatement: IrBuilderWithScope.(thisReceiver: IrValueParameter) -> IrStatement,
  )

  /**
   * To avoid `MethodTooLargeException`, we split field initializations up over multiple constructor
   * inits.
   *
   * @see <a href="https://github.com/ZacSweers/metro/issues/645">#645</a>
   */
  private val fieldBindings = mutableListOf<FieldBinding>()
  private val fieldsToTypeKeys = mutableMapOf<IrField, IrTypeKey>()
  private val fastInitEnabled = metroContext.fastInit
  private val expressionGeneratorFactory =
    IrGraphExpressionGenerator.Factory(
      context = this,
      node = node,
      bindingFieldContext = bindingFieldContext,
      bindingGraph = bindingGraph,
      bindingContainerTransformer = bindingContainerTransformer,
      membersInjectorTransformer = membersInjectorTransformer,
      assistedFactoryTransformer = assistedFactoryTransformer,
      graphExtensionGenerator = graphExtensionGenerator,
      parentTracer = parentTracer,
    )
  private val switchingProviderGenerator =
    if (fastInitEnabled) {
      SwitchingProviderGenerator(
        context = this,
        graphClass = graphClass,
        fieldNameAllocator = fieldNameAllocator,
        expressionGeneratorFactory = expressionGeneratorFactory,
      )
    } else {
      null
    }
  private fun partitionFieldInitializers(): List<List<FieldBinding>> {
    if (fieldBindings.isEmpty()) {
      return emptyList()
    }
    val keyOrder = sealResult.sortedKeys.withIndex().associate { (index, key) -> key to index }
    val orderedBindings =
      fieldBindings
        .withIndex()
        .sortedWith(
          compareBy(
            { indexed ->
              keyOrder[fieldsToTypeKeys.getValue(indexed.value.field)] ?: Int.MAX_VALUE
            },
            { indexed -> indexed.index },
          )
        )
        .map { it.value }

    if (orderedBindings.isEmpty()) {
      return listOf(emptyList())
    }

    val options = metroContext.options
    if (!options.enableComponentSharding) {
      return listOf(orderedBindings)
    }

    val maxBindingsPerShard = options.keysPerGraphShard
    if (maxBindingsPerShard <= 0 || orderedBindings.size <= maxBindingsPerShard) {
      return listOf(orderedBindings)
    }

    val bindingByKey = orderedBindings.associateBy { it.typeKey }
    val adjacency: SortedMap<IrTypeKey, SortedSet<IrTypeKey>> = TreeMap()
    for (binding in orderedBindings) {
      adjacency[binding.typeKey] = TreeSet()
    }
    for (binding in orderedBindings) {
      val dependencies =
        bindingGraph.requireBinding(binding.typeKey).dependencies
          .map { it.typeKey }
          .filter { it in bindingByKey }
      adjacency.getValue(binding.typeKey).addAll(dependencies)
    }
    val (components, componentOf) = adjacency.computeStronglyConnectedComponents()
    // componentOf should contain every key; if not, fall back to unique ids
    val componentIdForKey: (IrTypeKey) -> Int = { key ->
      componentOf[key] ?: components.size + keyOrder.getOrElse(key) { key.hashCode() }
    }

    val componentsInOrder = mutableListOf<List<FieldBinding>>()
    var index = 0
    while (index < orderedBindings.size) {
      val startBinding = orderedBindings[index]
      val componentId = componentIdForKey(startBinding.typeKey)
      val group = mutableListOf<FieldBinding>()
      while (index < orderedBindings.size) {
        val currentBinding = orderedBindings[index]
        if (componentIdForKey(currentBinding.typeKey) != componentId) {
          break
        }
        group += currentBinding
        index++
      }
      if (group.isNotEmpty()) {
        componentsInOrder += group
      }
    }

    val shards = mutableListOf<MutableList<FieldBinding>>()
    var currentShard = mutableListOf<FieldBinding>()
    var currentSize = 0
    for (componentBindings in componentsInOrder) {
      if (currentShard.isNotEmpty() && currentSize + componentBindings.size > maxBindingsPerShard) {
        shards += currentShard
        currentShard = mutableListOf()
        currentSize = 0
      }
      currentShard.addAll(componentBindings)
      currentSize += componentBindings.size
    }
    if (currentShard.isNotEmpty()) {
      shards += currentShard
    }

    return if (shards.size <= 1) {
      listOf(orderedBindings)
    } else {
      shards.map { it.toList() }
    }
  }

  private fun registerFieldInitializer(
    field: IrField,
    typeKey: IrTypeKey,
    init: FieldInitializer,
  ) {
    fieldsToTypeKeys[field] = typeKey
    fieldBindings += FieldBinding(field, typeKey, FieldOwner.Root, init)
  }

  fun IrField.withInit(typeKey: IrTypeKey, init: BasicFieldInitializer): IrField = apply {
    registerFieldInitializer(
      field = this,
      typeKey = typeKey,
      init = { componentReceiver, _, key -> init(componentReceiver, key) },
    )
  }

  fun IrField.withInit(typeKey: IrTypeKey, init: FieldInitializer): IrField = apply {
    registerFieldInitializer(this, typeKey, init)
  }

  fun IrField.initFinal(body: IrBuilderWithScope.() -> IrExpression): IrField = apply {
    isFinal = true
    initializer = createIrBuilder(symbol).run { irExprBody(body()) }
  }

  /**
   * Graph extensions may reserve field names for their linking, so if they've done that we use the
   * precomputed field rather than generate a new one.
   */
  private inline fun IrClass.getOrCreateBindingField(
    key: IrTypeKey,
    name: () -> String,
    type: () -> IrType,
    visibility: DescriptorVisibility = DescriptorVisibilities.PRIVATE,
  ): IrField {
    return bindingGraph.reservedField(key)?.field?.also { addChild(it) }
      ?: addField(
        fieldName = fieldNameAllocator.newName(name()),
        fieldType = type(),
        fieldVisibility = visibility,
      )
  }

  fun generate() =
    with(graphClass) {
      val ctor = primaryConstructor!!

      val constructorStatements =
        mutableListOf<IrBuilderWithScope.(thisReceiver: IrValueParameter) -> IrStatement>()

      val initStatements =
        mutableListOf<IrBuilderWithScope.(thisReceiver: IrValueParameter) -> IrStatement>()

      val thisReceiverParameter = thisReceiverOrFail

      // Check if this is a graph extension (child component)
      // Child components need deferred initialization because field initializers run before
      // the parent field is assigned in the constructor
      val isGraphExtension = graphClass.origin == Origins.GeneratedGraphExtension

      // Graph extension fields must be INTERNAL so nested grandchildren can access them
      // without Kotlin generating synthetic accessors with wrong receiver types
      val fieldVisibility = if (isGraphExtension) {
        DescriptorVisibilities.INTERNAL
      } else {
        DescriptorVisibilities.PRIVATE
      }

      fun addBoundInstanceField(
        typeKey: IrTypeKey,
        name: Name,
        initializer:
          IrBuilderWithScope.(thisReceiver: IrValueParameter, typeKey: IrTypeKey) -> IrExpression,
      ) {
        // Don't add it if it's not used
        if (typeKey !in sealResult.reachableKeys) return

        bindingFieldContext.putProviderField(
          typeKey,
          getOrCreateBindingField(
              typeKey,
              {
                name
                  .asString()
                  .removePrefix("$$")
                  .decapitalizeUS()
                  .suffixIfNot("Instance")
                  .suffixIfNot("Provider")
              },
              { metroSymbols.metroProvider.typeWith(typeKey.type) },
              visibility = fieldVisibility,
            )
            .let { field ->
              if (isGraphExtension) {
                // Child component: use deferred initialization in constructor
                // to avoid NPE when accessing parent fields before parent is assigned
                field.withInit(typeKey) { componentReceiver, key ->
                  instanceFactory(key.type, initializer(componentReceiver, key))
                }
              } else {
                // Root component: use inline field initializer
                field.initFinal {
                  instanceFactory(typeKey.type, initializer(thisReceiverParameter, typeKey))
                }
              }
            },
        )
      }

      node.creator?.let { creator ->
        // For graph extensions, constructor has ancestor parameters first, then creator params
        // Calculate offset to skip ancestor parameters when mapping creator params to constructor params
        val ancestorCount = graphClass.generatedGraphExtensionData?.ancestors?.size ?: 0

        // Verify we have enough constructor parameters for all creator params + ancestors
        val expectedParamCount = ancestorCount + creator.parameters.regularParameters.size
        if (ctor.regularParameters.size < expectedParamCount) {
          // Constructor has fewer parameters than expected
          // This can happen if some creator params were filtered out
          // We need to match by origin instead of index
        }

        for ((i, param) in creator.parameters.regularParameters.withIndex()) {
          val isBindsInstance = param.isBindsInstance

          // TODO if we copy the annotations over in FIR we can skip this creator lookup all
          //  together

          // Find the matching IR parameter by origin, not by index
          // This is safer than index-based matching when ancestors are involved
          val irParam = ctor.regularParameters.find { irP ->
            // Match by name and type if origins don't match
            irP.name == param.name && irP.type == param.type
          } ?: run {
            // Fallback to index-based if we can't find by name
            val paramIndex = i + ancestorCount
            if (paramIndex < ctor.regularParameters.size) {
              ctor.regularParameters[paramIndex]
            } else {
              // Parameter doesn't exist in constructor, skip it
              return@run null
            }
          } ?: continue  // Skip if we can't find the parameter

          val isDynamic = irParam.origin == Origins.DynamicContainerParam
          val isParentComponent = irParam.origin == Origins.ParentComponentParameter
          val isBindingContainer = creator.bindingContainersParameterIndices.isSet(i)

          // Skip parent component parameters - they're handled differently for nested graph extensions
          if (isParentComponent) {
            continue
          }

          if (isBindsInstance || isBindingContainer || isDynamic) {

            if (!isDynamic && param.typeKey in node.dynamicTypeKeys) {
              // Don't add it if there's a dynamic replacement
              continue
            }
            addBoundInstanceField(param.typeKey, param.name) { _, _ -> irGet(irParam) }
          } else {
            // It's a graph dep. Add all its accessors as available keys and point them at
            // this constructor parameter for provider field initialization
            val graphDep =
              node.includedGraphNodes[param.typeKey]
                ?: reportCompilerBug("Undefined graph node ${param.typeKey}")

            // Don't add it if it's not used
            if (param.typeKey !in sealResult.reachableKeys) continue

            val graphDepField =
              addSimpleInstanceField(
                fieldNameAllocator.newName(graphDep.sourceGraph.name.asString() + "Instance"),
                param.typeKey,
              ) {
                irGet(irParam)
              }
            // Link both the graph typekey and the (possibly-impl type)
            bindingFieldContext.putInstanceField(param.typeKey, graphDepField)
            bindingFieldContext.putInstanceField(graphDep.typeKey, graphDepField)

            // Expose the graph as a provider field
            // TODO this isn't always actually needed but different than the instance field above
            //  would be nice if we could determine if this field is unneeded
            val providerWrapperField =
              getOrCreateBindingField(
                param.typeKey,
                { graphDepField.name.asString() + "Provider" },
                { metroSymbols.metroProvider.typeWith(param.typeKey.type) },
                visibility = fieldVisibility,
              )

            bindingFieldContext.putProviderField(
              param.typeKey,
              if (isGraphExtension) {
                providerWrapperField.withInit(param.typeKey) { componentReceiver, key ->
                  instanceFactory(
                    key.type,
                    irGetField(irGet(componentReceiver), graphDepField),
                  )
                }
              } else {
                providerWrapperField.initFinal {
                  instanceFactory(
                    param.typeKey.type,
                    irGetField(irGet(thisReceiverParameter), graphDepField),
                  )
                }
              },
            )
            // Link both the graph typekey and the (possibly-impl type)
            bindingFieldContext.putProviderField(param.typeKey, providerWrapperField)
            bindingFieldContext.putProviderField(graphDep.typeKey, providerWrapperField)

            if (graphDep.hasExtensions) {
              val depMetroGraph = graphDep.sourceGraph.metroGraphOrFail
              val paramName = depMetroGraph.sourceGraphIfMetroGraph.name
              addBoundInstanceField(param.typeKey, paramName) { _, _ -> irGet(irParam) }
            }
          }
        }
      }

      // Create managed binding containers instance fields if used
      val allBindingContainers = buildSet {
        addAll(node.bindingContainers)
        addAll(node.allExtendedNodes.values.flatMap { it.bindingContainers })
      }
      allBindingContainers
        .sortedBy { it.kotlinFqName.asString() }
        .forEach { clazz ->
          val typeKey = IrTypeKey(clazz)
          if (typeKey !in node.dynamicTypeKeys) {
            // Only add if not replaced with a dynamic instance
            addBoundInstanceField(IrTypeKey(clazz), clazz.name) { _, _ ->
              // Can't use primaryConstructor here because it may be a Java dagger Module in interop
              val noArgConstructor = clazz.constructors.first { it.parameters.isEmpty() }
              irCallConstructor(noArgConstructor.symbol, emptyList())
            }
          }
        }

      // Don't add it if it's not used
      if (node.typeKey in sealResult.reachableKeys) {
        val thisGraphField =
          addSimpleInstanceField(fieldNameAllocator.newName("thisGraphInstance"), node.typeKey) {
            irGet(thisReceiverParameter)
          }

        bindingFieldContext.putInstanceField(node.typeKey, thisGraphField)

        // Expose the graph as a provider field
        // TODO this isn't always actually needed but different than the instance field above
        //  would be nice if we could determine if this field is unneeded
        val field =
          getOrCreateBindingField(
            node.typeKey,
            { "thisGraphInstanceProvider" },
            { metroSymbols.metroProvider.typeWith(node.typeKey.type) },
            visibility = fieldVisibility,
          )

        bindingFieldContext.putProviderField(
          node.typeKey,
          if (isGraphExtension) {
            field.withInit(node.typeKey) { componentReceiver, key ->
              instanceFactory(
                key.type,
                irGetField(irGet(componentReceiver), thisGraphField),
              )
            }
          } else {
            field.initFinal {
              instanceFactory(
                node.typeKey.type,
                irGetField(irGet(thisReceiverParameter), thisGraphField),
              )
            }
          },
        )
      }

      // Collect bindings and their dependencies for provider field ordering
      val providerAllocations =
        ProviderFieldCollector(bindingGraph, fastInitEnabled).collect()
      val providerFieldMap = LinkedHashMap<IrTypeKey, IrBinding>().apply {
        putAll(providerAllocations.fieldBindings)
      }
      val switchingProviderMap: MutableMap<IrTypeKey, IrBinding> =
        if (fastInitEnabled) {
          LinkedHashMap<IrTypeKey, IrBinding>().apply {
            putAll(providerAllocations.switchingBindings)
          }
        } else {
          mutableMapOf()
        }

      if (fastInitEnabled) {
        // Dynamic graph entries need bespoke fields to participate in lifecycle replacement.
        for (dynamicKey in node.dynamicTypeKeys.keys) {
          switchingProviderMap.remove(dynamicKey)?.let { binding ->
            providerFieldMap.putIfAbsent(dynamicKey, binding)
          }
        }

        // Delegate factories are handled separately; avoid replacing their backing fields.
        if (switchingProviderMap.isNotEmpty()) {
          val deferredKeys = sealResult.deferredTypes.toSet()
          for (deferredKey in deferredKeys) {
            switchingProviderMap.remove(deferredKey)
          }
        }
      }

      val initOrder =
        parentTracer.traceNested("Collect bindings") {
          buildList(providerFieldMap.size) {
            for (key in sealResult.sortedKeys) {
              if (key in sealResult.reachableKeys) {
                val binding = providerFieldMap[key]
                if (binding != null) {
                  add(binding)
                }
              }
            }
          }
        }

      // For all deferred types, assign them first as factories
      // TODO For any types that depend on deferred types, they need providers too?
      @Suppress("UNCHECKED_CAST")
      val deferredFields: Map<IrTypeKey, IrField> =
        sealResult.deferredTypes.associateWith { deferredTypeKey ->
          val binding = bindingGraph.requireBinding(deferredTypeKey)
          val field =
            getOrCreateBindingField(
                binding.typeKey,
                { binding.nameHint.decapitalizeUS() + "Provider" },
                { deferredTypeKey.type.wrapInProvider(metroSymbols.metroProvider) },
                visibility = fieldVisibility,
              )
              .withInit(binding.typeKey) { _, _ ->
                irInvoke(
                  callee = metroSymbols.metroDelegateFactoryConstructor,
                  typeArgs = listOf(deferredTypeKey.type),
                )
              }

          bindingFieldContext.putProviderField(deferredTypeKey, field)
          field
        }

      if (fastInitEnabled && switchingProviderMap.isNotEmpty()) {
        val switchingGenerator =
          switchingProviderGenerator
            ?: reportCompilerBug("Switching provider generator was not initialized for fast init")

        switchingProviderMap.forEach { (key, binding) ->
          if (!bindingFieldContext.hasProviderEntry(key)) {
            bindingFieldContext.putSwitchingProvider(key) { scope, componentReceiver ->
              switchingGenerator.createProviderExpression(scope, componentReceiver, binding)
            }
          }
        }
      }

      // Create fields in dependency-order
      initOrder
        .asSequence()
        .filterNot {
          // Don't generate deferred types here, we'll generate them last
          it.typeKey in deferredFields ||
            // Don't generate fields for anything already provided in provider/instance fields (i.e.
            // bound instance types)
            it.typeKey in bindingFieldContext ||
            // We don't generate fields for these even though we do track them in dependencies
            // above, it's just for propagating their aliased type in sorting
            it is IrBinding.Alias ||
            // For implicit outer class receivers we don't need to generate a field for them
            (it is IrBinding.BoundInstance && it.classReceiverParameter != null) ||
            // Parent graph bindings don't need duplicated fields
            (it is IrBinding.GraphDependency && it.fieldAccess != null)
        }
        .toList()
        .also { fieldBindings ->
          writeDiagnostic("keys-providerFields-${parentTracer.tag}.txt") {
            fieldBindings.joinToString("\n") { it.typeKey.toString() }
          }
          writeDiagnostic("keys-scopedProviderFields-${parentTracer.tag}.txt") {
            fieldBindings.filter { it.isScoped() }.joinToString("\n") { it.typeKey.toString() }
          }
        }
        .forEach { binding ->
          val key = binding.typeKey
          // Since assisted and member injections don't implement Factory, we can't just type these
          // as Provider<*> fields
          var isProviderType = true
          val suffix: String
          val fieldType =
            when (binding) {
              is IrBinding.ConstructorInjected if binding.isAssisted -> {
                isProviderType = false
                suffix = "Factory"
                binding.classFactory.factoryClass.typeWith() // TODO generic factories?
              }
              else -> {
                suffix = "Provider"
                metroSymbols.metroProvider.typeWith(key.type)
              }
            }

          // If we've reserved a field for this key here, pull it out and use that
          val field =
            getOrCreateBindingField(
              binding.typeKey,
              { binding.nameHint.decapitalizeUS().suffixIfNot(suffix) },
              { fieldType },
              visibility = fieldVisibility,
            )

          val accessType =
            if (isProviderType) {
              IrGraphExpressionGenerator.AccessType.PROVIDER
            } else {
              IrGraphExpressionGenerator.AccessType.INSTANCE
            }

          field.withInit(key) { componentReceiver, _, typeKey ->
            val baseExpression =
              if (fastInitEnabled && isProviderType) {
                switchingProviderGenerator!!
                  .createProviderExpression(this@withInit, componentReceiver, binding)
              } else {
                expressionGeneratorFactory
                  .create(componentReceiver)
                  .generateBindingCode(
                    binding,
                    accessType = accessType,
                    fieldInitKey = typeKey,
                  )
              }
            baseExpression.letIf(binding.isScoped() && isProviderType) {
              // If it's scoped, wrap it in double-check
              // DoubleCheck.provider(<provider>)
              it.doubleCheck(this@withInit, metroSymbols, binding.typeKey)
            }
          }
          if (isProviderType) {
            bindingFieldContext.putProviderField(key, field)
          } else {
            bindingFieldContext.putInstanceField(key, field)
          }
        }

      // Add statements to our constructor's deferred fields _after_ we've added all provider
      // fields for everything else. This is important in case they reference each other
      for ((deferredTypeKey, field) in deferredFields) {
        val binding = bindingGraph.requireBinding(deferredTypeKey)
        initStatements.add { thisReceiver ->
          irInvoke(
            dispatchReceiver = irGetObject(metroSymbols.metroDelegateFactoryCompanion),
            callee = metroSymbols.metroDelegateFactorySetDelegate,
            typeArgs = listOf(deferredTypeKey.type),
            // TODO de-dupe?
            args =
              listOf(
                irGetField(irGet(thisReceiver), field),
                createIrBuilder(symbol).run {
                  expressionGeneratorFactory
                    .create(thisReceiver)
                    .generateBindingCode(
                      binding,
                      accessType = IrGraphExpressionGenerator.AccessType.PROVIDER,
                      fieldInitKey = deferredTypeKey,
                    )
                    .letIf(binding.isScoped()) {
                      // If it's scoped, wrap it in double-check
                      // DoubleCheck.provider(<provider>)
                      it.doubleCheck(this@run, metroSymbols, binding.typeKey)
                    }
                },
              ),
          )
        }
      }

      val shardGroups = partitionFieldInitializers()
      if (shardGroups.size > 1) {
        val shardInstantiationStatements =
          mutableListOf<IrBuilderWithScope.(thisReceiver: IrValueParameter) -> IrStatement>()
        val shardInitializationStatements =
          mutableListOf<IrBuilderWithScope.(thisReceiver: IrValueParameter) -> IrStatement>()
        var shardInfos =
          shardGroups.mapIndexed { index, bindings ->
            createShard(index, bindings).also { info ->
              shardInstantiationStatements += info.instantiateStatement
              shardInitializationStatements += info.initializeStatement
            }
          }

        val shardOrderResult = computeShardInitializationOrder(shardInfos)
        val shardOrder = shardOrderResult.order
        if (shardOrder.size == shardInfos.size && shardOrder != shardInfos.indices.toList()) {
          shardInfos =
            shardOrder.mapIndexed { newIndex, originalIndex ->
              val info = shardInfos[originalIndex]
              info.bindings.forEach { it.owner = FieldOwner.Shard(newIndex) }
              info.copy(index = newIndex)
            }
          // Reorder statements to match initialization order
          val orderedInstantiation =
            shardOrder.map { shardInstantiationStatements[it] }
          shardInstantiationStatements.clear()
          shardInstantiationStatements += orderedInstantiation

          val orderedInitialization =
            shardOrder.map { shardInitializationStatements[it] }
          shardInitializationStatements.clear()
          shardInitializationStatements += orderedInitialization
        } else {
          shardInfos =
            shardInfos.mapIndexed { index, info ->
              info.bindings.forEach { it.owner = FieldOwner.Shard(index) }
              if (info.index != index) info.copy(index = index) else info
            }
        }

        val shardSummaries =
          shardInfos.map { info ->
            val deferredCount =
              if (info.index == shardInfos.lastIndex) initStatements.size else 0
            ShardSummary(
              index = info.index,
              className = info.shardClass.name.asString(),
              initFunctionName = info.initializeFunction.name.asString(),
              fieldCount = info.bindings.size,
              deferredStatementCount = deferredCount,
              totalStatements = info.bindings.size + deferredCount,
              keyStrings = info.bindings.map { it.typeKey.toString() },
            )
          }

        writeDiagnostic("sharding-plan-${parentTracer.tag}.txt") {
          buildString {
            appendLine("Shard count: ${shardInfos.size}")
            appendLine("Init function count: ${shardInfos.size}")
            appendLine("Total field initializers: ${fieldBindings.size}")
            appendLine("Total deferred statements: ${initStatements.size}")
            appendLine("Keys per shard limit: ${metroContext.options.keysPerGraphShard}")
            appendLine(
              "Initialization order: ${
                shardInfos.joinToString(" -> ") { "Shard ${it.index + 1}" }
              }"
            )
            appendLine("Topological sort: ${if (shardOrderResult.sortSucceeded) "SUCCESS" else "FAILED (using fallback order)"}")
            appendLine("Cross-shard dependencies: ${shardOrderResult.crossShardDependencies.size}")
            appendLine()
            if (shardOrderResult.crossShardDependencies.isNotEmpty()) {
              appendLine("Cross-shard dependency details:")
              shardOrderResult.crossShardDependencies
                .sortedWith(compareBy({ it.fromShard }, { it.toShard }))
                .forEach { dep ->
                  appendLine("  Shard ${dep.fromShard + 1} depends on Shard ${dep.toShard + 1}:")
                  appendLine("    ${dep.dependentKey}")
                  appendLine("      -> requires: ${dep.dependencyKey}")
                }
              appendLine()
            }
            shardSummaries.forEach { summary ->
              appendLine(
                "Shard ${summary.index + 1} (${summary.className}.${summary.initFunctionName})"
              )
              appendLine("  fieldCount: ${summary.fieldCount}")
              appendLine("  deferredStatementCount: ${summary.deferredStatementCount}")
              appendLine("  totalStatements: ${summary.totalStatements}")
              if (summary.keyStrings.isNotEmpty()) {
                appendLine("  keys:")
                summary.keyStrings.forEach { key ->
                  appendLine("    - $key")
                }
              } else {
                appendLine("  keys: (none)")
              }
            }
          }
        }

        constructorStatements.addAll(shardInstantiationStatements)
        constructorStatements.addAll(shardInitializationStatements)
        constructorStatements.addAll(initStatements)
      } else {
        // Small graph or single shard: inline initializations
        // Handle empty case (e.g., subcomponents with no field bindings)
        if (shardGroups.isNotEmpty()) {
          for (binding in shardGroups.single()) {
            if (isGraphExtension) {
              // Child component: defer initialization to avoid NPE accessing parent before it's assigned
              constructorStatements.add { thisReceiver ->
                irSetField(
                  irGet(thisReceiver),
                  binding.field,
                  binding.initializer.invoke(
                    this,
                    thisReceiver,
                    thisReceiver,
                    binding.typeKey,
                  )
                )
              }
            } else {
              // Root component: use inline field initializer
              binding.field.initFinal {
                binding.initializer.invoke(
                  this,
                  thisReceiverParameter,
                  thisReceiverParameter,
                  binding.typeKey,
                )
              }
            }
          }
        }
        constructorStatements.addAll(initStatements)
      }

      // Add extra constructor statements
      with(ctor) {
        val originalBody = checkNotNull(body)
        buildBlockBody {
          +originalBody.statements
          for (statement in constructorStatements) {
            +statement(thisReceiverParameter)
          }
        }
      }

      parentTracer.traceNested("Implement overrides") { node.implementOverrides() }

      if (!graphClass.origin.isGeneratedGraph) {
        parentTracer.traceNested("Generate Metro metadata") {
          // Finally, generate metadata
          val graphProto = node.toProto(bindingGraph = bindingGraph)
          val metroMetadata = MetroMetadata(METRO_VERSION, dependency_graph = graphProto)

          writeDiagnostic({
            "graph-metadata-${node.sourceGraph.kotlinFqName.asString().replace(".", "-")}.kt"
          }) {
            metroMetadata.toString()
          }

          // Write the metadata to the metroGraph class, as that's what downstream readers are
          // looking at and is the most complete view
          graphClass.metroMetadata = metroMetadata
          dependencyGraphNodesByClass(node.sourceGraph.classIdOrFail)?.let { it.proto = graphProto }
        }
      }
    }

  // TODO add asProvider support?
  private fun IrClass.addSimpleInstanceField(
    name: String,
    typeKey: IrTypeKey,
    initializerExpression: IrBuilderWithScope.() -> IrExpression,
  ): IrField =
    addField(
        fieldName = name.removePrefix("$$").decapitalizeUS(),
        fieldType = typeKey.type,
        fieldVisibility = DescriptorVisibilities.PRIVATE,
      )
      .initFinal { initializerExpression() }

  private fun DependencyGraphNode.implementOverrides() {
    // Implement abstract getters for accessors
    for ((contextualTypeKey, function, isOptionalDep) in accessors) {
      val binding = bindingGraph.findBinding(contextualTypeKey.typeKey)

      if (isOptionalDep && binding == null) {
        continue // Just use its default impl
      } else if (binding == null) {
        // Should never happen
        reportCompilerBug("No binding found for $contextualTypeKey")
      }

      function.ir.apply {
        val declarationToFinalize =
          function.ir.propertyIfAccessor.expectAs<IrOverridableDeclaration<*>>()
        if (declarationToFinalize.isFakeOverride) {
          declarationToFinalize.finalizeFakeOverride(graphClass.thisReceiverOrFail)
        }
        val irFunction = this
        body =
          createIrBuilder(symbol).run {
            if (binding is IrBinding.Multibinding) {
              // TODO if we have multiple accessors pointing at the same type, implement
              //  one and make the rest call that one. Not multibinding specific. Maybe
              //  groupBy { typekey }?
            }
            irExprBodySafe(
              symbol,
              typeAsProviderArgument(
                contextualTypeKey,
                expressionGeneratorFactory
                  .create(irFunction.dispatchReceiverParameter!!)
                  .generateBindingCode(binding, contextualTypeKey = contextualTypeKey),
                isAssisted = false,
                isGraphInstance = false,
              ),
            )
          }
      }
    }

    // Implement abstract injectors
    for ((contextKey, overriddenFunction) in injectors) {
      val typeKey = contextKey.typeKey
      overriddenFunction.ir.apply {
        finalizeFakeOverride(graphClass.thisReceiverOrFail)
        val targetParam = regularParameters[0]
        val binding = bindingGraph.requireBinding(contextKey) as IrBinding.MembersInjected

        // We don't get a MembersInjector instance/provider from the graph. Instead, we call
        // all the target inject functions directly
        body =
          createIrBuilder(symbol).irBlockBody {
            // TODO reuse, consolidate calling code with how we implement this in
            //  constructor inject code gen
            // val injectors =
            // membersInjectorTransformer.getOrGenerateAllInjectorsFor(declaration)
            // val memberInjectParameters = injectors.flatMap { it.parameters.values.flatten()
            // }

            // Extract the type from MembersInjector<T>
            val wrappedType =
              typeKey.copy(typeKey.type.requireSimpleType(targetParam).arguments[0].typeOrFail)

            for (type in
              pluginContext
                .referenceClass(binding.targetClassId)!!
                .owner
                .getAllSuperTypes(excludeSelf = false, excludeAny = true)) {
              val clazz = type.rawType()
              val generatedInjector =
                membersInjectorTransformer.getOrGenerateInjector(clazz) ?: continue
              for ((function, unmappedParams) in generatedInjector.declaredInjectFunctions) {
                val parameters =
                  if (typeKey.hasTypeArgs) {
                    val remapper = function.typeRemapperFor(wrappedType.type)
                    function.parameters(remapper)
                  } else {
                    unmappedParams
                  }
                // Record for IC
                trackFunctionCall(this@apply, function)
                +irInvoke(
                  dispatchReceiver = irGetObject(function.parentAsClass.symbol),
                  callee = function.symbol,
                  args =
                    buildList {
                      add(irGet(targetParam))
                      // Always drop the first parameter when calling inject, as the first is the
                      // instance param
                      for (parameter in parameters.regularParameters.drop(1)) {
                        val paramBinding = bindingGraph.requireBinding(parameter.contextualTypeKey)
                        add(
                          typeAsProviderArgument(
                            parameter.contextualTypeKey,
                            expressionGeneratorFactory
                              .create(overriddenFunction.ir.dispatchReceiverParameter!!)
                              .generateBindingCode(
                                paramBinding,
                                contextualTypeKey = parameter.contextualTypeKey,
                              ),
                            isAssisted = false,
                            isGraphInstance = false,
                          )
                        )
                      }
                    },
                )
              }
            }
          }
      }
    }

    // Implement no-op bodies for Binds providers
    // Note we can't source this from the node.bindsCallables as those are pointed at their original
    // declarations and we need to implement their fake overrides here
    bindsFunctions.forEach { function ->
      function.ir.apply {
        val declarationToFinalize = propertyIfAccessor.expectAs<IrOverridableDeclaration<*>>()
        if (declarationToFinalize.isFakeOverride) {
          declarationToFinalize.finalizeFakeOverride(graphClass.thisReceiverOrFail)
        }
        body = stubExpressionBody()
      }
    }

    // Implement bodies for contributed graphs
    // Sort by keys when generating so they have deterministic ordering
    // TODO make the value types something more strongly typed
    for ((typeKey, functions) in graphExtensions) {
      for (extensionAccessor in functions) {
        val function = extensionAccessor.accessor
        function.ir.apply {
          val declarationToFinalize =
            function.ir.propertyIfAccessor.expectAs<IrOverridableDeclaration<*>>()
          if (declarationToFinalize.isFakeOverride) {
            declarationToFinalize.finalizeFakeOverride(graphClass.thisReceiverOrFail)
          }
          val irFunction = this

          if (extensionAccessor.isFactory) {
            // Handled in regular accessors
          } else {
            // Graph extension creator. Use regular binding code gen
            // Could be a factory SAM function or a direct accessor. SAMs won't have a binding, but
            // we can synthesize one here as needed
            val binding =
              bindingGraph.findBinding(typeKey)
                ?: IrBinding.GraphExtension(
                  typeKey = typeKey,
                  parent = metroGraphOrFail,
                  accessor = function.ir,
                  // Implementing a factory SAM, no scoping or dependencies here,
                  extensionScopes = emptySet(),
                  dependencies = emptyList(),
                )
            val contextKey = IrContextualTypeKey.from(function.ir)
            body =
              createIrBuilder(symbol).run {
                irExprBodySafe(
                  symbol,
                  expressionGeneratorFactory
                    .create(irFunction.dispatchReceiverParameter!!)
                    .generateBindingCode(binding = binding, contextualTypeKey = contextKey),
                )
              }
          }
        }
      }
    }
  }

  private data class ShardSummary(
    val index: Int,
    val className: String,
    val initFunctionName: String,
    val fieldCount: Int,
    val deferredStatementCount: Int,
    val totalStatements: Int,
    val keyStrings: List<String>,
  )

  private fun createShard(
    index: Int,
    bindings: List<FieldBinding>,
  ): ShardInfo {
    val shardName = "Shard${index + 1}"
    val shardClass =
      pluginContext.irFactory
        .buildClass {
          name = fieldNameAllocator.newName(shardName).asName()
          kind = ClassKind.CLASS
          // Make shard class INTERNAL so nested child components can access it
          // This prevents Kotlin from generating synthetic accessors on the wrong class
          visibility = DescriptorVisibilities.INTERNAL
          origin = Origins.Default
        }
        .apply {
          superTypes = listOf(irBuiltIns.anyType)
          createThisReceiverParameter()
          graphClass.addChild(this)
        }

    val shardConstructor =
      shardClass
        .addConstructor {
          visibility = DescriptorVisibilities.PUBLIC
          isPrimary = true
          returnType = shardClass.defaultType
        }
        .apply { body = generateDefaultConstructorBody() }

    // Shard instance field must be INTERNAL so nested graph extensions (subcomponents) can access it
    // This allows child components to access parent fields that are located in shards
    val shardInstanceField =
      graphClass.addField(
        fieldNameAllocator.newName("${shardName.decapitalizeUS()}Instance"),
        shardClass.defaultType,
        DescriptorVisibilities.INTERNAL,
      )

    bindings.forEach { binding ->
      moveFieldToShard(binding.field, shardClass)
      binding.owner = FieldOwner.Shard(index)
      bindingFieldContext.updateProviderFieldOwner(
        binding.field,
        BindingFieldContext.Owner.Shard(shardInstanceField),
      )
      bindingFieldContext.updateInstanceFieldOwner(
        binding.field,
        BindingFieldContext.Owner.Shard(shardInstanceField),
      )
    }

      val initializeFunction =
        shardClass
          .addFunction("initialize", irBuiltIns.unitType)
          .apply {
            visibility = DescriptorVisibilities.PUBLIC
            val shardReceiver = dispatchReceiverParameter!!
            val componentParameter = addValueParameter("component", graphClass.defaultType)
            buildBlockBody {
              bindings.forEach { binding ->
                val value =
                  binding.initializer.invoke(
                    this,
                    componentParameter,
                    shardReceiver,
                    binding.typeKey,
                  )
                +irSetField(irGet(shardReceiver), binding.field, value)
              }
            }
          }

    val instantiateStatement:
      IrBuilderWithScope.(thisReceiver: IrValueParameter) -> IrStatement = { dispatchReceiver ->
        irSetField(
          irGet(dispatchReceiver),
          shardInstanceField,
          irCallConstructor(shardConstructor.symbol, emptyList()),
        )
      }
    val initializeStatement:
      IrBuilderWithScope.(thisReceiver: IrValueParameter) -> IrStatement = { dispatchReceiver ->
        irInvoke(
          dispatchReceiver = irGetField(irGet(dispatchReceiver), shardInstanceField),
          callee = initializeFunction.symbol,
          args = listOf(irGet(dispatchReceiver)),
        )
      }

    return ShardInfo(
      index = index,
      shardClass = shardClass,
      instanceField = shardInstanceField,
      initializeFunction = initializeFunction,
      bindings = bindings,
      instantiateStatement = instantiateStatement,
      initializeStatement = initializeStatement,
    )
  }

  private data class ShardOrderResult(
    val order: List<Int>,
    val crossShardDependencies: List<CrossShardDependency>,
    val sortSucceeded: Boolean,
  )

  private data class CrossShardDependency(
    val fromShard: Int,
    val toShard: Int,
    val dependentKey: String,
    val dependencyKey: String,
  )

  private fun computeShardInitializationOrder(shardInfos: List<ShardInfo>): ShardOrderResult {
    if (shardInfos.size <= 1) {
      return ShardOrderResult(
        order = shardInfos.indices.toList(),
        crossShardDependencies = emptyList(),
        sortSucceeded = true,
      )
    }

    val keyToShard = mutableMapOf<IrTypeKey, Int>()
    shardInfos.forEachIndexed { shardIndex, info ->
      info.bindings.forEach { keyToShard[it.typeKey] = shardIndex }
    }

    val edges = MutableList(shardInfos.size) { mutableSetOf<Int>() }
    val crossShardDeps = mutableListOf<CrossShardDependency>()

    // Helper function to resolve the actual implementation key through alias chains
    fun resolveActualKey(key: IrTypeKey): IrTypeKey {
      var current = bindingGraph.findBinding(key) ?: return key
      // Follow alias chain to find the actual implementation
      while (current is IrBinding.Alias) {
        current = bindingGraph.findBinding(current.aliasedType) ?: return current.aliasedType
      }
      return current.typeKey
    }

    shardInfos.forEachIndexed { shardIndex, info ->
      info.bindings.forEach { binding ->
        val dependencies = bindingGraph.requireBinding(binding.typeKey).dependencies
        for (dependency in dependencies) {
          // Resolve the actual binding that will be used for this dependency
          // This handles cases where the dependency type is an interface/abstract class
          // and the actual implementation is in a different shard, following alias chains
          val resolvedKey = resolveActualKey(dependency.typeKey)

          val dependencyShard = keyToShard[resolvedKey]
          if (dependencyShard != null && dependencyShard != shardIndex) {
            // dependencyShard must be initialized before shardIndex
            edges[dependencyShard].add(shardIndex)
            crossShardDeps.add(
              CrossShardDependency(
                fromShard = shardIndex,
                toShard = dependencyShard,
                dependentKey = binding.typeKey.toString(),
                dependencyKey = resolvedKey.toString(),
              )
            )
          }
        }
      }
    }

    val indegree = IntArray(shardInfos.size)
    edges.forEach { outgoing ->
      outgoing.forEach { indegree[it]++ }
    }

    val queue = ArrayDeque<Int>()
    indegree.forEachIndexed { index, value ->
      if (value == 0) queue.addLast(index)
    }

    val order = mutableListOf<Int>()
    while (queue.isNotEmpty()) {
      val shard = queue.removeFirst()
      order += shard
      for (dependent in edges[shard]) {
        indegree[dependent]--
        if (indegree[dependent] == 0) {
          queue.addLast(dependent)
        }
      }
    }

    val sortSucceeded = order.size == shardInfos.size
    return ShardOrderResult(
      order = if (sortSucceeded) order else shardInfos.indices.toList(),
      crossShardDependencies = crossShardDeps,
      sortSucceeded = sortSucceeded,
    )
  }

  private fun moveFieldToShard(field: IrField, shardClass: IrClass) {
    val parent = field.parentAsClass
    parent.declarations.remove(field)
    shardClass.addChild(field)
    field.parent = shardClass
    // Change field visibility to PUBLIC so no synthetic accessors are needed
    // Nested child components can access parent.shard3Instance.defaultProvider3 directly
    // without Kotlin generating access$get... methods
    field.visibility = DescriptorVisibilities.PUBLIC
  }
}
