// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.NameAllocator
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.decapitalizeUS
import dev.zacsweers.metro.compiler.graph.ShardingResult
import dev.zacsweers.metro.compiler.ir.parameters.wrapInProvider
import dev.zacsweers.metro.compiler.ir.transformers.AssistedFactoryTransformer
import dev.zacsweers.metro.compiler.ir.transformers.BindingContainerTransformer
import dev.zacsweers.metro.compiler.ir.transformers.MembersInjectorTransformer
import dev.zacsweers.metro.compiler.suffixIfNot
import dev.zacsweers.metro.compiler.tracing.Tracer
import dev.zacsweers.metro.compiler.tracing.traceNested
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addField
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irExprBody
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irSetField
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.defaultType as utilDefaultType
import org.jetbrains.kotlin.ir.util.statements
import org.jetbrains.kotlin.name.ClassId

/**
 * Generates sharded Metro component implementation.
 *
 * This generator creates a component structure where bindings are partitioned into nested
 * shard classes. Each shard is initialized in dependency order, with shards able to reference
 * providers from their dependent shards.
 *
 * Example output:
 * ```kotlin
 * class $$MetroGraph : AppComponent {
 *   private lateinit var shard0: Shard0
 *   private lateinit var shard1: Shard1
 *
 *   init {
 *     init()   // Initialize Shard0
 *     init2()  // Initialize Shard1
 *   }
 *
 *   private fun init() { shard0 = Shard0(this) }
 *   private fun init2() { shard1 = Shard1(this, shard0) }
 *
 *   private class Shard0(private val component: $$MetroGraph) {
 *     // Provider fields go here (Week 3 Day 3-4)
 *   }
 *
 *   private class Shard1(
 *     private val component: $$MetroGraph,
 *     private val shard0: Shard0
 *   ) {
 *     // Provider fields with cross-shard dependencies
 *   }
 * }
 * ```
 */
