// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.METRO_VERSION
import dev.zacsweers.metro.compiler.MetroLogger
import dev.zacsweers.metro.compiler.NameAllocator
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.decapitalizeUS
import dev.zacsweers.metro.compiler.exitProcessing
import dev.zacsweers.metro.compiler.expectAs
import dev.zacsweers.metro.compiler.graph.WrappedType
import dev.zacsweers.metro.compiler.ir.parameters.Parameter
import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.ir.parameters.wrapInProvider
import dev.zacsweers.metro.compiler.ir.transformers.AssistedFactoryTransformer
import dev.zacsweers.metro.compiler.ir.transformers.BindingContainerTransformer
import dev.zacsweers.metro.compiler.ir.transformers.MembersInjectorTransformer
import dev.zacsweers.metro.compiler.letIf
import dev.zacsweers.metro.compiler.mapToSet
import dev.zacsweers.metro.compiler.proto.MetroMetadata
import dev.zacsweers.metro.compiler.suffixIfNot
import dev.zacsweers.metro.compiler.tracing.Tracer
import dev.zacsweers.metro.compiler.tracing.traceNested
import org.jetbrains.kotlin.backend.common.lower.irThrow
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.addField
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irBoolean
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irConcat
import org.jetbrains.kotlin.ir.builders.irExprBody
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irIfThen
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irNotIs
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irSetField
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.builders.parent
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrOverridableDeclaration
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.addArgument
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.typeOrFail
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.DeepCopyIrTreeWithSymbols
import org.jetbrains.kotlin.ir.util.SymbolRemapper
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.companionObject
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.getAnnotation
import org.jetbrains.kotlin.ir.util.isFromJava
import org.jetbrains.kotlin.ir.util.isObject
import org.jetbrains.kotlin.ir.util.isStatic
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.nestedClasses
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.util.propertyIfAccessor
import org.jetbrains.kotlin.ir.util.remapTypes
import org.jetbrains.kotlin.ir.util.simpleFunctions
import org.jetbrains.kotlin.ir.util.statements
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

internal typealias FieldInitializer =
  IrBuilderWithScope.(thisReceiver: IrValueParameter, key: IrTypeKey) -> IrExpression

// Borrowed from Dagger
// https://github.com/google/dagger/blob/b39cf2d0640e4b24338dd290cb1cb2e923d38cb3/dagger-compiler/main/java/dagger/internal/codegen/writing/ComponentImplementation.java#L263
private const val STATEMENTS_PER_METHOD = 25

