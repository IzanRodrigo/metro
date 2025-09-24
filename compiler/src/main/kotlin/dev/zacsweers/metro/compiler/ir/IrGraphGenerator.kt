// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.*
import dev.zacsweers.metro.compiler.graph.sharding.Shard
import dev.zacsweers.metro.compiler.graph.sharding.ShardingContext
import dev.zacsweers.metro.compiler.graph.sharding.ShardingPlan
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.ir.parameters.wrapInProvider
import dev.zacsweers.metro.compiler.ir.transformers.AssistedFactoryTransformer
import dev.zacsweers.metro.compiler.ir.transformers.BindingContainerTransformer
import dev.zacsweers.metro.compiler.ir.transformers.MembersInjectorTransformer
import dev.zacsweers.metro.compiler.proto.MetroMetadata
import dev.zacsweers.metro.compiler.tracing.Tracer
import dev.zacsweers.metro.compiler.tracing.traceNested
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.DescriptorVisibility
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.builders.declarations.*
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrOverridableDeclaration
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.typeOrFail
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import java.util.LinkedHashMap

internal typealias FieldInitializer =
  IrBuilderWithScope.(thisReceiver: IrValueParameter, key: IrTypeKey) -> IrExpression

private enum class RequirementAccess {
  INSTANCE,
  PROVIDER,
}

private data class ShardRequirement(
  val key: IrTypeKey,
  val access: RequirementAccess,
  val componentField: IrField,
)

private data class RequirementFieldInfo(
  val requirement: ShardRequirement,
  val parameter: IrValueParameter,
  val field: IrField,
)

