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
   * Main entry point for generating the sharded component.
   */
  fun generate() {
    parentTracer.traceNested("Generate sharded component") { tracer ->
      // Generate each shard class
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
      // TODO: Generate provider fields (Week 3 Day 3-4)
      // TODO: Handle cross-shard dependencies (Week 3 Day 5)
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
   */
  private fun generateProviderFields(
    shardClass: IrClass,
    shard: dev.zacsweers.metro.compiler.graph.Shard<IrTypeKey>,
    componentParam: IrValueParameter,
    depShardParams: List<IrValueParameter>,
  ) {
    // Create a BindingFieldContext for this shard to track generated fields
    val bindingFieldContext = BindingFieldContext()

    // Pre-populate BindingFieldContext with bound instances from component
    // BoundInstances are stored in the component class, not in shards, so we need to
    // tell the BindingFieldContext about them so IrGraphExpressionGenerator can find them
    // TODO: Week 3 Day 5 - Implement proper cross-component field access
    // For now, create placeholder fields that will fail at runtime if accessed
    for (typeKey in shard.bindings) {
      val binding = bindingGraph.requireBinding(typeKey)
      if (binding is IrBinding.BoundInstance) {
        // Create a placeholder field in the component (not the shard)
        // This is a temporary workaround - proper implementation in Day 5
        val placeholderField = graphClass.addField {
          name = ("__boundInstance_" + binding.nameHint.decapitalizeUS()).asName()
          type = binding.typeKey.type.wrapInProvider(symbols.metroProvider)
          visibility = DescriptorVisibilities.PRIVATE
          origin = Origins.MetroGraphShard
        }
        bindingFieldContext.putProviderField(typeKey, placeholderField)
      }
    }

    // Create an expression generator for this shard's context
    // We'll use the shard's constructor's this receiver as the context
    val shardThisReceiver = shardClass.thisReceiver
      ?: error("Shard class has no this receiver")

    // Generate provider fields for each binding in dependency order
    for (typeKey in shard.bindings) {
      val binding = bindingGraph.requireBinding(typeKey)

      // Skip bound instances - they're provided by the component, not generated in shards
      // Also skip graph dependencies that already have field access
      if (binding is IrBinding.BoundInstance ||
          (binding is IrBinding.GraphDependency && binding.fieldAccess != null)) {
        continue
      }

      // Determine field type and name
      val isProviderType = true // All shard fields are providers
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

      // Generate the provider initializer
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
            bindingFieldContext = bindingFieldContext,
          )
        )
      }

      // Track the field for cross-shard dependency resolution
      bindingFieldContext.putProviderField(typeKey, providerField)
      shardProviderFields[typeKey] = providerField
    }
  }

  /**
   * Generates the initializer expression for a provider field.
   *
   * Returns: `Companion.provider(delegate = <binding expression>)`
   * Or for scoped: `DoubleCheck.provider(Companion.provider(...))`
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
