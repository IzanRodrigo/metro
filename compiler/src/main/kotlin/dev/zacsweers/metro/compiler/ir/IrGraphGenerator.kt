// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.METRO_VERSION
import dev.zacsweers.metro.compiler.NameAllocator
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.sharding.ShardFieldRegistry
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.decapitalizeUS
import dev.zacsweers.metro.compiler.expectAs
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.ir.parameters.wrapInProvider
import dev.zacsweers.metro.compiler.ir.transformers.AssistedFactoryTransformer
import dev.zacsweers.metro.compiler.ir.transformers.BindingContainerTransformer
import dev.zacsweers.metro.compiler.ir.transformers.MembersInjectorTransformer
import dev.zacsweers.metro.compiler.letIf
import dev.zacsweers.metro.compiler.proto.MetroMetadata
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.sharding.ShardingPlan
import dev.zacsweers.metro.compiler.suffixIfNot
import dev.zacsweers.metro.compiler.tracing.Tracer
import dev.zacsweers.metro.compiler.tracing.traceNested
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.DescriptorVisibility
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addField
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addTypeParameter
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildReceiverParameter
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irBranch
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irElseBranch
import org.jetbrains.kotlin.ir.builders.irEquals
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irWhen
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irDelegatingConstructorCall
import org.jetbrains.kotlin.ir.builders.irExprBody
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irSetField
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.ir.declarations.IrOverridableDeclaration
import org.jetbrains.kotlin.ir.expressions.impl.IrInstanceInitializerCallImpl
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.typeOrFail
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.util.propertyIfAccessor
import org.jetbrains.kotlin.ir.util.statements
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

internal typealias FieldInitializer =
  IrBuilderWithScope.(thisReceiver: IrValueParameter, key: IrTypeKey) -> IrExpression