// TODO further refactor
//  move IR code gen out to IrGraphExpression?Generator
internal class IrGraphGenerator(
  metroContext: IrMetroContext,
  contributionData: IrContributionData,
  private val dependencyGraphNodesByClass: (ClassId) -> DependencyGraphNode?,
  private val node: DependencyGraphNode,
  private val graphClass: IrClass,
  private val bindingGraph: IrBindingGraph,
  private val sealResult: IrBindingGraph.BindingGraphResult,
  private val parentTracer: Tracer,
  // TODO move these accesses to irAttributes
  private val bindingContainerTransformer: BindingContainerTransformer,
  private val membersInjectorTransformer: MembersInjectorTransformer,
  private val assistedFactoryTransformer: AssistedFactoryTransformer,
) : IrMetroContext by metroContext {

  private val fieldNameAllocator = NameAllocator(mode = NameAllocator.Mode.COUNT)
  private val functionNameAllocator = NameAllocator(mode = NameAllocator.Mode.COUNT)

  // TODO we can end up in awkward situations where we
  //  have the same type keys in both instance and provider fields
  //  this is tricky because depending on the context, it's not valid
  //  to use an instance (for example - you need a provider). How can we
  //  clean this up?
  // Fields for this graph and other instance params
  private val instanceFields = mutableMapOf<IrTypeKey, IrField>()

  // Fields for providers. May include both scoped and unscoped providers as well as bound
  // instances
  private val providerFields = LinkedHashMap<IrTypeKey, IrField>()

  private val contributedGraphGenerator =
    IrContributedGraphGenerator(metroContext, contributionData, node.sourceGraph)

  /**
   * To avoid `MethodTooLargeException`, we split field initializations up over multiple constructor
   * inits.
   *
   * @see <a href="https://github.com/ZacSweers/metro/issues/645">#645</a>
   */
  private val fieldInitializers = mutableListOf<Pair<IrField, FieldInitializer>>()
  private val fieldsToTypeKeys = mutableMapOf<IrField, IrTypeKey>()
  
  /**
   * List of field initializers that depend on shards being created.
   * These must be initialized AFTER shard instances are created.
   */
  private val shardDependentFieldInitializers = mutableListOf<Pair<IrField, FieldInitializer>>()
  
  // Sharding support
  private val shards = mutableListOf<IrGraphShard>()
  private val bindingToShard = mutableMapOf<IrBinding, IrGraphShard>()
  // Cache for quick lookup of which bindings are in which location
  private val bindingLocations = mutableMapOf<IrTypeKey, BindingLocation>()
  
  private sealed class BindingLocation {
    data class InMainGraph(val field: IrField) : BindingLocation()
    data class InShard(val shard: IrGraphShard, val field: IrField) : BindingLocation()
  }

  // Helper functions to encapsulate map modifications
  private fun addInstanceField(typeKey: IrTypeKey, field: IrField) {
    instanceFields[typeKey] = field
  }

  private fun addBindingToShard(binding: IrBinding, shard: IrGraphShard) {
    bindingToShard[binding] = shard
  }

  private fun addBindingLocation(typeKey: IrTypeKey, location: BindingLocation) {
    bindingLocations[typeKey] = location
  }

  private fun addFieldToTypeKey(field: IrField, typeKey: IrTypeKey) {
    fieldsToTypeKeys[field] = typeKey
  }

  fun IrField.withInit(typeKey: IrTypeKey, init: FieldInitializer): IrField = apply {
    addFieldToTypeKey(this, typeKey)
    fieldInitializers += (this to init)
  }

  fun IrField.initFinal(body: IrBuilderWithScope.() -> IrExpression): IrField = apply {
    isFinal = true
    initializer = createIrBuilder(symbol).run { irExprBody(body()) }
  }

  fun generate() =
    with(graphClass) {
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

        // When sharding might be enabled (threshold is set), fields need to be internal so nested shard classes
        // can access them without synthetic accessors (which cause dispatch receiver type mismatches)
        val fieldVisibility = if (options.bindingsPerGraphShard < Int.MAX_VALUE) {
          DescriptorVisibilities.INTERNAL
        } else {
          DescriptorVisibilities.PRIVATE
        }

        val field = addField(
          fieldName =
            fieldNameAllocator.newName(
              name.asString().suffixIfNot("Instance").suffixIfNot("Provider").decapitalizeUS()
            ),
          fieldType = symbols.metroProvider.typeWith(typeKey.type),
          fieldVisibility = fieldVisibility,
        )
          .initFinal {
            instanceFactory(typeKey.type, initializer(thisReceiverParameter, typeKey))
          }
        
        providerFields[typeKey] = field
        // Also add to bindingLocations so shards can find it
        addBindingLocation(typeKey, BindingLocation.InMainGraph(field))
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
                ?: node.extendedGraphNodes[param.typeKey]
                ?: error("Undefined graph node ${param.typeKey}")

            // Don't add it if it's not used
            if (param.typeKey !in sealResult.reachableKeys) continue

            val graphDepField =
              addSimpleInstanceField(
                fieldNameAllocator.newName(
                  graphDep.sourceGraph.name.asString().decapitalizeUS() + "Instance"
                ),
                graphDep.typeKey,
              ) {
                irGet(irParam)
              }
            addInstanceField(graphDep.typeKey, graphDepField)
            // Add to bindingLocations so shards can find it
            addBindingLocation(graphDep.typeKey, BindingLocation.InMainGraph(graphDepField))

            if (graphDep.isExtendable) {
              // Extended graphs
              addBoundInstanceField(param.typeKey, param.name) { _, _ -> irGet(irParam) }

              // Check that the input parameter is an instance of the metrograph class
              // Only do this for $$MetroGraph instances. Not necessary for ContributedGraphs
              if (graphDep.sourceGraph != graphClass) {
                val depMetroGraph = graphDep.sourceGraph.metroGraphOrFail
                constructorStatements.add {
                  irIfThen(
                    condition = irNotIs(irGet(irParam), depMetroGraph.symbol.typeWith()),
                    type = irBuiltIns.unitType,
                    thenPart =
                      irThrow(
                        irInvoke(
                          callee = irBuiltIns.illegalArgumentExceptionSymbol,
                          args =
                            listOf(
                              irConcat().apply {
                                addArgument(
                                  irString(
                                    "Constructor parameter ${irParam.name} _must_ be a Metro-compiler-generated instance of ${graphDep.sourceGraph.kotlinFqName.asString()} but was "
                                  )
                                )
                                addArgument(
                                  irInvoke(
                                    dispatchReceiver = irGet(irParam),
                                    callee = irBuiltIns.memberToString,
                                  )
                                )
                              }
                            ),
                        )
                      ),
                  )
                }
              }
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

        addInstanceField(node.typeKey, thisGraphField)
        // Add to bindingLocations so shards can find it
        addBindingLocation(node.typeKey, BindingLocation.InMainGraph(thisGraphField))

        // Expose the graph as a provider field
        // TODO this isn't always actually needed but different than the instance field above
        //  would be nice if we could determine if this field is unneeded
        // When sharding might be enabled (threshold is set), fields need to be internal so nested shard classes
        // can access them without synthetic accessors (which cause dispatch receiver type mismatches)
        val providerFieldVisibility = if (options.bindingsPerGraphShard < Int.MAX_VALUE) {
          DescriptorVisibilities.INTERNAL
        } else {
          DescriptorVisibilities.PRIVATE
        }
        val graphProviderField = addField(
              fieldName =
                fieldNameAllocator.newName(
                  node.sourceGraph.name.asString().decapitalizeUS().suffixIfNot("Provider")
                ),
              fieldType = symbols.metroProvider.typeWith(node.typeKey.type),
              fieldVisibility = providerFieldVisibility,
            )
            .initFinal {
              instanceFactory(
                node.typeKey.type,
                irGetField(irGet(thisReceiverParameter), thisGraphField),
              )
            }
        providerFields[node.typeKey] = graphProviderField
        addBindingLocation(node.typeKey, BindingLocation.InMainGraph(graphProviderField))
      }

      // Add instance fields for all the parent graphs
      for (parent in node.allExtendedNodes.values) {
        // Skip non-extendable parent graphs - this should be caught during FIR validation
        if (!parent.isExtendable) {
          loggerFor(MetroLogger.Type.GraphImplCodeGen).log(
            "Skipping non-extendable parent graph '${parent.sourceGraph.name}' when generating '${graphClass.name}'"
          )
          continue
        }
        val parentMetroGraph = parent.sourceGraph.metroGraphOrFail
        val instanceAccessors =
          parentMetroGraph.functions
            .filter {
              val metroAccessor =
                it.getAnnotation(Symbols.FqNames.MetroAccessor) ?: return@filter false
              // This has a single "isInstanceAccessor" property
              metroAccessor.getSingleConstBooleanArgumentOrNull() == true
            }
            .mapNotNull {
              val contextKey = IrContextualTypeKey.from(it)

              if (
                contextKey.typeKey == node.originalTypeKey ||
                  contextKey.typeKey == node.creator?.typeKey
              ) {
                // Accessor of this graph extension or its factory, no need to include these
                return@mapNotNull null
              }

              val metroFunction = metroFunctionOf(it)
              metroFunction to contextKey
            }

        for ((accessor, contextualTypeKey) in instanceAccessors) {
          // If this isn't extendable and this type key isn't used, ignore it
          if (!node.isExtendable && contextualTypeKey.typeKey !in sealResult.reachableKeys) {
            continue
          }

          val typeKey = contextualTypeKey.typeKey
          val field = instanceFields.getOrPut(typeKey) {
            addField(
                fieldName =
                  fieldNameAllocator.newName(
                    typeKey.type.rawType().name.asString().decapitalizeUS() + "Instance"
                  ),
                fieldType = typeKey.type,
                fieldVisibility = DescriptorVisibilities.PRIVATE,
              )
              .withInit(typeKey) { thisReceiver, _ ->
                val receiverTypeKey =
                  accessor.ir.dispatchReceiverParameter!!
                    .type
                    .let {
                      val rawType = it.rawTypeOrNull()
                      // This stringy check is unfortunate but origins are not visible
                      // across compilation boundaries
                      if (rawType?.name == Symbols.Names.MetroGraph) {
                        // if it's a $$MetroGraph, we actually want the parent type
                        rawType.parentAsClass.symbol.typeWith()
                      } else {
                        it
                      }
                    }
                    .let(IrTypeKey.Companion::invoke)
                irInvoke(
                  dispatchReceiver =
                    irGetField(
                      irGet(thisReceiver),
                      instanceFields[receiverTypeKey]
                        ?: error(
                          "Receiver type key $receiverTypeKey not found for binding $accessor"
                        ),
                    ),
                  callee = accessor.ir.symbol,
                  typeHint = accessor.ir.returnType,
                )
              }
          }
          // Add to bindingLocations so shards can find it
          addBindingLocation(typeKey, BindingLocation.InMainGraph(field))
        }
      }

      // Collect bindings and their dependencies for provider field ordering
      val fieldCollector = ProviderFieldCollector(bindingGraph)
      val initOrder =
        parentTracer.traceNested("Collect bindings") {
          val providerFieldBindings = fieldCollector.collect()
          buildList(providerFieldBindings.size) {
            for (key in sealResult.sortedKeys) {
              if (key in sealResult.reachableKeys) {
                providerFieldBindings[key]?.let(::add)
              }
            }
          }
        }

      val baseGenerationContext = GraphGenerationContext(thisReceiverParameter)

      // Handle GraphDependency provider field accessors
      val providerFieldAccessors = fieldCollector.collectProviderFieldAccessors()
      for ((key, binding) in providerFieldAccessors) {
        if (key in sealResult.reachableKeys) {
          val getter = binding.getter
          // Init a provider field pointing at this
          val accessorField = addField(
                fieldName =
                  fieldNameAllocator.newName(
                    "${getter.name.asString().decapitalizeUS().removeSuffix(Symbols.StringNames.METRO_ACCESSOR_SUFFIX)}Provider"
                  ),
                fieldType = symbols.metroProvider.typeWith(binding.typeKey.type),
                fieldVisibility = DescriptorVisibilities.PRIVATE,
              )
              .withInit(key) { thisReceiver, _ ->
                // If this is in instance fields, just do a quick assignment
                if (binding.typeKey in instanceFields) {
                  val field = instanceFields.getValue(binding.typeKey)
                  instanceFactory(binding.typeKey.type, irGetField(irGet(thisReceiver), field))
                } else {
                  generateBindingCode(
                    binding = binding,
                    generationContext = baseGenerationContext.withReceiver(thisReceiver),
                    fieldInitKey = key,
                  )
                }
              }
          providerFields[key] = accessorField
          addBindingLocation(key, BindingLocation.InMainGraph(accessorField))
        }
      }

      // For all deferred types, assign them first as factories
      // Deferred types are those that participate in dependency cycles broken by
      // deferrable edges (Provider/Lazy). They need DelegateFactory instances that
      // are initialized later to break the cycle.
      @Suppress("UNCHECKED_CAST")
      val deferredFields: Map<IrTypeKey, IrField> =
        sealResult.deferredTypes.associateWith { deferredTypeKey ->
          val binding = bindingGraph.requireBinding(deferredTypeKey, IrBindingStack.empty())
          val field =
            addField(
                fieldNameAllocator.newName(binding.nameHint.decapitalizeUS() + "Provider"),
                deferredTypeKey.type.wrapInProvider(symbols.metroProvider),
              )
              .withInit(binding.typeKey) { _, _ ->
                irInvoke(
                  callee = symbols.metroDelegateFactoryConstructor,
                  typeArgs = listOf(deferredTypeKey.type),
                )
              }

          providerFields[deferredTypeKey] = field
          addBindingLocation(deferredTypeKey, BindingLocation.InMainGraph(field))
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
            it.typeKey in instanceFields ||
            it.typeKey in providerFields ||
            // We don't generate fields for these even though we do track them in dependencies
            // above, it's just for propagating their aliased type in sorting
            it is IrBinding.Alias
        }
        .toList()
        .also { fieldBindings ->
          writeDiagnostic("keys-providerFields-${parentTracer.tag}.txt") {
            fieldBindings.joinToString("\n") { it.typeKey.toString() }
          }
          writeDiagnostic("keys-scopedProviderFields-${parentTracer.tag}.txt") {
            fieldBindings.filter { it.scope != null }.joinToString("\n") { it.typeKey.toString() }
          }
          
          // Check if sharding is needed
          if (fieldBindings.size > options.bindingsPerGraphShard) {
            val shardingLogger = loggerFor(MetroLogger.Type.ComponentSharding)
            shardingLogger.log("Graph ${graphClass.kotlinFqName} requires component sharding")
            shardingLogger.log("  Total bindings: ${fieldBindings.size}")
            shardingLogger.log("  Sharding threshold: ${options.bindingsPerGraphShard}")
            shardingLogger.log("  Bindings over threshold: ${fieldBindings.size - options.bindingsPerGraphShard}")
            
            log("Graph ${graphClass.kotlinFqName} has ${fieldBindings.size} bindings, " +
                "which exceeds the sharding threshold of ${options.bindingsPerGraphShard}. " +
                "Implementing component sharding.")

            // Implement dependency-aware sharding
            val shardingStrategy = GraphShardingStrategy(options.bindingsPerGraphShard)
            
            // Convert fieldBindings List to Map for the new API
            val bindingsMap = fieldBindings.associateBy { it.typeKey }
            // The topological sort has already been done in the binding graph seal process
            // We pass null to let the sharding strategy recompute dependencies as needed
            val shardingResult = shardingStrategy.distributeBindings(bindingsMap, bindingGraph = null)
            
            shardingLogger.log("Created ${shardingResult.shards.size} shards for distribution")
            shardingLogger.log("Identified ${shardingResult.parallelGroups.size} parallel generation groups")
            
            shardingResult.parallelGroups.forEachIndexed { groupIndex, shardIndices ->
              shardingLogger.log("  Parallel group ${groupIndex + 1}: shards ${shardIndices.sorted().joinToString(", ")}")
            }
            
            shardingResult.shards.forEach { shardInfo ->
              shardingLogger.log("  Shard ${shardInfo.index}: '${shardInfo.name}' with ${shardInfo.bindings.size} bindings")
              if (shardInfo.dependencies.isNotEmpty()) {
                shardingLogger.log("    Dependencies: ${shardInfo.dependencies.sorted().joinToString(", ")}")
              }
            }
            
            // Create shard classes - batch creation for better performance
            val startShardGen = System.currentTimeMillis()
            
            // Generate shards - could potentially be parallelized in the future
            // For now, generate them sequentially but with optimized creation
            shardingResult.shards.forEach { shardInfo ->
              val shardStartTime = System.currentTimeMillis()
              val shard = IrGraphShard(
                metroContext = metroContext,
                parentGraph = graphClass,
                shardName = shardInfo.name,
                shardIndex = shardInfo.index,
                bindings = shardInfo.bindings,
                bindingGraph = bindingGraph,
                bindingGenerator = { binding, thisReceiver, shardProviderFields ->
                  createIrBuilder(symbol).run {
                    // Use shard initialization context with O(1) field lookup
                    // This prevents recursive dependency generation which can cause exponential complexity.
                    // Pre-cache the shard class and parent graph field for performance
                    val currentShardClass = when (val parent = thisReceiver.parent) {
                      is IrFunction -> parent.parent as? IrClass
                      is IrClass -> parent
                      else -> null
                    }
                    val parentGraphField = currentShardClass?.declarations
                      ?.filterIsInstance<IrField>()
                      ?.find { it.name.asString() == "parentGraph" }
                    
                    val shardContext = GraphGenerationContext(
                      thisReceiver = thisReceiver,
                      isShardInitialization = true,
                      shardProviderFields = shardProviderFields,
                      parentGraphField = parentGraphField,
                      currentShardClass = currentShardClass
                    )
                    generateBindingCode(
                      binding = binding,
                      generationContext = shardContext,
                      fieldInitKey = binding.typeKey,
                    )
                  }
                }
              )
              shard.generate()
              shards.add(shard)
              
              // Track which bindings are in which shard
              shardInfo.bindings.forEach { binding ->
                addBindingToShard(binding, shard)
                // Also populate the location cache for O(1) lookups
                shard.getFieldForKey(binding.typeKey)?.let { field ->
                  addBindingLocation(binding.typeKey, BindingLocation.InShard(shard, field))
                }
              }
              
              val shardGenTime = System.currentTimeMillis() - shardStartTime
              if (shardGenTime > 100) {
                shardingLogger.log("  Shard ${shardInfo.index} generated in ${shardGenTime}ms (${shardInfo.bindings.size} bindings)")
              }
            }
            
            val shardGenTime = System.currentTimeMillis() - startShardGen
            shardingLogger.log("Generated ${shards.size} shard classes in ${shardGenTime}ms")
            
            // Generate debug reports for all shards after creation (non-critical path)
            if (debug) {
              shardingLogger.log("Generating debug reports for shards...")
              val debugStart = System.currentTimeMillis()
              shards.forEach { shard ->
                shard.generateDebugReports()
              }
              val debugTime = System.currentTimeMillis() - debugStart
              shardingLogger.log("Generated debug reports in ${debugTime}ms")
            }
            
            // DON'T initialize shards here - they need to be initialized AFTER parent fields
            // We'll add shard initialization at the end of constructorStatements after all fields are set
          }
        }
        .forEach { binding ->
          val key = binding.typeKey
          
          // Check if this binding is in a shard
          val shard = bindingToShard[binding]
          if (shard != null) {
            // This binding is in a shard - create a delegating field in the main graph
            val fieldType = when (binding) {
              is IrBinding.ConstructorInjected if binding.isAssisted -> {
                binding.classFactory.factoryClass.typeWith()
              }
              else -> {
                symbols.metroProvider.typeWith(key.type)
              }
            }
            
            // When sharding might be enabled (threshold is set), fields need to be internal so nested shard classes
            // can access them without synthetic accessors (which cause dispatch receiver type mismatches)
            val fieldVisibility = if (options.bindingsPerGraphShard < Int.MAX_VALUE) {
              DescriptorVisibilities.INTERNAL
            } else {
              DescriptorVisibilities.PRIVATE
            }
            
            val field = addField(
              fieldName = fieldNameAllocator.newName(
                binding.nameHint.decapitalizeUS().suffixIfNot(
                  if (binding is IrBinding.ConstructorInjected && binding.isAssisted) "Factory" else "Provider"
                )
              ),
              fieldType = fieldType,
              fieldVisibility = fieldVisibility,
            )
            
            // Don't initialize the field here - defer ALL initialization to constructor
            providerFields[key] = field
            addFieldToTypeKey(field, key)
            // Populate location cache for main graph bindings
            addBindingLocation(key, BindingLocation.InMainGraph(field))
            
            // Store the field and shard mapping for later initialization
            // These MUST be initialized AFTER shard instances are created
            val shardBinding = binding to shard
            shardDependentFieldInitializers.add(field to { thisReceiver, _ ->
              shardBinding.second.generateAccessorExpression(shardBinding.first, thisReceiver)
            })
          } else {
            // Normal binding processing (not sharded)
            var isProviderType = true
            val suffix: String
            val fieldType =
              when (binding) {
              is IrBinding.ConstructorInjected if binding.isAssisted -> {
                isProviderType = false
                suffix = "Factory"
                // For now, we don't support generic factories - use raw type
                // Supporting generics would require type parameter mapping from the injection site
                binding.classFactory.factoryClass.typeWith()
              }
              else -> {
                suffix = "Provider"
                symbols.metroProvider.typeWith(key.type)
              }
            }

            val field =
              addField(
                  fieldName =
                    fieldNameAllocator.newName(binding.nameHint.decapitalizeUS().suffixIfNot(suffix)),
                  fieldType = fieldType,
                  fieldVisibility = DescriptorVisibilities.PRIVATE,
                )
                .withInit(key) { thisReceiver, typeKey ->
                  generateBindingCode(
                      binding,
                    baseGenerationContext.withReceiver(thisReceiver),
                    fieldInitKey = typeKey,
                  )
                  .letIf(binding.scope != null && isProviderType) {
                    // If it's scoped, wrap it in double-check
                    // DoubleCheck.provider(<provider>)
                    it.doubleCheck(this@withInit, symbols, binding.typeKey)
                  }
                }
            providerFields[key] = field
            addBindingLocation(key, BindingLocation.InMainGraph(field))
          }
        }

      // After processing all regular bindings, also track alias bindings in bindingLocations
      // Aliases point to the same field as their underlying binding
      for (typeKey in sealResult.reachableKeys) {
        val binding = bindingGraph.requireBinding(typeKey, IrBindingStack.empty())
        if (binding is IrBinding.Alias) {
          // Resolve the alias to find the actual binding
          var currentBinding: IrBinding = binding
          val seen = mutableSetOf<IrBinding>()
          while (currentBinding is IrBinding.Alias) {
            if (!seen.add(currentBinding)) {
              error("Circular alias detected for binding: ${binding.typeKey}")
            }
            currentBinding = currentBinding.aliasedBinding(bindingGraph, IrBindingStack.empty())
          }
          // Find the location of the actual binding and map the alias to the same location
          val actualLocation = bindingLocations[currentBinding.typeKey]
          if (actualLocation != null) {
            addBindingLocation(binding.typeKey, actualLocation)
          }
        }
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
                  generateBindingCode(
                      binding,
                      baseGenerationContext.withReceiver(thisReceiver),
                      fieldInitKey = deferredTypeKey,
                    )
                    .letIf(binding.scope != null) {
                      // If it's scoped, wrap it in double-check
                      // DoubleCheck.provider(<provider>)
                      it.doubleCheck(this@run, symbols, binding.typeKey)
                    }
                },
              ),
          )
        }
      }

      // TWO-PHASE INITIALIZATION:
      // Phase 1: Create shard instances (constructor only stores parent reference)
      val shardCreateStatements = mutableListOf<IrBuilderWithScope.(thisReceiver: IrValueParameter) -> IrStatement>()
      // Phase 2: Initialize shards (populate all provider fields)
      val shardInitStatements = mutableListOf<IrBuilderWithScope.(thisReceiver: IrValueParameter) -> IrStatement>()
      
      if (shards.isNotEmpty()) {
        shards.forEach { shard ->
          // Phase 1: Create the shard instance
          shardCreateStatements.add { thisReceiver ->
            irSetField(
              receiver = irGet(thisReceiver),
              field = shard.shardField,
              value = irInvoke(
                callee = shard.shardClass.primaryConstructor!!.symbol,
                args = listOf(irGet(thisReceiver)) // Pass the parent graph instance to the shard
              )
            )
          }
          
          // Shard is initialized in its constructor (single-phase init)
          // No need for separate initialization call
        }
      }

      if (
        options.chunkFieldInits &&
          fieldInitializers.size + shardDependentFieldInitializers.size + initStatements.size > STATEMENTS_PER_METHOD
      ) {
        // Larger graph, split statements
        
        // First, chunk the parent field initializers ONLY (not shard-dependent stuff)
        val chunks =
          buildList<IrBuilderWithScope.(thisReceiver: IrValueParameter) -> IrStatement> {
              // Add regular field initializers first
              for ((field, init) in fieldInitializers) {
                add { thisReceiver ->
                  irSetField(
                    irGet(thisReceiver),
                    field,
                    init(thisReceiver, fieldsToTypeKeys.getValue(field)),
                  )
                }
              }
              for (statement in initStatements) {
                add { thisReceiver -> statement(thisReceiver) }
              }
              // DO NOT add shardDependentFieldInitializers here - they need shards to exist first
            }
            .chunked(STATEMENTS_PER_METHOD)

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
        
        // STEP 1: Initialize ALL parent graph fields first
        constructorStatements += buildList {
          for (initFunction in initFunctionsToCall) {
            add { dispatchReceiver ->
              irInvoke(dispatchReceiver = irGet(dispatchReceiver), callee = initFunction.symbol)
            }
          }
        }
        
        // STEP 2: Create ALL shard instances (Phase 1 of two-phase init)
        // Constructors only store parent references, no field initialization
        constructorStatements += shardCreateStatements
        
        // STEP 3: Initialize ALL shards (Phase 2 of two-phase init)
        // Now that all shards exist, they can safely reference each other
        constructorStatements += shardInitStatements
        
        // STEP 4: Initialize shard-dependent fields in parent (delegates to shards)
        // Now safe to access shard fields since all shards are fully initialized
        for ((field, init) in shardDependentFieldInitializers) {
          constructorStatements.add { thisReceiver ->
            irSetField(
              irGet(thisReceiver),
              field,
              init(thisReceiver, fieldsToTypeKeys.getValue(field)),
            )
          }
        }
      } else {
        // Small graph, just do it in the constructor
        
        // STEP 1: Initialize parent graph regular fields first
        // Assign those initializers directly to their fields and mark them as final
        for ((field, init) in fieldInitializers) {
          field.initFinal {
            val typeKey = fieldsToTypeKeys.getValue(field)
            init(thisReceiverParameter, typeKey)
          }
        }
        constructorStatements += initStatements
        
        // STEP 2: Create ALL shard instances (Phase 1 of two-phase init)
        constructorStatements += shardCreateStatements
        
        // STEP 3: Initialize ALL shards (Phase 2 of two-phase init) 
        constructorStatements += shardInitStatements
        
        // STEP 4: Initialize shard-dependent fields in parent (delegates to shards)
        // Now safe to access shard fields since all shards are fully initialized
        for ((field, init) in shardDependentFieldInitializers) {
          field.initFinal {
            val typeKey = fieldsToTypeKeys.getValue(field)
            init(thisReceiverParameter, typeKey)
          }
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
          // Shards are now initialized as part of constructorStatements above
        }
      }

      parentTracer.traceNested("Implement overrides") { tracer ->
        node.implementOverrides(baseGenerationContext, tracer)
      }

      if (node.isExtendable) {
        parentTracer.traceNested("Generate Metro metadata") {
          // Finally, generate metadata
          val graphProto =
            node.toProto(
              bindingGraph = bindingGraph,
              includedGraphClasses =
                node.allIncludedNodes
                  .filter { it.isExtendable }
                  .mapToSet { it.sourceGraph.classIdOrFail.asString() },
              parentGraphClasses =
                node.allExtendedNodes.values.mapToSet { it.sourceGraph.classIdOrFail.asString() },
            )
          val metroMetadata = MetroMetadata(METRO_VERSION, dependency_graph = graphProto)

          writeDiagnostic({
            "graph-metadata-${node.sourceGraph.kotlinFqName.asString().replace(".", "-")}.kt"
          }) {
            metroMetadata.toString()
          }

          // IR-generated types do not have metadata
          if (graphClass.origin !== Origins.ContributedGraph) {
            // Write the metadata to the metroGraph class, as that's what downstream readers are
            // looking at and is the most complete view
            graphClass.metroMetadata = metroMetadata
          }
          dependencyGraphNodesByClass(node.sourceGraph.classIdOrFail)?.let { it.proto = graphProto }
        }

        // Expose getters for provider and instance fields and expose them to metadata
        val providerFieldsSet = providerFields.values.toSet()
        sequence {
            for (entry in providerFields) {
              val binding = bindingGraph.requireBinding(entry.key, IrBindingStack.empty())
              if (
                binding is IrBinding.GraphDependency &&
                  binding.isProviderFieldAccessor &&
                  binding.ownerKey !in node.includedGraphNodes
              ) {
                // This'll get looked up directly by child graphs. Included graphs though _should_
                // be propagated because they are accessors-only APIs
                continue
              } else if (
                binding.scope == null &&
                  binding !is IrBinding.BoundInstance &&
                  binding !is IrBinding.GraphDependency
              ) {
                // Don't expose redundant accessors for unscoped bindings. BoundInstance bindings
                // still get passed on. GraphDependency bindings (if it reached here) should also
                // pass on
                continue
              }
              yield(entry)
            }
            yieldAll(instanceFields.entries)
          }
          .distinctBy {
            // Only generate once per field. Can happen for cases
            // where we add convenience keys for graph instance supertypes.
            it.value
          }
          .forEach { (key, field) ->
            val getter =
              addFunction(
                  name = "${field.name.asString()}${Symbols.StringNames.METRO_ACCESSOR_SUFFIX}",
                  returnType = field.type,
                  visibility = DescriptorVisibilities.PUBLIC,
                  origin = Origins.InstanceFieldAccessor,
                )
                .apply {
                  key.qualifier?.let {
                    annotations +=
                      it.ir.transform(DeepCopyIrTreeWithSymbols(SymbolRemapper.EMPTY), null)
                        as IrConstructorCall
                  }
                  // Add Deprecated(HIDDEN) annotation to hide
                  annotations += hiddenDeprecated()
                  // Annotate with @MetroAccessor
                  annotations +=
                    buildAnnotation(symbol, symbols.metroAccessorAnnotationConstructor) { call ->
                      if (key in instanceFields && field !in providerFieldsSet) {
                        // Set isInstanceAccessor
                        call.arguments[0] = irBoolean(true)
                      }
                    }
                  body =
                    createIrBuilder(symbol).run {
                      val expression =
                        if (key in instanceFields) {
                          irGetField(irGet(dispatchReceiverParameter!!), field)
                        } else {
                          val binding = bindingGraph.requireBinding(key, IrBindingStack.empty())
                          generateBindingCode(
                            binding,
                            baseGenerationContext.withReceiver(dispatchReceiverParameter!!),
                          )
                        }
                      irExprBodySafe(symbol, expression)
                    }
                }
            metadataDeclarationRegistrar.registerFunctionAsMetadataVisible(getter)
          }
      }
    }

  // TODO add asProvider support?
  private fun IrClass.addSimpleInstanceField(
    name: String,
    typeKey: IrTypeKey,
    initializerExpression: IrBuilderWithScope.() -> IrExpression,
  ): IrField {
    // When sharding might be enabled (threshold is set), fields need to be internal so nested shard classes
    // can access them without synthetic accessors (which cause dispatch receiver type mismatches)
    val fieldVisibility = if (options.bindingsPerGraphShard < Int.MAX_VALUE) {
      DescriptorVisibilities.INTERNAL
    } else {
      DescriptorVisibilities.PRIVATE
    }
    return addField(
        fieldName = name,
        fieldType = typeKey.type,
        fieldVisibility = fieldVisibility,
      )
      .initFinal { initializerExpression() }
  }

  private fun DependencyGraphNode.implementOverrides(
    context: GraphGenerationContext,
    parentTracer: Tracer,
  ) {
    // Implement abstract getters for accessors
    accessors.forEach { (function, contextualTypeKey) ->
      function.ir.apply {
        val declarationToFinalize =
          function.ir.propertyIfAccessor.expectAs<IrOverridableDeclaration<*>>()
        if (declarationToFinalize.isFakeOverride) {
          declarationToFinalize.finalizeFakeOverride(context.thisReceiver)
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
                generateBindingCode(
                  binding,
                  context.withReceiver(irFunction.dispatchReceiverParameter!!),
                  contextualTypeKey,
                ),
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
        finalizeFakeOverride(context.thisReceiver)
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

            val targetClassSymbol = pluginContext.referenceClass(binding.targetClassId)
            if (targetClassSymbol == null) {
              error("Could not find class ${binding.targetClassId} for MembersInjected binding parameters")
            }
            for (type in
              targetClassSymbol
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
                            generateBindingCode(
                              paramBinding,
                              context.withReceiver(
                                overriddenFunction.ir.dispatchReceiverParameter!!
                              ),
                              parameter.contextualTypeKey,
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
          declarationToFinalize.finalizeFakeOverride(context.thisReceiver)
        }
        body = stubExpressionBody()
      }
    }

    // Implement bodies for contributed graphs
    // Sort by keys when generating so they have deterministic ordering
    contributedGraphs.entries
      .sortedBy { it.key }
      .forEach { (typeKey, function) ->
        function.ir.apply {
          val declarationToFinalize =
            function.ir.propertyIfAccessor.expectAs<IrOverridableDeclaration<*>>()
          if (declarationToFinalize.isFakeOverride) {
            declarationToFinalize.finalizeFakeOverride(context.thisReceiver)
          }
          val irFunction = this
          val contributedGraph =
            getOrBuildContributedGraph(typeKey, sourceGraph, function, parentTracer)
          val ctor = contributedGraph.primaryConstructor!!
          body =
            createIrBuilder(symbol).run {
              irExprBodySafe(
                symbol,
                irCallConstructor(ctor.symbol, emptyList()).apply {
                  // First arg is always the graph instance
                  arguments[0] = irGet(irFunction.dispatchReceiverParameter!!)
                  for (i in 0 until regularParameters.size) {
                    arguments[i + 1] = irGet(irFunction.regularParameters[i])
                  }
                },
              )
            }
        }
      }
  }

  private fun getOrBuildContributedGraph(
    typeKey: IrTypeKey,
    parentGraph: IrClass,
    contributedAccessor: MetroSimpleFunction,
    parentTracer: Tracer,
  ): IrClass {
    val classId = typeKey.type.rawType().classIdOrFail
    return parentGraph.nestedClasses.firstOrNull { it.classId == classId }
      ?: run {
        // Find the function declaration in the original @ContributesGraphExtension.Factory
        val sourceFunction =
          contributedAccessor.ir
            .overriddenSymbolsSequence()
            .filter {
              it.owner.parentClassOrNull?.isAnnotatedWithAny(
                metroContext.symbols.classIds.contributesGraphExtensionFactoryAnnotations
              ) == true
            }
            .lastOrNull()
            ?.owner ?: contributedAccessor.ir

        val sourceFactory = sourceFunction.parentAsClass
        val sourceGraph = sourceFactory.parentAsClass
        parentTracer.traceNested("Generate contributed graph ${sourceGraph.name}") {
          contributedGraphGenerator.generateContributedGraph(
            sourceGraph = sourceGraph,
            sourceFactory = sourceFactory,
            factoryFunction = sourceFunction,
          )
        }
      }
  }

  private fun IrBuilderWithScope.generateBindingArguments(
    targetParams: Parameters,
    function: IrFunction,
    binding: IrBinding,
    generationContext: GraphGenerationContext,
  ): List<IrExpression?> {
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
      
      // Resolve alias to actual binding if needed
      val actualTypeKey = run {
        val binding = try {
          bindingGraph.requireBinding(contextualTypeKey, IrBindingStack.empty())
        } catch (e: Exception) {
          null
        }
        if (binding is IrBinding.Alias) {
          // Resolve the alias to find the actual binding's type key
          var currentBinding: IrBinding = binding
          val seen = mutableSetOf<IrBinding>()
          while (currentBinding is IrBinding.Alias) {
            if (!seen.add(currentBinding)) {
              error("Circular alias detected for binding: ${binding.typeKey}")
            }
            currentBinding = currentBinding.aliasedBinding(bindingGraph, IrBindingStack.empty())
          }
          currentBinding.typeKey
        } else {
          typeKey
        }
      }
      
      // Debug logging for dependency resolution in shards
      if (generationContext.isShardInitialization) {
        val logger = loggerFor(MetroLogger.Type.ComponentSharding)
        logger.log("=== Resolving dependency in shard: $typeKey ===")
        logger.log("  - Parameter name: ${param.name}")
        logger.log("  - ContextualTypeKey: $contextualTypeKey")
        logger.log("  - Original TypeKey: $typeKey")
        logger.log("  - Actual TypeKey (after alias resolution): $actualTypeKey")
        logger.log("  - In instanceFields: ${actualTypeKey in instanceFields}")
        logger.log("  - In providerFields: ${actualTypeKey in providerFields}")
        logger.log("  - In bindingLocations: ${bindingLocations[actualTypeKey]}")
        logger.log("  - In shardProviderFields: ${generationContext.shardProviderFields?.containsKey(actualTypeKey) ?: false}")
      }

      val metroProviderSymbols = symbols.providerSymbolsFor(contextualTypeKey)

      // TODO consolidate this logic with generateBindingCode
      if (!contextualTypeKey.requiresProviderInstance) {
        // IFF the parameter can take a direct instance, try our instance fields
        instanceFields[actualTypeKey]?.let { instanceField ->
          val receiver = if (generationContext.isShardInitialization) {
            // In shard context, access instance fields through parent graph
            val parentGraphField = generationContext.parentGraphField
              ?: error("Parent graph field not found in shard context")
            irGetField(irGet(generationContext.thisReceiver), parentGraphField)
          } else {
            irGet(generationContext.thisReceiver)
          }
          return@mapIndexed irGetField(receiver, instanceField).let {
            with(metroProviderSymbols) { transformMetroProvider(it, contextualTypeKey) }
          }
        }
      }

      val providerInstance =
        // First check bindingLocations - this handles both regular fields and aliases
        if (actualTypeKey in bindingLocations) {
          val location = bindingLocations[actualTypeKey]!!
          if (generationContext.isShardInitialization) {
            val parentGraphField = generationContext.parentGraphField
              ?: error("Parent graph field not found in shard context for accessing $typeKey")
            when (location) {
              is BindingLocation.InMainGraph -> {
                // Access from main graph
                val parentAccess = irGetField(irGet(generationContext.thisReceiver), parentGraphField)
                irGetField(parentAccess, location.field)
              }
              is BindingLocation.InShard -> {
                // Check if it's in the current shard
                val currentShardField = generationContext.shardProviderFields?.get(actualTypeKey)
                if (currentShardField != null && currentShardField == location.field) {
                  // It's in the current shard
                  irGetField(irGet(generationContext.thisReceiver), location.field)
                } else {
                  // It's in a different shard, access through parent
                  val parentAccess = irGetField(irGet(generationContext.thisReceiver), parentGraphField)
                  val shardAccess = irGetField(parentAccess, location.shard.shardField)
                  irGetField(shardAccess, location.field)
                }
              }
            }
          } else {
            // Normal context - use the field from location
            when (location) {
              is BindingLocation.InMainGraph -> {
                irGetField(irGet(generationContext.thisReceiver), location.field)
              }
              is BindingLocation.InShard -> {
                // Should not happen in normal context
                error("Shard binding accessed in non-shard context: $actualTypeKey")
              }
            }
          }
        } else if (actualTypeKey in providerFields) {
          // Fallback for fields not in bindingLocations (shouldn't happen if everything is tracked)
          if (generationContext.isShardInitialization) {
            // Check if field is in current shard
            val shardField = generationContext.shardProviderFields?.get(actualTypeKey)
            if (shardField != null) {
              // Field is in current shard
              irGetField(irGet(generationContext.thisReceiver), shardField)
            } else {
              // Field must be in parent graph - access through parent graph reference
              val parentGraphField = generationContext.parentGraphField
                ?: error("Parent graph field not found in shard context for accessing $actualTypeKey")
              val parentAccess = irGetField(irGet(generationContext.thisReceiver), parentGraphField)
              irGetField(parentAccess, providerFields.getValue(actualTypeKey))
            }
          } else {
            // Normal context - direct field access
            irGetField(irGet(generationContext.thisReceiver), providerFields.getValue(actualTypeKey))
          }
        } else {
          // Not in provider fields or bindingLocations - need to generate the binding
          val paramBinding = bindingGraph.requireBinding(contextualTypeKey, IrBindingStack.empty())
          
          if (paramBinding is IrBinding.Absent) {
            val logger = loggerFor(MetroLogger.Type.ComponentSharding)
            logger.log("WARNING: Absent binding found for $contextualTypeKey")
            
            error(
              "Cannot resolve binding for parameter '${param.name}' of type $contextualTypeKey." +
              " The binding appears to be absent - it may not be provided, contributed, or bound in the dependency graph." +
              if (generationContext.isShardInitialization) {
                " This occurred while generating shard '${generationContext.currentShardClass?.name ?: "unknown"}'."
              } else {
                ""
              } +
              " If this type is provided via @ContributesBinding, ensure the contributing module is included in the graph."
            )
          }
          
          generateBindingCode(
            paramBinding,
            generationContext,
            contextualTypeKey = param.contextualTypeKey,
          )
        }

      // Check if providerInstance is null before passing to typeAsProviderArgument
      if (providerInstance == null) {
        val logger = loggerFor(MetroLogger.Type.ComponentSharding)
        logger.log("ERROR: providerInstance is null for $typeKey (actual: $actualTypeKey)!")
        
        // Try to provide a helpful error message
        val bindingInfo = try {
          val binding = bindingGraph.requireBinding(contextualTypeKey, IrBindingStack.empty())
          when (binding) {
            is IrBinding.Absent -> " The binding is absent - it may be missing or not contributed to the graph."
            else -> " Binding type: ${binding::class.simpleName}"
          }
        } catch (e: Exception) {
          " Could not determine binding status: ${e.message}"
        }
        
        error(
          "Failed to resolve dependency for parameter '${param.name}' of type $typeKey" +
          " (resolved as: $actualTypeKey) when generating factory arguments." +
          bindingInfo +
          if (generationContext.isShardInitialization) {
            " This occurred in shard '${generationContext.currentShardClass?.name ?: "unknown"}' initialization."
          } else {
            ""
          }
        )
      }
      
      val result = typeAsProviderArgument(
        param.contextualTypeKey,
        providerInstance,
        isAssisted = param.isAssisted,
        isGraphInstance = param.isGraphInstance,
      )
      
      if (generationContext.isShardInitialization) {
        val logger = loggerFor(MetroLogger.Type.ComponentSharding)
        logger.log("  - Final result type: ${result.type}")
        logger.log("  - Result class: ${result::class.simpleName}")
        logger.log("=== End resolving dependency: $typeKey (actual: $actualTypeKey) ===")
      }
      
      result
    }
  }

  private fun generateMapKeyLiteral(binding: IrBinding): IrExpression {
    val mapKey =
      when (binding) {
        is IrBinding.Alias -> binding.annotations.mapKeys.first().ir
        is IrBinding.Provided -> binding.annotations.mapKeys.first().ir
        is IrBinding.ConstructorInjected -> binding.annotations.mapKeys.first().ir
        else -> error("Unsupported multibinding source: $binding")
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

  private fun IrBuilderWithScope.generateBindingCode(
    binding: IrBinding,
    generationContext: GraphGenerationContext,
    contextualTypeKey: IrContextualTypeKey = binding.contextualTypeKey,
    fieldInitKey: IrTypeKey? = null,
  ): IrExpression {
    if (binding is IrBinding.Absent) {
      error(
        "Absent bindings need to be checked prior to generateBindingCode(). ${binding.typeKey} missing."
      )
    }

    val metroProviderSymbols = symbols.providerSymbolsFor(contextualTypeKey)

    // If we're initializing the field for this key, don't ever try to reach for an existing
    // provider for it.
    // This is important for cases like DelegateFactory and breaking cycles.
    if (fieldInitKey == null || fieldInitKey != binding.typeKey) {
      // When in shard initialization context, we need special handling
      if (generationContext.isShardInitialization) {
        // First check if it's in the current shard (not for BoundInstance - they're always in main graph)
        if (binding !is IrBinding.BoundInstance) {
          val shardField = generationContext.shardProviderFields?.get(binding.typeKey)
          if (shardField != null) {
            return irGetField(irGet(generationContext.thisReceiver), shardField).let {
              with(metroProviderSymbols) { transformMetroProvider(it, contextualTypeKey) }
            }
          }
        }
        
        // Use the location cache for O(1) lookup of where the binding is located
        // This works for all bindings including BoundInstance
        val location = bindingLocations[binding.typeKey]
        if (location != null) {
          val parentGraphField = generationContext.parentGraphField
            ?: error("Parent graph field not found in context")
          val parentAccess = irGetField(irGet(generationContext.thisReceiver), parentGraphField)
          
          return when (location) {
            is BindingLocation.InMainGraph -> {
              // Access from main graph: this.parentGraph.field
              irGetField(parentAccess, location.field).let {
                with(metroProviderSymbols) { transformMetroProvider(it, contextualTypeKey) }
              }
            }
            is BindingLocation.InShard -> {
              // Access from another shard: this.parentGraph.shardX.bindingField
              val shardFieldAccess = irGetField(parentAccess, location.shard.shardField)
              irGetField(shardFieldAccess, location.field).let {
                with(metroProviderSymbols) { transformMetroProvider(it, contextualTypeKey) }
              }
            }
          }
        }
      } else {
        // Normal provider field access when not in shard initialization
        providerFields[binding.typeKey]?.let {
          return irGetField(irGet(generationContext.thisReceiver), it).let {
            with(metroProviderSymbols) { transformMetroProvider(it, contextualTypeKey) }
          }
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
              createFunction.parameters(remapper = remapper),
              createFunction.deepCopyWithSymbols(initialParent = createFunction.parent).also {
                it.parent = createFunction.parent
                it.remapTypes(remapper)
              },
              binding,
              generationContext,
            )
          }
        }
      }

      is IrBinding.ObjectClass -> {
        instanceFactory(binding.typeKey.type, irGetObject(binding.type.symbol))
      }

      is IrBinding.Alias -> {
        // For binds functions, just use the backing type
        // Resolve alias chains iteratively to avoid deep recursion
        var currentBinding: IrBinding = binding
        val seen = mutableSetOf<IrBinding>()
        while (currentBinding is IrBinding.Alias) {
          if (!seen.add(currentBinding)) {
            error("Circular alias detected for binding: ${binding.typeKey}")
          }
          currentBinding = currentBinding.aliasedBinding(bindingGraph, IrBindingStack.empty())
        }
        check(currentBinding != binding) { "Aliased binding aliases itself" }
        return generateBindingCode(currentBinding, generationContext)
      }

      is IrBinding.Provided -> {
        val factoryClass =
          bindingContainerTransformer.getOrLookupProviderFactory(binding)?.clazz
            ?: error(
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
            binding.parameters,
            createFunction.owner,
            binding,
            generationContext,
          )
        irInvoke(
          dispatchReceiver = irGetObject(creatorClass.symbol),
          callee = createFunction,
          args = args,
        )
      }

      is IrBinding.Assisted -> {
        // Example9_Factory_Impl.create(example9Provider);
        val implClass =
          assistedFactoryTransformer.getOrGenerateImplClass(binding.type) ?: return stubExpression()

        val dispatchReceiver: IrExpression?
        val createFunction: IrSimpleFunctionSymbol
        val isFromDagger: Boolean
        if (options.enableDaggerRuntimeInterop && implClass.isFromJava()) {
          // Dagger interop
          createFunction =
            implClass
              .simpleFunctions()
              .first {
                it.isStatic &&
                  (it.name == Symbols.Names.create ||
                    it.name == Symbols.Names.createFactoryProvider)
              }
              .symbol
          dispatchReceiver = null
          isFromDagger = true
        } else {
          val implClassCompanion = implClass.companionObject()!!
          createFunction = implClassCompanion.requireSimpleFunction(Symbols.StringNames.CREATE)
          dispatchReceiver = irGetObject(implClassCompanion.symbol)
          isFromDagger = false
        }

        val targetBinding =
          bindingGraph.requireBinding(binding.target.typeKey, IrBindingStack.empty())
        val delegateFactoryProvider = generateBindingCode(targetBinding, generationContext)
        val invokeCreateExpression =
          irInvoke(
            dispatchReceiver = dispatchReceiver,
            callee = createFunction,
            args = listOf(delegateFactoryProvider),
          )
        if (isFromDagger) {
          with(symbols.daggerSymbols) {
            val targetType =
              (createFunction.owner.returnType as IrSimpleType).arguments[0].typeOrFail
            transformToMetroProvider(invokeCreateExpression, targetType)
          }
        } else {
          invokeCreateExpression
        }
      }

      is IrBinding.Multibinding -> {
        generateMultibindingExpression(binding, contextualTypeKey, generationContext, fieldInitKey)
      }

      is IrBinding.MembersInjected -> {
        val injectedClassSymbol = referenceClass(binding.targetClassId)
        if (injectedClassSymbol == null) {
          error("Could not find class ${binding.targetClassId} for MembersInjected binding. This might be a shard generation ordering issue.")
        }
        
        val injectedClass = injectedClassSymbol.owner
        // Use symbol.typeWith() instead of defaultType to avoid NPE
        val injectedType = injectedClass.symbol.typeWith()
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
              binding.parameters,
              createFunction.owner,
              binding,
              generationContext,
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
        error("Unable to generate code for unexpected Absent binding: $binding")
      }

      is IrBinding.BoundInstance -> {
        // BoundInstance bindings can be from Factory parameters or the graph itself
        if (generationContext.isShardInitialization) {
          // Debug logging for BoundInstance access
          val logger = loggerFor(MetroLogger.Type.ComponentSharding)
          logger.log("BoundInstance access in shard: ${binding.typeKey}, location in cache: ${bindingLocations[binding.typeKey]}")
          
          // First check if this bound instance is tracked in bindingLocations (Factory parameters)
          val location = bindingLocations[binding.typeKey]
          if (location != null) {
            val parentGraphField = generationContext.parentGraphField
              ?: error("Parent graph field not found in context for BoundInstance binding: $binding")
            val parentAccess = irGetField(irGet(generationContext.thisReceiver), parentGraphField)
            
            return when (location) {
              is BindingLocation.InMainGraph -> {
                // Access bound instance from main graph: this.parentGraph.field
                irGetField(parentAccess, location.field).let {
                  with(metroProviderSymbols) { transformMetroProvider(it, contextualTypeKey) }
                }
              }
              is BindingLocation.InShard -> {
                // Should not happen for bound instances - they're always in main graph
                error("Bound instance should not be in a shard: $binding")
              }
            }
          }
          
          // If not found in bindingLocations, it might be the graph itself as a dependency
          val shardClass = generationContext.currentShardClass
            ?: error("Shard class not found in context for BoundInstance binding: $binding")

          if (!shardClass.name.asString().startsWith("GraphShard")) {
            error("Parent class is not a GraphShard for BoundInstance binding: $binding. " +
                  "Parent class name: ${shardClass.name}, " +
                  "Expected: GraphShard*")
          }
          
          val parentGraphField = generationContext.parentGraphField
            ?: error("Parent graph field not found in context for BoundInstance binding: $binding")
          
          // The parent graph IS the bound instance in this case
          return irGetField(irGet(generationContext.thisReceiver), parentGraphField)
        }
        
        // In non-shard context, this should have been handled by field access logic
        error("Unable to generate code for unexpected BoundInstance binding: $binding")
      }

      is IrBinding.GraphDependency -> {
        val ownerKey = binding.ownerKey
        val graphInstanceField =
          instanceFields[ownerKey]
            ?: run {
              // In shard context, instance fields from parent graph may not be directly available
              // This is expected - the field will be accessed through the parent graph reference
              if (!generationContext.isShardInitialization) {
                error(
                  "No matching included type instance found for type $ownerKey while processing ${node.typeKey}. Available instance fields ${instanceFields.keys}"
                )
              }
              null
            }

        val getterContextKey = IrContextualTypeKey.from(binding.getter)

        // For GraphDependency bindings in shards, we need to access the instance field
        // through the parent graph, as the field exists in the parent graph, not the shard
        val invokeGetter = if (generationContext.isShardInitialization) {
          // In shard context, access the graph dependency through the parent graph
          val parentGraphField = generationContext.parentGraphField
            ?: error("Parent graph field not found in shard context")
          val parentGraphAccess = irGetField(irGet(generationContext.thisReceiver), parentGraphField)
          
          // Now we need to find the instance field in the parent graph
          // The field should be accessible directly from the parent graph instance
          irInvoke(
            dispatchReceiver = parentGraphAccess,
            callee = binding.getter.symbol,
            typeHint = binding.typeKey.type,
          )
        } else {
          // In main graph context, access the field normally
          val receiver = irGet(generationContext.thisReceiver)
          irInvoke(
            dispatchReceiver = irGetField(receiver, graphInstanceField!!),
            callee = binding.getter.symbol,
            typeHint = binding.typeKey.type,
          )
        }

        if (getterContextKey.isLazyWrappedInProvider) {
          // TODO FIR this
          diagnosticReporter
            .at(binding.getter)
            .report(MetroIrErrors.METRO_ERROR, "Provider<Lazy<T>> accessors are not supported.")
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
                if (getterContextKey.isWrappedInLazy) {
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
      }
    }
  }

  private fun IrBuilderWithScope.generateMultibindingExpression(
    binding: IrBinding.Multibinding,
    contextualTypeKey: IrContextualTypeKey,
    generationContext: GraphGenerationContext,
    fieldInitKey: IrTypeKey?,
  ): IrExpression {
    return if (binding.isSet) {
      generateSetMultibindingExpression(binding, contextualTypeKey, generationContext, fieldInitKey)
    } else {
      // It's a map
      generateMapMultibindingExpression(binding, contextualTypeKey, generationContext, fieldInitKey)
    }
  }

  private fun IrBuilderWithScope.generateSetMultibindingExpression(
    binding: IrBinding.Multibinding,
    contextualTypeKey: IrContextualTypeKey,
    generationContext: GraphGenerationContext,
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
        generationContext,
        fieldInitKey,
      )
    } else {
      generateSetBuilderExpression(binding, elementType, generationContext, fieldInitKey)
    }
  }

  private fun IrBuilderWithScope.generateSetBuilderExpression(
    binding: IrBinding.Multibinding,
    elementType: IrType,
    generationContext: GraphGenerationContext,
    fieldInitKey: IrTypeKey?,
  ): IrExpression {
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
        args = listOf(generateMultibindingArgument(provider, generationContext, fieldInitKey))
      }

      else -> {
        // For sets larger than 1, we need to split the building process to avoid method size limits
        val setChunkSize = 3 // Small chunks to avoid method size issues
        
        if (size > setChunkSize) {
          // Generate helper functions for large sets
          // In shard contexts, parent is the constructor, so we need to get the containing class
          val parentClass: IrClass = when {
            generationContext.isShardInitialization && generationContext.currentShardClass != null -> {
              generationContext.currentShardClass!!
            }
            parent is IrClass -> parent
            parent is IrFunction -> (parent as IrFunction).parentAsClass as IrClass
            else -> error("Cannot determine parent class in context: parent is ${parent::class.simpleName}")
          } as IrClass
          
          val sourceBindings = binding.sourceBindings.map { bindingGraph.requireBinding(it, IrBindingStack.empty()) }
          val chunks = sourceBindings.chunked(setChunkSize)
          
          // Use internal visibility in shard contexts to avoid synthetic accessor issues
          val helperVisibility = if (generationContext.isShardInitialization) {
            DescriptorVisibilities.INTERNAL
          } else {
            DescriptorVisibilities.PRIVATE
          }
          
          // Generate chunk helper functions that populate the set
          val chunkHelpers = chunks.mapIndexed { index, chunk ->
            parentClass.addFunction {
              name = Name.identifier("populate${binding.nameHint}SetChunk$index")
              visibility = helperVisibility
              returnType = irBuiltIns.unitType
            }.apply {
              // Set the dispatch receiver parameter for this member function
              val correctReceiver = parentClass.thisReceiver
              if (correctReceiver != null) {
                val localReceiver = correctReceiver.copyTo(this)
                setDispatchReceiver(localReceiver)
              }
              
              // Add parameter for the mutable set
              addValueParameter {
                name = Name.identifier("set")
                type = irBuiltIns.mutableSetClass.typeWith(elementType)
              }
            }
          }
          
          // Generate bodies for chunk helpers
          chunkHelpers.forEachIndexed { index, chunkHelper ->
            val chunk = chunks[index]
            chunkHelper.body = createIrBuilder(chunkHelper.symbol).irBlockBody {
              val setParam = chunkHelper.regularParameters[0]
              
              chunk.forEach { provider ->
                +irInvoke(
                  dispatchReceiver = irGet(setParam),
                  callee = symbols.mutableSetAdd.symbol,
                  args = listOf(
                    generateMultibindingArgument(
                      provider, 
                      generationContext.withReceiver(chunkHelper.dispatchReceiverParameter!!),
                      fieldInitKey
                    )
                  )
                )
              }
            }
          }
          
          // Generate the main builder helper function
          val mainHelper = parentClass.addFunction {
            name = Name.identifier("build${binding.nameHint}Set")
            visibility = helperVisibility
            returnType = binding.typeKey.type
          }.apply {
            // Set the dispatch receiver parameter for this member function
            val correctReceiver = parentClass.thisReceiver
            if (correctReceiver != null) {
              val localReceiver = correctReceiver.copyTo(this)
              setDispatchReceiver(localReceiver)
            }
          }
          
          // Generate the body of the main helper
          mainHelper.body = createIrBuilder(mainHelper.symbol).irBlockBody {
            // Create mutable set
            val mutableSet = irTemporary(
              irInvoke(
                callee = symbols.mutableSetOf,
                typeHint = irBuiltIns.mutableSetClass.typeWith(elementType),
                typeArgs = listOf(elementType)
              ),
              nameHint = "mutableSet"
            )
            
            // Call each chunk helper to populate the set
            chunkHelpers.forEach { chunkHelper ->
              +irInvoke(
                dispatchReceiver = irGet(mainHelper.dispatchReceiverParameter!!),
                callee = chunkHelper.symbol,
                args = listOf(irGet(mutableSet))
              )
            }
            
            // Return as immutable set
            +irReturn(
              irInvoke(
                callee = symbols.toSet,
                typeHint = binding.typeKey.type,
                extensionReceiver = irGet(mutableSet)
              )
            )
          }
          
          // Return call to the helper function
          return irInvoke(
            callee = mainHelper.symbol,
            typeHint = binding.typeKey.type,
            dispatchReceiver = if (mainHelper.dispatchReceiverParameter != null) {
              irGet(generationContext.thisReceiver)
            } else {
              null
            }
          )
        } else {
          // Small sets can use the lambda approach
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
                      args =
                        listOf(
                          generateMultibindingArgument(provider, generationContext, fieldInitKey)
                        ),
                    )
                  }
              }
            )
          }
        }
      }
    }

    return irCall(callee = callee, type = binding.typeKey.type, typeArguments = listOf(elementType))
      .apply {
        for ((i, arg) in args.withIndex()) {
          arguments[i] = arg
        }
      }
  }

  private fun IrBuilderWithScope.generateSetFactoryExpression(
    elementType: IrType,
    collectionProviders: List<IrBinding>,
    individualProviders: List<IrBinding>,
    generationContext: GraphGenerationContext,
    fieldInitKey: IrTypeKey?,
  ): IrExpression {
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
            listOf(generateBindingCode(provider, generationContext, fieldInitKey = fieldInitKey)),
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
            listOf(generateBindingCode(provider, generationContext, fieldInitKey = fieldInitKey)),
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

  private fun IrBuilderWithScope.generateMapMultibindingExpression(
    binding: IrBinding.Multibinding,
    contextualTypeKey: IrContextualTypeKey,
    generationContext: GraphGenerationContext,
    fieldInitKey: IrTypeKey?,
  ): IrExpression {
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
    val rawValueTypeMetadata = rawValueType.typeOrFail.asContextualTypeKey(null, hasDefault = false)

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
        irInvoke(callee = valueProviderSymbols.mapFactoryEmptyFunction, typeHint = mapProviderType)
      }
    }

    // For maps, we need to split the building process to avoid method size limits
    // Map multibindings generate significantly more bytecode per entry than regular bindings
    // Each put() call can generate hundreds of bytecode instructions if the value is complex
    // ViewModelFactory maps are especially problematic
    val mapChunkSize = 2 // Small chunks for map multibindings
    
    if (size > mapChunkSize) {
      // Generate inline logic for large maps
      // In shard contexts, parent is the constructor, so we need to get the containing class
      val parentClass: IrClass = when {
        generationContext.isShardInitialization && generationContext.currentShardClass != null -> {
          generationContext.currentShardClass!!
        }
        parent is IrClass -> parent
        parent is IrFunction -> (parent as IrFunction).parentAsClass as IrClass
        else -> error("Cannot determine parent class in context: parent is ${parent::class.simpleName}")
      } as IrClass

      val sourceBindings = binding.sourceBindings.map { bindingGraph.requireBinding(it, IrBindingStack.empty()) }
      val chunks = sourceBindings.chunked(mapChunkSize)
      
      // Generate a main helper function that creates the builder and calls chunk functions
      // Use internal visibility in shard contexts to avoid synthetic accessor issues
      val helperVisibility = if (generationContext.isShardInitialization) {
        DescriptorVisibilities.INTERNAL
      } else {
        DescriptorVisibilities.PRIVATE
      }
      
      val mainHelper = parentClass.addFunction {
        name = Name.identifier("build${binding.nameHint}Map")
        visibility = helperVisibility
        returnType = mapProviderType
      }.apply {
        // Set the dispatch receiver parameter for this member function
        // Get the correct receiver - must match the parent class type
        val correctReceiver = parentClass.thisReceiver
        if (correctReceiver != null) {
          val localReceiver = correctReceiver.copyTo(this)
          setDispatchReceiver(localReceiver)
        }
      }
      
      // Generate chunk helper functions that populate a MutableMap
      val chunkHelpers = chunks.mapIndexed { index, chunk ->
        parentClass.addFunction {
          name = Name.identifier("populate${binding.nameHint}MapChunk$index")
          visibility = helperVisibility  // Use same visibility as main helper
          returnType = irBuiltIns.unitType
        }.apply {
          // Set the dispatch receiver parameter for this member function
          // Get the correct receiver - must match the parent class type
          val correctReceiver = parentClass.thisReceiver
          if (correctReceiver != null) {
            val localReceiver = correctReceiver.copyTo(this)
            setDispatchReceiver(localReceiver)
          }
          
          // Add mutable map parameter
          addValueParameter {
            name = Name.identifier("map")
            type = irBuiltIns.mutableMapClass.typeWith(
              keyType,
              if (useProviderFactory) {
                rawValueType.wrapInProvider(symbols.metroProvider)
              } else {
                rawValueType
              }
            )
          }
        }
      }
      
      // Generate bodies for chunk helpers
      chunkHelpers.forEachIndexed { index, chunkHelper ->
        val chunk = chunks[index]
        chunkHelper.body = createIrBuilder(chunkHelper.symbol).irBlockBody {
          val mapParam = chunkHelper.regularParameters[0]
          
          // Add each entry in the chunk
          chunk.forEach { sourceBinding ->
            val isMap = sourceBinding.contextualTypeKey.typeKey.type.rawType().symbol == irBuiltIns.mapClass
            if (isMap) {
              TODO("putAll isn't yet supported in large map helpers")
            }
            
            // Put directly into the mutable map
            +irInvoke(
              dispatchReceiver = irGet(mapParam),
              callee = symbols.mutableMapPut.symbol,
              args = listOf(
                generateMapKeyLiteral(sourceBinding),
                generateBindingCode(
                  sourceBinding, 
                  generationContext.withReceiver(chunkHelper.dispatchReceiverParameter!!), 
                  fieldInitKey = fieldInitKey
                ).let {
                  with(valueProviderSymbols) {
                    transformMetroProvider(it, originalValueContextKey)
                  }
                }
              )
            )
          }
        }
      }
      
      // Generate body for main helper
      mainHelper.body = createIrBuilder(mainHelper.symbol).irBlockBody {
        // Create builder
        val builderFunction = if (useProviderFactory) {
          valueProviderSymbols.mapProviderFactoryBuilderFunction
        } else {
          valueProviderSymbols.mapFactoryBuilderFunction
        }
        
        val builderVar = irTemporary(
          irInvoke(
            callee = builderFunction,
            typeArgs = listOf(keyType, valueType),
            typeHint = if (useProviderFactory) {
              valueProviderSymbols.mapProviderFactoryBuilder.typeWith(keyType, valueType)
            } else {
              valueProviderSymbols.mapFactoryBuilder.typeWith(keyType, valueType)
            },
            args = listOf(irInt(binding.sourceBindings.size))
          ),
          nameHint = "builder"
        )
        
        // Call each chunk helper
        chunkHelpers.forEach { chunkHelper ->
          +irInvoke(
            dispatchReceiver = irGet(mainHelper.dispatchReceiverParameter!!),
            callee = chunkHelper.symbol,
            args = listOf(irGet(builderVar))
          )
        }
        
        // Build and return
        val buildFunction = if (useProviderFactory) {
          valueProviderSymbols.mapProviderFactoryBuilderBuildFunction
        } else {
          valueProviderSymbols.mapFactoryBuilderBuildFunction
        }
        
        val instance = irInvoke(
          dispatchReceiver = irGet(builderVar),
          callee = buildFunction,
          typeHint = mapProviderType
        )
        
        +irReturn(
          with(valueProviderSymbols) { 
            transformToMetroProvider(instance, contextualTypeKey.toIrType()) 
          }
        )
      }
      
      // Call the main helper
      return irInvoke(
        dispatchReceiver = irGet(generationContext.thisReceiver),
        callee = mainHelper.symbol
      )
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
    val builder: IrExpression =
      irInvoke(
        callee = builderFunction,
        typeArgs = listOf(keyType, valueType),
        typeHint = builderType.typeWith(keyType, valueType),
        args = listOf(irInt(size)),
      )

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

    val withProviders =
      binding.sourceBindings
        .map { bindingGraph.requireBinding(it, IrBindingStack.empty()) }
        .fold(builder) { receiver, sourceBinding ->
          val providerTypeMetadata = sourceBinding.contextualTypeKey

          // TODO FIR this should be an error actually
          val isMap = providerTypeMetadata.typeKey.type.rawType().symbol == irBuiltIns.mapClass

          val putter =
            if (isMap) {
              // use putAllFunction
              // .putAll(1, FileSystemModule_Companion_ProvideMapInt1Factory.create())
              // TODO is this only for inheriting in GraphExtensions?
              TODO("putAll isn't yet supported")
            } else {
              // .put(1, FileSystemModule_Companion_ProvideMapInt1Factory.create())
              putFunction
            }
          irInvoke(
            dispatchReceiver = receiver,
            callee = putter,
            typeHint = builder.type,
            args =
              listOf(
                generateMapKeyLiteral(sourceBinding),
                generateBindingCode(sourceBinding, generationContext, fieldInitKey = fieldInitKey)
                  .let {
                    with(valueProviderSymbols) {
                      transformMetroProvider(it, originalValueContextKey)
                    }
                  },
              ),
          )
        }

    // .build()
    val buildFunction =
      if (useProviderFactory) {
        valueProviderSymbols.mapProviderFactoryBuilderBuildFunction
      } else {
        valueProviderSymbols.mapFactoryBuilderBuildFunction
      }

    val instance =
      irInvoke(dispatchReceiver = withProviders, callee = buildFunction, typeHint = mapProviderType)
    return with(valueProviderSymbols) { transformToMetroProvider(instance, originalType) }
  }

  private fun IrBuilderWithScope.generateMultibindingArgument(
    provider: IrBinding,
    generationContext: GraphGenerationContext,
    fieldInitKey: IrTypeKey?,
  ): IrExpression {
    val bindingCode = generateBindingCode(provider, generationContext, fieldInitKey = fieldInitKey)
    return typeAsProviderArgument(
      contextKey = IrContextualTypeKey.create(provider.typeKey),
      bindingCode = bindingCode,
      isAssisted = false,
      isGraphInstance = false,
    )
  }
}

internal class GraphGenerationContext(
  val thisReceiver: IrValueParameter,
  val isShardInitialization: Boolean = false,
  val shardProviderFields: Map<IrTypeKey, IrField>? = null,
  // Cache commonly accessed fields for performance
  val parentGraphField: IrField? = null,
  val currentShardClass: IrClass? = null
) {
  // Each declaration in FIR is actually generated with a different "this" receiver, so we
  // need to be able to specify this per-context.
  // TODO not sure if this is really the best way to do this? Only necessary when implementing
  //  accessors/injectors
  fun withReceiver(receiver: IrValueParameter): GraphGenerationContext =
    GraphGenerationContext(receiver, isShardInitialization, shardProviderFields, parentGraphField, currentShardClass)
}
