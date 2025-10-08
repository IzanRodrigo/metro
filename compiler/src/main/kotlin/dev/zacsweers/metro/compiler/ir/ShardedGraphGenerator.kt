// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:Suppress("DEPRECATION")
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.NameAllocator
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.decapitalizeUS
import dev.zacsweers.metro.compiler.expectAs
import dev.zacsweers.metro.compiler.graph.ShardingResult
import dev.zacsweers.metro.compiler.mapToSet
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.ir.parameters.wrapInProvider
import dev.zacsweers.metro.compiler.ir.transformers.AssistedFactoryTransformer
import dev.zacsweers.metro.compiler.ir.transformers.BindingContainerTransformer
import dev.zacsweers.metro.compiler.ir.transformers.MembersInjectorTransformer
import dev.zacsweers.metro.compiler.suffixIfNot
import dev.zacsweers.metro.compiler.tracing.Tracer
import dev.zacsweers.metro.compiler.tracing.traceNested
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.backend.common.lower.irThrow
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addField
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addTypeParameter
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.irAs
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irDelegatingConstructorCall
import org.jetbrains.kotlin.ir.builders.irEquals
import org.jetbrains.kotlin.ir.builders.irExprBody
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irSetField
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrOverridableDeclaration
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrBranchImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrElseBranchImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrWhenImpl
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.typeOrFail
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.defaultType as utilDefaultType
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.propertyIfAccessor
import org.jetbrains.kotlin.ir.util.statements
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.types.Variance

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
   * Maps provider field IrTypeKeys to the shard ID that contains them.
   * Used to associate fields with their shard instance fields.
   */
  private val providerFieldToShardId = mutableMapOf<IrTypeKey, Int>()

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
   * Ordered list of shard init methods as they are generated. Replaces reflective scanning +
   * numeric name parsing during constructor update. Deterministic because shard generation order
   * is deterministic. If empty (unexpected), updateComponentConstructor() falls back to a scan.
   */
  private val shardInitMethods = mutableListOf<IrSimpleFunction>()

  /**
   * Component-scope initialization methods that must execute before shard initialization (e.g.,
   * bound instance field wiring).
   */
  private val componentInitMethods = mutableListOf<IrSimpleFunction>()
  private val componentInitMethodArguments = mutableMapOf<IrSimpleFunction, List<IrValueParameter>>()

  /**
   * Max number of shard init method calls per partition wrapper. Mirrors the non-sharded
   * `IrGraphGenerator`'s `STATEMENTS_PER_METHOD` (25) which itself is borrowed from Dagger's
   * long-standing heuristic for keeping method bytecode size well below limits while balancing
   * dex/mapping overhead. Keeping these aligned simplifies reasoning and tuning.
   */
  // Use shared heuristic constant for parity with non-sharded generator.
  private val SHARD_INIT_CALLS_PER_WRAPPER = MetroConstants.MAX_INIT_STATEMENTS_PER_METHOD

  /** Chunk size for component-level bound instance initialization helper methods. */
  private val COMPONENT_INIT_CALLS_PER_METHOD = 200

  /**
   * Expression generator factory configured with the global binding field context.
   * This allows the standard implementOverrides() logic to work with sharded fields.
   */
  private val expressionGeneratorFactory =
    IrGraphExpressionGenerator.Factory(
      context = this,
      node = node,
      bindingFieldContext = globalBindingFieldContext,
      bindingGraph = bindingGraph,
      bindingContainerTransformer = bindingContainerTransformer,
      membersInjectorTransformer = membersInjectorTransformer,
      assistedFactoryTransformer = assistedFactoryTransformer,
      graphExtensionGenerator = graphExtensionGenerator,
      parentTracer = parentTracer,
    )

  /** Global binding ordering information shared across shard generation steps. */
  private val bindingPlannerResult = GlobalBindingPlanner(bindingGraph, sealResult).plan()

  private val graphClassId: ClassId = graphClass.classIdOrFail

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
   * Describes a field that must be initialized after construction.
   *
   * [buildInitializer] is invoked within the appropriate IR builder so the expression can be
   * created with the correct parent and captures. The parameter supplies the dispatch receiver
   * (`this`) for the enclosing class along with an optional mapping of constructor parameters to
   * copied parameters in helper methods.
   */
  private data class FieldInitialization(
    val field: IrField,
    val requiredParams: List<IrValueParameter> = emptyList(),
    val buildInitializer: IrBuilderWithScope.(IrValueParameter, Map<IrValueParameter, IrValueParameter>) -> IrExpression,
  )

  /**
   * Component-level field initialization metadata used to wire @BindsInstance parameters without
   * bloating the constructor body.
   */
  private val componentInitializations = mutableListOf<FieldInitialization>()

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
      componentInitMethods.clear()
      componentInitMethodArguments.clear()
      componentInitializations.clear()
      val componentCtor = graphClass.constructors.first { it.isPrimary }
      val componentThisReceiver = graphClass.thisReceiver
        ?: error("Component class has no this receiver")

      // First, handle BoundInstance fields in the component (not shards)
      tracer.traceNested("Generate BoundInstance fields") {
        generateBoundInstanceFields(componentCtor, componentThisReceiver)
      }

      // Generate each shard class with provider fields
      val shardClasses = tracer.traceNested("Generate shard classes") {
        generateShardClasses(tracer)
      }

      // Generate init methods that initialize shards and get shard instance fields
      val shardInstanceFields = tracer.traceNested("Generate init methods") {
        generateInitMethods(shardClasses)
      }

      // Update globalBindingFieldContext to associate shard provider fields with their shard instances
      tracer.traceNested("Update binding field context with shard locations") {
        updateBindingFieldContextWithShardLocations(shardClasses, shardInstanceFields)
      }

      // Mirror provider fields into already-generated extension impls now that shards exist.
      // Extensions were generated before sharding so they could not synthesize these delegation
      // fields earlier. Doing this before implementing overrides ensures expression generation
      // for extension code sees local provider fields, preventing NoSuchFieldError at runtime.
      tracer.traceNested("Mirror provider fields into extensions post-sharding") {
        synthesizeExtensionDelegations(shardClasses)
      }

      tracer.traceNested("Finalize component init methods") {
        finalizeComponentInitializations(componentCtor, componentThisReceiver)
      }

      // Update component constructor to call init methods
      tracer.traceNested("Update component constructor") {
        updateComponentConstructor()
      }

      // Implement component interface methods (accessors, injectors, factories)
      // Use the standard implementation from DependencyGraphNode, which works correctly
      // with our globalBindingFieldContext that tracks fields across shards
      tracer.traceNested("Implement overrides") {
        node.implementOverrides()
      }
    }
  }

  private fun synthesizeExtensionDelegations(shardClasses: List<ShardClassInfo>) {
    val shardIrClasses = shardClasses.map { it.irClass }
    val extensionImpls = graphClass.declarations.filterIsInstance<IrClass>()
      .filter { it.origin == Origins.GeneratedGraphExtension }
    if (extensionImpls.isEmpty()) return

    val shardProviderNames = shardIrClasses.flatMap { shard ->
      shard.declarations.filterIsInstance<IrField>()
        .filter { (it.type as? IrSimpleType)?.classOrNull == symbols.metroProvider }
        .map { it.name }
    }.toSet()
    val parentProviderFields = graphClass.declarations.filterIsInstance<IrField>()
      .filter { f ->
        val simple = f.type as? IrSimpleType ?: return@filter false
        simple.classOrNull == symbols.metroProvider && f.name !in shardProviderNames
      }

    // Metrics (A): aggregate counters
    var totalDelegatedFields = 0
    var totalExtensionsWithDelegations = 0

    // Optional (C): capture a structured delegation map for later metrics/debug dump.
    // Built if either full sharding debug is on OR the lightweight delegation map dump flag is set.
    // This allows emitting a summarized metrics + map section without enabling the very verbose
    // per-extension field tracing that shardingDebug produces.
    val delegationMapForDebug: MutableMap<String, MutableList<String>>? =
      if (options.shardingDebug) mutableMapOf() else null

    for (extension in extensionImpls) {
      val existingNames = extension.declarations.filterIsInstance<IrField>().map { it.name }.toMutableSet()
      val outerInstanceField = extension.declarations.filterIsInstance<IrField>()
        .firstOrNull { it.type == graphClass.utilDefaultType }
      val added = mutableListOf<IrField>()

      fun createDelegatedField(providerField: IrField, shardInstanceField: IrField?, underlyingType: IrType) {
        if (providerField.name in existingNames) return // Dedup (already present)
        val providerFieldType = underlyingType.wrapInProvider(symbols.metroProvider)
        val localField = extension.addField {
          name = providerField.name
          type = providerFieldType
          visibility = DescriptorVisibilities.PRIVATE
          isFinal = true
          origin = Origins.GeneratedGraphExtension
        }
        localField.initializer = DeclarationIrBuilder(pluginContext, localField.symbol, UNDEFINED_OFFSET, UNDEFINED_OFFSET)
          .irExprBody(
            DeclarationIrBuilder(pluginContext, localField.symbol, UNDEFINED_OFFSET, UNDEFINED_OFFSET).run {
              val providerLambda = irLambda(
                parent = extension,
                receiverParameter = null,
                valueParameters = emptyList(),
                returnType = underlyingType,
                suspend = false,
              ) {
                val parentInstance = if (outerInstanceField != null) {
                  irGetField(irGet(extension.thisReceiverOrFail), outerInstanceField)
                } else {
                  irGet(graphClass.thisReceiverOrFail)
                }
                val shardOrParent = if (shardInstanceField != null) {
                  irGetField(parentInstance, shardInstanceField)
                } else parentInstance
                +irReturn(
                  irInvoke(
                    dispatchReceiver = irGetField(shardOrParent, providerField),
                    callee = symbols.providerInvoke,
                    typeHint = underlyingType,
                  )
                )
              }
              irInvoke(
                dispatchReceiver = null,
                callee = symbols.metroProviderFunction,
                typeArgs = listOf(underlyingType),
                args = listOf(providerLambda),
                typeHint = providerFieldType,
              )
            }
          )
        added += localField
        existingNames += providerField.name
        val key = bindingGraph.bindingsSnapshot().keys.firstOrNull { it.type == underlyingType } ?: IrTypeKey(underlyingType)
        if (globalBindingFieldContext.providerField(key) == null) {
          globalBindingFieldContext.putProviderField(
            key,
            localField,
            ownerGraphClassId = graphClassId,
          )
        }
        delegationMapForDebug?.getOrPut(extension.name.asString()) { mutableListOf() }
          ?.add(providerField.name.asString())
      }

      // Mirror shard provider fields
      for (shard in shardIrClasses) {
        val shardInstanceField = graphClass.declarations.filterIsInstance<IrField>()
          .firstOrNull { it.type == shard.utilDefaultType }
        for (providerField in shard.declarations.filterIsInstance<IrField>()) {
          if (providerField.name in existingNames) continue
          val simple = providerField.type as? IrSimpleType ?: continue
          if (simple.classOrNull != symbols.metroProvider) continue
          val underlyingType = simple.arguments.firstOrNull()?.typeOrFail ?: continue
          createDelegatedField(providerField, shardInstanceField, underlyingType)
        }
      }

      // Mirror direct parent provider fields
      for (providerField in parentProviderFields) {
        if (providerField.name in existingNames) continue
        val simple = providerField.type as? IrSimpleType ?: continue
        val underlyingType = simple.arguments.firstOrNull()?.typeOrFail ?: continue
        createDelegatedField(providerField, null, underlyingType)
      }

      if (added.isNotEmpty()) {
        totalExtensionsWithDelegations++
        totalDelegatedFields += added.size
        val existingAttr = extension.extensionDelegatedProviderFields ?: mutableListOf()
        existingAttr += added
        extension.extensionDelegatedProviderFields = existingAttr
        if (options.shardingDebug) {
          writeDiagnostic("sharding-trace.txt") {
            buildString {
              appendLine("POST-SHARD-EXT-DEL ${extension.name} synthesized=${added.size}")
              added.forEach { fld -> appendLine("  field=${fld.name.asString()} type=${fld.type}") }
            }
          }
        }
      } else if (options.shardingDebug) {
        writeDiagnostic("sharding-trace.txt") { "POST-SHARD-EXT-DEL ${extension.name} synthesized=0" }
      }
    }

  // (C) Delegation map & metrics dump. Triggers only when shardingDebug is enabled.
  val dumpDelegation = options.shardingDebug
    if (dumpDelegation) {
      writeDiagnostic("sharding-trace.txt") {
        buildString {
          appendLine("POST-SHARD-EXT-DEL-METRICS totalFields=$totalDelegatedFields extensionsWithDelegations=$totalExtensionsWithDelegations extensions=${extensionImpls.size}")
          delegationMapForDebug?.forEach { (ext, fields) ->
            appendLine("  EXT ${ext} -> ${fields.size} fields")
            fields.sorted().forEach { f -> appendLine("    - $f") }
          }
        }
      }
    }
  }

  private fun generateBoundInstanceFields(
    componentCtor: IrConstructor,
    componentThisReceiver: IrValueParameter,
  ) {
    // EARLY DIAGNOSTIC MARKER: If this line does not appear in sharding-trace.txt, then the
    // running compiler plugin is NOT the patched composite copy. Unique token below for grep.
    writeDiagnostic("sharding-trace.txt") { "BOUND-INSTANCE-FIELDS-FIRST-LINE token=8f9d3b1 originClass=${this::class.qualifiedName}" }

    // Force log regardless of shardingDebug to debug missing Application bound instance
    writeDiagnostic("sharding-trace.txt") {
      buildString {
        append("CTOR-PARAMS ")
        append(componentCtor.regularParameters.joinToString { p -> "${p.name.asString()}:${p.type}" })
      }
    }

    // Removed prior platform-specific guard (android.app.Application). Bound instance handling
    // must remain generic. Any missing provider will be caught by the generic fallback below.

    // Process @BindsInstance constructor parameters.
    node.creator?.let { creator ->
      for ((i, param) in creator.parameters.regularParameters.withIndex()) {
        val isBindsInstance = param.isBindsInstance
        val irParam = componentCtor.regularParameters[i]
        val isGraphDependency = node.includedGraphNodes.containsKey(param.typeKey)
        val isBindingContainer = creator.bindingContainersParameterIndices.isSet(i)

        if (isBindsInstance || isBindingContainer) {
          val reachable = param.typeKey in sealResult.reachableKeys
          val baseName = param.name.asString()
            .removePrefix("$$")
            .decapitalizeUS()
            .suffixIfNot("Instance")

          val instanceField = graphClass.addField {
            name = fieldNameAllocator.newName(baseName).asName()
            type = param.typeKey.type
            visibility = DescriptorVisibilities.PRIVATE
            isFinal = false
            origin = Origins.MetroGraphShard
          }
          componentInitializations += FieldInitialization(
            field = instanceField,
            requiredParams = listOf(irParam),
          ) { _, paramMap ->
            irGet(paramMap.getValue(irParam))
          }
          if (reachable) {
            globalBindingFieldContext.putInstanceField(
              param.typeKey,
              instanceField,
              ownerGraphClassId = graphClassId,
            )
          }

          val providerFieldName = fieldNameAllocator.newName(baseName + "Provider")
          val providerField = graphClass.addField {
            name = providerFieldName.asName()
            type = symbols.metroProvider.typeWith(param.typeKey.type)
            visibility = DescriptorVisibilities.INTERNAL
            isFinal = false
            origin = Origins.MetroGraphShard
          }
          componentInitializations += FieldInitialization(
            field = providerField,
            requiredParams = emptyList(),
          ) { receiver, _ ->
            instanceFactory(
              param.typeKey.type,
              irGetField(irGet(receiver), instanceField)
            )
          }
          globalBindingFieldContext.putProviderField(
            param.typeKey,
            providerField,
            ownerGraphClassId = graphClassId,
          )

          if (options.shardingDebug) {
            writeDiagnostic("sharding-trace.txt") {
              buildString {
                appendLine(
                  "BOUND-INSTANCE param=${param.name.asString()} type=${param.typeKey.type} reachable=$reachable isBindsInstance=$isBindsInstance isBindingContainer=$isBindingContainer isGraphDependency=$isGraphDependency instanceField=${instanceField?.name?.asString()} providerField=${providerField.name.asString()}"
                )
              }
            }
          }
        } else if (options.shardingDebug) {
          writeDiagnostic("sharding-trace.txt") {
            "BOUND-INSTANCE-SKIP graphDepParam=${param.name.asString()} type=${param.typeKey.type}"
          }
        }
      }
    }

    // Fallback: ensure every BoundInstance binding has a provider (and optional instance) field.
    run {
      val ctorParamsByType = componentCtor.regularParameters.associateBy { IrTypeKey(it.type) }
      for ((typeKey, binding) in bindingGraph.bindingsSnapshot()) {
        if (binding !is IrBinding.BoundInstance) continue
        if (binding.classReceiverParameter != null || binding.providerFieldAccess != null) continue
        if (globalBindingFieldContext.providerField(typeKey) != null) continue

        val irParam = ctorParamsByType[typeKey] ?: componentCtor.regularParameters.firstOrNull { p ->
          val pClassifier = (p.type as? IrSimpleType)?.classOrNull
          val targetClassifier = (typeKey.type as? IrSimpleType)?.classOrNull
          pClassifier == targetClassifier
        } ?: continue

        val baseName = irParam.name.asString().removePrefix("$$").decapitalizeUS().suffixIfNot("Instance")

        val existingLocation = globalBindingFieldContext.instanceField(typeKey)
        val instanceField = existingLocation?.field ?: graphClass.addField {
          name = fieldNameAllocator.newName(baseName).asName()
          type = irParam.type
          visibility = DescriptorVisibilities.PRIVATE
          isFinal = false
          origin = Origins.MetroGraphShard
        }.also { field ->
          componentInitializations += FieldInitialization(
            field = field,
            requiredParams = listOf(irParam),
          ) { _, paramMap ->
            irGet(paramMap.getValue(irParam))
          }
          globalBindingFieldContext.putInstanceField(
            typeKey,
            field,
            ownerGraphClassId = graphClassId,
          )
        }

        val providerField = graphClass.addField {
          name = fieldNameAllocator.newName(baseName + "Provider").asName()
          type = symbols.metroProvider.typeWith(irParam.type)
          visibility = DescriptorVisibilities.INTERNAL
          isFinal = false
          origin = Origins.MetroGraphShard
        }
        componentInitializations += FieldInitialization(
          field = providerField,
          requiredParams = emptyList(),
        ) { receiver, _ ->
          instanceFactory(
            irParam.type,
            irGetField(irGet(receiver), instanceField)
          )
        }
        globalBindingFieldContext.putProviderField(typeKey, providerField)

        if (options.shardingDebug) {
          val reachable = typeKey in sealResult.reachableKeys
          writeDiagnostic("sharding-trace.txt") {
            "BOUND-INSTANCE-FALLBACK param=${irParam.name.asString()} type=${irParam.type} reachable=$reachable providerField=${providerField.name.asString()}"
          }
        }
      }
    }

    // Generic defensive duplication: mirror classifier-compatible providers when necessary.
    run {
      for ((bk, b) in bindingGraph.bindingsSnapshot()) {
        if (b !is IrBinding.BoundInstance) continue
        if (globalBindingFieldContext.providerField(bk) != null) continue
        val loose = globalBindingFieldContext.providerFieldLoose(bk) ?: continue
        val (matchedKey, loc) = loose
        globalBindingFieldContext.putProviderField(
          bk,
          loc.field,
          loc.shardField,
          loc.ownerGraphClassId,
        )
        if (options.shardingDebug) {
          writeDiagnostic("sharding-trace.txt") {
            "BOUND-INSTANCE-DUPLICATE addedKey=${bk.type} fromKey=${matchedKey.type}"
          }
        }
      }
    }

    // Component self reference (unchanged except for diagnostic)
    val compReachable = node.typeKey in sealResult.reachableKeys
    if (compReachable) {
      val thisGraphInstanceField = graphClass.addField {
        name = fieldNameAllocator.newName("thisGraphInstance").asName()
        type = node.typeKey.type
        visibility = DescriptorVisibilities.PRIVATE
        isFinal = false
        origin = Origins.MetroGraphShard
      }
      componentInitializations += FieldInitialization(
        field = thisGraphInstanceField
      ) { receiver, _ ->
        irGet(receiver)
      }

      val componentProviderField = graphClass.addField {
        name = fieldNameAllocator.newName("thisGraphInstanceProvider").asName()
        type = symbols.metroProvider.typeWith(node.typeKey.type)
        visibility = DescriptorVisibilities.INTERNAL
        isFinal = false
        origin = Origins.MetroGraphShard
      }
      componentInitializations += FieldInitialization(
        field = componentProviderField
      ) { receiver, _ ->
        instanceFactory(
          node.typeKey.type,
          irGetField(irGet(receiver), thisGraphInstanceField)
        )
      }
      globalBindingFieldContext.putProviderField(
        node.typeKey,
        componentProviderField,
        ownerGraphClassId = graphClassId,
      )

      if (options.shardingDebug) {
        writeDiagnostic("sharding-trace.txt") {
          "BOUND-INSTANCE thisGraph type=${node.typeKey.type} instanceField=${thisGraphInstanceField.name.asString()} providerField=${componentProviderField.name.asString()}"
        }
      }
    } else if (options.shardingDebug) {
      writeDiagnostic("sharding-trace.txt") {
        "BOUND-INSTANCE thisGraph SKIPPED (unreachable) type=${node.typeKey.type}"
      }
    }

  }

  private fun finalizeComponentInitializations(
    componentCtor: IrConstructor,
    componentThisReceiver: IrValueParameter,
  ) {
    if (componentInitializations.isEmpty()) return

    val constructorParams = componentCtor.regularParameters
    val chunks = componentInitializations.chunked(COMPONENT_INIT_CALLS_PER_METHOD)
    chunks.forEachIndexed { index, chunk ->
      val methodName = when {
        chunks.size == 1 -> "initBoundInstances"
        index == 0 -> "initBoundInstances"
        else -> "initBoundInstances${index + 1}"
      }

      val requiredParams = constructorParams.filter { ctorParam ->
        chunk.any { initialization -> ctorParam in initialization.requiredParams }
      }

      val initFunction = graphClass.addFunction {
        name = methodName.asName()
        visibility = DescriptorVisibilities.PRIVATE
        returnType = irBuiltIns.unitType
        origin = Origins.MetroGraphShard
      }.apply {
        val localReceiver = componentThisReceiver.copyTo(this)
        setDispatchReceiver(localReceiver)

        val paramMapping = requiredParams.associateWith { ctorParam ->
          addValueParameter {
            name = ctorParam.name
            type = ctorParam.type
            origin = Origins.MetroGraphShard
          }
        }

        body = DeclarationIrBuilder(pluginContext, symbol, UNDEFINED_OFFSET, UNDEFINED_OFFSET)
          .irBlockBody {
            chunk.forEach { initialization ->
              val effectiveMapping = initialization.requiredParams.associateWith { requiredParam ->
                paramMapping.getValue(requiredParam)
              }
              val initValue = initialization.buildInitializer(
                this,
                localReceiver,
                effectiveMapping
              )
              +irSetField(
                receiver = irGet(localReceiver),
                field = initialization.field,
                value = initValue
              )
            }
          }
      }

      componentInitMethods += initFunction
      componentInitMethodArguments[initFunction] = requiredParams
    }

    componentInitializations.clear()
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

      // Generate provider fields for bindings in this shard
      val fieldInitializations = generateProviderFields(this, shard, componentParam, depShardParams)

      // Set constructor body with proper super() call and field initialization
      val shardThis = this.thisReceiver ?: error("Shard class has no this receiver")
      ctor.body = ctor.generateDefaultConstructorBody {
        fieldInitializations.forEach { initialization ->
          +irSetField(
            receiver = irGet(shardThis),
            field = initialization.field,
            value = initialization.buildInitializer(this, shardThis, emptyMap())
          )
        }
      }
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
  ): List<FieldInitialization> {
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

    // Create a per-shard name allocator to avoid numeric suffixes across shards
    // Each shard is a separate class scope, so field names don't collide
    val shardFieldNameAllocator = NameAllocator()

    // Create a local binding field context for this shard
    // This will be populated with both local fields and delegation fields
    val localBindingFieldContext = BindingFieldContext()
    val fieldInitializations = mutableListOf<FieldInitialization>()

    // First pass: Create delegation fields for dependencies from other shards or component
    // This ensures all dependencies are available locally when generating binding code
    //
    // We look at ALL bindings in the binding graph to find dependencies that might be needed
    // by this shard. This is simpler than extracting dependencies from each binding type.
    val allDependenciesInShard = mutableSetOf<IrTypeKey>()

    // Collect transitive dependency closure (BFS) so that delegation fields include indirect
    // dependencies (e.g., android.content.Context -> android.app.Application). Without this,
    // switching provider partition generation can miss bound instance providers that are only
    // transitively required, triggering BoundInstance misses.
    run {
      val queue = ArrayDeque<IrTypeKey>()
      // Seed with direct deps of shard bindings
      for (typeKey in shard.bindings) {
        val binding = bindingGraph.requireBinding(typeKey)
        binding.dependencies.forEach { dep ->
          if (allDependenciesInShard.add(dep.typeKey)) queue.addLast(dep.typeKey)
        }
      }
      val visited = mutableSetOf<IrTypeKey>()
      while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        if (!visited.add(current)) continue
        val b = bindingGraph.findBinding(current) ?: continue
        b.dependencies.forEach { child ->
          if (allDependenciesInShard.add(child.typeKey)) queue.addLast(child.typeKey)
        }
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
          // These were added to globalBindingFieldContext by generateBoundInstanceFields(). Keys
          // may not match exactly if one side has flexible/platform type vs not-null, so attempt
          // a loose classifier/arity match when exact lookup fails.
          val componentFieldLocationExact = globalBindingFieldContext.providerField(depKey)
          val componentFieldLoose = if (componentFieldLocationExact == null) {
            globalBindingFieldContext.providerFieldLoose(depKey)
          } else null
          val targetFieldLocation = componentFieldLocationExact ?: componentFieldLoose?.second
          val effectiveTypeKey = if (componentFieldLocationExact != null) depKey else componentFieldLoose?.first
          if (targetFieldLocation != null && effectiveTypeKey != null) {
            val delegationField = createCrossShardDelegationField(
              shardClass = shardClass,
              typeKey = effectiveTypeKey,
              sourceReceiver = componentParam,
              sourceField = targetFieldLocation.field,
              fieldNameAllocator = shardFieldNameAllocator,
            )
            fieldInitializations += delegationField
            localBindingFieldContext.putProviderField(effectiveTypeKey, delegationField.field)
          }
          // If still null, it might be a GraphDependency with fieldAccess which is handled
          // directly by IrGraphExpressionGenerator
        }
        else -> {
          // Cross-shard dependency - create delegation field
          val depShardParam = shardAccessContext.depShardParams[depShardId]
          val depShardFieldLocation = globalBindingFieldContext.providerField(depKey)

          if (depShardParam != null && depShardFieldLocation != null) {
            val delegationField = createCrossShardDelegationField(
              shardClass = shardClass,
              typeKey = depKey,
              sourceReceiver = depShardParam,
              sourceField = depShardFieldLocation.field,
              fieldNameAllocator = shardFieldNameAllocator,
            )
            fieldInitializations += delegationField
            localBindingFieldContext.putProviderField(depKey, delegationField.field)
          }
        }
      }
    }

    // Second pass: Generate actual provider fields for bindings owned by this shard
    // Filter out bindings we shouldn't generate fields for. Result is a list of (key, binding).
    val bindingsToGenerate: List<Pair<IrTypeKey, IrBinding>> = shard.bindings.mapNotNull { typeKey ->
      val binding = bindingGraph.requireBinding(typeKey)
      if (binding is IrBinding.BoundInstance ||
        (binding is IrBinding.GraphDependency && binding.fieldAccess != null)
      ) {
        null // handled in component or via fieldAccess
      } else typeKey to binding
    }

    // Fast-init support: assign stable IDs and synthesize a SwitchingProvider class once
    val orderedBindingsWithIds = if (options.fastInit) assignBindingIds(bindingsToGenerate.map { it.first }) else emptyList()
    val bindingIdMap = if (orderedBindingsWithIds.isNotEmpty()) orderedBindingsWithIds.toMap() else emptyMap()
    val switchingProviderClass = if (options.fastInit && orderedBindingsWithIds.isNotEmpty()) {
      generateSwitchingProviderClass(
        shardClass = shardClass,
        shard = shard,
        orderedBindings = orderedBindingsWithIds,
        bindingFieldContext = localBindingFieldContext,
        shardThisReceiver = shardThisReceiver,
      )
    } else null

    // Generate provider fields
    for ((typeKey, binding) in bindingsToGenerate) {
      // Determine field type and name
      val suffix = "Provider"
      val fieldType = symbols.metroProvider.typeWith(typeKey.type)

      // Allocate a unique field name using the shard-local allocator
      val fieldName = shardFieldNameAllocator.newName(
        binding.nameHint.decapitalizeUS().suffixIfNot(suffix)
      )

      // Create the field
      val providerField = shardClass.addField {
        name = fieldName.asName()
        type = fieldType
        visibility = DescriptorVisibilities.INTERNAL // Must be internal for cross-shard access
        isFinal = false
        origin = Origins.MetroGraphShard
      }

      fieldInitializations += FieldInitialization(field = providerField) { shardReceiver, _ ->
        if (options.fastInit && switchingProviderClass != null) {
          val bindingId = bindingIdMap[typeKey]
            ?: error("Missing binding ID for $typeKey in shard ${shard.id}")

          val switchingProviderConstructor = switchingProviderClass.constructors.first { it.isPrimary }
          val switchingProviderInstance = irCallConstructor(
            switchingProviderConstructor.symbol,
            typeArguments = listOf(typeKey.type)
          ).apply {
            var idx = 0
            arguments[idx++] = irGet(shardReceiver)
            arguments[idx++] = irInt(bindingId)
          }

          if (binding.isScoped()) {
            switchingProviderInstance.doubleCheck(this, symbols, typeKey)
          } else {
            switchingProviderInstance
          }
        } else {
          generateProviderInitializer(
            binding = binding,
            typeKey = typeKey,
            shardThisReceiver = shardReceiver,
            bindingFieldContext = localBindingFieldContext,
          )
        }
      }

      // Track the field in both local and global contexts
      localBindingFieldContext.putProviderField(typeKey, providerField)
      globalBindingFieldContext.putProviderField(
        typeKey,
        providerField,
        ownerGraphClassId = shardClass.classId ?: graphClassId,
      )
      shardProviderFields[typeKey] = providerField
      providerFieldToShardId[typeKey] = shard.id
    }

    return fieldInitializations
  }

  /**
   * Assigns sequential IDs to bindings in topological order (dependencies first).
   *
   * This ordering provides better code locality in the SwitchingProvider's when expression.
   */
  private fun assignBindingIds(typeKeys: List<IrTypeKey>): List<Pair<IrTypeKey, Int>> {
    // Build a dependency map for the bindings
    val dependencyMap = typeKeys.associateWith { typeKey ->
      val binding = bindingGraph.requireBinding(typeKey)
      binding.dependencies.map { it.typeKey }.filter { it in typeKeys }.toSet()
    }

    // Perform topological sort (dependencies first)
    val sorted = mutableListOf<IrTypeKey>()
    val visited = mutableSetOf<IrTypeKey>()
    val visiting = mutableSetOf<IrTypeKey>()

    fun visit(key: IrTypeKey) {
      if (key in visited) return
      if (key in visiting) {
        // Cycle detected - just add it now (cycles are handled by Provider<T> wrapping)
        return
      }

      visiting.add(key)
      dependencyMap[key]?.forEach { dep ->
        if (dep in typeKeys) {
          visit(dep)
        }
      }
      visiting.remove(key)
      visited.add(key)
      sorted.add(key)
    }

    typeKeys.forEach { visit(it) }

    // Assign sequential IDs
    return sorted.mapIndexed { index, typeKey -> typeKey to index }
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
    fieldNameAllocator: NameAllocator,
  ): FieldInitialization {
    val binding = bindingGraph.requireBinding(typeKey)
    val fieldName = fieldNameAllocator.newName(
      binding.nameHint.decapitalizeUS().suffixIfNot("Provider")
    )

    val delegationField = shardClass.addField {
      name = fieldName.asName()
      type = symbols.metroProvider.typeWith(typeKey.type)
      visibility = DescriptorVisibilities.PRIVATE // Delegation fields are private
      isFinal = false
      origin = Origins.MetroGraphShard
    }

    return FieldInitialization(delegationField) { _, _ ->
      irInvoke(
        dispatchReceiver = null,
        callee = symbols.metroProviderFunction,
        typeArgs = listOf(typeKey.type),
        args = listOf(
          irLambda(
            parent = shardClass,
            receiverParameter = null,
            valueParameters = emptyList(),
            returnType = typeKey.type,
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
        ),
        typeHint = symbols.metroProvider.typeWith(typeKey.type)
      )
    }
  }

  /**
   * Generates a SwitchingProvider class for fast-init mode.
   *
   * Creates a nested class that implements Provider<T> and uses when() expression routing
   * to instantiate bindings based on an integer ID.
   *
   * Generated code structure:
   * ```kotlin
   * private class $$SwitchingProvider0<T>(
   *   private val shard: Shard0,
   *   private val id: Int
   * ) : Provider<T> {
   *   override fun invoke(): T {
   *     return when (id / 100) {
   *       0 -> invoke0()
   *       1 -> invoke1()
   *       else -> throw AssertionError(id)
   *     } as T
   *   }
   *
   *   private fun invoke0(): Any {
   *     return when (id % 100) {
   *       0 -> FooImpl(...)
   *       1 -> BarImpl(...)
   *       else -> throw AssertionError(id)
   *     }
   *   }
   * }
   * ```
   *
   * @param shardClass The shard class that will contain this SwitchingProvider
   * @param shard The shard data structure with bindings
   * @param orderedBindings List of (TypeKey, ID) pairs in topological order
   * @param bindingFieldContext Context for accessing provider fields
   * @param shardThisReceiver The this receiver parameter for the shard
   */
  private fun generateSwitchingProviderClass(
    shardClass: IrClass,
    shard: dev.zacsweers.metro.compiler.graph.Shard<IrTypeKey>,
    orderedBindings: List<Pair<IrTypeKey, Int>>,
    bindingFieldContext: BindingFieldContext,
    shardThisReceiver: IrValueParameter,
  ): IrClass {
    val shardId = shard.id
    val className = "\$\$SwitchingProvider$shardId"

    // Create the SwitchingProvider class
    val switchingProviderClass = pluginContext.irFactory.buildClass {
      name = Name.identifier(className)
      visibility = DescriptorVisibilities.PRIVATE
      modality = Modality.FINAL
      kind = ClassKind.CLASS
    }.apply {
      parent = shardClass

      // Add type parameter <T> with OUT variance and explicit upper bound
      // The upper bound is critical to avoid "List is empty" errors during IR lowering
      val typeParam = addTypeParameter {
        name = Name.identifier("T")
        variance = Variance.OUT_VARIANCE
        // Set upper bound to Any? (nullable type)
        superTypes.add(pluginContext.irBuiltIns.anyNType)
      }

      // CRITICAL: Must call createThisReceiverParameter AFTER adding type parameters
      createThisReceiverParameter()

      // Implement Provider<T> interface
      superTypes = listOf(
        symbols.metroProvider.typeWith(typeParam.defaultType)
      )

      // Add constructor with parameters: (shard: Shard{N}, id: Int)
      val constructor = addConstructor {
        isPrimary = true
        visibility = DescriptorVisibilities.PRIVATE
      }

      // Add shard parameter
      val shardParam = constructor.addValueParameter {
        name = Name.identifier("shard")
        type = shardClass.utilDefaultType
      }

      // Add id parameter
      val idParam = constructor.addValueParameter {
        name = Name.identifier("id")
        type = pluginContext.irBuiltIns.intType
      }

      // Add private field for shard
      val shardField = addField {
        name = Name.identifier("shard")
        type = shardClass.utilDefaultType
        visibility = DescriptorVisibilities.PRIVATE
        isFinal = true
        origin = Origins.MetroGraphShard
      }

      // Add private field for id
      val idField = addField {
        name = Name.identifier("id")
        type = pluginContext.irBuiltIns.intType
        visibility = DescriptorVisibilities.PRIVATE
        isFinal = true
        origin = Origins.MetroGraphShard
      }

      // Generate constructor body with super() call and field initialization
      constructor.body = DeclarationIrBuilder(pluginContext, constructor.symbol).irBlockBody {
        // Super constructor call (required)
        +irDelegatingConstructorCall(
          pluginContext.irBuiltIns.anyClass.owner.constructors.single()
        )

        // Initialize shard field
        +irSetField(
          receiver = irGet(thisReceiver!!),
          field = shardField,
          value = irGet(shardParam)
        )

        // Initialize id field
        +irSetField(
          receiver = irGet(thisReceiver!!),
          field = idField,
          value = irGet(idParam)
        )
      }

      // Generate partition methods (invoke0, invoke1, etc.)
      val partitionMethods = generatePartitionMethods(
        switchingProviderClass = this,
        shardClass = shardClass,
        orderedBindings = orderedBindings,
        shardField = shardField,
        idField = idField,
        bindingFieldContext = bindingFieldContext
      )

      // Generate the main invoke() method
      generateInvokeMethod(
        switchingProviderClass = this,
        typeParam = typeParam,
        partitionMethods = partitionMethods,
        idField = idField
      )

      // Add this class to the shard
      shardClass.declarations.add(this)
    }

    return switchingProviderClass
  }

  /**
   * Generates partition methods (invoke0, invoke1, etc.) for the SwitchingProvider.
   *
   * Each partition method handles up to 100 bindings using a when() expression that routes
   * based on (id % 100).
   *
   * @param switchingProviderClass The SwitchingProvider class being generated
   * @param orderedBindings List of (TypeKey, ID) pairs in topological order
   * @param shardField The field holding the shard reference
   * @param idField The field holding the binding ID
   * @param bindingFieldContext Context for accessing provider fields
   * @return List of generated partition methods
   */
  @Suppress("DEPRECATION")
  private fun generatePartitionMethods(
    switchingProviderClass: IrClass,
    shardClass: IrClass,
    orderedBindings: List<Pair<IrTypeKey, Int>>,
    shardField: IrField,
    idField: IrField,
    bindingFieldContext: BindingFieldContext,
  ): List<IrSimpleFunction> {
    val partitionMethods = mutableListOf<IrSimpleFunction>()
    val maxCasesPerPartition = 100

    // Group bindings by partition (id / 100)
    val partitions = orderedBindings.groupBy { (_, id) -> id / maxCasesPerPartition }

    for ((partitionId, bindingsInPartition) in partitions.toSortedMap()) {
      val methodName = "invoke$partitionId"

      val partitionMethod = switchingProviderClass.addFunction(
        name = methodName,
        returnType = pluginContext.irBuiltIns.anyNType
      ).apply {
        visibility = DescriptorVisibilities.PRIVATE
        modality = Modality.FINAL

        // Set dispatch receiver
        // Note: we can't use copyTo() here because the SwitchingProvider has type parameters
        // and the partition method doesn't, which causes a remapping error.
        // Instead, create a new parameter directly.
        val methodThisReceiver = switchingProviderClass.thisReceiver!!.copyTo(
          this,
          type = switchingProviderClass.utilDefaultType // Use the concrete type, not parameterized
        )
        setDispatchReceiver(methodThisReceiver)

        // Generate method body with when expression
        body = DeclarationIrBuilder(pluginContext, symbol).irBlockBody {
          // Create when expression: when (id % 100) { ... }
          val whenExpr = IrWhenImpl(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            type = pluginContext.irBuiltIns.anyNType
          )

          // Add branches for each binding in this partition
          for ((typeKey, bindingId) in bindingsInPartition) {
            val binding = bindingGraph.requireBinding(typeKey)
            val caseValue = bindingId % maxCasesPerPartition

            // Generate the binding instantiation code
            // We need to generate code that accesses dependencies via shard.providerField.invoke()
            // The expression generator will look up provider fields in bindingFieldContext
            // and generate the appropriate invoke() calls

            // Create expression generator factory for this binding
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

            // Generate the binding code using INSTANCE access type
            // This generates the actual instantiation (not wrapped in Provider)
            //
            // IMPORTANT: The expression generator needs to access fields from the shard class.
            // We pass the shard class's thisReceiver so it generates field access expressions
            // using the shard's context. Then we transform these expressions to access fields
            // through this.shard instead of directly.
            val rawBindingExpression = expressionGeneratorFactory
              .create(shardClass.thisReceiver!!)
              .generateBindingCode(
                binding = binding,
                accessType = IrGraphExpressionGenerator.AccessType.INSTANCE,
                fieldInitKey = null,
              )

            // Transform the expression to replace shard's `this` with `this.shard`
            val transformedExpression = rawBindingExpression.transform(object : IrElementTransformerVoid() {
              override fun visitGetValue(expression: IrGetValue): IrExpression {
                // If this is accessing the shard's thisReceiver, replace with this.shard
                return if (expression.symbol.owner == shardClass.thisReceiver) {
                  irGetField(
                    receiver = irGet(methodThisReceiver),
                    field = shardField
                  )
                } else {
                  super.visitGetValue(expression)
                }
              }
            }, null)

            // CRITICAL FIX: The expression generator may return a Provider/Factory object
            // instead of the actual instance for certain binding types (@Provides methods,
            // factory classes, etc.). We need to invoke the provider to get the instance.
            //
            // When AccessType.INSTANCE is used with generateBindingCode(), it SHOULD invoke
            // providers automatically if a provider field exists (see IrGraphExpressionGenerator:132-136).
            // However, when generating code inside SwitchingProvider partition methods, we're in
            // a context where provider fields don't exist yet (they're being initialized), so
            // the expression generator returns factory.create() expressions instead of
            // factory.create().invoke() expressions.
            //
            // Solution: Check if the binding type typically returns a provider, and if so,
            // wrap the expression in a .invoke() call.
            //
            // For Alias bindings (@Binds), we need to check the aliased binding type recursively
            // since the expression generator resolves aliases automatically.
            fun needsInvocation(b: IrBinding): Boolean {
              return when (b) {
                is IrBinding.Provided -> true
                is IrBinding.ConstructorInjected -> true
                is IrBinding.Alias -> needsInvocation(b.aliasedBinding(bindingGraph))
                else -> false
              }
            }

            val bindingExpression = if (needsInvocation(binding)) {
              // Binding returns a factory/provider that needs to be invoked
              irInvoke(
                dispatchReceiver = transformedExpression,
                callee = symbols.providerInvoke
              )
            } else {
              // Binding returns an instance directly (ObjectClass, BoundInstance, etc.)
              transformedExpression
            }

            // Add branch: if (id % 100 == caseValue) return bindingExpression
            // Calculate id % 100
            // Find the rem function on Int
            val remFunction = pluginContext.irBuiltIns.intClass.owner.declarations
              .filterIsInstance<IrSimpleFunction>()
              .single {
                val regularParams = it.parameters.filter { p -> p.kind == IrParameterKind.Regular }
                it.name.asString() == "rem" &&
                regularParams.size == 1 &&
                regularParams.first().type == pluginContext.irBuiltIns.intType
              }

            val idModExpr = irCall(remFunction).apply {
              // With K2 parameter API, arguments includes all parameter types
              // arguments[0] = dispatch receiver (id)
              // arguments[1] = first regular parameter (divisor)
              arguments[0] = irGetField(
                receiver = irGet(methodThisReceiver),
                field = idField
              )
              arguments[1] = irInt(maxCasesPerPartition)
            }

            // Create the condition: idMod == caseValue
            val condition = irEquals(idModExpr, irInt(caseValue))

            whenExpr.branches.add(
              IrBranchImpl(
                startOffset = UNDEFINED_OFFSET,
                endOffset = UNDEFINED_OFFSET,
                condition = condition,
                result = bindingExpression
              )
            )
          }

          // Add else branch: throw AssertionError(id)
          // Find AssertionError class
          val assertionErrorClass = pluginContext.referenceClass(
            ClassId(FqName("kotlin"), Name.identifier("AssertionError"))
          )?.owner

          val elseResult = if (assertionErrorClass != null) {
            val assertionErrorConstructor = assertionErrorClass.constructors.first {
              val regularParams = it.parameters.filter { p -> p.kind == IrParameterKind.Regular }
              regularParams.size == 1 &&
              regularParams.first().type == pluginContext.irBuiltIns.anyNType
            }
            irThrow(
              irCallConstructor(
                assertionErrorConstructor.symbol,
                typeArguments = emptyList()
              ).apply {
                arguments[0] = irGetField(
                  receiver = irGet(methodThisReceiver),
                  field = idField
                )
              }
            )
          } else {
            // Fallback: throw IllegalArgumentException
            val illegalArgClass = pluginContext.referenceClass(
              ClassId(FqName("kotlin"), Name.identifier("IllegalArgumentException"))
            )?.owner
            val illegalArgConstructor = illegalArgClass?.constructors?.first {
              val regularParams = it.parameters.filter { p -> p.kind == IrParameterKind.Regular }
              regularParams.size == 1 &&
              regularParams.first().type == pluginContext.irBuiltIns.stringType
            }
            if (illegalArgConstructor != null) {
              irThrow(
                irCallConstructor(
                  illegalArgConstructor.symbol,
                  typeArguments = emptyList()
                ).apply {
                  arguments[0] = irString("Invalid binding ID")
                }
              )
            } else {
              // Ultimate fallback: just throw a simple error
              irThrow(
                irString("Invalid binding ID") as org.jetbrains.kotlin.ir.expressions.IrExpression
              )
            }
          }

          whenExpr.branches.add(
            IrElseBranchImpl(
              startOffset = UNDEFINED_OFFSET,
              endOffset = UNDEFINED_OFFSET,
              condition = IrConstImpl.boolean(
                startOffset = UNDEFINED_OFFSET,
                endOffset = UNDEFINED_OFFSET,
                type = pluginContext.irBuiltIns.booleanType,
                value = true
              ),
              result = elseResult
            )
          )

          +irReturn(whenExpr)
        }
      }

      partitionMethods.add(partitionMethod)
    }

    return partitionMethods
  }

  /**
   * Generates the main invoke() method for the SwitchingProvider.
   *
   * Creates a when() expression that routes to partition methods based on (id / 100),
   * then casts the result to T.
   *
   * @param switchingProviderClass The SwitchingProvider class being generated
   * @param typeParam The type parameter T
   * @param partitionMethods List of partition methods to route to
   * @param idField The field holding the binding ID
   */
  @Suppress("DEPRECATION")
  private fun generateInvokeMethod(
    switchingProviderClass: IrClass,
    typeParam: org.jetbrains.kotlin.ir.declarations.IrTypeParameter,
    partitionMethods: List<IrSimpleFunction>,
    idField: IrField,
  ) {
    val maxCasesPerPartition = 100

    // Find the Provider.invoke() method to override
    val providerInvokeSymbol = symbols.providerInvoke

    switchingProviderClass.addFunction(
      name = "invoke",
      returnType = typeParam.defaultType
    ).apply {
      visibility = DescriptorVisibilities.PUBLIC
      modality = Modality.OPEN  // Override methods should be OPEN, not OVERRIDE
      overriddenSymbols = listOf(providerInvokeSymbol)

      // Set dispatch receiver
      // Note: Use concrete type for the same reason as partition methods
      val invokeThisReceiver = switchingProviderClass.thisReceiver!!.copyTo(
        this,
        type = switchingProviderClass.typeWith(typeParam.defaultType) // Provider<T>
      )
      setDispatchReceiver(invokeThisReceiver)

      // Generate method body
      body = DeclarationIrBuilder(pluginContext, symbol).irBlockBody {
        // Create when expression: when (id / 100) { ... }
        val whenExpr = IrWhenImpl(
          startOffset = UNDEFINED_OFFSET,
          endOffset = UNDEFINED_OFFSET,
          type = pluginContext.irBuiltIns.anyNType
        )

        // Add branches for each partition method
        for (partitionMethod in partitionMethods) {
          // Extract partition ID from method name (invoke0 -> 0)
          val partitionId = partitionMethod.name.asString().removePrefix("invoke").toInt()

          // Generate: if (id / 100 == partitionId) return invoke{partitionId}()
          // Calculate id / 100
          // Find the div function on Int
          val divFunction = pluginContext.irBuiltIns.intClass.owner.declarations
            .filterIsInstance<IrSimpleFunction>()
            .single {
              val regularParams = it.parameters.filter { p -> p.kind == IrParameterKind.Regular }
              it.name.asString() == "div" &&
              regularParams.size == 1 &&
              regularParams.first().type == pluginContext.irBuiltIns.intType
            }

          val idDivExpr = irCall(divFunction).apply {
            // With K2 parameter API, arguments includes all parameter types
            // arguments[0] = dispatch receiver (id)
            // arguments[1] = first regular parameter (divisor)
            arguments[0] = irGetField(
              receiver = irGet(invokeThisReceiver),
              field = idField
            )
            arguments[1] = irInt(maxCasesPerPartition)
          }

          // Create the condition: idDiv == partitionId
          val condition = irEquals(idDivExpr, irInt(partitionId))

          // Create the result: call partition method
          val result = irCall(partitionMethod).apply {
            dispatchReceiver = irGet(invokeThisReceiver)
          }

          whenExpr.branches.add(
            IrBranchImpl(
              startOffset = UNDEFINED_OFFSET,
              endOffset = UNDEFINED_OFFSET,
              condition = condition,
              result = result
            )
          )
        }

        // Add else branch: throw AssertionError(id)
        // Find AssertionError class
        val assertionErrorClass = pluginContext.referenceClass(
          ClassId(FqName("kotlin"), Name.identifier("AssertionError"))
        )?.owner

        val elseResult = if (assertionErrorClass != null) {
          val assertionErrorConstructor = assertionErrorClass.constructors.first {
            val regularParams = it.parameters.filter { p -> p.kind == IrParameterKind.Regular }
            regularParams.size == 1 &&
            regularParams.first().type == pluginContext.irBuiltIns.anyNType
          }
          irThrow(
            irCallConstructor(
              assertionErrorConstructor.symbol,
              typeArguments = emptyList()
            ).apply {
              arguments[0] = irGetField(
                receiver = irGet(invokeThisReceiver),
                field = idField
              )
            }
          )
        } else {
          // Fallback: throw IllegalArgumentException
          val illegalArgClass = pluginContext.referenceClass(
            ClassId(FqName("kotlin"), Name.identifier("IllegalArgumentException"))
          )?.owner
          val illegalArgConstructor = illegalArgClass?.constructors?.first {
            val regularParams = it.parameters.filter { p -> p.kind == IrParameterKind.Regular }
            regularParams.size == 1 &&
            regularParams.first().type == pluginContext.irBuiltIns.stringType
          }
          if (illegalArgConstructor != null) {
            irThrow(
              irCallConstructor(
                illegalArgConstructor.symbol,
                typeArguments = emptyList()
              ).apply {
                arguments[0] = irString("Invalid partition ID")
              }
            )
          } else {
            // Ultimate fallback: just throw a simple error
            irThrow(
              irString("Invalid partition ID") as org.jetbrains.kotlin.ir.expressions.IrExpression
            )
          }
        }

        whenExpr.branches.add(
          IrElseBranchImpl(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            condition = IrConstImpl.boolean(
              startOffset = UNDEFINED_OFFSET,
              endOffset = UNDEFINED_OFFSET,
              type = pluginContext.irBuiltIns.booleanType,
              value = true
            ),
            result = elseResult
          )
        )

        // Cast to T and return
        +irReturn(
          irAs(whenExpr, typeParam.defaultType)
        )
      }
    }
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
  private fun generateInitMethods(shardClasses: List<ShardClassInfo>): List<IrField> {
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

    // Defensive clear in case of re-entry
    shardInitMethods.clear()

    shardClasses.forEachIndexed { index, shardInfo ->
      val initMethodName = initMethodNameAllocator.newName("init")
      val initFunction = graphClass.addFunction {
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
      shardInitMethods += initFunction
    }

    return shardFields
  }

  /**
   * Updates the global binding field context to associate shard provider fields with their
   * shard instance fields. This allows accessor methods to correctly generate code like
   * `this.shard3.providerField` instead of `this.providerField`.
   */
  private fun updateBindingFieldContextWithShardLocations(
    shardClasses: List<ShardClassInfo>,
    shardInstanceFields: List<IrField>
  ) {
    // For each provider field in a shard, update its FieldLocation to include the shard instance field
    for ((typeKey, shardId) in providerFieldToShardId) {
      val providerField = shardProviderFields[typeKey] ?: continue
      val shardInstanceField = shardInstanceFields[shardId]

      // Re-put the field with its shard location
      val owningShardClassId = shardClasses
        .firstOrNull { it.shardId == shardId }
        ?.irClass
        ?.classId
        ?: graphClassId

      globalBindingFieldContext.putProviderField(
        typeKey,
        providerField,
        shardInstanceField,
        ownerGraphClassId = owningShardClassId,
      )
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
    val componentMethods = componentInitMethods.toList()
    val shardMethods = when {
      shardInitMethods.isNotEmpty() -> shardInitMethods.toList()
      componentMethods.isEmpty() && shardInitMethods.isEmpty() -> {
        graphClass.declarations
          .filterIsInstance<IrSimpleFunction>()
          .filter {
            it.name.asString().startsWith("init") &&
              it.parameters.filter { p -> p.kind == IrParameterKind.Regular }.isEmpty() &&
              it.origin == Origins.MetroGraphShard
          }
          .sortedBy { fn ->
            val n = fn.name.asString()
            when (n) {
              "init" -> 1
              else -> n.removePrefix("init").toIntOrNull() ?: Int.MAX_VALUE
            }
          }
      }
      else -> emptyList()
    }

    if (options.shardingDebug) {
      writeDiagnostic("sharding-trace.txt") {
        val label = if (componentMethods.isNotEmpty() || shardMethods.isNotEmpty()) {
          "INIT-ORDER-STORED "
        } else {
          "INIT-ORDER-FALLBACK "
        }
        label + (componentMethods + shardMethods).joinToString { it.name.asString() }
      }
    }

    val componentCallTargets: List<Pair<IrSimpleFunction, List<IrValueParameter>>> =
      componentMethods.map { method ->
        method to componentInitMethodArguments[method].orEmpty()
      }

    val maxCallsPerWrapper = SHARD_INIT_CALLS_PER_WRAPPER
    val needsPartition = shardMethods.size > maxCallsPerWrapper

    val wrapperFunctions: List<IrSimpleFunction> = if (needsPartition) {
      shardMethods.chunked(maxCallsPerWrapper).mapIndexed { index, chunk ->
        graphClass.addFunction {
          name = Name.identifier("initShardGroup$index")
          visibility = DescriptorVisibilities.PRIVATE
          returnType = irBuiltIns.unitType
          origin = Origins.MetroGraphShard
        }.apply {
          val componentThisReceiver = graphClass.thisReceiver ?: error("Component class has no this receiver")
          val localReceiver = componentThisReceiver.copyTo(this)
          setDispatchReceiver(localReceiver)
          body = DeclarationIrBuilder(pluginContext, symbol, UNDEFINED_OFFSET, UNDEFINED_OFFSET)
            .irBlockBody {
              for (m in chunk) {
                +irCall(m.symbol).apply { dispatchReceiver = irGet(localReceiver) }
              }
            }
        }
      }
    } else emptyList()

    if (options.shardingDebug && needsPartition) {
      writeDiagnostic("sharding-trace.txt") {
        "INIT-PARTITION wrapperCount=${wrapperFunctions.size} totalInitCalls=${shardMethods.size} maxPerWrapper=$maxCallsPerWrapper"
      }
    }

    ctor.body = DeclarationIrBuilder(pluginContext, ctor.symbol, UNDEFINED_OFFSET, UNDEFINED_OFFSET)
      .irBlockBody {
        // Add original constructor body statements first
        if (originalBody != null) {
          originalBody.statements.forEach { +it }
        }

        // Component init methods (if any) run before shard initializers.
        componentCallTargets.forEach { (method, ctorParams) ->
          +irCall(method.symbol).apply {
            dispatchReceiver = irGet(thisReceiver)
            val methodParams = method.parameters.filter { it.kind == IrParameterKind.Regular }
            ctorParams.forEachIndexed { index, ctorParam ->
              val methodParam = methodParams[index]
              arguments[methodParam.indexInParameters] = irGet(ctorParam)
            }
          }
        }

        if (needsPartition) {
          for (wf in wrapperFunctions) {
            +irCall(wf.symbol).apply { dispatchReceiver = irGet(thisReceiver) }
          }
        } else {
          for (initMethod in shardMethods) {
            +irCall(initMethod.symbol).apply { dispatchReceiver = irGet(thisReceiver) }
          }
        }
      }
  }
  /**
   * Implements component interface methods (accessors, injectors, factories).
   * This is copied from IrGraphGenerator.implementOverrides() but uses our
   * expressionGeneratorFactory which is configured with globalBindingFieldContext.
   *
   * The key difference from the non-sharded version: our expressionGeneratorFactory
   * uses globalBindingFieldContext which knows about fields in shards, not just component.
   */
  private fun DependencyGraphNode.implementOverrides() {
    // Collect graph extension factory accessors to skip them here (handled in graphExtensions loop)
    val graphExtensionFactoryAccessors = graphExtensions.values.flatten()
      .filter { it.isFactory }
      .mapToSet { it.accessor }

    // Implement abstract getters for accessors
    accessors.forEach { (function, contextualTypeKey) ->
      // Skip graph extension factories - they're handled in the graphExtensions loop
      if (function in graphExtensionFactoryAccessors) return@forEach

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
            // Factory for graph extension - directly instantiate the FactoryImpl
            // Don't use provider fields since they may be in shards
            val contextKey = IrContextualTypeKey.from(function.ir)
            val binding = bindingGraph.requireBinding(contextKey)
                as IrBinding.GraphExtensionFactory

            body =
              createIrBuilder(symbol).run {
                irExprBodySafe(
                  symbol,
                  expressionGeneratorFactory
                    .create(irFunction.dispatchReceiverParameter!!)
                    .generateBindingCode(
                      binding = binding,
                      contextualTypeKey = contextKey,
                      // Force instance access to avoid provider field lookup
                      accessType = IrGraphExpressionGenerator.AccessType.INSTANCE,
                      // Pass a dummy fieldInitKey to prevent provider field reuse
                      fieldInitKey = binding.typeKey,
                    ),
                )
              }
          } else {
            // Graph extension creator. Use regular binding code gen
            val binding =
              bindingGraph.findBinding(typeKey)
                ?: IrBinding.GraphExtension(
                  typeKey = typeKey,
                  parent = node.metroGraphOrFail,
                  accessor = function.ir,
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
}
