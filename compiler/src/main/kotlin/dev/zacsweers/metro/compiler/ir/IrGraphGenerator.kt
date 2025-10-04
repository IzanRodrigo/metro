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
import org.jetbrains.kotlin.backend.jvm.codegen.AnnotationCodegen.Companion.annotationClass
import org.jetbrains.kotlin.ir.util.classId
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
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.fromSymbolOwner
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
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

/**
 * Represents where a requirement comes from.
 */
private enum class RequirementSource {
  /** Field exists in main graph and needs to be passed to shard */
  MAIN_GRAPH,
  /** Field exists in another shard */
  CROSS_SHARD,
}

private data class ShardRequirement(
  val key: IrTypeKey,
  val access: RequirementAccess,
  val source: RequirementSource,
  val componentField: IrField? = null, // May be null for SHARD_LOCAL initially
  val shardIndex: Int? = null, // For CROSS_SHARD requirements
)

private data class RequirementFieldInfo(
  val requirement: ShardRequirement,
  val parameter: IrValueParameter,
  val field: IrField,
)


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
  private val fieldOwnershipRegistry = FieldOwnershipRegistry()
  private val contextAwareFieldFactory = ContextAwareFieldFactory(fieldOwnershipRegistry, metroContext)

  /**
   * To avoid `MethodTooLargeException`, we split field initializations up over multiple constructor
   * inits.
   *
   * @see <a href="https://github.com/ZacSweers/metro/issues/645">#645</a>
   */
  private val fieldInitializers = mutableListOf<Pair<IrField, FieldInitializer>>()
  private val fieldsToTypeKeys = mutableMapOf<IrField, IrTypeKey>()
  init {
    bindingFieldContext.fieldOwnershipRegistry = fieldOwnershipRegistry
  }

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
      fieldOwnershipRegistry = fieldOwnershipRegistry,
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
    shardingContext: ShardingContext,
  ): List<ShardRequirement> {
    if (shard.bindingOrdinals.isEmpty()) return emptyList()

    val requirements = LinkedHashMap<IrTypeKey, ShardRequirement>()
    val processedKeys = mutableSetOf<IrTypeKey>()

    // Helper function to determine if a binding stays in main graph
    fun isMainGraphBinding(binding: IrBinding): Boolean {
      return when (binding) {
        is IrBinding.BoundInstance -> true // Constructor parameters need main graph access
        is IrBinding.ObjectClass -> true // Module containers (singletons) stay in main graph
        is IrBinding.Alias -> true // Simple type aliases stay in main
        is IrBinding.GraphDependency -> binding.fieldAccess != null // External graph references
        is IrBinding.GraphExtensionFactory -> true // Extension factories are handled specially
        is IrBinding.Multibinding -> true // Multibinding containers need aggregation logic in main graph
        is IrBinding.Provided -> true // @Provides methods stay in main graph to avoid cross-shard dependency issues
        else -> {
          // Check if this is a MultibindingElement contribution
          if (binding.typeKey.qualifier?.ir?.annotationClass?.classId == Symbols.ClassIds.MultibindingElement) {
            return true // MultibindingElement contributions must co-locate with their containers in main graph
          }

          // Check if this binding is aliased by any MultibindingElement-qualified alias
          // This handles cases like NavigationFinder.Stub which is aliased by @ContributesIntoSet
          val allBindings = bindingGraph.bindingsSnapshot()
          for ((aliasTypeKey, aliasBinding) in allBindings) {
            if (aliasBinding is IrBinding.Alias &&
                aliasBinding.aliasedType == binding.typeKey &&
                aliasBinding.typeKey.qualifier?.ir?.annotationClass?.classId == Symbols.ClassIds.MultibindingElement) {
              return true // This binding is aliased by a MultibindingElement, so it should stay in main graph
            }
          }

          false // Other bindings can be sharded (ConstructorInjected, etc.)
        }
      }
    }

    // Helper function to add a requirement if it's external to this shard
    fun addExternalRequirement(
      key: IrTypeKey,
      access: RequirementAccess,
      binding: IrBinding? = null
    ) {
      // Skip if already processed
      if (requirements.containsKey(key)) return

      // Check where this dependency lives
      // CRITICAL FIX: Resolve aliases to find the actual implementation
      val resolvedKey = key
      val dependencyOrdinal = shardingPlan.bindingOrdinals[resolvedKey] ?: run {
        // If not found, check if this is an interface with an alias binding
        val aliasBinding = bindingGraph.bindingsSnapshot().values
          .filterIsInstance<IrBinding.Alias>()
          .firstOrNull { it.typeKey == key }

        if (aliasBinding != null) {
          // Found an alias - use the implementation's ordinal
          shardingPlan.bindingOrdinals[aliasBinding.aliasedType]
        } else {
          null
        }
      }

      if (dependencyOrdinal != null) {
        val dependencyShardIndex = shardingPlan.bindingToShard.getOrElse(dependencyOrdinal) { -1 }

        // Skip if it's in the same shard
        if (dependencyShardIndex == shard.index) return

        if (dependencyShardIndex >= 0) {
          // Cross-shard dependency
          // Mark this type key as needing PUBLIC visibility for cross-shard access
          shardingContext.crossShardAccessedTypeKeys.add(key)

          requirements[key] = ShardRequirement(
            key = key,
            access = access,
            source = RequirementSource.CROSS_SHARD,
            componentField = null,
            shardIndex = dependencyShardIndex,
          )
          return
        }

        // Handle case where binding is marked as shardable but not assigned to any shard (-1)
        // This can happen when the sharding algorithm doesn't properly assign all bindings
        if (dependencyShardIndex == -1) {
          // Try to get the actual binding to determine how to handle it
          val actualBinding = binding ?: try {
            bindingGraph.requireBinding(key)
          } catch (e: Exception) {
            null
          }

          // If it should have been in main graph, treat it as main graph requirement
          if (actualBinding != null && isMainGraphBinding(actualBinding)) {
            val componentField = when (access) {
              RequirementAccess.PROVIDER -> bindingFieldContext.providerField(key)
              RequirementAccess.INSTANCE -> bindingFieldContext.instanceField(key)
            }

            if (componentField != null && componentField.parent == graphClass) {
              requirements[key] = ShardRequirement(
                key = key,
                access = access,
                source = RequirementSource.MAIN_GRAPH,
                componentField = componentField,
                shardIndex = null,
              )
            }
          }
          return
        }
      }

      // Check if it's a main graph binding
      val actualBinding = binding ?: try {
        bindingGraph.requireBinding(key)
      } catch (e: Exception) {
        null
      }

      if (actualBinding != null && isMainGraphBinding(actualBinding)) {
        val componentField = when (access) {
          RequirementAccess.PROVIDER -> bindingFieldContext.providerField(key)
          RequirementAccess.INSTANCE -> bindingFieldContext.instanceField(key)
        }

        if (componentField != null && componentField.parent == graphClass) {
          requirements[key] = ShardRequirement(
            key = key,
            access = access,
            source = RequirementSource.MAIN_GRAPH,
            componentField = componentField,
            shardIndex = null,
          )
        }
      }
    }

    // Process each binding in this shard
    for (ordinal in shard.bindingOrdinals) {
      val bindingKey = ordinalToKey[ordinal] ?: continue
      if (processedKeys.contains(bindingKey)) continue
      processedKeys.add(bindingKey)

      val binding = bindingGraph.requireBinding(bindingKey)

      // Check if the binding itself needs main graph access (e.g., GraphDependency)
      if (binding is IrBinding.GraphDependency) {
        val componentField = bindingFieldContext.providerField(bindingKey)
        if (componentField != null && componentField.parent == graphClass) {
          // This binding itself needs access to the main graph
          addExternalRequirement(bindingKey, RequirementAccess.PROVIDER, binding)
        }
      }

      // Process all direct dependencies
      for (dependency in binding.dependencies) {
        val dependencyKey = dependency.typeKey
        // CRITICAL FIX: Always use PROVIDER access for external dependencies
        // This ensures lazy initialization and proper scoping across shards
        // Even if requiresProviderInstance is false, we should pass providers
        // to maintain consistency and avoid eager instantiation issues
        val access = RequirementAccess.PROVIDER

        addExternalRequirement(dependencyKey, access)
      }

      // Handle bindings that might have additional dependencies beyond the declared ones
      // Most binding types are already handled through the general dependency processing above
      // but we can add specific handling for special cases if needed in the future
    }

    // Add diagnostic logging
    if (options.debug) {
      writeDiagnostic("shard-${shard.index}-dependency-analysis.txt") {
        buildString {
          appendLine("=== Shard ${shard.index} Dependency Analysis ===")
          appendLine("Bindings in this shard: ${shard.bindingOrdinals.size}")
          appendLine()

          // Show which bindings are in this shard
          appendLine("Bindings assigned to this shard:")
          shard.bindingOrdinals.take(20).forEach { ordinal ->
            val key = ordinalToKey[ordinal]
            if (key != null) {
              val binding = try {
                bindingGraph.requireBinding(key)
              } catch (e: Exception) {
                null
              }
              appendLine("  [$ordinal] $key - ${binding?.javaClass?.simpleName ?: "Unknown"}")
            }
          }
          if (shard.bindingOrdinals.size > 20) {
            appendLine("  ... and ${shard.bindingOrdinals.size - 20} more")
          }
          appendLine()

          // Show external requirements
          appendLine("External requirements (${requirements.size} total):")
          requirements.values.forEach { req ->
            appendLine("  ${req.key}:")
            appendLine("    - Source: ${req.source}")
            appendLine("    - Access: ${req.access}")
            if (req.shardIndex != null) {
              appendLine("    - From shard: ${req.shardIndex}")
            }
          }

          // Show any bindings that should have stayed in main graph
          appendLine()
          appendLine("Bindings that should stay in main graph (sanity check):")
          var foundMainGraphBindings = 0
          for (ordinal in shard.bindingOrdinals) {
            val key = ordinalToKey[ordinal] ?: continue
            val binding = try {
              bindingGraph.requireBinding(key)
            } catch (e: Exception) {
              continue
            }
            if (isMainGraphBinding(binding)) {
              appendLine("  WARNING: $key (${binding.javaClass.simpleName}) should be in main graph!")
              foundMainGraphBindings++
            }
          }
          if (foundMainGraphBindings == 0) {
            appendLine("  None found (good!)")
          }
        }
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
    bindingFieldContext.refreshOwnership()

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

      bindingFieldContext.putProviderField(typeKey, field, BindingFieldContext.FieldOwner.MainGraph)

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
              // Unwrap providers for instance fields (same logic as sharded path)
              val paramValue = irGet(irParam)
              // Check if type is Provider or any subtype (e.g., DoubleCheck)
              val providerType = symbols.metroProvider.typeWith(pluginContext.irBuiltIns.anyNType)
              if (paramValue.type.isSubtypeOf(providerType, irTypeSystemContext)) {
                irCall(symbols.providerInvoke).apply { dispatchReceiver = paramValue }
              } else {
                paramValue
              }
            }
          bindingFieldContext.putInstanceField(param.typeKey, graphDepField, BindingFieldContext.FieldOwner.MainGraph)
          bindingFieldContext.putInstanceField(graphDep.typeKey, graphDepField, BindingFieldContext.FieldOwner.MainGraph)

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

      bindingFieldContext.putInstanceField(node.typeKey, thisGraphField, BindingFieldContext.FieldOwner.MainGraph)

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
        BindingFieldContext.FieldOwner.MainGraph
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

    // Build a set of ordinals that have provider fields (from initOrder)
    // Store in ShardingContext so SwitchingProvider can access it
    shardingContext.ordinalsWithProviderFields = initOrder.mapNotNull { binding ->
      shardingContext.plan.bindingOrdinals[binding.typeKey]
    }.toSet()

    // CRITICAL: Update bindingToShard for ordinals without provider fields BEFORE requirement collection
    // Mark them as unassigned (-1) so they're treated as main graph bindings, not cross-shard
    for (shard in nonEmptyShards) {
      shard.bindingOrdinals.forEach { ordinal ->
        if (ordinal !in shardingContext.ordinalsWithProviderFields) {
          shardingContext.plan.bindingToShard[ordinal] = -1
        }
      }
    }

    val shardRequirementsByIndex = nonEmptyShards.associate { shard ->
      shard.index to collectShardRequirements(shard, shardingContext.plan, ordinalToKey, this, shardingContext)
    }

    // Collect all required type keys from shard requirements that need early initialization
    val requiredMultibindingKeys = shardRequirementsByIndex.values.flatten()
      .filter { requirement -> requirement.source == RequirementSource.MAIN_GRAPH }
      .map { it.key }
      .distinct()
      .filter { key ->
        // Check if this is a multibinding that needs early initialization
        val binding = bindingGraph.requireBinding(key)
        binding is IrBinding.Multibinding
      }
      .toSet()

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
      // Use PUBLIC visibility for cross-shard access without synthetic accessors
      val placeholderField = addField(
        fieldName = "shard${shard.index}".asName(),
        fieldType = pluginContext.irBuiltIns.anyNType, // Temporary type
        fieldVisibility = DescriptorVisibilities.PUBLIC, // PUBLIC for cross-shard access
      ).apply {
        isFinal = true
      }

      shardingContext.shardFields[actualIndex] = placeholderField
    }

    // Generate shard classes and update field types immediately
    // IMPORTANT: We must update each shard field's type right after generating its class,
    // so that later shards can correctly access earlier shard fields with proper types
    for (shard in nonEmptyShards) {
      val requirements = shardRequirementsByIndex[shard.index].orEmpty()
      generateShardClass(shard, shardingContext, initOrder, requirements, expressionGeneratorFactory)

      // Update the shard field type IMMEDIATELY after generating the shard class
      // This ensures cross-shard field access has the correct type information
      val actualIndex = shardingContext.shardIndexMapping[shard.index]
        ?: error("Missing shard index mapping for shard ${shard.index}")
      val shardClass = shardingContext.shardClasses[actualIndex]
      val shardField = shardingContext.shardFields[actualIndex]
        ?: error("Missing shard field for index $actualIndex")
      shardField.type = shardClass.defaultType
    }

    // Update shard field types and initialize them IN ORDER
    // IMPORTANT: We must initialize each shard before the next one needs to access its fields
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

      // Get the shard field (type already updated above)
      val shardField = shardingContext.shardFields[actualIndex]
        ?: reportCompilerBug("Missing shard field for index $actualIndex")

      // Build the initialization statement for this shard
      // We'll execute them in order to ensure each shard is available for the next
      val shardInitStatement: IrBuilderWithScope.(thisReceiver: IrValueParameter) -> IrStatement = { thisReceiver ->
        // Pass the outer instance as a constructor parameter
        val constructor = shardClass.primaryConstructor!!
        // Get external requirements (MAIN_GRAPH and CROSS_SHARD)
        val requirementsForShard = shardRequirementsByIndex[originalIndex].orEmpty()

        // Use irCall instead of irCallConstructor for K2 IR compatibility
        val constructorCall = irCall(constructor.symbol).apply {
          // Initialize arguments list with correct size
          val totalParams = 1 + requirementsForShard.size // graph param + requirements
          for (i in 0 until totalParams) {
            arguments.add(null) // Pre-size the list
          }

          // Pass the outer instance as the first argument (always at index 0)
          arguments[0] = irGet(thisReceiver)

          requirementsForShard.forEachIndexed { paramIndex, requirement ->
            val rawValueExpression = when (requirement.source) {
              RequirementSource.MAIN_GRAPH -> {
                requirement.componentField?.let { field ->
                  irGetField(irGet(thisReceiver), field)
                } ?: error("Missing component field for main graph requirement: ${requirement.key}")
              }
              RequirementSource.CROSS_SHARD -> {
                val shardIndex = requirement.shardIndex
                  ?: error("Missing shard index for cross-shard requirement: ${requirement.key}")
                val actualShardIndex = shardingContext.shardIndexMapping[shardIndex]
                  ?: error("No mapping for shard index $shardIndex")
                val shardField = shardingContext.shardFields[actualShardIndex]
                  ?: error("No shard field for index $actualShardIndex")

                val otherShardAccess = irGetField(irGet(thisReceiver), shardField)
                val fieldInOtherShard = shardingContext.fieldRegistry.findField(requirement.key)?.field
                  ?: error("Field not found in registry for ${requirement.key}")
                irGetField(otherShardAccess, fieldInOtherShard)
              }
            }

            // CRITICAL FIX: Always pass providers directly to maintain lazy initialization
            // Since we've changed all parameters to Provider<T>, we always pass the provider
            // without unwrapping it. The shard will handle any necessary unwrapping internally.
            val valueExpression = rawValueExpression

            // Parameters after the graph parameter (which is at index 0)
            arguments[paramIndex + 1] = valueExpression
          }
        }

        irSetField(
          irGet(thisReceiver),
          shardField,
          constructorCall
        )
      }

      // Add to constructor statements - these will be executed in order
      constructorStatements.add(shardInitStatement)
    }

    // CRITICAL: Add early initialization for multibinding fields required by shards
    // These must be initialized BEFORE the shard instantiation statements
    if (requiredMultibindingKeys.isNotEmpty()) {
      val earlyInitStatements = mutableListOf<IrBuilderWithScope.(thisReceiver: IrValueParameter) -> IrStatement>()

      for (typeKey in requiredMultibindingKeys) {
        val binding = bindingGraph.requireBinding(typeKey)
        if (binding is IrBinding.Multibinding) {
          val field = bindingFieldContext.providerFieldDescriptor(typeKey)?.field
          if (field != null) {
            // Add an early initialization statement for this multibinding field
            earlyInitStatements.add { thisReceiver ->
              val initExpression = createIrBuilder(symbol).run {
                expressionGeneratorFactory
                  .create(thisReceiver)
                  .generateBindingCode(
                    binding,
                    accessType = IrGraphExpressionGenerator.AccessType.PROVIDER,
                    fieldInitKey = null // Don't skip field access
                  )
              }
              irSetField(irGet(thisReceiver), field, initExpression)
            }
          }
        }
      }

      // Insert early init statements at the beginning of constructor statements
      constructorStatements.addAll(0, earlyInitStatements)
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
        val binding = bindingGraph.requireBinding(deferredTypeKey)
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

        bindingFieldContext.putProviderField(deferredTypeKey, field, BindingFieldContext.FieldOwner.MainGraph)
        field
      }

    // Add deferred field initialization statements
    for ((deferredTypeKey, field) in deferredFields) {
      val binding = bindingGraph.requireBinding(deferredTypeKey)
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
                  .create()
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
          BindingFieldContext.FieldOwner.MainGraph,
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
                // Unwrap providers for instance fields (mirrors shard requirement logic at lines 642-650)
                val paramValue = irGet(irParam)
                // Check if type is Provider or any subtype (e.g., DoubleCheck)
                val providerType = symbols.metroProvider.typeWith(pluginContext.irBuiltIns.anyNType)
                if (paramValue.type.isSubtypeOf(providerType, irTypeSystemContext)) {
                  irCall(symbols.providerInvoke).apply { dispatchReceiver = paramValue }
                } else {
                  paramValue
                }
              }
            // Link both the graph typekey and the (possibly-impl type)
            bindingFieldContext.putInstanceField(param.typeKey, graphDepField, BindingFieldContext.FieldOwner.MainGraph)
            bindingFieldContext.putInstanceField(graphDep.typeKey, graphDepField, BindingFieldContext.FieldOwner.MainGraph)

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
            // Can't use primaryConstructor here because it may be a Java dagger Module in interop
            val noArgConstructor = clazz.constructors.first { it.parameters.isEmpty() }
            irCallConstructor(noArgConstructor.symbol, emptyList())
          }
        }

      // Don't add it if it's not used
      if (node.typeKey in sealResult.reachableKeys) {
        val thisGraphField =
          addSimpleInstanceField(fieldNameAllocator.newName("thisGraphInstance"), node.typeKey) {
            irGet(thisReceiverParameter)
          }

        bindingFieldContext.putInstanceField(node.typeKey, thisGraphField, BindingFieldContext.FieldOwner.MainGraph)

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
          BindingFieldContext.FieldOwner.MainGraph,
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
          val binding = bindingGraph.requireBinding(deferredTypeKey)
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

          bindingFieldContext.putProviderField(deferredTypeKey, field, BindingFieldContext.FieldOwner.MainGraph)
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

      // Debug multibinding filtering
      val multibindingsInInitOrder = mainGraphInitBindings
        .filterIsInstance<IrBinding.Multibinding>()
        .filter { !it.isSet && it.sourceBindings.size >= 50 }

      writeDiagnostic("multibinding-filtering-debug.txt") {
        buildString {
          appendLine("=== Multibinding Filtering Debug ===")
          appendLine("Total bindings in initOrder: ${mainGraphInitBindings.size}")
          appendLine("Large map multibindings: ${multibindingsInInitOrder.size}")
          multibindingsInInitOrder.forEach { mb ->
            appendLine()
            appendLine("Multibinding: ${mb.typeKey}")
            appendLine("  Size: ${mb.sourceBindings.size}")
            appendLine("  In deferredFields: ${mb.typeKey in deferredFields}")
            appendLine("  In bindingFieldContext: ${mb.typeKey in bindingFieldContext}")
            appendLine("  Has provider field: ${bindingFieldContext.providerFieldDescriptor(mb.typeKey) != null}")
            appendLine("  Has instance field: ${bindingFieldContext.instanceFieldDescriptor(mb.typeKey) != null}")
          }
        }
      }

      // Use the extracted method to generate provider fields
      val providerFieldResult = generateProviderFields(
        targetClass = this,
        bindings = mainGraphInitBindings
          .asSequence()
          .filterNot {
            // Special handling for Multibindings - they should NOT be filtered out
            // even if they're already in bindingFieldContext
            if (it is IrBinding.Multibinding) {
              // Check if we already have a field for this multibinding
              val existingField = bindingFieldContext.providerFieldDescriptor(it.typeKey)
              if (existingField != null) {
                writeDiagnostic("multibinding-already-has-field.txt") {
                  "Multibinding ${it.typeKey} already has field: ${existingField.field.name}"
                }
                // Skip if we already created it (e.g., in early initialization)
                return@filterNot true
              }
              // Otherwise, let it through to get a provider field
              writeDiagnostic("multibinding-needs-field.txt") {
                "Multibinding ${it.typeKey} needs a provider field (size=${(it as? IrBinding.Multibinding)?.sourceBindings?.size})"
              }
              return@filterNot false
            }

            // Original filtering logic for non-multibindings
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
        fieldOwner = BindingFieldContext.FieldOwner.MainGraph,
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
        val binding = bindingGraph.requireBinding(deferredTypeKey)
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
                    .create()
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
    fieldNameAllocator: NameAllocator?,
    thisReceiver: IrValueParameter,
    expressionGeneratorFactory: IrGraphExpressionGenerator.Factory,
    fieldOwner: BindingFieldContext.FieldOwner,
    initializeFields: Boolean = true,
    shardingContext: ShardingContext? = null,
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
        // Use GlobalFieldNameRegistry if in sharding context, otherwise use NameAllocator
        val fieldName = if (shardingContext != null) {
          // Use global registry for shard fields to ensure uniqueness across all shards
          // Extract shard index from fieldOwner for debugging
          val shardIndex = (fieldOwner as? BindingFieldContext.FieldOwner.Shard)?.index
          shardingContext.globalFieldNameRegistry.generateUniqueFieldName(
            baseName = binding.nameHint.decapitalizeUS(),
            typeHint = suffix,
            shardIndex = shardIndex
          )
        } else {
          // Regular field name generation for non-sharded context
          requireNotNull(fieldNameAllocator) {
            "NameAllocator must be provided when not in sharding context"
          }.newName(binding.nameHint.decapitalizeUS().suffixIfNot(suffix))
        }
        val isShardContext = bindingFieldContext.currentShardClass == this

        // Special dual-field handling in shard + fastInit context to avoid SwitchingProvider recursion
        if (isShardContext && options.fastInit && isProviderType && shardingContext != null) {
          // Build distinct names for backing and accessor fields via global registry
          val shardIndex = (fieldOwner as? BindingFieldContext.FieldOwner.Shard)?.index
          val baseName = binding.nameHint.decapitalizeUS()
          val backingFieldName = shardingContext.globalFieldNameRegistry.generateUniqueFieldName(
            baseName = baseName,
            typeHint = "BackingProvider",
            shardIndex = shardIndex
          ).asName()
          val accessorFieldName = shardingContext.globalFieldNameRegistry.generateUniqueFieldName(
            baseName = baseName,
            typeHint = "Provider",
            shardIndex = shardIndex
          ).asName()

          // Visibility: PUBLIC if cross-shard accessed, else INTERNAL
          val isCrossShard = shardingContext.crossShardAccessedTypeKeys.contains(binding.typeKey)
          val fieldVisibility = if (isCrossShard) DescriptorVisibilities.PUBLIC else DescriptorVisibilities.INTERNAL

          // Create backing provider field (normal generation)
          val backingField = addField {
            name = backingFieldName
            type = symbols.metroProvider.typeWith(key.type)
            visibility = fieldVisibility
          }
          // Register BACKING field in BindingFieldContext so SwitchingProvider delegate uses this
          bindingFieldContext.putProviderField(key, backingField, fieldOwner)

          // Create accessor provider field (will hold SwitchingProvider)
          val accessorField = addField {
            name = accessorFieldName
            type = symbols.metroProvider.typeWith(key.type)
            visibility = fieldVisibility
          }

          // Backing field initializer: normal provider code path
          fieldInitializers[backingField] = { thisRec: IrValueParameter, typeKey: IrTypeKey ->
            createIrBuilder(symbol).run {
              expressionGeneratorFactory
                .create(thisRec, shardIndex)
                .generateBindingCode(binding, accessType = IrGraphExpressionGenerator.AccessType.PROVIDER, fieldInitKey = typeKey)
                .letIf(binding.isScoped()) {
                  it.doubleCheck(this@run, symbols, binding.typeKey)
                }
            }
          }
          fieldsToTypeKeys[backingField] = key

          // Accessor field initializer: SwitchingProvider if available; otherwise delegate to backing field
          fieldInitializers[accessorField] = { thisRec: IrValueParameter, _: IrTypeKey ->
            createIrBuilder(symbol).run {
              // Get switching provider class for this shard
              val switchingProviderClass = shardingContext.switchingProviders[shardIndex!!]
              if (switchingProviderClass != null) {
                val ctor = switchingProviderClass.constructors.first()
                irCall(ctor.symbol).apply {
                  // Prepare value arguments (3 total)
                  for (i in 0 until 3) {
                    arguments.add(null)
                  }
                  // arg0: shard instance (this)
                  arguments[0] = irGet(thisRec)
                  // arg1: graph instance via outer field if present
                  val outerField = shardingContext.outerFields[shardIndex]
                  val graphInstance = if (outerField != null) {
                    irGetField(irGet(thisRec), outerField)
                  } else {
                    irGet(thisRec)
                  }
                  arguments[1] = graphInstance
                  // arg2: unique id for this binding in this shard
                  val currentId = shardingContext.switchingProviderIdCounters.getOrPut(shardIndex) { 0 }
                  shardingContext.switchingProviderIdCounters[shardIndex] = currentId + 1
                  arguments[2] = irInt(currentId)
                }
              } else {
                // Fallback: delegate to backing provider field directly
                irGetField(irGet(thisRec), backingField)
              }
            }
          }
          fieldsToTypeKeys[accessorField] = key

          // Skip default single-field logic for this binding
          return@forEach
        }

        val field =
          if (isShardContext) {
            // Shards need their own provider fields; do not reuse the main graph's reserved fields
            // Debug logging for Shard0 field creation
            if (targetClass.name.asString().contains("Shard0")) {
              println("[IrGraphGenerator] Adding provider field to Shard0:")
              println("  Field name: $fieldName")
              println("  Binding: ${binding.nameHint}")
              println("  Type: ${binding.typeKey}")
              println("  isShardContext: $isShardContext")

              // Check if field already exists in the class
              val existingField = targetClass.declarations.filterIsInstance<IrField>()
                .firstOrNull { it.name.asString() == fieldName }
              if (existingField != null) {
                println("  WARNING: Field '$fieldName' already exists in ${targetClass.name}!")
                println("  Existing field type: ${existingField.type}")
                println("  New field type would be: $fieldType")
                println("  This would cause a ClassFormatError!")
              }
            }

            // Simply create the field - the GlobalFieldNameRegistry should ensure uniqueness
            // Use PUBLIC visibility for cross-shard accessed fields to avoid synthetic accessor issues
            val fieldVisibility = if (shardingContext?.crossShardAccessedTypeKeys?.contains(binding.typeKey) == true) {
              DescriptorVisibilities.PUBLIC
            } else {
              DescriptorVisibilities.INTERNAL
            }
            addField {
              name = fieldName.asName()
              type = fieldType
              visibility = fieldVisibility
            }
          } else {
            // Use PUBLIC visibility for cross-shard accessed fields to avoid synthetic accessor issues
            val fieldVisibility = if (shardingContext?.crossShardAccessedTypeKeys?.contains(binding.typeKey) == true) {
              DescriptorVisibilities.PUBLIC
            } else {
              DescriptorVisibilities.INTERNAL
            }
            getOrCreateBindingField(
              binding.typeKey,
              { fieldName },
              { fieldType },
              fieldVisibility,
            )
          }
        // Ensure correct visibility is set for cross-shard accessed fields
        val finalVisibility = if (shardingContext?.crossShardAccessedTypeKeys?.contains(binding.typeKey) == true) {
          DescriptorVisibilities.PUBLIC
        } else {
          DescriptorVisibilities.INTERNAL
        }
  field.visibility = finalVisibility

        val accessType =
          if (isProviderType) {
            IrGraphExpressionGenerator.AccessType.PROVIDER
          } else {
            IrGraphExpressionGenerator.AccessType.INSTANCE
          }

        fieldInitializers[field] = { thisRec: IrValueParameter, typeKey: IrTypeKey ->
          createIrBuilder(symbol).run {
            // Extract shard index from fieldOwner if it's a shard
            val shardIndex = (fieldOwner as? BindingFieldContext.FieldOwner.Shard)?.index

            // Use SwitchingProvider when:
            // 1. fastInit is enabled
            // 2. It's a provider type
            // 3. We're IN a shard context (generating fields for the shard itself)
            // Dagger uses SwitchingProvider for all provider fields within shards
            if (options.fastInit && isProviderType && isShardContext && shardIndex != null) {
              // In shard fastInit single-field case, still fall back to regular providers (dual-field handled above)
              expressionGeneratorFactory
                .create(thisRec, shardIndex)
                .generateBindingCode(binding, accessType = accessType, fieldInitKey = typeKey)
                .letIf(binding.isScoped() && isProviderType) {
                  it.doubleCheck(this@run, symbols, binding.typeKey)
                }
            } else {
              // Original implementation for non-shard or non-fastInit cases
              expressionGeneratorFactory
                .create(thisRec, shardIndex)
                .generateBindingCode(binding, accessType = accessType, fieldInitKey = typeKey)
                .letIf(binding.isScoped() && isProviderType) {
                  it.doubleCheck(this@run, symbols, binding.typeKey)
                }
            }
          }
        }
        fieldsToTypeKeys[field] = key
        bindingFieldContext.putProviderField(key, field, fieldOwner)
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
          fieldInitializers.size + initStatements.size > MetroConstants.STATEMENTS_PER_METHOD
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
          .chunked(MetroConstants.STATEMENTS_PER_METHOD)

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
    expressionGeneratorFactory: IrGraphExpressionGenerator.Factory,
  ) {
    val shardScaffold = IrShardGenerator(
      context = this@IrGraphGenerator,
      parentClass = this,
      shard = shard,
      shardingContext = shardingContext,
    ).generateShardClass()
    val shardClass = shardScaffold.shardClass
    shardingContext.shardClasses.add(shardClass)
    shardingContext.outerFields[shard.index] = shardScaffold.outerField

    shardClass.apply {
      val ctor = shardScaffold.constructor
      val thisReceiverParameter = thisReceiverOrFail

      val requirementFieldInfos = mutableListOf<RequirementFieldInfo>()
      val baseConstructorStatements = ctor.body?.statements?.toList().orEmpty()
      val previousProviderFields = mutableMapOf<IrTypeKey, BindingFieldContext.FieldDescriptor?>()
      val previousInstanceFields = mutableMapOf<IrTypeKey, BindingFieldContext.FieldDescriptor?>()

      // Handle external requirements (MAIN_GRAPH and CROSS_SHARD) - these need constructor parameters
      // CRITICAL FIX: Always use Provider<T> for external dependencies to ensure proper lazy initialization
      for (requirement in requirements) {
        val baseName = requirement.key.toVariableName()
        val sanitizedBase = baseName.removePrefix("$$").decapitalizeUS()
        // Always use Provider suffix since we're passing Provider<T>
        val parameterName = (sanitizedBase + "Provider").suffixIfNot("Param").asName()
        // Always use Provider<T> type for parameters
        val parameterType = symbols.metroProvider.typeWith(requirement.key.type)

        val parameter = ctor.addValueParameter {
          name = parameterName
          type = parameterType
          origin = Origins.Default
        }

        val fieldName = shardingContext.globalFieldNameRegistry.generateUniqueFieldName(
          baseName = (sanitizedBase + "Provider"),
          typeHint = "Field",
          shardIndex = shard.index
        )

        // Use ContextAwareFieldFactory to create field with correct parent metadata
        val field = contextAwareFieldFactory.createParameterField(
          key = requirement.key,
          context = ContextAwareFieldFactory.FieldContext.Shard(shardClass, shard.index),
          fieldName = fieldName.asName(),
          fieldType = parameterType  // Always Provider<T>
        )

        // Add the field to the shard class
        // Debug logging for Shard0 requirement field addition
        if (shard.index == 0) {
          println("[IrGraphGenerator] Adding requirement field to Shard0:")
          println("  Field name: ${field.name}")
          println("  Requirement: ${requirement.key}")
          println("  Access type: ${requirement.access}")

          // Check for existing field with same name
          val existingField = shardClass.declarations.filterIsInstance<IrField>()
            .firstOrNull { it.name.asString() == field.name.asString() }
          if (existingField != null) {
            println("  ERROR: Field '${field.name}' already exists in Shard0!")
            println("  This will cause a ClassFormatError!")
          }
        }

        // Only add field if it doesn't already exist
        val existingField = shardClass.declarations.filterIsInstance<IrField>()
          .firstOrNull { it.name.asString() == field.name.asString() }
        if (existingField == null) {
          shardClass.addChild(field)
        } else {
          println("[IrGraphGenerator] DUPLICATE PREVENTED: Requirement field '${field.name}' already exists in ${shardClass.name}, skipping addition")
        }

        // Register in fieldOwnershipRegistry as shard field
        fieldOwnershipRegistry.registerShardField(requirement.key, shard.index, field)

        shardingContext.requirementFields
          .getOrPut(shard.index) { mutableMapOf() }[requirement.key] = field

        requirementFieldInfos += RequirementFieldInfo(requirement, parameter, field)

        // Always register as provider field since we're storing Provider<T>
        previousProviderFields[requirement.key] = bindingFieldContext.providerFieldDescriptor(requirement.key)
        bindingFieldContext.putProviderField(requirement.key, field, BindingFieldContext.FieldOwner.Shard(shard.index))
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

      // Filter bindings for this shard to only those with provider fields
      // Bindings without provider fields (e.g., used only once, not scoped) should not be in shards
      // Note: bindingToShard was already updated earlier (before requirement collection)
      val (filteredOrdinals, skippedOrdinals) = shard.bindingOrdinals.partition { it in shardingContext.ordinalsWithProviderFields }

      val shardBindings = mutableListOf<IrBinding>()
      val missingOrdinals = mutableListOf<Int>()
      for (ordinal in filteredOrdinals) {
        // Find the binding with this ordinal
        val binding = allBindings.find { b ->
          shardingContext.plan.bindingOrdinals[b.typeKey] == ordinal
        }
        if (binding != null) {
          shardBindings.add(binding)
        } else {
          missingOrdinals.add(ordinal)
        }
      }

      // Filter out BoundInstance bindings - these are handled by the main graph
      val shardProviderBindings = shardBindings.filter { it !is IrBinding.BoundInstance }

      // Write diagnostic about what bindings are in this shard
      writeDiagnostic("shard-bindings-${shard.name}.txt") {
        buildString {
          appendLine("=== Bindings for ${shard.name} ===")
          appendLine("Ordinals originally assigned: ${shard.bindingOrdinals.size}")
          appendLine("Ordinals with provider fields: ${filteredOrdinals.size}")
          appendLine("Ordinals skipped (no provider field): ${skippedOrdinals.size}")
          if (skippedOrdinals.isNotEmpty()) {
            appendLine("\nSkipped ordinals (these don't need provider fields):")
            skippedOrdinals.take(10).forEach { ordinal ->
              val typeKey = shardingContext.plan.bindingOrdinals.entries.find { it.value == ordinal }?.key
              appendLine("  [ord=$ordinal] ${typeKey ?: "<unknown>"}")
            }
            if (skippedOrdinals.size > 10) {
              appendLine("  ... and ${skippedOrdinals.size - 10} more")
            }
          }
          appendLine("Bindings found in initOrder: ${shardBindings.size}")
          if (missingOrdinals.isNotEmpty()) {
            appendLine("\nERROR: Missing ordinals (in filter but not in initOrder):")
            missingOrdinals.forEach { ordinal ->
              val typeKey = shardingContext.plan.bindingOrdinals.entries.find { it.value == ordinal }?.key
              appendLine("  [ord=$ordinal] ${typeKey ?: "<unknown>"}")
            }
          }
          appendLine("\nProvider bindings (after filtering BoundInstance): ${shardProviderBindings.size}")
          shardProviderBindings.forEach { binding ->
            val ordinal = shardingContext.plan.bindingOrdinals[binding.typeKey] ?: -1
            appendLine("  [ord=$ordinal] ${binding.typeKey}: ${binding.javaClass.simpleName}")
          }
        }
      }

      // Skip generation for empty shards but still register a placeholder
      if (shardProviderBindings.isEmpty()) {
        // We still need to maintain the shard index alignment
        // but we don't generate the actual class
        return
      }

      // Set the current shard class in the binding context so the expression generator
      // knows to use outer references for BoundInstance fields
      bindingFieldContext.currentShardClass = this

      // Generate provider field DECLARATIONS for this shard's bindings (excluding BoundInstance)
      // We do declarations first so SwitchingProvider codegen can find provider fields.
      // Field initialization happens later after SwitchingProvider is generated.
      val providerFieldResult = generateProviderFields(
        targetClass = this,
        bindings = shardProviderBindings,
        bindingFieldContext = bindingFieldContext,
        fieldNameAllocator = null, // Use shardingContext's registry instead
        thisReceiver = thisReceiverParameter,
        expressionGeneratorFactory = expressionGeneratorFactory,
        fieldOwner = BindingFieldContext.FieldOwner.Shard(shard.index),
        initializeFields = false, // We'll initialize them separately
        shardingContext = shardingContext, // Pass sharding context for global field name registry
      )

      // Now generate SwitchingProvider so initializers can reference it (fastInit path)
      if (shardProviderBindings.isNotEmpty()) {
        // Check if any of the shard's bindings are actually shardable
        val hasShardableBindings = shard.bindingOrdinals.any { ordinal ->
          shardingContext.plan.shardableBindings[ordinal]
        }

        if (hasShardableBindings) {
          val switchingProviderGenerator = IrSwitchingProviderGenerator(
            context = this@IrGraphGenerator,
            shardClass = this,
            shard = shard,
            shardingContext = shardingContext,
            bindingFieldContext = bindingFieldContext,
            bindingGraph = bindingGraph,
            expressionGeneratorFactory = expressionGeneratorFactory
          )
          switchingProviderGenerator.generate()
        }
      }

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

      // Write diagnostic about what fields were created
      writeDiagnostic("shard-fields-created-${shard.name}.txt") {
        buildString {
          appendLine("=== Fields Created in ${shard.name} ===")
          appendLine("Total fields: ${providerFieldResult.fieldInitializers.size}")
          providerFieldResult.fieldInitializers.forEach { (field, _) ->
            val typeKey = providerFieldResult.fieldsToTypeKeys[field]
            appendLine("  ${field.name}: $typeKey")
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
            bindingFieldContext.putProviderField(key, previousField.field, previousField.owner)
          } else {
            bindingFieldContext.removeProviderField(key)
          }
        }
        for ((key, previousField) in previousInstanceFields) {
          if (previousField != null) {
            bindingFieldContext.putInstanceField(key, previousField.field, previousField.owner)
          } else {
            bindingFieldContext.removeInstanceField(key)
          }
        }
      }

      // Set the constructor body
      ctor.body = createIrBuilder(ctor.symbol).irBlockBody {
        // CRITICAL: Must carefully order initialization to avoid null pointer exceptions
        // The base constructor statements typically include:
        // 1. Super constructor call
        // 2. Instance initializer (which initializes fields)
        // 3. Graph field storage
        // 4. Optional initializeFields call

        // We need to reorder to:
        // 1. Super constructor call (always first)
        // 2. Graph field storage (needed for cross-shard access)
        // 3. Requirement field storage (external dependencies)
        // 4. Instance initializer (uses requirement fields)
        // 5. Other initialization

        // Separate the base constructor statements by type
        val superConstructorCall = baseConstructorStatements.firstOrNull {
          it is org.jetbrains.kotlin.ir.expressions.IrDelegatingConstructorCall
        }
        val instanceInitializer = baseConstructorStatements.firstOrNull {
          it is org.jetbrains.kotlin.ir.expressions.IrInstanceInitializerCall
        }
        val otherStatements = baseConstructorStatements.filter {
          it !== superConstructorCall && it !== instanceInitializer
        }

        // Execute in the correct order
        // 1. Super constructor call (if present)
        superConstructorCall?.let { +it }

        // 2. Graph field and other non-initializer statements (includes graph field storage)
        otherStatements.forEach { +it }

        // 3. Initialize requirement fields (these are needed by instance initializer)
        for (requirementInfo in requirementFieldInfos) {
          +irSetField(
            irGet(thisReceiverParameter),
            requirementInfo.field,
            irGet(requirementInfo.parameter)
          )
        }

        // 4. Instance initializer (now requirement fields are available)
        instanceInitializer?.let { +it }

        // 5. Add our initialization statements
        for (statement in constructorStatements) {
          +statement(thisReceiverParameter)
        }
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
        val binding = bindingGraph.requireBinding(contextualTypeKey)
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
          bindingGraph.requireBinding(contextKey)
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
