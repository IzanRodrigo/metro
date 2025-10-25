// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.METRO_VERSION
import dev.zacsweers.metro.compiler.NameAllocator
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.decapitalizeUS
import dev.zacsweers.metro.compiler.expectAs
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.buildBlockBody
import dev.zacsweers.metro.compiler.ir.createIrBuilder
import dev.zacsweers.metro.compiler.ir.doubleCheck
import dev.zacsweers.metro.compiler.ir.finalizeFakeOverride
import dev.zacsweers.metro.compiler.ir.getAllSuperTypes
import dev.zacsweers.metro.compiler.ir.graph.expressions.BindingExpressionGenerator
import dev.zacsweers.metro.compiler.ir.graph.expressions.GraphExpressionGenerator
import dev.zacsweers.metro.compiler.ir.instanceFactory
import dev.zacsweers.metro.compiler.ir.irExprBodySafe
import dev.zacsweers.metro.compiler.ir.irGetProperty
import dev.zacsweers.metro.compiler.ir.irInvoke
import dev.zacsweers.metro.compiler.ir.metroGraphOrFail
import dev.zacsweers.metro.compiler.ir.metroMetadata
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.ir.parameters.wrapInProvider
import dev.zacsweers.metro.compiler.ir.rawType
import dev.zacsweers.metro.compiler.ir.regularParameters
import dev.zacsweers.metro.compiler.ir.requireSimpleType
import dev.zacsweers.metro.compiler.ir.setDispatchReceiver
import dev.zacsweers.metro.compiler.ir.sourceGraphIfMetroGraph
import dev.zacsweers.metro.compiler.ir.stubExpressionBody
import dev.zacsweers.metro.compiler.ir.thisReceiverOrFail
import dev.zacsweers.metro.compiler.ir.toProto
import dev.zacsweers.metro.compiler.ir.trackFunctionCall
import dev.zacsweers.metro.compiler.ir.transformers.AssistedFactoryTransformer
import dev.zacsweers.metro.compiler.ir.transformers.BindingContainerTransformer
import dev.zacsweers.metro.compiler.ir.transformers.MembersInjectorTransformer
import dev.zacsweers.metro.compiler.ir.typeAsProviderArgument
import dev.zacsweers.metro.compiler.ir.typeRemapperFor
import dev.zacsweers.metro.compiler.ir.writeDiagnostic
import dev.zacsweers.metro.compiler.isGeneratedGraph
import dev.zacsweers.metro.compiler.letIf
import dev.zacsweers.metro.compiler.proto.MetroMetadata
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.suffixIfNot
import dev.zacsweers.metro.compiler.tracing.Tracer
import dev.zacsweers.metro.compiler.tracing.traceNested
import dev.zacsweers.metro.compiler.graph.computeStronglyConnectedComponents
import java.util.SortedMap
import java.util.SortedSet
import java.util.TreeMap
import java.util.TreeSet
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.DescriptorVisibility
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.addBackingField
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addGetter
import org.jetbrains.kotlin.ir.builders.declarations.addProperty
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildProperty
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irExprBody
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irSetField
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrOverridableDeclaration
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.typeOrFail
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.util.propertyIfAccessor
import org.jetbrains.kotlin.ir.util.statements
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

internal typealias PropertyInitializer =
  IrBuilderWithScope.(thisReceiver: IrValueParameter, key: IrTypeKey) -> IrExpression

internal typealias InitStatement =
  IrBuilderWithScope.(thisReceiver: IrValueParameter) -> IrStatement