// Borrowed from Dagger
// https://github.com/google/dagger/blob/b39cf2d0640e4b24338dd290cb1cb2e923d38cb3/dagger-compiler/main/java/dagger/internal/codegen/writing/ComponentImplementation.java#L263
private const val STATEMENTS_PER_METHOD = 25

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
) : IrMetroContext by metroContext {

  private val bindingFieldContext = BindingFieldContext()

  /**
   * To avoid `MethodTooLargeException`, we split field initializations up over multiple constructor
   * inits.
   *
   * @see <a href="https://github.com/ZacSweers/metro/issues/645">#645</a>
   */
  private val fieldInitializers = mutableListOf<Pair<IrField, FieldInitializer>>()
  private val fieldsToTypeKeys = mutableMapOf<IrField, IrTypeKey>()
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

  fun IrField.withInit(typeKey: IrTypeKey, init: FieldInitializer): IrField = apply {
    fieldsToTypeKeys[this] = typeKey
    fieldInitializers += (this to init)
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
    visibility: DescriptorVisibility = DescriptorVisibilities.INTERNAL,
  ): IrField {
    return bindingGraph.reservedField(key)?.field?.also { addChild(it) }
      ?: addField(fieldName = name().asName(), fieldType = type(), fieldVisibility = visibility)
  }

  fun generate() =
    with(graphClass) {
      // Check if we have a sharding plan (keysPerShard > 0 enables sharding)
      val shardingPlan = sealResult.shardingPlan
      if (shardingPlan != null && shardingPlan.isShardingNeeded) {
        // Generate with sharding enabled
        generateWithSharding(shardingPlan)
      } else {
        // Generate without sharding (existing logic)
        generateWithoutSharding()
      }
    }

  private fun collectShardRequirements(
    shard: Shard,
    shardingPlan: ShardingPlan,
    ordinalToKey: Map<Int, IrTypeKey>,
    graphClass: IrClass,
  ): List<ShardRequirement> {
    if (shard.bindingOrdinals.isEmpty()) return emptyList()

    val requirements = LinkedHashMap<IrTypeKey, ShardRequirement>()

    for (ordinal in shard.bindingOrdinals) {
      val bindingKey = ordinalToKey[ordinal] ?: continue
      val binding = bindingGraph.requireBinding(bindingKey, IrBindingStack.empty())

      fun addRequirementForKey(key: IrTypeKey, access: RequirementAccess) {
        val componentField =
          when (access) {
            RequirementAccess.PROVIDER -> bindingFieldContext.providerField(key)
            RequirementAccess.INSTANCE -> bindingFieldContext.instanceField(key)
          }

        if (componentField != null && componentField.parent == graphClass && key !in requirements) {
          requirements[key] = ShardRequirement(key, access, componentField)
        }
      }

      if (binding is IrBinding.GraphDependency) {
        addRequirementForKey(bindingKey, RequirementAccess.PROVIDER)
      }

      for (dependency in binding.dependencies) {
        val dependencyKey = dependency.typeKey

        // Skip dependencies that stay within the same shard
        val dependencyOrdinal = shardingPlan.bindingOrdinals[dependencyKey]
        if (dependencyOrdinal != null) {
          val dependencyShardIndex =
            shardingPlan.bindingToShard.getOrElse(dependencyOrdinal) { -1 }
          if (dependencyShardIndex == shard.index) continue

          // Skip cross-shard dependencies for now (they will continue to be handled via shard fields)
          if (dependencyShardIndex >= 0) continue
        }

        // Determine the access mode required for this dependency
        val access =
          if (dependency.requiresProviderInstance) RequirementAccess.PROVIDER
          else RequirementAccess.INSTANCE

        addRequirementForKey(dependencyKey, access)
      }
    }

    return requirements.values.toList()
  }

  private fun IrClass.generateWithSharding(shardingPlan: ShardingPlan) {
    val shardedTypeKeys: Set<IrTypeKey> = buildSet {
      for ((typeKey, ordinal) in shardingPlan.bindingOrdinals) {
        val shardIndex = shardingPlan.bindingToShard.getOrElse(ordinal) { -1 }
        if (shardIndex >= 0) add(typeKey)
      }
    }

    // Create sharding context
    val shardingContext = ShardingContext(
      plan = shardingPlan,
      fieldRegistry = shardingPlan.fieldRegistry,
      mainGraphClass = this,
      shardedTypeKeys = shardedTypeKeys,
    )

    // Set sharding context in the binding field context
    bindingFieldContext.shardingContext = shardingContext

    writeDiagnostic("sharded-type-keys-${node.sourceGraph.name.asString()}.txt") {
      buildString {
        appendLine("=== Sharded Type Keys ===")
        shardedTypeKeys.sortedBy { it.toString() }.forEach { key ->
          appendLine(key.toString())
        }
      }
    }

    val ctor = primaryConstructor!!
    val constructorStatements =
      mutableListOf<IrBuilderWithScope.(thisReceiver: IrValueParameter) -> IrStatement>()
    val initStatements =
      mutableListOf<IrBuilderWithScope.(thisReceiver: IrValueParameter) -> IrStatement>()
    val thisReceiverParameter = thisReceiverOrFail

    // Helper function to add bound instance fields
    fun addBoundInstanceField(
      typeKey: IrTypeKey,
      name: Name,
      initializer: IrBuilderWithScope.(thisReceiver: IrValueParameter, typeKey: IrTypeKey) -> IrExpression,
    ) {
      // Don't add it if it's not used
      if (typeKey !in sealResult.reachableKeys) return

      val field = getOrCreateBindingField(
        typeKey,
        {
          fieldNameAllocator.newName(
            name
              .asString()
              .removePrefix("$$")
              .decapitalizeUS()
              .suffixIfNot("Instance")
              .suffixIfNot("Provider")
          )
        },
        { symbols.metroProvider.typeWith(typeKey.type) },
        // Use INTERNAL visibility for BoundInstance fields to avoid synthetic accessors from shards
        DescriptorVisibilities.INTERNAL
      )
        .initFinal {
          instanceFactory(typeKey.type, initializer(thisReceiverParameter, typeKey))
        }

      bindingFieldContext.putProviderField(typeKey, field)

      // Also register in sharding context for inner class access
      shardingContext.mainGraphFields[typeKey] = field
    }

    // Collect bindings and their dependencies for provider field ordering
    val initOrder = parentTracer.traceNested("Collect bindings") {
      val providerFieldBindings = ProviderFieldCollector(bindingGraph).collect()
      buildList(providerFieldBindings.size) {
        for (key in sealResult.sortedKeys) {
          if (key in sealResult.reachableKeys) {
            providerFieldBindings[key]?.let(::add)
          }
        }
      }
    }

    // First, process bound instances and graph dependencies in the main graph
    // These don't get sharded
    node.creator?.let { creator ->
      for ((i, param) in creator.parameters.regularParameters.withIndex()) {
        val isBindsInstance = param.isBindsInstance

        val irParam = ctor.regularParameters[i]

        if (isBindsInstance || creator.bindingContainersParameterIndices.isSet(i)) {
          addBoundInstanceField(param.typeKey, param.name) { _, _ -> irGet(irParam) }
        } else {
          // It's a graph dep. Add all its accessors as available keys
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
          bindingFieldContext.putInstanceField(param.typeKey, graphDepField)
          bindingFieldContext.putInstanceField(graphDep.typeKey, graphDepField)

          if (graphDep.hasExtensions) {
            val depMetroGraph = graphDep.sourceGraph.metroGraphOrFail
            val sourceGraph = depMetroGraph.sourceGraphIfMetroGraph
            val paramName = sourceGraph.name
            addBoundInstanceField(param.typeKey, paramName) { _, _ -> irGet(irParam) }
          }
        }
      }
    }

    // Register the graph's own provider field if it's needed
    // This is necessary for shards that depend on the graph itself as a BoundInstance
    if (node.typeKey in sealResult.reachableKeys) {
      val thisGraphField =
        addSimpleInstanceField(fieldNameAllocator.newName("thisGraphInstance"), node.typeKey) {
          irGet(thisReceiverParameter)
        }

      bindingFieldContext.putInstanceField(node.typeKey, thisGraphField)

      // Expose the graph as a provider field
      val field =
        getOrCreateBindingField(
          node.typeKey,
          { fieldNameAllocator.newName("thisGraphInstanceProvider") },
          { symbols.metroProvider.typeWith(node.typeKey.type) },
          // Use INTERNAL visibility to avoid synthetic accessors from shards
          DescriptorVisibilities.INTERNAL
        )

      bindingFieldContext.putProviderField(
        node.typeKey,
        field.initFinal {
          instanceFactory(
            node.typeKey.type,
            irGetField(irGet(thisReceiverParameter), thisGraphField),
          )
        },
      )

      // Register in mainGraphFields with both implementation and interface types
      shardingContext.mainGraphFields[node.typeKey] = field

      // Also register with the interface type if it exists
      // The sourceGraph is the interface (e.g., AppComponent)
      // while node.typeKey is the implementation (e.g., AppComponent.$$MetroGraph)
      val interfaceTypeKey = IrTypeKey(node.sourceGraph)
      if (interfaceTypeKey != node.typeKey) {
        shardingContext.mainGraphFields[interfaceTypeKey] = field
      }

    }

    // Generate shard classes
    val shards = shardingPlan.shards

    // Filter out empty shards before generation
    val nonEmptyShards = shards.filter { shard ->
      val hasNonBoundInstanceBindings = shard.bindingOrdinals.any { ordinal ->
        val binding = initOrder.find { b ->
          shardingContext.plan.bindingOrdinals[b.typeKey] == ordinal
        }
        binding != null && binding !is IrBinding.BoundInstance
      }
      hasNonBoundInstanceBindings
    }

    val ordinalToKey = shardingPlan.bindingOrdinals.entries.associate { (key, ordinal) ->
      ordinal to key
    }
    val shardRequirementsByIndex = nonEmptyShards.associate { shard ->
      shard.index to collectShardRequirements(shard, shardingContext.plan, ordinalToKey, this)
    }

    // Write diagnostic report about shard generation
    if (options.debug) {
      writeDiagnostic("shard-generation-${node.sourceGraph.name.asString()}.txt") {
        buildString {
          appendLine("=== Shard Generation Report for ${node.sourceGraph.name} ===")
          appendLine("Total shards in plan: ${shards.size}")
          appendLine("Non-empty shards after filtering: ${nonEmptyShards.size}")
          if (nonEmptyShards.isEmpty() && shards.isNotEmpty()) {
            appendLine("WARNING: All shards were filtered out as empty (containing only BoundInstance bindings)")
          }
          appendLine()
          appendLine("Shards to be generated:")
          nonEmptyShards.forEachIndexed { index, shard ->
            appendLine("  ${shard.name} (index: ${shard.index}): ${shard.bindingOrdinals.size} bindings")
          }
        }
      }
    }

    // Pre-create shard index mapping and placeholder shard fields
    // This is needed because during shard generation, cross-shard dependencies
    // need to know about the shard field mapping
    nonEmptyShards.forEachIndexed { actualIndex, shard ->
      shardingContext.shardIndexMapping[shard.index] = actualIndex

      // Create a placeholder field that will be properly typed later
      // For now, use Any? as the type - we'll update it after shard class creation
      // Use INTERNAL visibility to avoid synthetic accessor generation issues
      val placeholderField = addField(
        fieldName = "shard${shard.index}".asName(),
        fieldType = pluginContext.irBuiltIns.anyNType, // Temporary type
        fieldVisibility = DescriptorVisibilities.INTERNAL, // Changed from PRIVATE to avoid synthetic accessors
      ).apply {
        isFinal = true
      }

      shardingContext.shardFields[actualIndex] = placeholderField
    }

    // Generate shard classes
    for (shard in nonEmptyShards) {
      val requirements = shardRequirementsByIndex[shard.index].orEmpty()
      generateShardClass(shard, shardingContext, initOrder, requirements)
    }

    // Update shard field types now that shard classes exist
    // and initialize them in the constructor
    for ((actualIndex, shardClass) in shardingContext.shardClasses.withIndex()) {
      // Extract the original shard index from the class name (e.g., "Shard3" -> 3)
      val shardClassName = shardClass.name.asString()
      val originalIndex = shardClassName.removePrefix("Shard").toIntOrNull()
        ?: error("Invalid shard class name: $shardClassName")

      // The mapping should already exist from pre-creation above
      // Just verify it's consistent
      if (shardingContext.shardIndexMapping[originalIndex] != actualIndex) {
        reportCompilerBug("Inconsistent shard index mapping: original=$originalIndex, actual=$actualIndex, mapped=${shardingContext.shardIndexMapping[originalIndex]}")
      }

      // Update the placeholder field with the correct type
      val shardField = shardingContext.shardFields[actualIndex]
        ?: reportCompilerBug("Missing shard field for index $actualIndex")

      // Update the field type to the actual shard class type
      shardField.type = shardClass.defaultType

      // Initialize the shard in the constructor
      constructorStatements.add { thisReceiver ->
        // Pass the outer instance as a constructor parameter
        val constructor = shardClass.primaryConstructor!!
        // Use irCall instead of irCallConstructor for K2 IR compatibility
        val constructorCall = irCall(constructor.symbol).apply {
          // Pass the outer instance as the first argument
          arguments[0] = irGet(thisReceiver)

          val requirementsForShard = shardRequirementsByIndex[originalIndex].orEmpty()
          requirementsForShard.forEachIndexed { paramIndex, requirement ->
            val valueExpression = irGetField(irGet(thisReceiver), requirement.componentField)
            arguments[paramIndex + 1] = valueExpression
          }
        }

        irSetField(
          irGet(thisReceiver),
          shardField,
          constructorCall
        )
      }
    }

    // Write final diagnostic report about shard field mapping
    if (options.debug) {
      writeDiagnostic("shard-field-mapping-${node.sourceGraph.name.asString()}.txt") {
        buildString {
          appendLine("=== Shard Field Mapping Report for ${node.sourceGraph.name} ===")
          appendLine("ShardIndexMapping: ${shardingContext.shardIndexMapping}")
          appendLine("ShardFields keys: ${shardingContext.shardFields.keys}")
          appendLine("Total shard classes: ${shardingContext.shardClasses.size}")
          appendLine()
          appendLine("Field mappings:")
          shardingContext.shardIndexMapping.forEach { (original, actual) ->
            val field = shardingContext.shardFields[actual]
            appendLine("  Original index $original -> Actual index $actual -> Field: ${field?.name}")
          }
          if (shardingContext.shardClasses.isEmpty()) {
            appendLine()
            appendLine("WARNING: No shard classes were generated!")
          }
        }
      }
    }

    // Handle deferred types (same as non-sharded)
    val deferredFields: Map<IrTypeKey, IrField> =
      sealResult.deferredTypes.associateWith { deferredTypeKey ->
        val binding = bindingGraph.requireBinding(deferredTypeKey, IrBindingStack.empty())
        val field =
          getOrCreateBindingField(
            binding.typeKey,
            { fieldNameAllocator.newName(binding.nameHint.decapitalizeUS() + "Provider") },
            { deferredTypeKey.type.wrapInProvider(symbols.metroProvider) },
            DescriptorVisibilities.INTERNAL,
          )
            .withInit(binding.typeKey) { _, _ ->
              irInvoke(
                callee = symbols.metroDelegateFactoryConstructor,
                typeArgs = listOf(deferredTypeKey.type),
              )
            }

        bindingFieldContext.putProviderField(deferredTypeKey, field)
        field
      }

    // Add deferred field initialization statements
    for ((deferredTypeKey, field) in deferredFields) {
      val binding = bindingGraph.requireBinding(deferredTypeKey, IrBindingStack.empty())
      initStatements.add { thisReceiver ->
        irInvoke(
          dispatchReceiver = irGetObject(symbols.metroDelegateFactoryCompanion),
          callee = symbols.metroDelegateFactorySetDelegate,
          typeArgs = listOf(deferredTypeKey.type),
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
                    it.doubleCheck(this@run, symbols, binding.typeKey)
                  }
              },
            ),
        )
      }
    }

    // Add constructor statements
    with(ctor) {
      val originalBody = checkNotNull(body)
      buildBlockBody {
        +originalBody.statements
        for (statement in constructorStatements) {
          +statement(thisReceiverParameter)
        }
        for (statement in initStatements) {
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

        // Write the metadata to the metroGraph class
        graphClass.metroMetadata = metroMetadata
        dependencyGraphNodesByClass(node.sourceGraph.classIdOrFail)?.let { it.proto = graphProto }
      }
    }
  }

  private fun IrClass.generateWithoutSharding() {
    // Original generate() logic starts here

      val ctor = primaryConstructor!!

      val constructorStatements =
        mutableListOf<IrBuilderWithScope.(thisReceiver: IrValueParameter) -> IrStatement>()

      val initStatements =
        mutableListOf<IrBuilderWithScope.(thisReceiver: IrValueParameter) -> IrStatement>()

      val thisReceiverParameter = thisReceiverOrFail

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
                fieldNameAllocator.newName(
                  name
                    .asString()
                    .removePrefix("$$")
                    .decapitalizeUS()
                    .suffixIfNot("Instance")
                    .suffixIfNot("Provider")
                )
              },
              { symbols.metroProvider.typeWith(typeKey.type) },
              // Use INTERNAL visibility for BoundInstance fields to avoid synthetic accessors
              DescriptorVisibilities.INTERNAL
            )
            .initFinal {
              instanceFactory(typeKey.type, initializer(thisReceiverParameter, typeKey))
            },
        )
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
              addSimpleInstanceField(
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
              val sourceGraph = depMetroGraph.sourceGraphIfMetroGraph
              val paramName = sourceGraph.name
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
            { fieldNameAllocator.newName("thisGraphInstanceProvider") },
            { symbols.metroProvider.typeWith(node.typeKey.type) },
            // Use INTERNAL visibility to avoid synthetic accessors
            DescriptorVisibilities.INTERNAL
          )

        bindingFieldContext.putProviderField(
          node.typeKey,
          field.initFinal {
            instanceFactory(
              node.typeKey.type,
              irGetField(irGet(thisReceiverParameter), thisGraphField),
            )
          },
        )
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

    val mainGraphInitBindings = initOrder

    writeDiagnostic("main-graph-init-bindings-${node.sourceGraph.name.asString()}.txt") {
      buildString {
        appendLine("=== Main Graph Init Bindings ===")
        appendLine("Total initOrder=${initOrder.size}, after filtering=${mainGraphInitBindings.size}")
        mainGraphInitBindings.forEach { binding ->
          appendLine(buildString {
            append(binding.typeKey)
            append(" :: ")
            append(binding::class.simpleName)
          })
        }
      }
    }

    // For all deferred types, assign them first as factories
      // TODO For any types that depend on deferred types, they need providers too?
      @Suppress("UNCHECKED_CAST")
      val deferredFields: Map<IrTypeKey, IrField> =
        sealResult.deferredTypes.associateWith { deferredTypeKey ->
          val binding = bindingGraph.requireBinding(deferredTypeKey, IrBindingStack.empty())
          val field =
            getOrCreateBindingField(
                binding.typeKey,
                { fieldNameAllocator.newName(binding.nameHint.decapitalizeUS() + "Provider") },
                { deferredTypeKey.type.wrapInProvider(symbols.metroProvider) },
                DescriptorVisibilities.INTERNAL,
              )
              .withInit(binding.typeKey) { _, _ ->
                irInvoke(
                  callee = symbols.metroDelegateFactoryConstructor,
                  typeArgs = listOf(deferredTypeKey.type),
                )
              }

          bindingFieldContext.putProviderField(deferredTypeKey, field)
          field
        }

      // Create fields in dependency-order
      mainGraphInitBindings
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

      // Use the extracted method to generate provider fields
      val providerFieldResult = generateProviderFields(
        targetClass = this,
        bindings = mainGraphInitBindings
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
          .toList(),
        bindingFieldContext = bindingFieldContext,
        fieldNameAllocator = fieldNameAllocator,
        thisReceiver = thisReceiverParameter,
        expressionGeneratorFactory = expressionGeneratorFactory,
      )

      // The field initializers from the extracted method are already initialized,
      // so we just need to track them for later use
      for ((field, initializer) in providerFieldResult.fieldInitializers) {
        fieldInitializers += (field to initializer)
      }
      for ((field, typeKey) in providerFieldResult.fieldsToTypeKeys) {
        fieldsToTypeKeys[field] = typeKey
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

      // Use the extracted initialization method
      // Convert list to map for the initialization method
      val fieldInitializersMap = fieldInitializers.toMap()
      constructorStatements += initializeFieldsInConstructor(
        targetClass = this,
        fieldInitializers = fieldInitializersMap,
        fieldsToTypeKeys = fieldsToTypeKeys,
        initStatements = initStatements,
        thisReceiverParameter = thisReceiverParameter,
        chunkIfNeeded = true
      )

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

  /**
   * Generates provider fields for the given bindings.
   * This method is reusable for both main graph and shard classes.
   *
   * @param targetClass The class where fields will be generated
   * @param bindings The bindings to generate fields for
   * @param bindingFieldContext The context to store field references
   * @param fieldNameAllocator The name allocator for field names
   * @param thisReceiver The this receiver for the target class
   * @param expressionGeneratorFactory Factory for generating binding expressions
   * @param initializeFields If true, fields will be initialized immediately. If false, only field declarations are created.
   */
  private fun generateProviderFields(
    targetClass: IrClass,
    bindings: List<IrBinding>,
    bindingFieldContext: BindingFieldContext,
    fieldNameAllocator: NameAllocator,
    thisReceiver: IrValueParameter,
    expressionGeneratorFactory: IrGraphExpressionGenerator.Factory,
    initializeFields: Boolean = true,
  ): ProviderFieldGenerationResult {
    val fieldInitializers = mutableMapOf<IrField, FieldInitializer>()
    val fieldsToTypeKeys = mutableMapOf<IrField, IrTypeKey>()

    with(targetClass) {
      bindings.forEach { binding ->
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
              symbols.metroProvider.typeWith(key.type)
            }
          }

        // If we've reserved a field for this key here, pull it out and use that
        val field =
          getOrCreateBindingField(
            binding.typeKey,
            { fieldNameAllocator.newName(binding.nameHint.decapitalizeUS().suffixIfNot(suffix)) },
            { fieldType },
            DescriptorVisibilities.INTERNAL,
          )
        field.visibility = DescriptorVisibilities.INTERNAL

        val accessType =
          if (isProviderType) {
            IrGraphExpressionGenerator.AccessType.PROVIDER
          } else {
            IrGraphExpressionGenerator.AccessType.INSTANCE
          }

        fieldInitializers[field] = { thisRec: IrValueParameter, typeKey: IrTypeKey ->
          createIrBuilder(symbol).run {
            expressionGeneratorFactory
              .create(thisRec)
              .generateBindingCode(binding, accessType = accessType, fieldInitKey = typeKey)
              .letIf(binding.isScoped() && isProviderType) {
                // If it's scoped, wrap it in double-check
                // DoubleCheck.provider(<provider>)
                it.doubleCheck(this@run, symbols, binding.typeKey)
              }
          }
        }
        fieldsToTypeKeys[field] = key
        bindingFieldContext.putProviderField(key, field)
      }
    }

    // Initialize the fields if requested
    if (initializeFields) {
      fieldInitializers.forEach { (field, initializer) ->
        field.withInit(fieldsToTypeKeys[field]!!) { thisRec, typeKey ->
          initializer(thisRec, typeKey)
        }
      }
    }

    return ProviderFieldGenerationResult(fieldInitializers, fieldsToTypeKeys)
  }

  data class ProviderFieldGenerationResult(
    val fieldInitializers: Map<IrField, FieldInitializer>,
    val fieldsToTypeKeys: Map<IrField, IrTypeKey>,
  )

  /**
   * Handles field initialization in the constructor, with support for chunking large graphs.
   * This method is reusable for both main graph and shard classes.
   *
   * @param targetClass The class where initialization will occur
   * @param fieldInitializers Map of fields to their initializers
   * @param fieldsToTypeKeys Map of fields to their type keys
   * @param initStatements Additional initialization statements to execute
   * @param thisReceiverParameter The this receiver for the class
   * @param chunkIfNeeded If true, will chunk initialization across multiple methods for large graphs
   */
  private fun initializeFieldsInConstructor(
    targetClass: IrClass,
    fieldInitializers: Map<IrField, FieldInitializer>,
    fieldsToTypeKeys: Map<IrField, IrTypeKey>,
    initStatements: List<IrBuilderWithScope.(thisReceiver: IrValueParameter) -> IrStatement>,
    thisReceiverParameter: IrValueParameter,
    chunkIfNeeded: Boolean = true,
  ): List<IrBuilderWithScope.(thisReceiver: IrValueParameter) -> IrStatement> {
    val constructorStatements = mutableListOf<IrBuilderWithScope.(thisReceiver: IrValueParameter) -> IrStatement>()

    with(targetClass) {
      if (chunkIfNeeded && options.chunkFieldInits &&
          fieldInitializers.size + initStatements.size > STATEMENTS_PER_METHOD
      ) {
        // Larger graph, split statements
        // Chunk our constructor statements and split across multiple init functions
        val chunks =
          buildList<IrBuilderWithScope.(thisReceiver: IrValueParameter) -> IrStatement> {
            // Add field initializers first
            for ((field, init) in fieldInitializers) {
              add { thisReceiver ->
                // CRITICAL: Preserve shard context for chunked init methods
                // The targetClass might be a shard, and we need to maintain that context
                // when the field initializer expressions are generated
                val isShardClass = targetClass.name.asString().startsWith("Shard")
                if (isShardClass) {
                  // Temporarily set the shard context for this field initialization
                  val savedShardClass = bindingFieldContext.currentShardClass
                  bindingFieldContext.currentShardClass = targetClass
                  try {
                    irSetField(
                      irGet(thisReceiver),
                      field,
                      init(thisReceiver, fieldsToTypeKeys.getValue(field)),
                    )
                  } finally {
                    bindingFieldContext.currentShardClass = savedShardClass
                  }
                } else {
                  irSetField(
                    irGet(thisReceiver),
                    field,
                    init(thisReceiver, fieldsToTypeKeys.getValue(field)),
                  )
                }
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
            addFunction(initName, irBuiltIns.unitType, visibility = DescriptorVisibilities.INTERNAL)
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
        for ((field, init) in fieldInitializers) {
          field.initFinal {
            val typeKey = fieldsToTypeKeys.getValue(field)
            init(thisReceiverParameter, typeKey)
          }
        }
        constructorStatements += initStatements
      }
    }

    return constructorStatements
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
        fieldVisibility = DescriptorVisibilities.INTERNAL,
      )
      .initFinal { initializerExpression() }

  /**
   * Generates a shard inner class containing a subset of bindings.
   */
  private fun IrClass.generateShardClass(
    shard: Shard,
    shardingContext: ShardingContext,
    allBindings: List<IrBinding>,
    requirements: List<ShardRequirement>,
  ) {
    val shardClass = pluginContext.irFactory.buildClass {
      name = shard.name.asName()
      kind = ClassKind.CLASS
      visibility = DescriptorVisibilities.INTERNAL
      modality = Modality.FINAL
      isInner = false // Make it a static nested class instead of inner
      origin = Origins.Default
    }.apply {
      parent = this@generateShardClass
      createThisReceiverParameter()
    }

    // Add the shard class to the main graph
    addChild(shardClass)
    shardingContext.shardClasses.add(shardClass)

    shardClass.apply {
      // Add a field to store the outer class reference (since we're not using inner class)
      val outerField = addField {
        name = "outer".asName()
        type = this@generateShardClass.defaultType
        visibility = DescriptorVisibilities.INTERNAL
        isFinal = true
      }

      // IMPORTANT: Store the outer field reference IMMEDIATELY after creation
      // This ensures it's available during provider field generation
      shardingContext.outerFields[shard.index] = outerField

      // Add primary constructor with outer parameter
      val ctor = addConstructor {
        visibility = DescriptorVisibilities.PUBLIC
        isPrimary = true
        returnType = defaultType
      }

      // Add parameter for outer class reference
      val outerParam = ctor.addValueParameter {
        name = "outer".asName()
        type = this@generateShardClass.defaultType
        origin = Origins.Default
      }

      val thisReceiverParameter = thisReceiverOrFail

      val requirementNameAllocator = NameAllocator()
      val requirementFieldInfos = mutableListOf<RequirementFieldInfo>()
      val previousProviderFields = mutableMapOf<IrTypeKey, IrField?>()
      val previousInstanceFields = mutableMapOf<IrTypeKey, IrField?>()

      for (requirement in requirements) {
        val baseName = requirementNameAllocator.newName(requirement.key.toVariableName())
        val sanitizedBase = baseName.removePrefix("$$").decapitalizeUS()
        val suffix = when (requirement.access) {
          RequirementAccess.PROVIDER -> "Provider"
          RequirementAccess.INSTANCE -> "Instance"
        }
        val parameterName = (sanitizedBase + suffix).suffixIfNot("Param").asName()
        val parameterType = when (requirement.access) {
          RequirementAccess.PROVIDER -> symbols.metroProvider.typeWith(requirement.key.type)
          RequirementAccess.INSTANCE -> requirement.key.type
        }
        val parameter = ctor.addValueParameter {
          name = parameterName
          type = parameterType
          origin = Origins.Default
        }

        val fieldName = fieldNameAllocator.newName((sanitizedBase + suffix).suffixIfNot("Field"))
        val field = addField {
          name = fieldName.asName()
          type = parameterType
          visibility = DescriptorVisibilities.INTERNAL
          isFinal = true
        }

        requirementFieldInfos += RequirementFieldInfo(requirement, parameter, field)

        when (requirement.access) {
          RequirementAccess.PROVIDER -> {
            previousProviderFields[requirement.key] = bindingFieldContext.providerField(requirement.key)
            bindingFieldContext.putProviderField(requirement.key, field)
          }
          RequirementAccess.INSTANCE -> {
            previousInstanceFields[requirement.key] = bindingFieldContext.instanceField(requirement.key)
            bindingFieldContext.putInstanceField(requirement.key, field)
          }
        }
      }

      writeDiagnostic("shard-requirements-${node.sourceGraph.name.asString()}-${shard.name}.txt") {
        buildString {
          appendLine("=== Shard Requirements Report ===")
          appendLine("Shard: ${shard.name}")
          appendLine("Requirement count: ${requirements.size}")
          requirementFieldInfos.forEachIndexed { index, info ->
            appendLine(
              "  [$index] ${info.requirement.key} -> ${info.requirement.access} (field=${info.field.name}, param=${info.parameter.name})"
            )
          }
        }
      }

      // Create a separate name allocator for this shard
      val shardFieldNameAllocator = NameAllocator()

      // Filter bindings for this shard
      val shardBindings = mutableListOf<IrBinding>()
      for (ordinal in shard.bindingOrdinals) {
        // Find the binding with this ordinal
        val binding = allBindings.find { b ->
          shardingContext.plan.bindingOrdinals[b.typeKey] == ordinal
        }
        if (binding != null) {
          shardBindings.add(binding)
        }
      }

      // Filter out BoundInstance bindings - these are handled by the main graph
      val shardProviderBindings = shardBindings.filter { it !is IrBinding.BoundInstance }

      // Skip generation for empty shards but still register a placeholder
      if (shardProviderBindings.isEmpty()) {
        // We still need to maintain the shard index alignment
        // but we don't generate the actual class
        return
      }

      // Generate provider fields for this shard's bindings (excluding BoundInstance)
      // Set the current shard class in the binding context so the expression generator
      // knows to use outer references for BoundInstance fields
      bindingFieldContext.currentShardClass = this
      val providerFieldResult = generateProviderFields(
        targetClass = this,
        bindings = shardProviderBindings,
        bindingFieldContext = bindingFieldContext,
        fieldNameAllocator = shardFieldNameAllocator,
        thisReceiver = thisReceiverParameter,
        expressionGeneratorFactory = expressionGeneratorFactory,
        initializeFields = false, // We'll initialize them separately
      )

      // Register fields in the shard field registry
      for ((field, _) in providerFieldResult.fieldInitializers) {
        val typeKey = providerFieldResult.fieldsToTypeKeys[field]
        if (typeKey != null) {
          val binding = shardProviderBindings.find { it.typeKey == typeKey }
          if (binding != null) {
            shardingContext.fieldRegistry.registerField(
              typeKey = typeKey,
              shardIndex = shard.index,
              field = field,
              fieldName = field.name.asString(),
              binding = binding
            )
          }
        }
      }

      // Initialize fields in the constructor if needed
      // IMPORTANT: Keep currentShardClass set during field initialization
      // so that BoundInstance references can be correctly resolved through outer
      val constructorStatements = try {
        initializeFieldsInConstructor(
          targetClass = this,
          fieldInitializers = providerFieldResult.fieldInitializers,
          fieldsToTypeKeys = providerFieldResult.fieldsToTypeKeys,
          initStatements = emptyList(),
          thisReceiverParameter = thisReceiverParameter,
          chunkIfNeeded = options.chunkFieldInits
        )
      } finally {
        // Reset the shard context AFTER all field initialization is complete
        bindingFieldContext.currentShardClass = null
        for ((key, previousField) in previousProviderFields) {
          if (previousField != null) {
            bindingFieldContext.putProviderField(key, previousField)
          } else {
            bindingFieldContext.removeProviderField(key)
          }
        }
        for ((key, previousField) in previousInstanceFields) {
          if (previousField != null) {
            bindingFieldContext.putInstanceField(key, previousField)
          } else {
            bindingFieldContext.removeInstanceField(key)
          }
        }
      }

      // Set the constructor body
      ctor.body = createIrBuilder(ctor.symbol).irBlockBody {
        // Call super constructor
        +irDelegatingConstructorCall(context.irBuiltIns.anyClass.owner.constructors.single())

        // Set the outer field
        +irSetField(
          irGet(thisReceiverParameter),
          outerField,
          irGet(outerParam)
        )

        // Store constructor parameters for shard requirements
        for (requirementInfo in requirementFieldInfos) {
          +irSetField(
            irGet(thisReceiverParameter),
            requirementInfo.field,
            irGet(requirementInfo.parameter)
          )
        }

        // Add our initialization statements
        for (statement in constructorStatements) {
          +statement(thisReceiverParameter)
        }
      }

      // Generate SwitchingProvider if fastInit is enabled
      if (options.fastInit && shardBindings.isNotEmpty()) {
        val switchingProviderGenerator = IrSwitchingProviderGenerator(
          context = this@IrGraphGenerator,
          shardClass = this,
          shard = shard,
          shardingContext = shardingContext,
          bindingFieldContext = bindingFieldContext
        )
        switchingProviderGenerator.generate()
      }
    }
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

  private fun IrTypeKey.toVariableName(): String {
    return render(short = true, includeQualifier = false)
      .replace(Regex("[^A-Za-z0-9_]"), "_")
  }
}