internal class ShardedGraphGenerator(
  metroContext: IrMetroContext,
  private val dependencyGraphNodesByClass: (ClassId) -> DependencyGraphNode?,
  private val node: DependencyGraphNode,
  private val graphClass: IrClass,
  private val bindingGraph: IrBindingGraph,
  private val sealResult: IrBindingGraph.BindingGraphResult,
  private val shardingResult: ShardingResult<IrTypeKey>,
  private val fieldNameAllocator: NameAllocator,
  private val parentTracer: Tracer,
  private val bindingContainerTransformer: BindingContainerTransformer,
  private val membersInjectorTransformer: MembersInjectorTransformer,
  private val assistedFactoryTransformer: AssistedFactoryTransformer,
  private val graphExtensionGenerator: IrGraphExtensionGenerator,
) : IrMetroContext by metroContext {

  // Note: We'll create expression generator factories per-shard since each shard has its own
  // BindingFieldContext

  /**
   * Tracks provider fields generated for each binding across all shards.
   * Used for cross-shard dependency resolution.
   */
  private val shardProviderFields = mutableMapOf<IrTypeKey, IrField>()

  /**
   * Global binding field context shared across all shards.
   * Tracks all provider fields regardless of which shard they're in.
   */
  private val globalBindingFieldContext = BindingFieldContext()

  /**
   * Tracks shard field references for cross-shard access.
   * Maps shard ID to the lateinit field in the component class.
   */
  private val shardFieldsInComponent = mutableMapOf<Int, IrField>()

  /**
   * Information about a generated shard class.
   */
  private data class ShardClassInfo(
    val shardId: Int,
    val irClass: IrClass,
    val constructor: IrConstructor,
    val componentParam: IrValueParameter,
    val depShardParams: List<IrValueParameter>,
  )

  /**
   * Context for accessing fields from within a shard.
   * Provides information needed to generate cross-shard field access expressions.
   */
  private data class ShardAccessContext(
    val shardId: Int,
    val thisReceiver: IrValueParameter,
    val componentParam: IrValueParameter,
    val depShardParams: Map<Int, IrValueParameter>, // shardId → parameter
  )

  /**
   * Main entry point for generating the sharded component.
   */
  fun generate() {
    parentTracer.traceNested("Generate sharded component") { tracer ->
      // First, handle BoundInstance fields in the component (not shards)
      tracer.traceNested("Generate BoundInstance fields") {
        generateBoundInstanceFields()
      }

      // Generate each shard class with provider fields
      val shardClasses = tracer.traceNested("Generate shard classes") {
        generateShardClasses(tracer)
      }

      // Generate init methods that initialize shards
      tracer.traceNested("Generate init methods") {
        generateInitMethods(shardClasses)
      }

      // Update component constructor to call init methods
      tracer.traceNested("Update component constructor") {
        updateComponentConstructor()
      }

      // TODO: Implement component interface methods (Week 4)
    }
  }

  /**
   * Generates provider fields for BoundInstance bindings in the component class.
   * These are constructor parameters that need to be wrapped in providers and accessible
   * from all shards.
   *
   * This mirrors the logic from IrGraphGenerator.addBoundInstanceField (lines 164-193)
   * to ensure @BindsInstance parameters are properly handled in sharded mode.
   */
  private fun generateBoundInstanceFields() {
    val componentCtor = graphClass.constructors.first { it.isPrimary }
    val componentThisReceiver = graphClass.thisReceiver
      ?: error("Component class has no this receiver")

    // Process @BindsInstance constructor parameters
    // This follows the same pattern as IrGraphGenerator lines 195-204
    node.creator?.let { creator ->
      for ((i, param) in creator.parameters.regularParameters.withIndex()) {
        val isBindsInstance = param.isBindsInstance
        val irParam = componentCtor.regularParameters[i]

        if (isBindsInstance || creator.bindingContainersParameterIndices.isSet(i)) {
          // Don't add if it's not used
          if (param.typeKey !in sealResult.reachableKeys) continue

          // Create provider field that wraps the constructor parameter
          val fieldName = fieldNameAllocator.newName(
            param.name.asString()
              .removePrefix("$$")
              .decapitalizeUS()
              .suffixIfNot("Instance")
              .suffixIfNot("Provider")
          )

          val providerField = graphClass.addField {
            name = fieldName.asName()
            type = symbols.metroProvider.typeWith(param.typeKey.type)
            visibility = DescriptorVisibilities.INTERNAL // Must be internal for shard access
            isFinal = true
            origin = Origins.MetroGraphShard
          }

          // Initialize using instanceFactory - this creates: InstanceFactory.Companion.invoke(value = irParam)
          // This is the correct pattern for BoundInstance bindings (not lambda-based providers)
          providerField.initializer = DeclarationIrBuilder(
            pluginContext,
            providerField.symbol,
            UNDEFINED_OFFSET,
            UNDEFINED_OFFSET
          ).run {
            irExprBody(
              // instanceFactory(typeKey.type, irGet(irParam))
              instanceFactory(param.typeKey.type, irGet(irParam))
            )
          }

          // Add to global context so shards can access it
          globalBindingFieldContext.putProviderField(param.typeKey, providerField)
        }
      }
    }

    // Handle component self-reference binding (IrGraphGenerator lines 251-278)
    // Don't add it if it's not used
    if (node.typeKey in sealResult.reachableKeys) {
      // Create thisGraphInstance field that holds reference to component itself
      val thisGraphInstanceField = graphClass.addField {
        name = fieldNameAllocator.newName("thisGraphInstance").asName()
        type = node.typeKey.type
        visibility = DescriptorVisibilities.PRIVATE
        isFinal = true
        origin = Origins.MetroGraphShard
      }

      thisGraphInstanceField.initializer = DeclarationIrBuilder(
        pluginContext,
        thisGraphInstanceField.symbol,
        UNDEFINED_OFFSET,
        UNDEFINED_OFFSET
      ).run {
        irExprBody(irGet(componentThisReceiver))
      }

      // Create provider field that wraps thisGraphInstance
      val componentProviderField = graphClass.addField {
        name = fieldNameAllocator.newName("thisGraphInstanceProvider").asName()
        type = symbols.metroProvider.typeWith(node.typeKey.type)
        visibility = DescriptorVisibilities.INTERNAL // Must be internal for shard access
        isFinal = true
        origin = Origins.MetroGraphShard
      }

      componentProviderField.initializer = DeclarationIrBuilder(
        pluginContext,
        componentProviderField.symbol,
        UNDEFINED_OFFSET,
        UNDEFINED_OFFSET
      ).run {
        irExprBody(
          instanceFactory(
            node.typeKey.type,
            irGetField(irGet(componentThisReceiver), thisGraphInstanceField)
          )
        )
      }

      // Add to global context
      globalBindingFieldContext.putProviderField(node.typeKey, componentProviderField)
    }
  }

  /**
   * Generates all shard nested classes.
   */
  private fun generateShardClasses(tracer: Tracer): List<ShardClassInfo> {
    return shardingResult.shards.map { shard ->
      tracer.traceNested("Generate Shard${shard.id}") {
        generateShardClass(shard.id)
      }
    }
  }

  /**
   * Generates a single shard nested class.
   */
  private fun generateShardClass(shardId: Int): ShardClassInfo {
    val shard = shardingResult.shards[shardId]
    val shardClassName = "Shard$shardId"

    // Build the shard class
    val shardClass = irFactory.buildClass {
      name = shardClassName.asName()
      kind = ClassKind.CLASS
      modality = Modality.FINAL
      visibility = DescriptorVisibilities.PRIVATE
      origin = Origins.MetroGraphShard
    }.apply {
      parent = graphClass
      createThisReceiverParameter()

      // Create primary constructor
      val ctor = addConstructor {
        visibility = DescriptorVisibilities.PRIVATE
        isPrimary = true
        origin = Origins.MetroGraphShard
      }

      // Parameter 1: component reference
      val componentParam = ctor.addValueParameter {
        name = "component".asName()
        type = graphClass.utilDefaultType
        origin = Origins.MetroGraphShard
      }

      // Parameters 2+: dependent shards (sorted by ID for determinism)
      // Note: We need to look up the shard classes that have already been created
      val depShardParams = shard.dependencies.sorted().map { depShardId ->
        // Find the already-generated shard class
        val depShardClass = graphClass.declarations
          .filterIsInstance<IrClass>()
          .firstOrNull { it.name.asString() == "Shard$depShardId" }

        if (depShardClass != null) {
          ctor.addValueParameter {
            name = "shard$depShardId".asName()
            type = depShardClass.utilDefaultType
            origin = Origins.MetroGraphShard
          }
        } else {
          // This shard hasn't been created yet - this shouldn't happen if shards are sorted
          error("Shard$depShardId not found when generating Shard$shardId")
        }
      }

      // Create constructor body (empty for now)
      ctor.body = DeclarationIrBuilder(pluginContext, ctor.symbol, UNDEFINED_OFFSET, UNDEFINED_OFFSET)
        .irBlockBody {}

      // Store component and dependent shards as fields (optional for now, will add in Week 3 Day 3-4)
      // For now, just keep the class structure minimal

      // Generate provider fields for bindings in this shard
      generateProviderFields(this, shard, componentParam, depShardParams)

      ShardClassInfo(
        shardId = shardId,
        irClass = this,
        constructor = ctor,
        componentParam = componentParam,
        depShardParams = depShardParams,
      )
    }

    // Add shard class to component
    graphClass.addChild(shardClass)

    val primaryCtor = shardClass.constructors.first { it.isPrimary }
    // Use new IR parameter API: parameters includes ALL parameters
    val allParams = primaryCtor.parameters
    return ShardClassInfo(
      shardId = shardId,
      irClass = shardClass,
      constructor = primaryCtor,
      componentParam = allParams[0],
      depShardParams = allParams.drop(1),
    )
  }

  /**
   * Generates provider fields for all bindings within a shard class.
   *
   * This creates `internal val fooProvider: Provider<Foo>` fields initialized with
   * binding expressions generated by IrGraphExpressionGenerator.
   *
   * For scoped bindings, providers are wrapped with DoubleCheck.provider().
   *
   * This method generates two types of fields:
   * 1. **Actual provider fields** for bindings owned by this shard
   * 2. **Delegation fields** for cross-shard dependencies (delegates to other shards/component)
   */
  private fun generateProviderFields(
    shardClass: IrClass,
    shard: dev.zacsweers.metro.compiler.graph.Shard<IrTypeKey>,
    componentParam: IrValueParameter,
    depShardParams: List<IrValueParameter>,
  ) {
    // Create shard access context for cross-shard field resolution
    val shardThisReceiver = shardClass.thisReceiver
      ?: error("Shard class has no this receiver")

    val shardAccessContext = ShardAccessContext(
      shardId = shard.id,
      thisReceiver = shardThisReceiver,
      componentParam = componentParam,
      depShardParams = depShardParams.mapIndexed { index, param ->
        // Map parameter to its shard ID based on dependency order
        val depShardId = shard.dependencies.sorted()[index]
        depShardId to param
      }.toMap()
    )

    // Create a local binding field context for this shard
    // This will be populated with both local fields and delegation fields
    val localBindingFieldContext = BindingFieldContext()

    // First pass: Create delegation fields for dependencies from other shards or component
    // This ensures all dependencies are available locally when generating binding code
    //
    // We look at ALL bindings in the binding graph to find dependencies that might be needed
    // by this shard. This is simpler than extracting dependencies from each binding type.
    val allDependenciesInShard = mutableSetOf<IrTypeKey>()

    // Collect all potential dependencies by looking at the dependency edges in the binding graph
    for (typeKey in shard.bindings) {
      val binding = bindingGraph.requireBinding(typeKey)
      // Get dependencies from the binding graph's dependency structure
      binding.dependencies.forEach { dep ->
        allDependenciesInShard.add(dep.typeKey)
      }
    }

    // Create delegation fields for cross-shard and component dependencies
    for (depKey in allDependenciesInShard) {
      // Skip if we've already created a delegation field for this dependency
      if (depKey in localBindingFieldContext) continue

      // Skip if this dependency is owned by the current shard (will be added in second pass)
      val depShardId = shardingResult.bindingToShard[depKey]
      if (depShardId == shard.id) continue

      when {
        depShardId == null -> {
          // BoundInstance or GraphDependency binding in component (not in any shard)
          // These were added to globalBindingFieldContext by generateBoundInstanceFields()
          // Create delegation field that accesses the component's provider field
          val componentField = globalBindingFieldContext.providerField(depKey)
          if (componentField != null) {
            val delegationField = createCrossShardDelegationField(
              shardClass = shardClass,
              typeKey = depKey,
              sourceReceiver = componentParam,
              sourceField = componentField,
            )
            localBindingFieldContext.putProviderField(depKey, delegationField)
          }
          // If componentField is null, it might be a GraphDependency with fieldAccess
          // which is handled directly by IrGraphExpressionGenerator
        }
        else -> {
          // Cross-shard dependency - create delegation field
          val depShardParam = shardAccessContext.depShardParams[depShardId]
          val depShardField = globalBindingFieldContext.providerField(depKey)

          if (depShardParam != null && depShardField != null) {
            val delegationField = createCrossShardDelegationField(
              shardClass = shardClass,
              typeKey = depKey,
              sourceReceiver = depShardParam,
              sourceField = depShardField,
            )
            localBindingFieldContext.putProviderField(depKey, delegationField)
          }
        }
      }
    }

    // Second pass: Generate actual provider fields for bindings owned by this shard
    for (typeKey in shard.bindings) {
      val binding = bindingGraph.requireBinding(typeKey)

      // Skip bound instances - they're in the component, not generated in shards
      // Also skip graph dependencies that already have field access
      if (binding is IrBinding.BoundInstance ||
          (binding is IrBinding.GraphDependency && binding.fieldAccess != null)) {
        continue
      }

      // Determine field type and name
      val suffix = "Provider"
      val fieldType = symbols.metroProvider.typeWith(typeKey.type)

      // Allocate a unique field name
      val fieldName = fieldNameAllocator.newName(
        binding.nameHint.decapitalizeUS().suffixIfNot(suffix)
      )

      // Create the field
      val providerField = shardClass.addField {
        name = fieldName.asName()
        type = fieldType
        visibility = DescriptorVisibilities.INTERNAL // Must be internal for cross-shard access
        isFinal = true
        origin = Origins.MetroGraphShard
      }

      // Generate the provider initializer using local context (which includes delegations)
      providerField.initializer = DeclarationIrBuilder(
        pluginContext,
        providerField.symbol,
        UNDEFINED_OFFSET,
        UNDEFINED_OFFSET
      ).run {
        irExprBody(
          // Generate the binding expression
          generateProviderInitializer(
            binding = binding,
            typeKey = typeKey,
            shardThisReceiver = shardThisReceiver,
            bindingFieldContext = localBindingFieldContext,
          )
        )
      }

      // Track the field in both local and global contexts
      localBindingFieldContext.putProviderField(typeKey, providerField)
      globalBindingFieldContext.putProviderField(typeKey, providerField)
      shardProviderFields[typeKey] = providerField
    }
  }

  /**
   * Creates a delegation field that accesses a provider from another shard or component.
   *
   * Example generated code:
   * ```kotlin
   * private val fooProvider: Provider<Foo> =
   *   Companion.provider { sourceReceiver.sourceField.invoke() }
   * ```
   */
  private fun createCrossShardDelegationField(
    shardClass: IrClass,
    typeKey: IrTypeKey,
    sourceReceiver: IrValueParameter,
    sourceField: IrField,
  ): IrField {
    val binding = bindingGraph.requireBinding(typeKey)
    val fieldName = fieldNameAllocator.newName(
      binding.nameHint.decapitalizeUS().suffixIfNot("Provider")
    )

    val delegationField = shardClass.addField {
      name = fieldName.asName()
      type = symbols.metroProvider.typeWith(typeKey.type)
      visibility = DescriptorVisibilities.PRIVATE // Delegation fields are private
      isFinal = true
      origin = Origins.MetroGraphShard
    }

    // Generate initializer: Companion.provider { sourceReceiver.sourceField.invoke() }
    delegationField.initializer = DeclarationIrBuilder(
      pluginContext,
      delegationField.symbol,
      UNDEFINED_OFFSET,
      UNDEFINED_OFFSET
    ).run {
      irExprBody(
        // Companion.provider { sourceReceiver.sourceField.invoke() }
        irInvoke(
          dispatchReceiver = null, // Static call
          callee = symbols.metroProviderFunction,
          typeArgs = listOf(typeKey.type),
          args = listOf(
            // Lambda: { sourceReceiver.sourceField.invoke() }
            irLambda(
              parent = shardClass,
              receiverParameter = null,
              emptyList(),
              typeKey.type,
              suspend = false,
            ) {
              +irReturn(
                irInvoke(
                  dispatchReceiver = irGetField(
                    receiver = irGet(sourceReceiver),
                    field = sourceField
                  ),
                  callee = symbols.providerInvoke
                )
              )
            }
          )
        )
      )
    }

    return delegationField
  }

  /**
   * Generates the initializer expression for a provider field.
   *
   * Returns: `Companion.provider(delegate = <binding expression>)`
   * Or for scoped: `DoubleCheck.provider(Companion.provider(...))`
   *
   * Cross-shard dependencies are resolved via delegation fields in the local context.
   */
  private fun generateProviderInitializer(
    binding: IrBinding,
    typeKey: IrTypeKey,
    shardThisReceiver: IrValueParameter,
    bindingFieldContext: BindingFieldContext,
  ): IrExpression {
    return DeclarationIrBuilder(
      pluginContext,
      shardThisReceiver.symbol,
      UNDEFINED_OFFSET,
      UNDEFINED_OFFSET
    ).run {
      // Create the expression generator factory for this shard's context
      val expressionGeneratorFactory = IrGraphExpressionGenerator.Factory(
        context = this@ShardedGraphGenerator,
        node = node,
        bindingFieldContext = bindingFieldContext,
        bindingGraph = bindingGraph,
        bindingContainerTransformer = bindingContainerTransformer,
        membersInjectorTransformer = membersInjectorTransformer,
        assistedFactoryTransformer = assistedFactoryTransformer,
        graphExtensionGenerator = graphExtensionGenerator,
        parentTracer = parentTracer,
      )

      // Create the expression generator
      val expressionGenerator = expressionGeneratorFactory.create(
        thisReceiver = shardThisReceiver,
      )

      // Generate the binding code (constructor call, provides method, etc.)
      val bindingExpression = expressionGenerator.generateBindingCode(
        binding = binding,
        accessType = IrGraphExpressionGenerator.AccessType.PROVIDER,
        fieldInitKey = typeKey, // Pass to avoid circular reference
      )

      // Wrap with DoubleCheck if scoped
      if (binding.isScoped()) {
        bindingExpression.doubleCheck(this, symbols, typeKey)
      } else {
        bindingExpression
      }
    }
  }

  /**
   * Generates init() methods that initialize each shard.
   */
  private fun generateInitMethods(shardClasses: List<ShardClassInfo>) {
    // Add shard reference fields to component
    val shardFields = shardClasses.map { shardInfo ->
      graphClass.addField {
        name = "shard${shardInfo.shardId}".asName()
        type = shardInfo.irClass.utilDefaultType
        visibility = DescriptorVisibilities.PRIVATE
        origin = Origins.MetroGraphShard
        // lateinit - will be initialized in init methods
        isFinal = false
      }
    }

    // Generate init() methods
    val initMethodNameAllocator = NameAllocator(mode = NameAllocator.Mode.COUNT)

    shardClasses.forEachIndexed { index, shardInfo ->
      val initMethodName = initMethodNameAllocator.newName("init")

      graphClass.addFunction {
        name = initMethodName.asName()
        visibility = DescriptorVisibilities.PRIVATE
        returnType = irBuiltIns.unitType
        origin = Origins.MetroGraphShard
      }.apply {
        // Create dispatch receiver (copy from component's this receiver)
        val componentThisReceiver = graphClass.thisReceiver
          ?: error("Component class has no this receiver")
        val localReceiver = componentThisReceiver.copyTo(this)
        setDispatchReceiver(localReceiver)

        body = DeclarationIrBuilder(pluginContext, symbol, UNDEFINED_OFFSET, UNDEFINED_OFFSET)
          .irBlockBody {
            val thisReceiver = localReceiver
            // this.shardN = ShardN(this, shard0, shard1, ...)
            +irSetField(
              receiver = irGet(thisReceiver),
              field = shardFields[index],
              value = irCallConstructor(
                callee = shardInfo.constructor.symbol,
                typeArguments = emptyList()
              ).apply {
                // Use new IR parameter API (K2 2.2.20+)
                // arguments[param.indexInParameters] instead of putValueArgument(index, ...)
                val ctorParams = shardInfo.constructor.parameters

                // Argument 0: component reference
                arguments[ctorParams[0].indexInParameters] = irGet(thisReceiver)

                // Arguments 1+: dependent shards
                val depShards = shardingResult.shards[shardInfo.shardId].dependencies.sorted()
                depShards.forEachIndexed { depIndex, depShardId ->
                  arguments[ctorParams[depIndex + 1].indexInParameters] = irGetField(
                    receiver = irGet(thisReceiver),
                    field = shardFields[depShardId]
                  )
                }
              }
            )
          }
      }
    }
  }

  /**
   * Updates the component constructor to call all init methods in order.
   */
  private fun updateComponentConstructor() {
    val ctor = graphClass.constructors.first { it.isPrimary }
    val originalBody = ctor.body
    // Use the component's thisReceiver, not the constructor's dispatchReceiver
    val thisReceiver = graphClass.thisReceiver
      ?: error("Component class has no this receiver")

    // Find all init methods (init, init2, init3, ...)
    val initMethods = graphClass.declarations
      .filterIsInstance<IrSimpleFunction>()
      .filter {
        it.name.asString().startsWith("init") &&
        it.parameters.filter { p -> p.kind == IrParameterKind.Regular }.isEmpty() &&
        it.origin == Origins.MetroGraphShard
      }
      .sortedBy { it.name.asString() }

    ctor.body = DeclarationIrBuilder(pluginContext, ctor.symbol, UNDEFINED_OFFSET, UNDEFINED_OFFSET)
      .irBlockBody {
        // Add original constructor body statements first
        if (originalBody != null) {
          originalBody.statements.forEach { +it }
        }

        // Call all init methods in order
        for (initMethod in initMethods) {
          +irCall(
            callee = initMethod.symbol
          ).apply {
            dispatchReceiver = irGet(thisReceiver)
          }
        }
      }
  }
}