internal class IrGraphGenerator(
  metroContext: IrMetroContext,
  private val dependencyGraphNodesByClass: (ClassId) -> DependencyGraphNode?,
  private val node: DependencyGraphNode,
  private val graphClass: IrClass,
  private val bindingGraph: IrBindingGraph,
  private val sealResult: IrBindingGraph.BindingGraphResult,
  private val propertyNameAllocator: NameAllocator,
  private val parentTracer: Tracer,
  // TODO move these accesses to irAttributes
  bindingContainerTransformer: BindingContainerTransformer,
  private val membersInjectorTransformer: MembersInjectorTransformer,
  assistedFactoryTransformer: AssistedFactoryTransformer,
  graphExtensionGenerator: IrGraphExtensionGenerator,
) : IrMetroContext by metroContext {

  private var _functionNameAllocatorInitialized = false
  private val _functionNameAllocator = NameAllocator(mode = NameAllocator.Mode.COUNT)
  private val functionNameAllocator: NameAllocator
    get() {
      if (!_functionNameAllocatorInitialized) {
        // pre-allocate existing function names
        for (function in graphClass.functions) {
          _functionNameAllocator.newName(function.name.asString())
        }
        _functionNameAllocatorInitialized = true
      }
      return _functionNameAllocator
    }

  private val bindingPropertyContext = BindingPropertyContext()

  /**
   * Cache for lazily-created properties (e.g., multibinding getters). These are created on-demand
   * and added to the graph at the end to ensure deterministic ordering. Keyed by contextualTypeKey
   * to handle variants like Map<K,V> vs Map<K,Provider<V>>.
   */
  private val lazyProperties = mutableMapOf<IrContextualTypeKey, IrProperty>()

  /**
   * To avoid `MethodTooLargeException`, we split property field initializations up over multiple
   * constructor inits.
   *
   * @see <a href="https://github.com/ZacSweers/metro/issues/645">#645</a>
   */
  private val propertyInitializers = mutableListOf<Pair<IrProperty, PropertyInitializer>>()
  // TODO replace with irAttribute
  private val propertiesToTypeKeys = mutableMapOf<IrProperty, IrTypeKey>()
  private val expressionGeneratorFactory =
    GraphExpressionGenerator.Factory(
      context = this,
      node = node,
      bindingPropertyContext = bindingPropertyContext,
      bindingGraph = bindingGraph,
      bindingContainerTransformer = bindingContainerTransformer,
      membersInjectorTransformer = membersInjectorTransformer,
      assistedFactoryTransformer = assistedFactoryTransformer,
      graphExtensionGenerator = graphExtensionGenerator,
      parentTracer = parentTracer,
      getterPropertyFor = ::getOrCreateLazyProperty,
    )

  // ===== Component Sharding Data Structures =====

  /**
   * Represents the owner of a property for component sharding purposes.
   *
   * Properties can either be owned by the root component or by a specific shard. This is used to
   * track property location during shard generation and to generate correct access patterns
   * (direct access for root, cross-shard access for shard-owned).
   */
  private sealed interface PropertyOwner {
    /** Property is owned by the root component class */
    data object Root : PropertyOwner

    /** Property is owned by a nested shard class */
    data class Shard(val index: Int) : PropertyOwner
  }

  /**
   * Tracks a property binding with its associated metadata for sharding.
   *
   * @property property The IR property itself
   * @property typeKey The type key for this binding
   * @property owner The current owner (Root or Shard). Initially Root, updated during shard
   *   generation
   * @property initializer The initialization expression for this property
   */
  private data class PropertyBinding(
    val property: IrProperty,
    val typeKey: IrTypeKey,
    var owner: PropertyOwner = PropertyOwner.Root,
    val initializer: PropertyInitializer,
  )

  /**
   * Contains all metadata for a generated shard.
   *
   * @property index Zero-based shard index (shard 0, shard 1, etc.)
   * @property shardClass The generated nested shard class
   * @property instanceProperty The property on the root component holding the shard instance (e.g.,
   *   `val shard1: Shard1`)
   * @property initializeFunction The `initialize(component)` function on the shard class
   * @property bindings The property bindings that were moved to this shard
   * @property dependencies Set of other shard indices this shard depends on (for initialization
   *   order)
   */
  private data class ShardInfo(
    val index: Int,
    val shardClass: IrClass,
    val instanceProperty: IrProperty,
    val initializeFunction: IrSimpleFunction,
    val bindings: List<PropertyBinding>,
    val dependencies: Set<Int> = emptySet(),
  )

  /**
   * Result of computing shard initialization order.
   *
   * @property shards List of all shards in the original order
   * @property initializationOrder List of shard indices in the order they should be initialized
   *   (topologically sorted by dependencies)
   */
  private data class ShardOrderResult(
    val shards: List<ShardInfo>,
    val initializationOrder: List<Int>,
  )

  fun IrProperty.withInit(typeKey: IrTypeKey, init: PropertyInitializer): IrProperty = apply {
    // Only necessary for fields
    if (backingField != null) {
      propertiesToTypeKeys[this] = typeKey
      propertyInitializers += (this to init)
    } else {
      getter!!.apply {
        this.body =
          createIrBuilder(symbol).run { irExprBodySafe(init(dispatchReceiverParameter!!, typeKey)) }
      }
    }
  }

  fun IrProperty.initFinal(body: IrBuilderWithScope.() -> IrExpression): IrProperty = apply {
    backingField?.apply {
      isFinal = true
      initializer = createIrBuilder(symbol).run { irExprBody(body()) }
      return@apply
    }
    getter?.apply { this.body = createIrBuilder(symbol).run { irExprBodySafe(body()) } }
  }

  /**
   * Graph extensions may reserve property names for their linking, so if they've done that we use
   * the precomputed property rather than generate a new one.
   */
  private fun IrClass.getOrCreateBindingProperty(
    key: IrTypeKey,
    name: () -> String,
    type: () -> IrType,
    propertyType: PropertyType,
    visibility: DescriptorVisibility = DescriptorVisibilities.PRIVATE,
  ): IrProperty {
    val property =
      bindingGraph.reservedProperty(key)?.property?.also { addChild(it) }
        ?: addProperty {
            this.name = propertyNameAllocator.newName(name()).asName()
            this.visibility = visibility
          }
          .apply { graphPropertyData = GraphPropertyData(key, type()) }

    return property.ensureInitialized(propertyType, type)
  }

  /**
   * Creates or retrieves a lazily-generated property for the given binding and contextual type key.
   * These properties are cached and added to the graph at the end of generation for deterministic
   * ordering.
   *
   * This is primarily used for multibindings where different accessors may need different variants
   * (e.g., Map<K, V> vs Map<K, Provider<V>>).
   */
  fun getOrCreateLazyProperty(
    binding: IrBinding,
    contextualTypeKey: IrContextualTypeKey,
    bodyGenerator: IrBuilderWithScope.(GraphExpressionGenerator) -> IrBody,
  ): IrProperty {
    return lazyProperties.getOrPut(contextualTypeKey) {
      // Create the property but don't add it to the graph yet
      graphClass.factory
        .buildProperty {
          this.name = propertyNameAllocator.newName(binding.nameHint.decapitalizeUS()).asName()
          this.visibility = DescriptorVisibilities.PRIVATE
        }
        .apply {
          parent = graphClass
          graphPropertyData =
            GraphPropertyData(contextualTypeKey.typeKey, contextualTypeKey.toIrType())

          // Add getter with the provided body generator
          addGetter {
              returnType = contextualTypeKey.toIrType()
              visibility = DescriptorVisibilities.PRIVATE
            }
            .apply {
              val getterReceiver = graphClass.thisReceiver!!.copyTo(this)
              setDispatchReceiver(getterReceiver)
              val expressionGenerator = expressionGeneratorFactory.create(getterReceiver)
              this.body = createIrBuilder(symbol).bodyGenerator(expressionGenerator)
            }
        }
    }
  }

  // ===== Component Sharding Algorithm =====

  /**
   * Partitions property initializers into shards using an SCC-aware algorithm.
   *
   * This follows Dagger's proven sharding approach:
   * 1. Sort properties by dependency order (use sealResult.sortedKeys)
   * 2. Compute strongly-connected components (SCCs) to identify binding groups
   * 3. Fill shards sequentially, keeping SCCs together
   * 4. Ensure each shard stays under the keysPerGraphShard limit
   *
   * ## Algorithm Details
   *
   * The algorithm ensures that:
   * - Bindings with circular dependencies (SCCs) stay in the same shard
   * - Shards are filled in topological order
   * - No SCC is split across shards (maintains locality)
   * - Deterministic output (same input → same sharding)
   *
   * ## Example
   *
   * Given 1000 bindings with keysPerGraphShard=400:
   * ```
   * Shard 1: Bindings 0-399 (including SCC groups 0-5)
   * Shard 2: Bindings 400-799 (including SCC groups 6-12)
   * Shard 3: Bindings 800-999 (including SCC groups 13-18)
   * ```
   *
   * @return List of shard groups, where each group contains the property bindings for that shard. If
   *   sharding is disabled or unnecessary, returns a single group with all bindings.
   */
  private fun partitionPropertyInitializers(): List<List<PropertyBinding>> {
    if (propertyInitializers.isEmpty()) {
      return emptyList()
    }

    // Build a map from type key to its position in the dependency order
    val keyOrder = sealResult.sortedKeys.withIndex().associate { (index, key) -> key to index }

    // Convert property initializers to PropertyBinding objects and sort by dependency order
    val orderedBindings =
      propertyInitializers
        .withIndex()
        .map { (originalIndex, pair) ->
          val (property, initializer) = pair
          PropertyBinding(
            property = property,
            typeKey = propertiesToTypeKeys.getValue(property),
            owner = PropertyOwner.Root,
            initializer = initializer,
          ) to originalIndex
        }
        .sortedWith(
          compareBy(
            // Primary: dependency order
            { (binding, _) -> keyOrder[binding.typeKey] ?: Int.MAX_VALUE },
            // Secondary: original insertion order (for determinism when keys not in keyOrder)
            { (_, originalIndex) -> originalIndex },
          )
        )
        .map { it.first }

    if (orderedBindings.isEmpty()) {
      return listOf(emptyList())
    }

    // Check if sharding is enabled and necessary
    if (!options.enableComponentSharding) {
      return listOf(orderedBindings)
    }

    val maxBindingsPerShard = options.keysPerGraphShard
    if (maxBindingsPerShard <= 0 || orderedBindings.size <= maxBindingsPerShard) {
      // Not enough bindings to require sharding
      return listOf(orderedBindings)
    }

    // Build adjacency list for SCC computation
    val bindingByKey = orderedBindings.associateBy { it.typeKey }
    val adjacency: SortedMap<IrTypeKey, SortedSet<IrTypeKey>> = TreeMap()

    // Initialize adjacency list with all keys
    for (binding in orderedBindings) {
      adjacency[binding.typeKey] = TreeSet()
    }

    // Populate dependencies
    for (binding in orderedBindings) {
      val dependencies =
        bindingGraph
          .requireBinding(binding.typeKey)
          .dependencies
          .map { it.typeKey }
          .filter { it in bindingByKey } // Only include dependencies that are in our binding set

      adjacency.getValue(binding.typeKey).addAll(dependencies)
    }

    // Compute strongly-connected components using Tarjan's algorithm
    val (components, componentOf) = adjacency.computeStronglyConnectedComponents()

    // If a key is not in componentOf (e.g., disconnected), assign it a unique component ID
    val componentIdForKey: (IrTypeKey) -> Int = { key ->
      componentOf[key] ?: (components.size + keyOrder.getOrElse(key) { key.hashCode() })
    }

    // Group bindings by their SCC, maintaining dependency order
    val componentsInOrder = mutableListOf<List<PropertyBinding>>()
    var index = 0

    while (index < orderedBindings.size) {
      val startBinding = orderedBindings[index]
      val componentId = componentIdForKey(startBinding.typeKey)
      val group = mutableListOf<PropertyBinding>()

      // Collect all bindings in the same SCC
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

    // Fill shards by adding complete SCCs until we hit the limit
    val shards = mutableListOf<MutableList<PropertyBinding>>()
    var currentShard = mutableListOf<PropertyBinding>()
    var currentSize = 0

    for (componentBindings in componentsInOrder) {
      // If adding this SCC would exceed the limit, start a new shard
      // Important: We never split an SCC across shards
      if (currentShard.isNotEmpty() && currentSize + componentBindings.size > maxBindingsPerShard) {
        shards += currentShard
        currentShard = mutableListOf()
        currentSize = 0
      }

      currentShard.addAll(componentBindings)
      currentSize += componentBindings.size
    }

    // Add the last shard
    if (currentShard.isNotEmpty()) {
      shards += currentShard
    }

    // If we ended up with only one shard, sharding is unnecessary
    return if (shards.size <= 1) {
      listOf(orderedBindings)
    } else {
      shards.map { it.toList() }
    }
  }

  /**
   * Computes the initialization order for shards based on cross-shard dependencies.
   *
   * Shards must be initialized in topological order to ensure that when Shard B depends on bindings
   * in Shard A, Shard A is initialized first.
   *
   * ## Algorithm
   *
   * 1. Build a dependency graph between shards
   * 2. For each shard, check if its bindings depend on bindings in other shards
   * 3. Topologically sort shards by dependencies
   * 4. Return the sorted initialization order
   *
   * ## Example
   *
   * ```
   * Shard 1: [A, B, C]
   * Shard 2: [D depends on A, E, F]
   * Shard 3: [G depends on D, H]
   *
   * Result: [1, 2, 3] (Shard 1 → Shard 2 → Shard 3)
   * ```
   *
   * @param shardGroups The partitioned shards (from partitionPropertyInitializers)
   * @return ShardOrderResult with complete shard metadata and initialization order
   */
  private fun computeShardInitializationOrder(
    shardGroups: List<List<PropertyBinding>>
  ): ShardOrderResult {
    if (shardGroups.size <= 1) {
      // Single shard or no shards - no ordering needed
      // Note: ShardInfo creation happens later in generateShardClasses
      return ShardOrderResult(shards = emptyList(), initializationOrder = emptyList())
    }

    // Build a map from binding type key to shard index
    val bindingToShard = mutableMapOf<IrTypeKey, Int>()
    shardGroups.forEachIndexed { shardIndex, bindings ->
      bindings.forEach { binding -> bindingToShard[binding.typeKey] = shardIndex }
    }

    // Compute dependencies between shards
    // shardDependencies[i] = set of shard indices that shard i depends on
    val shardDependencies = Array(shardGroups.size) { mutableSetOf<Int>() }

    shardGroups.forEachIndexed { shardIndex, bindings ->
      for (binding in bindings) {
        // Get all dependencies for this binding
        val deps = bindingGraph.requireBinding(binding.typeKey).dependencies.map { it.typeKey }

        for (depKey in deps) {
          val depShardIndex = bindingToShard[depKey]
          if (depShardIndex != null && depShardIndex != shardIndex) {
            // This binding depends on a binding in another shard
            shardDependencies[shardIndex].add(depShardIndex)
          }
        }
      }
    }

    // Topological sort using Kahn's algorithm
    val inDegree = IntArray(shardGroups.size)
    shardDependencies.forEach { deps -> deps.forEach { inDegree[it]++ } }

    // Use a priority queue for deterministic ordering
    val queue = java.util.PriorityQueue<Int>()
    for (i in shardDependencies.indices) {
      if (inDegree[i] == 0) {
        queue.add(i)
      }
    }

    val initializationOrder = mutableListOf<Int>()
    while (queue.isNotEmpty()) {
      val shardIndex = queue.remove()
      initializationOrder += shardIndex

      for (dependent in shardDependencies.indices) {
        if (shardIndex in shardDependencies[dependent]) {
          if (--inDegree[dependent] == 0) {
            queue.add(dependent)
          }
        }
      }
    }

    check(initializationOrder.size == shardGroups.size) {
      "Cycle detected in shard dependencies (should be impossible after SCC grouping)"
    }

    // Note: ShardInfo objects will be created during shard class generation
    // This just returns the order - the actual ShardInfo list is built later
    return ShardOrderResult(
      shards = emptyList(), // Populated later in generateShardClasses
      initializationOrder = initializationOrder,
    )
  }

  /**
   * Generates shard classes for the partitioned property groups.
   *
   * For each shard group, this creates:
   * 1. A nested shard class (e.g., `Shard1`, `Shard2`)
   * 2. A property on the root component to hold the shard instance
   * 3. An `initialize(component)` method on the shard
   * 4. Moves properties from the root to the shard
   *
   * ## Generated Structure
   *
   * ```kotlin
   * class Component$$$MetroGraph {
   *   private val shard1: Shard1
   *   private val shard2: Shard2
   *
   *   init {
   *     shard1 = Shard1()
   *     shard1.initialize(this)
   *     shard2 = Shard2()
   *     shard2.initialize(this)
   *   }
   *
   *   private class Shard1 {
   *     private val fooProvider: Provider<Foo>
   *     private val barProvider: Provider<Bar>
   *
   *     fun initialize(component: Component$$$MetroGraph) {
   *       fooProvider = ...
   *       barProvider = ...
   *     }
   *   }
   * }
   * ```
   *
   * @param shardGroups The partitioned property bindings
   * @return List of ShardInfo containing all metadata for each shard
   */
  private fun generateShardClasses(shardGroups: List<List<PropertyBinding>>): List<ShardInfo> {
    return shardGroups.mapIndexed { index, bindings -> generateShardClass(index, bindings) }
  }

  /**
   * Generates a single shard class with all its properties and initialize method.
   *
   * @param shardIndex Zero-based index (0 for Shard1, 1 for Shard2, etc.)
   * @param bindings The property bindings that should be moved to this shard
   * @return ShardInfo containing all metadata for this shard
   */
  private fun generateShardClass(shardIndex: Int, bindings: List<PropertyBinding>): ShardInfo {
    val shardName = "Shard${shardIndex + 1}" // Shard1, Shard2, etc.

    // Create the nested shard class
    val shardClass =
      graphClass.factory.buildClass {
          name = Name.identifier(shardName)
          visibility = DescriptorVisibilities.PRIVATE
          modality = Modality.FINAL
        }
        .apply {
          // Set parent to the graph class (nested class)
          parent = graphClass

          // Add to the graph class as a nested class
          graphClass.addChild(this)

          // Set supertype to Any
          superTypes = listOf(irBuiltIns.anyType)

          // Add primary constructor
          addConstructor {
            isPrimary = true
            visibility = DescriptorVisibilities.PUBLIC
          }.apply {
            // Empty constructor body (properties initialized in initialize() method)
            body = createIrBuilder(symbol).irBlockBody {}
          }
        }

    // Move properties to the shard class
    for (binding in bindings) {
      movePropertyToShard(binding.property, shardClass)
    }

    // Create the shard instance property on the root component
    val shardInstanceProperty =
      graphClass.addProperty {
        this.name = Name.identifier(shardName.replaceFirstChar { it.lowercase() }) // shard1, shard2
        this.visibility = DescriptorVisibilities.PRIVATE
      }.apply {
        // Add backing field with the shard class type
        addBackingField {
          type = irBuiltIns.anyType // Safe: use anyType to avoid initialization issues
          visibility = DescriptorVisibilities.PRIVATE
        }

        // Add getter that returns the field
        addGetter().apply {
          returnType = irBuiltIns.anyType
          visibility = DescriptorVisibilities.PRIVATE
          body =
            createIrBuilder(symbol).irBlockBody {
              +irReturn(irGetField(irGet(dispatchReceiverParameter!!), backingField!!))
            }
        }
      }

    // Create the initialize(component) method on the shard
    val initializeFunction =
      shardClass.addFunction {
        name = Name.identifier("initialize")
        returnType = irBuiltIns.unitType
        visibility = DescriptorVisibilities.INTERNAL
      }.apply {
        // Add parameter: component with type matching the graph class
        val componentParam =
          addValueParameter {
            name = Name.identifier("component")
            type = irBuiltIns.anyType // Safe: use anyType
          }

        // Build the function body - initialize all properties
        body =
          createIrBuilder(symbol).irBlockBody {
            val shardReceiver = dispatchReceiverParameter!!

            for (binding in bindings) {
              val property = binding.property
              val backingField = property.backingField

              if (backingField != null) {
                // Generate: this.field = initializer(component, typeKey)
                val initValue = binding.initializer.invoke(this@irBlockBody, componentParam, binding.typeKey)
                +irSetField(
                  irGet(shardReceiver),
                  backingField,
                  initValue,
                )
              }
            }
          }
      }

    // Update ownership tracking for all moved properties
    for (binding in bindings) {
      binding.owner = PropertyOwner.Shard(shardIndex)
      bindingPropertyContext.updateProviderPropertyOwner(
        binding.property,
        BindingPropertyContext.Owner.Shard(shardInstanceProperty),
      )
      // Also update instance property owner if it exists
      bindingPropertyContext.updateInstancePropertyOwner(
        binding.property,
        BindingPropertyContext.Owner.Shard(shardInstanceProperty),
      )
    }

    return ShardInfo(
      index = shardIndex,
      shardClass = shardClass,
      instanceProperty = shardInstanceProperty,
      initializeFunction = initializeFunction,
      bindings = bindings,
    )
  }

  /**
   * Moves a property from the root component class to a shard class.
   *
   * This updates the property's parent and removes it from the root component's declarations. The
   * property is then added as a child of the shard class.
   *
   * @param property The property to move
   * @param shardClass The shard class to move the property to
   */
  private fun movePropertyToShard(property: IrProperty, shardClass: IrClass) {
    // Remove from graph class
    graphClass.declarations.remove(property)

    // Add to shard class
    shardClass.addChild(property)
    property.parent = shardClass
  }

  fun generate() =
    with(graphClass) {
      val ctor = primaryConstructor!!

      val constructorStatements = mutableListOf<InitStatement>()

      val thisReceiverParameter = thisReceiverOrFail

      fun addBoundInstanceField(
        typeKey: IrTypeKey,
        name: Name,
        propertyType: PropertyType,
        initializer:
          IrBuilderWithScope.(thisReceiver: IrValueParameter, typeKey: IrTypeKey) -> IrExpression,
      ) {
        // Don't add it if it's not used
        if (typeKey !in sealResult.reachableKeys) return

        bindingPropertyContext.putProviderProperty(
          typeKey,
          getOrCreateBindingProperty(
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
              propertyType,
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

          val isDynamic = irParam.origin == Origins.DynamicContainerParam
          val isBindingContainer = creator.bindingContainersParameterIndices.isSet(i)
          if (isBindsInstance || isBindingContainer || isDynamic) {

            if (!isDynamic && param.typeKey in node.dynamicTypeKeys) {
              // Don't add it if there's a dynamic replacement
              continue
            }
            addBoundInstanceField(param.typeKey, param.name, PropertyType.FIELD) { _, _ ->
              irGet(irParam)
            }
          } else {
            // It's a graph dep. Add all its accessors as available keys and point them at
            // this constructor parameter for provider property initialization
            val graphDep =
              node.includedGraphNodes[param.typeKey]
                ?: reportCompilerBug("Undefined graph node ${param.typeKey}")

            // Don't add it if it's not used
            if (param.typeKey !in sealResult.reachableKeys) continue

            val graphDepProperty =
              addSimpleInstanceProperty(
                propertyNameAllocator.newName(graphDep.sourceGraph.name.asString() + "Instance"),
                param.typeKey,
              ) {
                irGet(irParam)
              }
            // Link both the graph typekey and the (possibly-impl type)
            bindingPropertyContext.putInstanceProperty(param.typeKey, graphDepProperty)
            bindingPropertyContext.putInstanceProperty(graphDep.typeKey, graphDepProperty)

            // Expose the graph as a provider property
            // TODO this isn't always actually needed but different than the instance property above
            //  would be nice if we could determine if this property is unneeded
            val providerWrapperProperty =
              getOrCreateBindingProperty(
                param.typeKey,
                { graphDepProperty.name.asString() + "Provider" },
                { metroSymbols.metroProvider.typeWith(param.typeKey.type) },
                PropertyType.FIELD,
              )

            bindingPropertyContext.putProviderProperty(
              param.typeKey,
              providerWrapperProperty.initFinal {
                instanceFactory(
                  param.typeKey.type,
                  irGetProperty(irGet(thisReceiverParameter), graphDepProperty),
                )
              },
            )
            // Link both the graph typekey and the (possibly-impl type)
            bindingPropertyContext.putProviderProperty(param.typeKey, providerWrapperProperty)
            bindingPropertyContext.putProviderProperty(graphDep.typeKey, providerWrapperProperty)

            if (graphDep.hasExtensions) {
              val depMetroGraph = graphDep.sourceGraph.metroGraphOrFail
              val paramName = depMetroGraph.sourceGraphIfMetroGraph.name
              addBoundInstanceField(param.typeKey, paramName, PropertyType.FIELD) { _, _ ->
                irGet(irParam)
              }
            }
          }
        }
      }

      // Create managed binding containers instance properties if used
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
            addBoundInstanceField(IrTypeKey(clazz), clazz.name, PropertyType.FIELD) { _, _ ->
              // Can't use primaryConstructor here because it may be a Java dagger Module in interop
              val noArgConstructor = clazz.constructors.first { it.parameters.isEmpty() }
              irCallConstructor(noArgConstructor.symbol, emptyList())
            }
          }
        }

      // Don't add it if it's not used
      if (node.typeKey in sealResult.reachableKeys) {
        val thisGraphProperty =
          addSimpleInstanceProperty(
            propertyNameAllocator.newName("thisGraphInstance"),
            node.typeKey,
          ) {
            irGet(thisReceiverParameter)
          }

        bindingPropertyContext.putInstanceProperty(node.typeKey, thisGraphProperty)

        // Expose the graph as a provider property
        // TODO this isn't always actually needed but different than the instance field above
        //  would be nice if we could determine if this field is unneeded
        val property =
          getOrCreateBindingProperty(
            node.typeKey,
            { "thisGraphInstanceProvider" },
            { metroSymbols.metroProvider.typeWith(node.typeKey.type) },
            PropertyType.FIELD,
          )

        bindingPropertyContext.putProviderProperty(
          node.typeKey,
          property.initFinal {
            instanceFactory(
              node.typeKey.type,
              irGetProperty(irGet(thisReceiverParameter), thisGraphProperty),
            )
          },
        )
      }

      // Collect bindings and their dependencies for provider property ordering
      val initOrder =
        parentTracer.traceNested("Collect bindings") {
          val collectedProperties = BindingPropertyCollector(bindingGraph).collect()
          buildList(collectedProperties.size) {
            for (key in sealResult.sortedKeys) {
              if (key in sealResult.reachableKeys) {
                collectedProperties[key]?.let(::add)
              }
            }
          }
        }

      // For all deferred types, assign them first as factories
      // DelegateFactory properties can be initialized inline since they're just empty factories.
      @Suppress("UNCHECKED_CAST")
      val deferredProperties: Map<IrTypeKey, IrProperty> =
        sealResult.deferredTypes.associateWith { deferredTypeKey ->
          val binding = bindingGraph.requireBinding(deferredTypeKey)
          val property =
            getOrCreateBindingProperty(
                binding.typeKey,
                { binding.nameHint.decapitalizeUS() + "Provider" },
                { deferredTypeKey.type.wrapInProvider(metroSymbols.metroProvider) },
                PropertyType.FIELD,
              )
              .withInit(binding.typeKey) { _, _ ->
                irInvoke(
                  callee = metroSymbols.metroDelegateFactoryConstructor,
                  typeArgs = listOf(deferredTypeKey.type),
                )
              }

          bindingPropertyContext.putProviderProperty(deferredTypeKey, property)
          property
        }

      initOrder
        .asSequence()
        .filterNot { (binding, _) ->
          // Don't generate deferred types here, we'll generate them last
          binding.typeKey in deferredProperties ||
            // Don't generate properties for anything already provided in provider/instance
            // properties (i.e.
            // bound instance types)
            binding.typeKey in bindingPropertyContext ||
            // We don't generate properties for these even though we do track them in dependencies
            // above, it's just for propagating their aliased type in sorting
            binding is IrBinding.Alias ||
            // For implicit outer class receivers we don't need to generate a property for them
            (binding is IrBinding.BoundInstance && binding.classReceiverParameter != null) ||
            // Parent graph bindings don't need duplicated properties
            (binding is IrBinding.GraphDependency && binding.propertyAccess != null)
        }
        .toList()
        .also { propertyBindings ->
          writeDiagnostic("keys-providerProperties-${parentTracer.tag}.txt") {
            propertyBindings.joinToString("\n") { it.binding.typeKey.toString() }
          }
          writeDiagnostic("keys-scopedProviderProperties-${parentTracer.tag}.txt") {
            propertyBindings
              .filter { it.binding.isScoped() }
              .joinToString("\n") { it.binding.typeKey.toString() }
          }
        }
        .forEach { (binding, propertyType) ->
          val key = binding.typeKey
          // Since assisted-inject classes don't implement Factory, we can't just type these
          // as Provider<*> properties
          var isProviderType = true
          val suffix: String
          val irType =
            if (binding is IrBinding.ConstructorInjected && binding.isAssisted) {
              isProviderType = false
              suffix = "Factory"
              binding.classFactory.factoryClass.typeWith() // TODO generic factories?
            } else if (propertyType == PropertyType.GETTER && binding is IrBinding.Multibinding) {
              // Getters don't need to be providers for multibindings
              isProviderType = false
              suffix = ""
              binding.typeKey.type
            } else {
              suffix = "Provider"
              metroSymbols.metroProvider.typeWith(key.type)
            }

          val accessType =
            if (isProviderType) {
              BindingExpressionGenerator.AccessType.PROVIDER
            } else {
              BindingExpressionGenerator.AccessType.INSTANCE
            }

          // If we've reserved a property for this key here, pull it out and use that
          val property =
            getOrCreateBindingProperty(
              binding.typeKey,
              { binding.nameHint.decapitalizeUS().suffixIfNot(suffix) },
              { irType },
              propertyType,
            )

          property.withInit(key) { thisReceiver, typeKey ->
            expressionGeneratorFactory
              .create(thisReceiver)
              .generateBindingCode(binding, accessType = accessType, fieldInitKey = typeKey)
              .letIf(binding.isScoped() && isProviderType) {
                // If it's scoped, wrap it in double-check
                // DoubleCheck.provider(<provider>)
                it.doubleCheck(this@withInit, metroSymbols, binding.typeKey)
              }
          }

          if (isProviderType) {
            bindingPropertyContext.putProviderProperty(key, property)
          } else {
            bindingPropertyContext.putInstanceProperty(key, property)
          }
        }

      fun addDeferredSetDelegateCalls(collector: MutableList<InitStatement>) {
        // Add statements to our constructor's deferred properties _after_ we've added all provider
        // properties for everything else. This is important in case they reference each other
        for ((deferredTypeKey, field) in deferredProperties) {
          val binding = bindingGraph.requireBinding(deferredTypeKey)
          collector.add { thisReceiver ->
            irInvoke(
              dispatchReceiver = irGetObject(metroSymbols.metroDelegateFactoryCompanion),
              callee = metroSymbols.metroDelegateFactorySetDelegate,
              typeArgs = listOf(deferredTypeKey.type),
              // TODO de-dupe?
              args =
                listOf(
                  irGetProperty(irGet(thisReceiver), field),
                  createIrBuilder(symbol).run {
                    expressionGeneratorFactory
                      .create(thisReceiver)
                      .generateBindingCode(
                        binding,
                        accessType = BindingExpressionGenerator.AccessType.PROVIDER,
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
      }

      // ==== Component Sharding Integration ====
      // Partition properties into shards if sharding is enabled and beneficial
      val shardGroups = partitionPropertyInitializers()

      if (shardGroups.size > 1) {
        // Sharding is enabled and beneficial - generate shard classes
        val shardInfos = generateShardClasses(shardGroups)

        // Compute initialization order based on cross-shard dependencies
        val orderResult = computeShardInitializationOrder(shardGroups)
        val initOrder = orderResult.initializationOrder

        // Generate diagnostic report showing sharding plan
        writeDiagnostic("sharding-plan-${parentTracer.tag}.txt") {
          buildString {
            appendLine("=== Metro Component Sharding Plan ===")
            appendLine()
            appendLine("Component: ${graphClass.kotlinFqName}")
            appendLine("Total bindings: ${propertyInitializers.size}")
            appendLine("Keys per shard limit: ${options.keysPerGraphShard}")
            appendLine("Shard count: ${shardInfos.size}")
            appendLine("Sharding enabled: ${options.enableComponentSharding}")
            appendLine()

            appendLine("Initialization order: ${
              initOrder.joinToString(" → ") { "Shard${it + 1}" }
            }")
            appendLine()

            shardInfos.forEach { info ->
              appendLine("Shard ${info.index + 1}:")
              appendLine("  Class: ${info.shardClass.kotlinFqName}")
              appendLine("  Bindings: ${info.bindings.size}")
              appendLine("  Instance property: ${info.instanceProperty.name}")
              appendLine("  Initialize function: ${info.initializeFunction.name}")

              if (info.bindings.size <= 10) {
                // Show all bindings for small shards
                appendLine("  Binding keys:")
                info.bindings.forEach { binding ->
                  appendLine("    - ${binding.typeKey}")
                }
              } else {
                // Show first and last for large shards
                appendLine("  Binding keys (first 5):")
                info.bindings.take(5).forEach { binding ->
                  appendLine("    - ${binding.typeKey}")
                }
                appendLine("    ... (${info.bindings.size - 10} more)")
                appendLine("  Binding keys (last 5):")
                info.bindings.takeLast(5).forEach { binding ->
                  appendLine("    - ${binding.typeKey}")
                }
              }
              appendLine()
            }

            // Compute and display cross-shard dependencies
            appendLine("Cross-shard dependencies:")
            val bindingToShard = mutableMapOf<IrTypeKey, Int>()
            shardInfos.forEach { info ->
              info.bindings.forEach { binding ->
                bindingToShard[binding.typeKey] = info.index
              }
            }

            var crossShardDepCount = 0
            shardInfos.forEach { info ->
              info.bindings.forEach { binding ->
                val deps = bindingGraph.requireBinding(binding.typeKey).dependencies
                deps.forEach { dep ->
                  val depShard = bindingToShard[dep.typeKey]
                  if (depShard != null && depShard != info.index) {
                    appendLine("  Shard${info.index + 1}.${binding.typeKey} → Shard${depShard + 1}.${dep.typeKey}")
                    crossShardDepCount++
                  }
                }
              }
            }

            if (crossShardDepCount == 0) {
              appendLine("  (none)")
            }
            appendLine()
            appendLine("Total cross-shard dependencies: $crossShardDepCount")
          }
        }

        // Add shard instantiation and initialization to constructor
        // Two-phase: 1) Create all shards, 2) Initialize in dependency order
        constructorStatements += buildList {
          // Phase 1: Instantiate all shards
          for (info in shardInfos) {
            add { dispatchReceiver ->
              irSetField(
                irGet(dispatchReceiver),
                info.instanceProperty.backingField!!,
                irCallConstructor(info.shardClass.primaryConstructor!!.symbol, emptyList()),
              )
            }
          }

          // Phase 2: Initialize shards in dependency order
          for (shardIndex in initOrder) {
            val info = shardInfos[shardIndex]
            add { dispatchReceiver ->
              irInvoke(
                dispatchReceiver = irGetField(irGet(dispatchReceiver), info.instanceProperty.backingField!!),
                callee = info.initializeFunction.symbol,
                args = listOf(irGet(dispatchReceiver)), // Pass component as argument
              )
            }
          }

          // Add deferred setDelegate calls after all shards are initialized
          addDeferredSetDelegateCalls(this)
        }
      } else {
        // No sharding needed - use the original chunked init logic
        val mustChunkInits =
          options.chunkFieldInits && propertyInitializers.size > options.statementsPerInitFun

        if (mustChunkInits) {
          // Larger graph, split statements
          // Chunk our constructor statements and split across multiple init functions
          val chunks =
            buildList<InitStatement> {
                // Add property initializers and interleave setDelegate calls as dependencies are
                // ready
                for ((property, init) in propertyInitializers) {
                  val typeKey = propertiesToTypeKeys.getValue(property)

                  // Add this property's initialization
                  add { thisReceiver ->
                    irSetField(
                      irGet(thisReceiver),
                      property.backingField!!,
                      init(thisReceiver, typeKey),
                    )
                  }
                }

                addDeferredSetDelegateCalls(this)
              }
              .chunked(options.statementsPerInitFun)

          val initFunctionsToCall =
            chunks.map { statementsChunk ->
              val initName = functionNameAllocator.newName("init")
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
          // Assign those initializers directly to their properties and mark them as final
          for ((property, init) in propertyInitializers) {
            property.initFinal {
              val typeKey = propertiesToTypeKeys.getValue(property)
              init(thisReceiverParameter, typeKey)
            }
          }
          addDeferredSetDelegateCalls(constructorStatements)
        }
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

      // Add lazy properties to graph in deterministic order
      if (lazyProperties.isNotEmpty()) {
        lazyProperties.values
          .sortedBy { it.name.asString() }
          .forEach { property -> addChild(property) }
      }

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
  private fun IrClass.addSimpleInstanceProperty(
    name: String,
    typeKey: IrTypeKey,
    initializerExpression: IrBuilderWithScope.() -> IrExpression,
  ): IrProperty =
    addProperty {
        this.name = name.removePrefix("$$").decapitalizeUS().asName()
        this.visibility = DescriptorVisibilities.PRIVATE
      }
      .apply { this.addBackingField { this.type = typeKey.type } }
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

      val irFunction = function.ir
      irFunction.apply {
        val declarationToFinalize =
          irFunction.propertyIfAccessor.expectAs<IrOverridableDeclaration<*>>()
        if (declarationToFinalize.isFakeOverride) {
          declarationToFinalize.finalizeFakeOverride(graphClass.thisReceiverOrFail)
        }
        body =
          createIrBuilder(symbol).run {
            if (binding is IrBinding.Multibinding) {
              // TODO if we have multiple accessors pointing at the same type, implement
              //  one and make the rest call that one. Not multibinding specific. Maybe
              //  groupBy { typekey }?
            }
            irExprBodySafe(
              typeAsProviderArgument(
                contextualTypeKey,
                expressionGeneratorFactory
                  .create(irFunction.dispatchReceiverParameter!!)
                  .generateBindingCode(binding, contextualTypeKey = contextualTypeKey),
                isAssisted = false,
                isGraphInstance = false,
              )
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
      val irFunction = function.ir
      irFunction.apply {
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
        val irFunction = function.ir
        irFunction.apply {
          val declarationToFinalize =
            irFunction.propertyIfAccessor.expectAs<IrOverridableDeclaration<*>>()
          if (declarationToFinalize.isFakeOverride) {
            declarationToFinalize.finalizeFakeOverride(graphClass.thisReceiverOrFail)
          }

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
                  accessor = irFunction,
                  // Implementing a factory SAM, no scoping or dependencies here,
                  extensionScopes = emptySet(),
                  dependencies = emptyList(),
                )
            val contextKey = IrContextualTypeKey.from(irFunction)
            body =
              createIrBuilder(symbol).run {
                irExprBodySafe(
                  expressionGeneratorFactory
                    .create(irFunction.dispatchReceiverParameter!!)
                    .generateBindingCode(binding = binding, contextualTypeKey = contextKey)
                )
              }
          }
        }
      }
    }
  }
}
