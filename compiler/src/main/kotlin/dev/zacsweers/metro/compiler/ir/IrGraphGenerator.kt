// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
//

package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.METRO_VERSION
import dev.zacsweers.metro.compiler.NameAllocator
import dev.zacsweers.metro.compiler.Origins
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
import dev.zacsweers.metro.compiler.suffixIfNot
import dev.zacsweers.metro.compiler.tracing.Tracer
import dev.zacsweers.metro.compiler.tracing.traceNested
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.DescriptorVisibility
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.addField
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.addTypeParameter
 
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irExprBody
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irWhen
import org.jetbrains.kotlin.ir.builders.irBranch
import org.jetbrains.kotlin.ir.builders.irElseBranch
import org.jetbrains.kotlin.ir.builders.irSetField
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irEquals
import org.jetbrains.kotlin.ir.builders.irIfThenElse
import org.jetbrains.kotlin.ir.builders.irAs
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.getSimpleFunction
import org.jetbrains.kotlin.ir.util.copyTypeParametersFrom
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrOverridableDeclaration
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
// use builder DSL instead of Impl classes for K2 compatibility
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.typeOrFail
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.util.propertyIfAccessor
import org.jetbrains.kotlin.ir.util.statements
import org.jetbrains.kotlin.ir.util.SYNTHETIC_OFFSET
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.StandardClassIds
import dev.zacsweers.metro.compiler.graph.buildFullAdjacency
import dev.zacsweers.metro.compiler.graph.computeStronglyConnectedComponents

internal typealias FieldInitializer =
  IrBuilderWithScope.(thisReceiver: IrValueParameter, key: IrTypeKey) -> IrExpression