// Use shared constant from ShardingConstants
private const val STATEMENTS_PER_METHOD = ShardingConstants.STATEMENTS_PER_METHOD

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
  private val shardingPlan: ShardingPlan? = null,
) : IrMetroContext by metroContext {

  private val bindingFieldContext = BindingFieldContext()
  
  /**
   * Registry for tracking which binding fields are in which shards.
   * This coordinates field naming between shard generation and cross-shard access.
   */
  private val shardFieldRegistry = ShardFieldRegistry()

  /**
   * To avoid `MethodTooLargeException`, we split field initializations up over multiple constructor
   * inits. Each class (main graph or shard) maintains its own initialization list.
   *
   * @see <a href="https://github.com/ZacSweers/metro/issues/645">#645</a>
   */
  private val fieldInitializersByClass = mutableMapOf<IrClass, MutableList<Triple<IrField, IrTypeKey, FieldInitializer>>>()
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
      shardFieldRegistry = shardFieldRegistry,
    )
  
  private var shardInfos: Map<Int, ShardGenerator.ShardInfo> = emptyMap()

  /** Owner IrClass for a binding key based on the sharding plan. */
  private fun ownerClassFor(key: IrTypeKey): IrClass {
    val idx = shardingPlan?.bindingToShard?.get(key)
    return if (idx != null && idx != 0) {
      checkNotNull(shardInfos[idx]?.shardClass) { "Shard class missing for index $idx" }
    } else {
      graphClass
    }
  }

  fun IrField.withInit(typeKey: IrTypeKey, init: FieldInitializer): IrField = apply {
    // The typeKey is already registered in shardFieldRegistry when the field is created
    // Determine which class this field belongs to (main graph or shard)
    val owningClass = parent as IrClass
    // Store the type key alongside the field and initializer to avoid reverse lookups later
    fieldInitializersByClass.getOrPut(owningClass) { mutableListOf() }.add(Triple(this, typeKey, init))
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
      ?: addField(fieldName = name().asName(), fieldType = type(), fieldVisibility = visibility)
  }

  fun generate() =
    with(graphClass) {
      val ctor = primaryConstructor!!

      val constructorStatements =
        mutableListOf<IrBuilderWithScope.(thisReceiver: IrValueParameter) -> IrStatement>()

      val initStatements =
        mutableListOf<IrBuilderWithScope.(thisReceiver: IrValueParameter) -> IrStatement>()

      val thisReceiverParameter = thisReceiverOrFail
      
      // Generate shard classes and fields if sharding is enabled
      val (shardInfos, shardInitializers) = if (shardingPlan?.requiresSharding() == true) {
        parentTracer.traceNested("Generate shards") {
          generateShards()
        }
      } else {
        emptyMap<Int, ShardGenerator.ShardInfo>() to emptyList()
      }
      
      // Add shard initializations to constructor statements (BEFORE other field initializations)
      constructorStatements.addAll(shardInitializers)

      fun addBoundInstanceField(
        typeKey: IrTypeKey,
        name: Name,
        initializer:
          IrBuilderWithScope.(thisReceiver: IrValueParameter, typeKey: IrTypeKey) -> IrExpression,
      ) {
        // Don't add it if it's not used
        if (typeKey !in sealResult.reachableKeys) return

        val target = ownerClassFor(typeKey)
        val fieldName = fieldNameAllocator.newName(
          name
            .asString()
            .removePrefix("$$")
            .decapitalizeUS()
            .suffixIfNot("Instance")
            .suffixIfNot("Provider")
        )
        val field = target.getOrCreateBindingField(
          typeKey,
          { fieldName },
          { symbols.metroProvider.typeWith(typeKey.type) },
        ).initFinal {
          instanceFactory(typeKey.type, initializer(thisReceiverParameter, typeKey))
        }
        
        bindingFieldContext.putProviderField(typeKey, field)
        
        // Register the field in the shard field registry if sharding is enabled
        if (shardingPlan != null) {
          val shardIndex = shardingPlan.bindingToShard[typeKey] ?: 0
          // We need to get the binding for this typeKey
          val binding = bindingGraph.requireBinding(typeKey, IrBindingStack.empty())
          shardFieldRegistry.registerField(
            typeKey = typeKey,
            shardIndex = shardIndex,
            field = field,
            fieldName = fieldName,
            binding = binding
          )
        }
      }

      node.creator?.let { creator ->
        for ((i, param) in creator.parameters.regularParameters.withIndex()) {
          val isBindsInstance = param.isBindsInstance

          // TODO if we copy the annotations over in FIR we can skip this creator lookup all
          //  together
          val irParam = ctor.regularParameters[i]

          if (isBindsInstance || creator.bindingContainersParameterIndices.isSet(i)) {
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
              graphClass.addSimpleInstanceField(
                fieldNameAllocator.newName(graphDep.sourceGraph.name.asString() + "Instance"),
                param.typeKey,
              ) {
                irGet(irParam)
              }
            // Link both the graph typekey and the (possibly-impl type)
            bindingFieldContext.putInstanceField(param.typeKey, graphDepField)
            bindingFieldContext.putInstanceField(graphDep.typeKey, graphDepField)

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
          addBoundInstanceField(IrTypeKey(clazz), clazz.name) { _, _ ->
            irCallConstructor(clazz.primaryConstructor!!.symbol, emptyList())
          }
        }

      // Don't add it if it's not used
      if (node.typeKey in sealResult.reachableKeys) {
        val thisGraphField =
          graphClass.addSimpleInstanceField(fieldNameAllocator.newName("thisGraphInstance"), node.typeKey) {
            irGet(thisReceiverParameter)
          }

        bindingFieldContext.putInstanceField(node.typeKey, thisGraphField)

        // Expose the graph as a provider field
        // TODO this isn't always actually needed but different than the instance field above
        //  would be nice if we could determine if this field is unneeded
        val target = ownerClassFor(node.typeKey)
        val field =
          target.getOrCreateBindingField(
            node.typeKey,
            { fieldNameAllocator.newName("thisGraphInstanceProvider") },
            { symbols.metroProvider.typeWith(node.typeKey.type) },
          ).initFinal {
            instanceFactory(
              node.typeKey.type,
              irGetField(irGet(thisReceiverParameter), thisGraphField),
            )
          }

        bindingFieldContext.putProviderField(node.typeKey, field)
        
        // Register the field in the shard field registry if sharding is enabled
        if (shardingPlan != null) {
          val shardIndex = shardingPlan.bindingToShard[node.typeKey] ?: 0
          shardFieldRegistry.registerField(
            typeKey = node.typeKey,
            shardIndex = shardIndex,
            field = field,
            fieldName = field.name.asString(),
            binding = bindingGraph.requireBinding(node.typeKey, IrBindingStack.empty())
          )
        }
      }

      // Collect bindings and their dependencies for provider field ordering
      val initOrder =
        parentTracer.traceNested("Collect bindings") {
          val providerFieldBindings = ProviderFieldCollector(bindingGraph).collect()
          buildList(providerFieldBindings.size) {
            for (key in sealResult.sortedKeys) {
              if (key in sealResult.reachableKeys) {
                providerFieldBindings[key]?.let(::add)
              }
            }
          }
        }

      // For all deferred types, assign them first as factories
      // TODO For any types that depend on deferred types, they need providers too?
      @Suppress("UNCHECKED_CAST")
      val deferredFields: Map<IrTypeKey, IrField> =
        sealResult.deferredTypes.associateWith { deferredTypeKey ->
          val binding = bindingGraph.requireBinding(deferredTypeKey, IrBindingStack.empty())
          val target = ownerClassFor(binding.typeKey)
          val fieldName = fieldNameAllocator.newName(binding.nameHint.decapitalizeUS() + "Provider")
          val field =
            target.getOrCreateBindingField(binding.typeKey,
                { fieldName },
                { deferredTypeKey.type.wrapInProvider(symbols.metroProvider) },
              )
              .withInit(binding.typeKey) { _, _ ->
                irInvoke(
                  callee = symbols.metroDelegateFactoryConstructor,
                  typeArgs = listOf(deferredTypeKey.type),
                )
              }

          bindingFieldContext.putProviderField(deferredTypeKey, field)
          
          // Register the field in the shard field registry if sharding is enabled
          if (shardingPlan != null) {
            val shardIndex = shardingPlan.bindingToShard[binding.typeKey] ?: 0
            shardFieldRegistry.registerField(
              typeKey = binding.typeKey,
              shardIndex = shardIndex,
              field = field,
              fieldName = fieldName,
              binding = binding
            )
          }
          
          field
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
          
          // Check if this binding should go into a shard
          val targetShardIndex = shardingPlan?.bindingToShard?.get(key)
          val targetShard = targetShardIndex?.let { shardInfos[it] }
          
          // If sharding is enabled and this binding is assigned to a non-component shard,
          // generate it in that shard instead of the main class
          if (targetShard != null && !targetShard.shard.isComponentShard) {
            // This binding goes into a shard
            // For now, we'll just track it - Phase 3 will handle the actual generation
            // TODO: Generate field in shard class instead of main class
            // TODO: Update bindingFieldContext to track shard location
          }
          
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
                symbols.metroProvider.typeWith(key.type)
              }
            }

          // Determine where to create the field (main class or shard)
          val targetClass = if (targetShard != null && !targetShard.shard.isComponentShard) {
            if (options.debug) {
              log("[MetroSharding] Distributing field for ${binding.typeKey.render(short = true)} to Shard${targetShard.shard.index}")
            }
            targetShard.shardClass
          } else {
            this // Main graph class
          }
          
          // If we've reserved a field for this key here, pull it out and use that
          val fieldName = fieldNameAllocator.newName(binding.nameHint.decapitalizeUS().suffixIfNot(suffix))
          val field = targetClass.getOrCreateBindingField(
              binding.typeKey,
              { fieldName },
              { fieldType },
            )
          
          // Register the field in the shard field registry
          // Always register fields when sharding is available, even if not actively sharding
          // This ensures singleton behavior is maintained
          if (shardingPlan != null) {
            val shardIndex = targetShardIndex ?: 0 // 0 represents the main component
            if (options.debug) {
              log("[MetroSharding] Registering field '$fieldName' for ${binding.typeKey.render(short = true)} in shard $shardIndex")
            }
            shardFieldRegistry.registerField(
              typeKey = binding.typeKey,
              shardIndex = shardIndex,
              field = field,
              fieldName = fieldName,
              binding = binding
            )
          }

          val accessType =
            if (isProviderType) {
              IrGraphExpressionGenerator.AccessType.PROVIDER
            } else {
              IrGraphExpressionGenerator.AccessType.INSTANCE
            }

          field.withInit(key) { thisReceiver, typeKey ->
            expressionGeneratorFactory
              .create(thisReceiver)
              .generateBindingCode(binding, accessType = accessType, fieldInitKey = typeKey)
              .letIf(binding.isScoped() && isProviderType) {
                // If it's scoped, wrap it in double-check
                // DoubleCheck.provider(<provider>)
                it.doubleCheck(this@withInit, symbols, binding.typeKey)
              }
          }
          
          // Track the field location for cross-shard access
          if (targetShard != null && !targetShard.shard.isComponentShard) {
            // Store shard reference in binding field context for Phase 3
            // Phase 3 will use this to generate proper cross-shard access
          }
          
          bindingFieldContext.putProviderField(key, field)
        }

      // Add statements to our constructor's deferred fields _after_ we've added all provider
      // fields for everything else. This is important in case they reference each other
      for ((deferredTypeKey, field) in deferredFields) {
        val binding = bindingGraph.requireBinding(deferredTypeKey, IrBindingStack.empty())
        initStatements.add { thisReceiver ->
          irInvoke(
            dispatchReceiver = irGetObject(symbols.metroDelegateFactoryCompanion),
            callee = symbols.metroDelegateFactorySetDelegate,
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
                      it.doubleCheck(this@run, symbols, binding.typeKey)
                    }
                },
              ),
          )
        }
      }

      // Get field initializers for the main graph class
      val mainGraphInitializers = fieldInitializersByClass[this@with] ?: emptyList()
      
      if (
        options.chunkFieldInits &&
          mainGraphInitializers.size + initStatements.size > STATEMENTS_PER_METHOD
      ) {
        // Larger graph, split statements
        // Chunk our constructor statements and split across multiple init functions
        val chunks =
          buildList<IrBuilderWithScope.(thisReceiver: IrValueParameter) -> IrStatement> {
              // Add field initializers first
              for ((field, typeKey, init) in mainGraphInitializers) {
                add { thisReceiver ->
                  // Use the stored type key directly, no registry lookup needed
                  irSetField(
                    irGet(thisReceiver),
                    field,
                    init(thisReceiver, typeKey),
                  )
                }
              }
              for (statement in initStatements) {
                add { thisReceiver -> statement(thisReceiver) }
              }
            }
            .chunked(STATEMENTS_PER_METHOD)

        val initAllocator = NameAllocator(mode = NameAllocator.Mode.COUNT)
        val initFunctionsToCall =
          chunks.map { statementsChunk ->
            val initName = initAllocator.newName("init")
            addFunction(initName, irBuiltIns.unitType, visibility = DescriptorVisibilities.PRIVATE)
              .apply {
                val localReceiver = thisReceiverParameter.copyTo(this)
                setDispatchReceiver(localReceiver)
                buildBlockBody {
                  for (statement in statementsChunk) {
                    +statement(localReceiver)
                  }
                }
              }
          }
        constructorStatements += buildList {
          for (initFunction in initFunctionsToCall) {
            add { dispatchReceiver ->
              irInvoke(dispatchReceiver = irGet(dispatchReceiver), callee = initFunction.symbol)
            }
          }
        }
      } else {
        // Small graph, just do it in the constructor
        // Assign those initializers directly to their fields and mark them as final
        for ((field, typeKey, init) in mainGraphInitializers) {
          field.initFinal {
            // Use the stored type key directly, no registry lookup needed
            init(thisReceiverParameter, typeKey)
          }
        }
        constructorStatements += initStatements
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

      if (graphClass.origin != Origins.GeneratedGraphExtension) {
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
  ): IrField {
    // Determine the correct owner class based on sharding
    val owner = ownerClassFor(typeKey)
    val fieldName = name.removePrefix("$$").decapitalizeUS()

    val field = owner.addField(
        fieldName = fieldName,
        fieldType = typeKey.type,
        fieldVisibility = DescriptorVisibilities.PRIVATE,
      )
      .initFinal { initializerExpression() }

    // Register the field in the shard field registry if sharding is enabled
    if (shardingPlan != null) {
      val shardIndex = shardingPlan.bindingToShard[typeKey] ?: 0
      // Try to get the binding if available
      val binding = try {
        bindingGraph.requireBinding(typeKey, IrBindingStack.empty())
      } catch (e: Exception) {
        // For non-binding fields like thisGraphInstance, create a placeholder
        null
      }

      if (binding != null) {
        shardFieldRegistry.registerField(
          typeKey = typeKey,
          shardIndex = shardIndex,
          field = field,
          fieldName = fieldName,
          binding = binding
        )
      }
    }

    return field
  }

  private fun DependencyGraphNode.implementOverrides() {
    // Implement abstract getters for accessors
    accessors.forEach { (function, contextualTypeKey) ->
      function.ir.apply {
        val declarationToFinalize =
          function.ir.propertyIfAccessor.expectAs<IrOverridableDeclaration<*>>()
        if (declarationToFinalize.isFakeOverride) {
          declarationToFinalize.finalizeFakeOverride(graphClass.thisReceiverOrFail)
        }
        val irFunction = this
        val binding = bindingGraph.requireBinding(contextualTypeKey, IrBindingStack.empty())
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
    injectors.forEach { (overriddenFunction, contextKey) ->
      val typeKey = contextKey.typeKey
      overriddenFunction.ir.apply {
        finalizeFakeOverride(graphClass.thisReceiverOrFail)
        val targetParam = regularParameters[0]
        val binding =
          bindingGraph.requireBinding(contextKey, IrBindingStack.empty())
            as IrBinding.MembersInjected

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
              typeKey.copy(typeKey.type.expectAs<IrSimpleType>().arguments[0].typeOrFail)

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
                        val paramBinding =
                          bindingGraph.requireBinding(
                            parameter.contextualTypeKey,
                            IrBindingStack.empty(),
                          )
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
  
  /**
   * Generates shard classes and their initialization.
   * Following Dagger's pattern, shards are initialized in the constructor before other bindings.
   */
  private fun IrClass.generateShards(): Pair<Map<Int, ShardGenerator.ShardInfo>, List<IrBuilderWithScope.(IrValueParameter) -> IrStatement>> {
    requireNotNull(shardingPlan) { "generateShards called without sharding plan" }
    
    if (options.debug) {
      log("[MetroSharding] Generating ${shardingPlan.additionalShards().size} shard classes")
    }
    
    // First, generate shard classes with their field initialization methods
    val infos = mutableMapOf<Int, ShardGenerator.ShardInfo>()
    
    // Process each shard (skipping shard 0 which is the main component)
    for (shard in shardingPlan.additionalShards()) {
      if (options.debug) {
        log("[MetroSharding] Generating Shard${shard.index} for ${shard.bindings.size} bindings")
        val sampleBindings = shard.bindings.take(3).joinToString { it.render(short = true) }
        log("[MetroSharding]   Sample bindings: $sampleBindings")
      }
      
      val generator = ShardGenerator(
        context = this@IrGraphGenerator,
        parentClass = this,
        shard = shard,
        bindingGraph = bindingGraph,
        fieldNameAllocator = fieldNameAllocator,
        shardingPlan = shardingPlan,
        fieldRegistry = shardFieldRegistry
      )
      
      // Generate the shard class with field initialization
      // First create the class, then we'll add the initialization method
      val shardClass = generator.generateShardClass()
      
      // Get the field initializers for this shard class
      val shardInitializers = fieldInitializersByClass[shardClass] ?: emptyList()
      
      // Create ShardInfo
      val shardInfo = ShardGenerator.ShardInfo(
        shard = shard,
        shardClass = shardClass,
        shardField = null, // Will be set below
        generator = generator
      )
      
      // If there are initializers, generate the initializeFields method and update constructor
      if (shardInitializers.isNotEmpty()) {
        // Generate the initializeFields method
        val initMethod = generator.generateShardFieldInitialization(shardInfo, shardInitializers)
        
        // Update the constructor to call initializeFields
        val constructor = shardClass.primaryConstructor
          ?: error("Shard class missing primary constructor")
        
        constructor.body = createIrBuilder(constructor.symbol).irBlockBody {
          // Call the super constructor first
          +irDelegatingConstructorCall(irBuiltIns.anyClass.owner.primaryConstructor!!)
          // Initialize the instance
          +IrInstanceInitializerCallImpl(
            UNDEFINED_OFFSET,
            UNDEFINED_OFFSET,
            shardClass.symbol,
            shardClass.defaultType,
          )
          // Call this.initializeFields() at the end of constructor
          +irCall(initMethod.symbol).also { call ->
            call.dispatchReceiver = irGet(shardClass.thisReceiver!!)
          }
        }
      }
      
      // Generate the field in the parent class to hold this shard instance
      val shardField = generator.generateShardField(shardClass)
      
      // Update the ShardInfo with the field
      val updatedShardInfo = shardInfo.copy(shardField = shardField)
      infos[shard.index] = updatedShardInfo
    }
    
    // Store for later use in binding distribution
    shardInfos = infos
    
    // Collect field initializations to add to constructor
    val shardInitializers = mutableListOf<IrBuilderWithScope.(IrValueParameter) -> IrStatement>()
    
    // Initialize shard fields in constructor
    // This must happen BEFORE other binding fields are initialized
    for ((shardIndex, shardInfo) in infos) {
      val shard = shardInfo.shard
      val shardField = shardInfo.shardField
      val shardClass = shardInfo.shardClass
      
      // Determine which modules this shard needs
      // For now, pass all modules - Phase 3 will optimize this
      // TODO: Implement proper module detection
      val moduleParams = emptyList<IrValueParameter>()
      
      // Add field initialization in the constructor
      shardField?.let { field ->
        shardInitializers.add { thisReceiver ->
          // Initialize the shard field with a new instance
          val initialization = shardInfo.generator.generateShardInitialization(
            shardClass = shardClass,
            thisReceiver = thisReceiver,
            moduleParameters = moduleParams
          )
          
          // Set the field value
          irSetField(irGet(thisReceiver), field, initialization)
        }
      }
    }
    
    // Return both the infos and their initializations
    return infos to shardInitializers
  }

  /**
   * Generates a SwitchingProvider nested class for efficient dependency dispatch.
   * This follows Dagger's pattern of using ID-based switching for large graphs.
   *
   * @param providerCases Map of provider ID to the expression that creates the dependency
   * @param typeParameter The type parameter for the SwitchingProvider<T>
   * @return The generated SwitchingProvider class
   */
  private fun IrClass.generateSwitchingProvider(
    providerCases: Map<Int, IrBuilderWithScope.(thisReceiver: IrValueParameter) -> IrExpression>,
    typeParameter: IrType = irBuiltIns.anyNType
  ): IrClass {
    val switchingProviderClass = pluginContext.irFactory.buildClass {
      name = "SwitchingProvider".asName()
      kind = ClassKind.CLASS
      visibility = DescriptorVisibilities.PRIVATE
      modality = Modality.FINAL
      origin = Origins.Default
    }

    // Make it a nested class of the component
    addChild(switchingProviderClass)
    switchingProviderClass.createThisReceiverParameter()

    // Add type parameter <T>
    val typeParam = switchingProviderClass.addTypeParameter {
      name = "T".asName()
      variance = Variance.OUT_VARIANCE
    }

    // Add parent and id fields
    val parentField = switchingProviderClass.addField {
      name = "parent".asName()
      type = this@generateSwitchingProvider.defaultType
      visibility = DescriptorVisibilities.PRIVATE
      isFinal = true
    }

    val idField = switchingProviderClass.addField {
      name = "id".asName()
      type = irBuiltIns.intType
      visibility = DescriptorVisibilities.PRIVATE
      isFinal = true
    }

    // Add constructor
    val constructor = switchingProviderClass.addConstructor {
      visibility = DescriptorVisibilities.PUBLIC
      isPrimary = true
    }

    val parentParam = constructor.addValueParameter("parent", this@generateSwitchingProvider.defaultType)
    val idParam = constructor.addValueParameter("id", irBuiltIns.intType)

    constructor.body = context.irFactory.createBlockBody(UNDEFINED_OFFSET, UNDEFINED_OFFSET).apply {
      val builder = context.createIrBuilder(constructor.symbol)
      val thisRef = requireNotNull(switchingProviderClass.thisReceiver)

      // Call super constructor
      statements += builder.irDelegatingConstructorCall(context.irBuiltIns.anyClass.owner.constructors.single())

      // Set fields
      statements += builder.irSetField(
        receiver = builder.irGet(thisRef),
        field = parentField,
        value = builder.irGet(parentParam)
      )

      statements += builder.irSetField(
        receiver = builder.irGet(thisRef),
        field = idField,
        value = builder.irGet(idParam)
      )
    }

    // Add Provider<T> supertype
    val providerType = symbols.metroProvider.typeWith(typeParam.defaultType)
    switchingProviderClass.superTypes = listOf(providerType)

    // Generate invoke() method with when expression
    val invokeMethod = switchingProviderClass.addFunction {
      name = "invoke".asName()
      visibility = DescriptorVisibilities.PUBLIC
      returnType = typeParam.defaultType
      modality = Modality.OPEN
    }

    invokeMethod.overriddenSymbols = listOf(
      symbols.metroProvider.owner.declarations
        .filterIsInstance<IrSimpleFunction>()
        .first { it.name.asString() == "invoke" }
        .symbol
    )

    val thisReceiver = invokeMethod.addValueParameter {
      name = "<this>".asName()
      type = switchingProviderClass.defaultType
      origin = IrDeclarationOrigin.INSTANCE_RECEIVER
      index = -1
    }
    invokeMethod.dispatchReceiverParameter = thisReceiver

    // Split cases into chunks if needed (max 100 per method)
    val CASES_PER_METHOD = 100
    if (providerCases.size <= CASES_PER_METHOD) {
      // Single method with all cases
      invokeMethod.body = generateSwitchingProviderBody(
        switchingProviderClass,
        parentField,
        idField,
        providerCases,
        typeParam.defaultType
      )
    } else {
      // Split into multiple methods
      val chunks = providerCases.entries.chunked(CASES_PER_METHOD)
      val helperMethods = chunks.mapIndexed { index, chunk ->
        val helperMethod = switchingProviderClass.addFunction {
          name = "invoke${index + 1}".asName()
          visibility = DescriptorVisibilities.PRIVATE
          returnType = typeParam.defaultType
        }

        helperMethod.buildReceiverParameter { type = switchingProviderClass.defaultType }

        helperMethod.body = generateSwitchingProviderBody(
          switchingProviderClass,
          parentField,
          idField,
          chunk.associate { it.key to it.value },
          typeParam.defaultType
        )

        helperMethod to chunk.first().key..chunk.last().key
      }

      // Main invoke method delegates to helpers based on ID range
      invokeMethod.body = context.irFactory.createBlockBody(UNDEFINED_OFFSET, UNDEFINED_OFFSET).apply {
        val builder = context.createIrBuilder(invokeMethod.symbol)
        val idValue = builder.irGetField(builder.irGet(thisReceiver), idField)

        statements += builder.irReturn(
          builder.irWhen(
            typeParam.defaultType,
            helperMethods.map { (method, range) ->
              val condition = if (range.first == range.last) {
                builder.irEquals(idValue, builder.irInt(range.first))
              } else {
                builder.irCall(context.irBuiltIns.intClass.owner.declarations
                  .filterIsInstance<IrSimpleFunction>()
                  .first { it.name.asString() == "rangeTo" }
                  .symbol
                ).apply {
                  dispatchReceiver = builder.irInt(range.first)
                  putValueArgument(0, builder.irInt(range.last))
                }.let { rangeExpr ->
                  builder.irCall(symbols.contains).apply {
                    dispatchReceiver = rangeExpr
                    putValueArgument(0, idValue)
                  }
                }
              }

              builder.irBranch(
                condition,
                builder.irCall(method.symbol).apply {
                  dispatchReceiver = builder.irGet(thisReceiver)
                }
              )
            } + builder.irElseBranch(
              builder.irCall(symbols.error).apply {
                putValueArgument(0, builder.irString("Unknown provider id: \$id"))
              }
            )
          )
        )
      }
    }

    return switchingProviderClass
  }

  /**
   * Generates the body of a SwitchingProvider method with when expression.
   */
  private fun generateSwitchingProviderBody(
    switchingProviderClass: IrClass,
    parentField: IrField,
    idField: IrField,
    cases: Map<Int, IrBuilderWithScope.(thisReceiver: IrValueParameter) -> IrExpression>,
    returnType: IrType
  ): IrBlockBody {
    return context.irFactory.createBlockBody(UNDEFINED_OFFSET, UNDEFINED_OFFSET).apply {
      val builder = context.createIrBuilder(switchingProviderClass.symbol)
      val thisReceiver = requireNotNull(switchingProviderClass.thisReceiver)

      val parentAccess = builder.irGetField(builder.irGet(thisReceiver), parentField)
      val idValue = builder.irGetField(builder.irGet(thisReceiver), idField)

      // Generate when expression
      val whenExpr = builder.irWhen(
        returnType,
        cases.map { (id, expression) ->
          builder.irBranch(
            builder.irEquals(idValue, builder.irInt(id)),
            expression(builder, parentAccess as IrValueParameter)
          )
        } + builder.irElseBranch(
          builder.irCall(symbols.error).apply {
            putValueArgument(0, builder.irString("Unknown provider id: \$id"))
          }
        )
      )

      statements += builder.irReturn(whenExpr)
    }
  }
}