// Borrowed from Dagger
// https://github.com/google/dagger/blob/b39cf2d0640e4b24338dd290cb1cb2e923d38cb3/dagger-compiler/main/java/dagger/internal/codegen/writing/ComponentImplementation.java#L263
private fun IrMetroContext.statementsPerInit(): Int {
  // If keysPerComponentShard is set, prefer it as our per-init partition target.
  val byShard = options.keysPerComponentShard
  if (byShard > 0) return byShard
  // Else respect chunkFieldInits + maxStatementsPerInit; if maxStatementsPerInit <= 0, disable chunking
  if (!options.chunkFieldInits || options.maxStatementsPerInit <= 0) return Int.MAX_VALUE
  return options.maxStatementsPerInit
}

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
  // Fast-init: collect provider entries to assign ids and generate per-shard selectors later
  private data class FastInitEntry(
    val key: IrTypeKey,
    val binding: IrBinding,
    val field: IrField,
    val scoped: Boolean,
  )
  private val fastInitEntries = mutableListOf<FastInitEntry>()
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

        bindingFieldContext.putInstanceField(node.typeKey, thisGraphField)

        // Expose the graph as a provider field
        // TODO this isn't always actually needed but different than the instance field above
        //  would be nice if we could determine if this field is unneeded
        val field =
          getOrCreateBindingField(
            node.typeKey,
            { fieldNameAllocator.newName("thisGraphInstanceProvider") },
            { symbols.metroProvider.typeWith(node.typeKey.type) },
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
                symbols.metroProvider.typeWith(key.type)
              }
            }

          // If we've reserved a field for this key here, pull it out and use that
          val field =
            getOrCreateBindingField(
              binding.typeKey,
              { fieldNameAllocator.newName(binding.nameHint.decapitalizeUS().suffixIfNot(suffix)) },
              { fieldType },
            )

          val accessType =
            if (isProviderType) {
              IrGraphExpressionGenerator.AccessType.PROVIDER
            } else {
              IrGraphExpressionGenerator.AccessType.INSTANCE
            }

          if (isProviderType && options.fastInit) {
            // Fast-init path: initialize as DelegateFactory and defer setDelegate.
            // We'll later assign stable ids, generate per-shard selector lambdas, and set
            // delegates to SwitchingProvider(id, selector).
            field.withInit(key) { _, _ ->
              irInvoke(
                callee = symbols.metroDelegateFactoryConstructor,
                typeArgs = listOf(key.type),
              )
            }
            fastInitEntries += FastInitEntry(key = key, binding = binding, field = field, scoped = binding.isScoped())
            bindingFieldContext.putProviderField(key, field)
          } else {
            // Default path: assign provider/factory directly
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
            bindingFieldContext.putProviderField(key, field)
          }
        }

      // If fast-init is enabled, generate per-shard selector lambdas and set delegates to
      // SwitchingProvider(id, selector) with stable ids.
      if (options.fastInit && fastInitEntries.isNotEmpty()) {
        // Build global order for stable ids
        val globalOrderIndex = sealResult.sortedKeys.withIndex().associate { it.value to it.index }

        // Provider keys in this graph (fast-init only)
        val providerKeys = fastInitEntries.map { it.key }
        val providerBindings = providerKeys.associateWith { bindingGraph.requireBinding(it) }

        // Compute SCCs among provider-only subgraph
        val adjacency = buildFullAdjacency(
          bindings = providerBindings,
          dependenciesOf = { b -> b.dependencies.map { it.typeKey }.filter { it in providerBindings } },
          onMissing = { _, _ -> /* ignore */ },
        )
        val (components, componentOf) = adjacency.computeStronglyConnectedComponents()

        // Group keys by component and order components by earliest appearance in global order
        val compToKeys = mutableMapOf<Int, MutableList<IrTypeKey>>()
        for (key in providerKeys) {
          val cid = componentOf.getValue(key)
          compToKeys.getOrPut(cid, ::mutableListOf) += key
        }
        val componentsOrdered = compToKeys.values.sortedBy { keys -> keys.minOf { globalOrderIndex.getValue(it) } }

        // Pack components into shards up to keysPerShard (or single shard if <= 0)
        val keysPerShard = options.keysPerComponentShard
        val shardKeyBins = mutableListOf<List<IrTypeKey>>()
        var current = mutableListOf<IrTypeKey>()
        var count = 0
        for (compKeys in componentsOrdered) {
          val compSize = compKeys.size
          if (keysPerShard > 0 && count > 0 && count + compSize > keysPerShard) {
            shardKeyBins += current.toList()
            current = mutableListOf()
            count = 0
          }
          current.addAll(compKeys)
          count += compSize
        }
        if (current.isNotEmpty()) shardKeyBins += current.toList()

        // Per-shard ShardN classes each with their own nested SwitchingProvider and IDs
        data class ShardInfo(
          val shardClass: IrClass,
          val shardFieldOnGraph: IrField,
          val nestedSwitchingProviderClass: IrClass,
          val idOf: Map<IrTypeKey, Int>,
          val makeFunctionsById: Map<Int, IrSimpleFunction>
        )
        val shardInfos = mutableMapOf<Int, ShardInfo>()
        val keyToShard = mutableMapOf<IrTypeKey, Int>()
        // Map binding keys to their backing provider fields on the graph
        val keyToProviderField: Map<IrTypeKey, IrField> =
          fieldsToTypeKeys.entries.associate { (field, key) -> key to field }

        for ((shardIndex, bin) in shardKeyBins.withIndex()) {
          val orderedKeys = bin.sortedBy { globalOrderIndex.getValue(it) }
          val idOf = orderedKeys.withIndex().associate { (i, k) -> k to i }

          // For each key, generate a "make" function on the graph that returns the INSTANCE for that key.
          // SwitchingProvider will call these to avoid recursive provider->SwitchingProvider invocations.
          val makeFunctionsById = buildMap {
            for (key in orderedKeys) {
              val id = idOf.getValue(key)
              val funName = Name.identifier("__make_${shardIndex}_$id")
              val makeFun = graphClass.addFunction {
                name = funName
                visibility = DescriptorVisibilities.INTERNAL
                modality = org.jetbrains.kotlin.descriptors.Modality.FINAL
                origin = Origins.Default
                returnType = key.type
              }.apply {
                // Dispatch receiver is the graph instance
                setDispatchReceiver(thisReceiverOrFail.copyTo(this))
                body = createIrBuilder(symbol).run {
                  val binding = bindingGraph.requireBinding(key)
                  val expr = expressionGeneratorFactory
                    .create(dispatchReceiverParameter!!)
                    .generateBindingCode(
                      binding,
                      accessType = IrGraphExpressionGenerator.AccessType.INSTANCE,
                    )
                  irExprBody(expr)
                }
              }
              put(id, makeFun)
            }
          }

          // class ShardN(owner: Graph) { inner SwitchingProvider will dispatch by id using owner }
          val shardName = Name.identifier("Shard$shardIndex")
          val shardClass = pluginContext.irFactory
            .buildClass {
              name = shardName
              kind = org.jetbrains.kotlin.descriptors.ClassKind.CLASS
              visibility = DescriptorVisibilities.PRIVATE
              modality = org.jetbrains.kotlin.descriptors.Modality.FINAL
              origin = Origins.Default
              isCompanion = false
            }
            .apply {
              createThisReceiverParameter()
              graphClass.addChild(this)
              // private val owner: GraphClass
              val shardOwnerField = addField("owner", graphClass.defaultType, DescriptorVisibilities.PRIVATE)
              // Primary constructor(owner: GraphClass) { this.owner = owner }
              addConstructor {
                visibility = DescriptorVisibilities.PUBLIC
                isPrimary = true
                this.returnType = this@apply.defaultType
              }.apply {
                val pOwner = addValueParameter("owner", graphClass.defaultType)
                body = generateDefaultConstructorBody {
                  +irSetField(irGet(thisReceiverOrFail), shardOwnerField, irGet(pOwner))
                }
              }
            }
          // Nested provider that switches based on id and reads from owner
          // Retrieve the owner field symbol for later use in nested provider code
          val shardOwnerField = shardClass.declarations
            .filterIsInstance<IrField>()
            .first { it.name.asString() == "owner" }

          // class SwitchingProvider<T>(private val id: Int, private val shard: ShardN) : Provider<T> { override fun invoke(): T = when(id){...} }
          val nestedProviderClass = pluginContext.irFactory
            .buildClass {
              name = Name.identifier("SwitchingProvider")
              kind = org.jetbrains.kotlin.descriptors.ClassKind.CLASS
              visibility = DescriptorVisibilities.PRIVATE
              modality = org.jetbrains.kotlin.descriptors.Modality.FINAL
              origin = Origins.Default
            }
            .apply {
              shardClass.addChild(this)
              createThisReceiverParameter()
              // Copy single type parameter <T> from Provider<T>
              typeParameters = copyTypeParametersFrom(symbols.metroProvider.owner)
              val tParam = typeParameters.first()
              // Super type: Provider<T>
              superTypes = listOf(symbols.metroProvider.typeWith(tParam.defaultType))

              // Fields: private val id: Int; private val shard: ShardN
              val idField = addField("id", irBuiltIns.intType, DescriptorVisibilities.PRIVATE)
              val shardRefField = addField("shard", shardClass.defaultType, DescriptorVisibilities.PRIVATE)

              // Primary constructor(id: Int, shard: ShardN) { this.id = id; this.shard = shard }
              val ownerDefaultType = this.defaultType
              addConstructor {
                visibility = DescriptorVisibilities.PUBLIC
                isPrimary = true
                this.returnType = ownerDefaultType
              }.apply {
                val pId = addValueParameter("id", irBuiltIns.intType)
                val pShard = addValueParameter("shard", shardClass.defaultType)
                body = generateDefaultConstructorBody {
                  +irSetField(irGet(thisReceiverOrFail), idField, irGet(pId))
                  +irSetField(irGet(thisReceiverOrFail), shardRefField, irGet(pShard))
                }
              }

              // override fun invoke(): T = when(id){ ... -> owner.provider.invoke() ... else -> error("Invalid selector id") }
              val invokeFun = addFunction {
                name = Name.identifier("invoke")
                visibility = DescriptorVisibilities.PUBLIC
                modality = org.jetbrains.kotlin.descriptors.Modality.FINAL
                origin = Origins.Default
                returnType = tParam.defaultType
              }
              // Configure dispatch receiver and build body explicitly
              invokeFun.setDispatchReceiver(thisReceiverOrFail.copyTo(invokeFun))
              invokeFun.body = createIrBuilder(invokeFun.symbol).run {
                val recvParam = invokeFun.dispatchReceiverParameter!!
                val idExpr = irGetField(irGet(recvParam), idField)
                val ownerExpr = irGetField(
                  irGetField(irGet(recvParam), shardRefField),
                  shardOwnerField
                )
                  val branches = buildList {
                  for (key in orderedKeys) {
                    val idValue = idOf.getValue(key)
                      val cond = irEquals(idExpr, irInt(idValue))
                      val makeFun = makeFunctionsById.getValue(idValue)
                      val valueExpr = irInvoke(ownerExpr, callee = makeFun.symbol)
                      add(irBranch(cond, valueExpr))
                  }
                  add(irElseBranch(stubExpression("Invalid selector id")))
                }
                val whenExpr = irWhen(tParam.defaultType, branches)
                irExprBody(whenExpr)
              }
            }

          // Create a field on the graph to hold the shard instance and initialize it
          val shardField = graphClass.addField("shard$shardIndex", shardClass.defaultType, DescriptorVisibilities.PRIVATE)
          initStatements.add { thisReceiver ->
            val ctor = shardClass.primaryConstructor!!.symbol
            val newShard = irCall(ctor).apply {
              arguments[0] = irGet(thisReceiver)
            }
            irSetField(irGet(thisReceiver), shardField, newShard)
          }

          shardInfos[shardIndex] = ShardInfo(shardClass, shardField, nestedProviderClass, idOf, makeFunctionsById)
          for (k in orderedKeys) keyToShard[k] = shardIndex
        }

        // Emit setDelegate statements for fast-init entries using id+selector
        for (entry in fastInitEntries) {
          initStatements.add { thisReceiver ->
            val shardIndex = keyToShard.getValue(entry.key)
            val shardInfo = shardInfos.getValue(shardIndex)
            val idValue = shardInfo.idOf.getValue(entry.key)
            val switchingCtor = irCall(shardInfo.nestedSwitchingProviderClass.primaryConstructor!!.symbol).apply {
              typeArguments[0] = entry.key.type
              arguments[0] = irInt(idValue)
              arguments[1] = irGetField(irGet(thisReceiver), shardInfo.shardFieldOnGraph)
            }
            // Wrap with DoubleCheck only when scoped; otherwise return raw switching provider
            val switching: IrExpression = if (entry.scoped) switchingCtor.doubleCheck(this, symbols, entry.key) else switchingCtor
            irInvoke(
              dispatchReceiver = irGetObject(symbols.metroDelegateFactoryCompanion),
              callee = symbols.metroDelegateFactorySetDelegate,
              typeArgs = listOf(entry.key.type),
              args = listOf(
                irGetField(irGet(thisReceiver), entry.field),
                switching,
              ),
            )
          }
        }
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

      val keysPerShard = options.keysPerComponentShard
      if (keysPerShard > 0 && fieldInitializers.size > keysPerShard) {
        // Key-count based sharding: split only the provider field initializers by key count.
        // Deferred setDelegate statements are emitted in a trailing init() to preserve ordering
        // and avoid interleaving complexity.
        // Build topology-preserving order for the provider fields using the global graph order
        val providerKeys = fieldInitializers.map { (field, _) -> fieldsToTypeKeys.getValue(field) }
        val globalOrderIndex = sealResult.sortedKeys.withIndex().associate { it.value to it.index }

        // Subgraph over provider keys for SCC computation
        val providerBindings = providerKeys.associateWith { bindingGraph.requireBinding(it) }
        val adjacency = buildFullAdjacency(
          bindings = providerBindings,
          dependenciesOf = { b -> b.dependencies.map { it.typeKey }.filter { it in providerBindings } },
          onMissing = { _, _ -> /* ignore missing edges outside of provider set */ },
        )
        val (components, componentOf) = adjacency.computeStronglyConnectedComponents()

        // Group provider keys by SCC id
        val compToKeys = mutableMapOf<Int, MutableList<IrTypeKey>>()
        for (key in providerKeys) {
          val cid = componentOf.getValue(key)
          compToKeys.getOrPut(cid, ::mutableListOf) += key
        }
        // Sort components by earliest appearance in global order
        val componentsOrdered = compToKeys.values.sortedBy { keys -> keys.minOf { globalOrderIndex.getValue(it) } }

        // Pack components into shards up to keysPerShard
        val shardKeyBins = mutableListOf<List<IrTypeKey>>()
        var current = mutableListOf<IrTypeKey>()
        var count = 0
        for (compKeys in componentsOrdered) {
          val compSize = compKeys.size
          if (count > 0 && count + compSize > keysPerShard) {
            shardKeyBins += current.toList()
            current = mutableListOf()
            count = 0
          }
          current.addAll(compKeys)
          count += compSize
        }
        if (current.isNotEmpty()) shardKeyBins += current.toList()

        // Build init statement chunks per shard, with keys in global topological order
        val keyToFieldInit = fieldInitializers.associateBy({ fieldsToTypeKeys.getValue(it.first) }, { it })
        val fieldChunks = shardKeyBins.map { bin ->
          val orderedKeys = bin.sortedBy { globalOrderIndex.getValue(it) }
          buildList<IrBuilderWithScope.(thisReceiver: IrValueParameter) -> IrStatement> {
            for (key in orderedKeys) {
              val (field, init) = keyToFieldInit.getValue(key)
              add { thisReceiver ->
                irSetField(
                  irGet(thisReceiver),
                  field,
                  init(thisReceiver, key),
                )
              }
            }
          }
        }
    val initAllocator = NameAllocator(mode = NameAllocator.Mode.COUNT)
    val initFunctionsToCall = mutableListOf<IrSimpleFunction>()

        // Generate per-key chunks as init functions
        for (statementsChunk in fieldChunks) {
          val initName = initAllocator.newName("init")
          val initFun =
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
          initFunctionsToCall += initFun
        }

        // After all field initializers, emit deferred setDelegate statements in chunks as well
        if (initStatements.isNotEmpty()) {
          val delegateChunks = initStatements.chunked(keysPerShard)
          for (delegateChunk in delegateChunks) {
            val initName = initAllocator.newName("init")
            val initFun =
              addFunction(initName, irBuiltIns.unitType, visibility = DescriptorVisibilities.PRIVATE)
                .apply {
                  val localReceiver = thisReceiverParameter.copyTo(this)
                  setDispatchReceiver(localReceiver)
                  buildBlockBody {
                    for (statement in delegateChunk) {
                      +statement(localReceiver)
                    }
                  }
                }
            initFunctionsToCall += initFun
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
        val maxPerInit = metroContext.statementsPerInit()
        if (fieldInitializers.size + initStatements.size > maxPerInit) {
          // Larger graph, split statements by total statement count (legacy behavior)
          val chunks =
            buildList<IrBuilderWithScope.(thisReceiver: IrValueParameter) -> IrStatement> {
                // Add field initializers first
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
              }
              .chunked(maxPerInit)

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
        for ((field, init) in fieldInitializers) {
          field.initFinal {
            val typeKey = fieldsToTypeKeys.getValue(field)
            init(thisReceiverParameter, typeKey)
          }
        }
        constructorStatements += initStatements
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

      if (graphClass.origin != Origins.GeneratedGraphExtension) {
        parentTracer.traceNested("Generate Metro metadata") {
          // Finally, generate metadata
          val graphProto = node.toProto(bindingGraph = bindingGraph)
          val metroMetadata = MetroMetadata(METRO_VERSION, dependency_graph = graphProto)

          writeDiagnostic({
            "graph-metadata-${node.sourceGraph.classIdOrFail.asString().replace(".", "-")}.kt"
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

            @Suppress("DEPRECATION")
            for (type in
              pluginContext.referenceClass(binding.targetClassId)!!
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
}
