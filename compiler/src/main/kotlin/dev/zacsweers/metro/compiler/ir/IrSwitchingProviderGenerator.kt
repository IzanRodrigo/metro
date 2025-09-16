// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:Suppress("DEPRECATION")

package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.MetroConstants.STATEMENTS_PER_METHOD
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.graph.sharding.ShardFieldRegistry
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.builders.declarations.addField
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrBranchImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrInstanceInitializerCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrStringConcatenationImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.nonDispatchParameters
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.name.Name

internal class IrSwitchingProviderGenerator(
  private val context: IrMetroContext,
  private val bindingFieldContext: BindingFieldContext? = null,
  private val shardFieldRegistry: ShardFieldRegistry? = null,
  private val expressionGenerator: IrGraphExpressionGenerator? = null,
  private val graphTypeKey: IrTypeKey? = null,
) : IrMetroContext by context {

  companion object {
    /**
     * Creates the SwitchingProvider nested class within the graph class.
     * The class is created as a shell with constructor body populated later.
     *
     * @param context The IR metro context
     * @param graphClass The parent graph class to add the SwitchingProvider to
     * @return The created SwitchingProvider class, or null if fastInit is disabled
     */
    fun createSwitchingProviderClass(
      context: IrMetroContext,
      graphClass: IrClass
    ): IrClass? = with(context) {
      if (!options.fastInit) {
        if (options.debug) {
          log("SwitchingProvider disabled via compiler option")
        }
        return null
      }

      // SwitchingProvider has already been created in FIR phase
      // Find and return the existing class
      val switchingProviderClass = graphClass.declarations
        .filterIsInstance<IrClass>()
        .firstOrNull { it.name == Symbols.Names.SwitchingProvider }

      if (switchingProviderClass == null) {
        if (options.debug) {
          log("SwitchingProvider class not found - was FIR generation skipped?")
        }
        return null
      }

      switchingProviderClass
    }

    /**
     * Populates the SwitchingProvider constructor body with field assignments.
     * This must be called after the class is created but before it's used.
     *
     * @param context The IR metro context
     * @param switchingProviderClass The SwitchingProvider class to populate
     * @param graphClass The parent graph class
     */
    fun populateSwitchingProviderConstructor(
      context: IrMetroContext,
      switchingProviderClass: IrClass,
      graphClass: IrClass
    ) = with(context) {
      val spCtor = switchingProviderClass.primaryConstructor
        ?: error("SwitchingProvider must have primary constructor")

      // Add Provider<T> supertype if not already present
      if (switchingProviderClass.superTypes.isEmpty() ||
          switchingProviderClass.superTypes.all { !it.isProvider() }) {
        val typeParam = switchingProviderClass.typeParameters.firstOrNull()
          ?: error("SwitchingProvider must have type parameter T")
        // Create the type reference for the type parameter T
        val typeParamType = irBuiltIns.anyType  // Fallback to Any for now, as the actual type will be resolved at usage
        val providerType = symbols.metroProvider.typeWith(typeParamType)
        switchingProviderClass.superTypes = switchingProviderClass.superTypes + providerType
      }

      // First, create backing fields for graph and id if they don't exist
      val graphField = switchingProviderClass.declarations
        .filterIsInstance<IrField>()
        .firstOrNull { it.name == Symbols.Names.graph }
        ?: switchingProviderClass.addField {
          name = Symbols.Names.graph
          type = graphClass.defaultType
          visibility = DescriptorVisibilities.PRIVATE
          isFinal = true
        }

      val idField = switchingProviderClass.declarations
        .filterIsInstance<IrField>()
        .firstOrNull { it.name == Symbols.Names.id }
        ?: switchingProviderClass.addField {
          name = Symbols.Names.id
          type = irBuiltIns.intType
          visibility = DescriptorVisibilities.PRIVATE
          isFinal = true
        }

      // Build constructor body: super(), field assignments, instance initializer
      spCtor.body = irFactory.createBlockBody(UNDEFINED_OFFSET, UNDEFINED_OFFSET).apply {
        val builder = createIrBuilder(spCtor.symbol)
        val thisParam = switchingProviderClass.thisReceiver
          ?: error("SwitchingProvider must have thisReceiver")

        // Find constructor parameters - use nonDispatchParameters to avoid deprecated API
        val params = spCtor.nonDispatchParameters
        require(params.size >= 2) {
          "SwitchingProvider constructor must have at least 2 parameters but found ${params.size}"
        }
        val graphParam = params[0]  // First param should be graph
        val idParam = params[1]     // Second param should be id

        // Validate parameter names
        require(graphParam.name == Symbols.Names.graph) {
          "Expected first parameter to be 'graph' but got '${graphParam.name}'"
        }
        require(idParam.name == Symbols.Names.id) {
          "Expected second parameter to be 'id' but got '${idParam.name}'"
        }

        // Call super constructor (Any)
        statements += builder.irDelegatingConstructorCall(
          irBuiltIns.anyClass.owner.primaryConstructor!!
        )

        // Assign fields
        statements += builder.irSetField(
          receiver = builder.irGet(thisParam),
          field = graphField,
          value = builder.irGet(graphParam)
        )

        statements += builder.irSetField(
          receiver = builder.irGet(thisParam),
          field = idField,
          value = builder.irGet(idParam)
        )

        // Call instance initializer if needed
        statements += IrInstanceInitializerCallImpl(
          UNDEFINED_OFFSET,
          UNDEFINED_OFFSET,
          switchingProviderClass.symbol,
          irBuiltIns.unitType
        )
      }

      if (options.debug) {
        log("Populated SwitchingProvider constructor body with field assignments")
      }
    }

    // Extension function to check if a type is a Provider type
    private fun IrType.isProvider(): Boolean {
      val classifier = (this as? IrSimpleType)?.classifier?.owner as? IrClass
      return classifier?.name?.asString()?.contains("Provider") == true
    }

    /**
     * Populates the SwitchingProvider invoke() method if it exists.
     * This should be called after all fields are created and initialized.
     *
     * @param context The IR metro context
     * @param graphClass The main graph class containing the SwitchingProvider
     * @param switchingProviderClass The SwitchingProvider class (may be null)
     * @param switchingIds Map of type keys to their assigned switch IDs
     * @param bindingGraph The binding graph containing all bindings
     * @param bindingFieldContext The field context for looking up fields
     * @param shardFieldRegistry The shard field registry for cross-shard access
     * @param expressionGeneratorFactory Factory for creating expression generators
     * @param node The dependency graph node
     */
    fun populateSwitchingProviderIfExists(
      context: IrMetroContext,
      graphClass: IrClass,
      switchingProviderClass: IrClass?,
      switchingIds: Map<IrTypeKey, Int>,
      bindingGraph: IrBindingGraph,
      bindingFieldContext: BindingFieldContext,
      shardFieldRegistry: ShardFieldRegistry,
      expressionGeneratorFactory: IrGraphExpressionGenerator.Factory,
      node: DependencyGraphNode
    ) = with(context) {
      if (switchingProviderClass == null) {
        // If we have bindings to handle but no SwitchingProvider class, that's an error
        if (switchingIds.isNotEmpty()) {
          error("Missing SwitchingProvider class in ${graphClass.name} with ${switchingIds.size} bindings to handle – did FIR generation run?")
        }
        // No SwitchingProvider and no bindings, nothing to do
        return
      }

      // Find the invoke function
      val invokeFun = switchingProviderClass.declarations
        .filterIsInstance<IrSimpleFunction>()
        .firstOrNull { it.name.asString() == "invoke" }
        ?: error("SwitchingProvider must have invoke() function")

      // If we have SwitchingProvider but no bindings, provide a fake implementation
      if (switchingIds.isEmpty()) {
        if (options.debug) {
          log("IrGraphGenerator: No bindings registered for SwitchingProvider - generating error body")
        }
        invokeFun.body = irFactory.createBlockBody(UNDEFINED_OFFSET, UNDEFINED_OFFSET).apply {
          val builder = createIrBuilder(invokeFun.symbol)
          statements += builder.irReturn(
            builder.irInvoke(
              callee = symbols.stdlibErrorFunction,
              args = listOf(builder.irString("SwitchingProvider not implemented - no bindings registered"))
            )
          )
        }
        return
      }

      if (options.debug) {
        log("IrGraphGenerator: Populating SwitchingProvider with ${switchingIds.size} bindings")
      }

      // Build the ordered list of bindings based on their IDs
      val idToBinding = switchingIds.entries
        .sortedBy { it.value }
        .mapNotNull { (typeKey, _) ->
          bindingGraph.bindingsSnapshot()[typeKey]
        }

      // Get the dispatch receiver of invoke (the SwitchingProvider instance)
      val spThis = requireNotNull(invokeFun.dispatchReceiverParameter) {
        "invoke() must have dispatch receiver"
      }

      // Find graph and id fields in SwitchingProvider
      val graphField = switchingProviderClass.declarations.filterIsInstance<IrField>()
        .firstOrNull { it.name == Symbols.Names.graph }
        ?: error("SwitchingProvider must have field: graph")
      val idField = switchingProviderClass.declarations.filterIsInstance<IrField>()
        .firstOrNull { it.name == Symbols.Names.id }
        ?: error("SwitchingProvider must have field: id")

      // Build the invoke body
      invokeFun.body = irFactory.createBlockBody(UNDEFINED_OFFSET, UNDEFINED_OFFSET).apply {
        val builder = createIrBuilder(invokeFun.symbol)

        // Get graph from SwitchingProvider field (created once, can be reused)
        val graphExpr = builder.irGetField(builder.irGet(spThis), graphField)

        // Create a lambda to generate fresh ID field access expressions
        // This avoids the "duplicate IR node" validation error
        val idExprFactory: () -> IrExpression = {
          builder.irGetField(builder.irGet(spThis), idField)
        }

        // CRITICAL: Build expression generator with the SwitchingProvider's dispatch receiver
        // The expression generator will use the graph field from SwitchingProvider for field access
        // This ensures correct receiver context when generating binding code inside invoke()
        val expressionGenerator = expressionGeneratorFactory.create(spThis)

        // Create the SwitchingProviderGenerator instance
        val switchingGenerator = IrSwitchingProviderGenerator(
          context = context,
          bindingFieldContext = bindingFieldContext,
          shardFieldRegistry = shardFieldRegistry,
          expressionGenerator = expressionGenerator,
          graphTypeKey = node.typeKey
        )

        // Populate the body with graph-aware expressions
        // Note: We pass idExprFactory() to create a fresh expression for the method
        val invokeStatements = switchingGenerator.populateInvokeBody(
          builder = builder,
          graphClass = graphClass,
          switchingProviderClass = switchingProviderClass,
          idToBinding = idToBinding,
          graphExpr = graphExpr,
          idExpr = idExprFactory(),  // Create one fresh expression for this call
          returnType = invokeFun.returnType
        )
        statements.addAll(invokeStatements)
      }
    }
  }

  // Helper extension functions for type checking
  private fun IrType.isProvider(): Boolean {
    val classifier = this.classifierOrNull
    return classifier is IrClassSymbol &&
           classifier.owner.fqNameWhenAvailable?.asString()?.let { fqName ->
             fqName == "javax.inject.Provider" ||
             fqName == "dev.zacsweers.metro.runtime.MetroProvider"
           } == true
  }

  private fun IrType.isLazy(): Boolean {
    val classifier = this.classifierOrNull
    return classifier is IrClassSymbol &&
           classifier.owner.fqNameWhenAvailable?.asString() == "kotlin.Lazy"
  }

  /**
   * Helper to resolve the owner of a field when it might be in a shard.
   * Returns the appropriate receiver expression (main graph or shard instance).
   *
   * IMPORTANT: This method creates FRESH expressions each time to avoid duplicate IR nodes.
   * This is critical for IR validation - we cannot reuse the same field access node.
   *
   * IMPORTANT: This method does NOT add any caching wrappers (DoubleCheck).
   * Caching is handled at the field initialization level in IrGraphGenerator,
   * not in the SwitchingProvider's invoke() dispatch logic.
   */
  context(scope: IrBuilderWithScope)
  private fun resolveOwnerForShard(
    switchingProviderClass: IrClass,
    graphClass: IrClass,
    shardIndex: Int?
  ): IrExpression = with(scope) {
    // Always create a fresh graph expression to avoid duplicate IR nodes
    val invokeFunction = switchingProviderClass.declarations
      .filterIsInstance<IrSimpleFunction>()
      .firstOrNull { it.name.asString() == "invoke" }
      ?: error("SwitchingProvider must have invoke() function")

    val spThis = invokeFunction.dispatchReceiverParameter
      ?: error("invoke() must have dispatch receiver")

    val graphField = switchingProviderClass.declarations.filterIsInstance<IrField>()
      .firstOrNull { it.name == Symbols.Names.graph }
      ?: error("SwitchingProvider must have field: graph")

    val freshGraphExpr = irGetField(irGet(spThis), graphField)

    if (shardIndex == null || shardIndex == 0) {
      // Field is in main graph
      if (debug && shardIndex == 0) {
        log("SwitchingProviderGenerator: Accessing field in main graph")
      }
      freshGraphExpr
    } else {
      // Field is in a shard, access via graph.shardN
      if (debug) {
        log("SwitchingProviderGenerator: Cross-shard access - main -> shard$shardIndex")
      }
      val shardFieldOnGraph = graphClass.declarations
        .filterIsInstance<IrField>()
        .firstOrNull { it.name.asString() == "shard$shardIndex" }
        ?: error("Missing shard field: shard$shardIndex")
      irGetField(freshGraphExpr, shardFieldOnGraph)
    }
  }

  /**
   * Populates the invoke() body of SwitchingProvider with a when(id) expression.
   *
   * IMPORTANT: This method MUST NOT add any caching wrappers (DoubleCheck).
   * Caching is already applied at the field initialization level when the provider
   * fields are created. The invoke() method should only dispatch to the appropriate
   * binding code without additional wrapping.
   *
   * For large numbers of bindings (> 100), this will generate helper methods to avoid
   * hitting JVM method size limits. The main invoke() method will dispatch to the
   * appropriate helper based on ID ranges.
   *
   * @param builder The IR builder for creating expressions
   * @param graphClass The main graph class containing the SwitchingProvider
   * @param switchingProviderClass The SwitchingProvider class itself
   * @param idToBinding Ordered list of bindings by their assigned IDs
   * @param graphExpr Expression to access the graph instance from SwitchingProvider.graph
   * @param idExpr Expression to access the id from SwitchingProvider.id
   * @param returnType The return type of invoke() (typically T from Provider<T>)
   */
  @Suppress("DEPRECATION")
  fun populateInvokeBody(
    builder: IrBuilderWithScope,
    graphClass: IrClass,
    switchingProviderClass: IrClass,
    idToBinding: List<IrBinding>, // same order as assigned IDs
    graphExpr: IrExpression,      // The graph instance from SwitchingProvider.graph field
    idExpr: IrExpression,          // The id from SwitchingProvider.id field
    returnType: IrType
  ): List<IrStatement> {
    // Defensive check: empty bindings list
    if (idToBinding.isEmpty()) {
      if (debug) {
        log("SwitchingProviderGenerator: No bindings registered for SwitchingProvider")
      }
      return builder.run {
        listOf(
          irReturn(
            irInvoke(
              callee = symbols.stdlibErrorFunction,
              args = listOf(irString("SwitchingProvider not implemented - no bindings registered"))
            )
          )
        )
      }
    }

    // Check if we need to split into helper methods
    if (idToBinding.size > STATEMENTS_PER_METHOD) {
      if (debug) {
        log("SwitchingProviderGenerator: Splitting ${idToBinding.size} bindings into helper methods")
      }
      // Generate helper methods and dispatch to them
      return generateSplitInvokeBody(
        builder, graphClass, switchingProviderClass,
        idToBinding, graphExpr, idExpr, returnType
      )
    }
    // Build branches for when(id) expression
    val branches = mutableListOf<IrBranchImpl>()

    // Add a branch for each binding ID
    idToBinding.forEachIndexed { id, binding ->
      val bindingExpr = builder.run {
        // CRITICAL: Inside SwitchingProvider.invoke(), we must NEVER invoke provider fields
        // because they might be wrapped in DoubleCheck(SwitchingProvider(...)), causing recursion.
        // Instead, we always generate the instance directly.

        // For all binding types, we generate the instance creation code directly.
        // This is what gets called when the provider is invoked.
        // Debug all bindings to understand what's happening
        if (debug) {
          log("SwitchingProvider: Processing binding ${binding.typeKey.render(short = true)}")
          log("  Binding class: ${binding::class.simpleName}")
          if (graphTypeKey != null) {
            log("  Graph type: ${graphTypeKey.render(short = true)}")
            log("  Match: ${binding.typeKey == graphTypeKey}")
          }
        }

        // Special case: If this binding represents the graph itself, return the graph instance
        // This can happen when GraphExtensionFactory needs the parent graph (AppComponent)
        if (graphTypeKey != null && binding.typeKey == graphTypeKey) {
          if (debug) {
            log("SwitchingProvider: MATCH! Detected graph binding (${binding::class.simpleName}), returning thisGraphInstance")
          }

          // Access through graph.thisGraphInstance field if it exists
          val thisGraphInstanceField = graphClass.declarations
            .filterIsInstance<IrField>()
            .firstOrNull { field ->
              field.name.asString().contains("thisGraphInstance")
            }

          if (thisGraphInstanceField != null) {
            // Get the graph from SwitchingProvider and access its thisGraphInstance field
            val graphField = switchingProviderClass.declarations.filterIsInstance<IrField>()
              .firstOrNull { it.name == Symbols.Names.graph }
              ?: error("SwitchingProvider must have field: graph")

            val invokeFunction = switchingProviderClass.declarations
              .filterIsInstance<IrSimpleFunction>()
              .firstOrNull { it.name.asString() == "invoke" }
              ?: error("SwitchingProvider must have invoke() function")

            val spThis = invokeFunction.dispatchReceiverParameter
              ?: error("invoke() must have dispatch receiver")

            val graphAccess = irGetField(irGet(spThis), graphField)
            return@run irGetField(graphAccess, thisGraphInstanceField)
          } else {
            // Fallback: just return the graph itself
            val graphField = switchingProviderClass.declarations.filterIsInstance<IrField>()
              .firstOrNull { it.name == Symbols.Names.graph }
              ?: error("SwitchingProvider must have field: graph")

            val invokeFunction = switchingProviderClass.declarations
              .filterIsInstance<IrSimpleFunction>()
              .firstOrNull { it.name.asString() == "invoke" }
              ?: error("SwitchingProvider must have invoke() function")

            val spThis = invokeFunction.dispatchReceiverParameter
              ?: error("invoke() must have dispatch receiver")

            return@run irGetField(irGet(spThis), graphField)
          }
        }

        when (binding) {
            // BoundInstance must always be read from fields, never constructed inline
            is IrBinding.BoundInstance -> {
              // Try to resolve the field in order of preference:
              // 1. Instance field (most common for BoundInstance)
              // 2. Provider field (if caller expects a provider)
              // 3. Shard registry (if sharding is active)

              val instanceField = bindingFieldContext?.instanceField(binding.typeKey)
              val providerField = bindingFieldContext?.providerField(binding.typeKey)
              val shardInfo = shardFieldRegistry?.findField(binding.typeKey)

              when {
                instanceField != null -> {
                  // Resolve the owner (could be main graph or shard)
                  val owner = resolveOwnerForShard(switchingProviderClass, graphClass, shardInfo?.shardIndex)
                  // Return the instance directly
                  irGetField(owner, instanceField)
                }

                providerField != null -> {
                  // If we have a provider field, use it and invoke
                  val owner = resolveOwnerForShard(switchingProviderClass, graphClass, shardInfo?.shardIndex)
                  // Get the provider and invoke it
                  val providerExpr = irGetField(owner, providerField)
                  irCall(symbols.providerInvoke).apply {
                    dispatchReceiver = providerExpr
                  }
                }

                shardInfo != null -> {
                  // Only shard registry has the field
                  val owner = resolveOwnerForShard(switchingProviderClass, graphClass, shardInfo.shardIndex)
                  irGetField(owner, shardInfo.field)
                }

                else -> {
                  error("BoundInstance must have a field (instance or provider): ${binding.typeKey}")
                }
              }
            }

            is IrBinding.GraphDependency -> {
              // GraphDependency should read from the appropriate field or call getter
              // NEVER call inline or constructor paths for GraphDependency
              when {
                binding.fieldAccess != null -> {
                  // Use safeGetField for proper cross-shard access
                  // First try to get the instance field
                  val instanceField = bindingFieldContext?.instanceField(binding.typeKey)
                  val providerField = bindingFieldContext?.providerField(binding.typeKey)

                  when {
                    instanceField != null -> {
                      // Check if field might be in a shard
                      val shardInfo = shardFieldRegistry?.findField(binding.typeKey)
                      val owner = resolveOwnerForShard(switchingProviderClass, graphClass, shardInfo?.shardIndex)

                      // Use expressionGenerator's safeGetField if available for proper cross-shard handling
                      if (expressionGenerator != null) {
                        expressionGenerator.safeGetField(owner, instanceField, binding.typeKey)
                      } else {
                        // Fallback to direct field access
                        irGetField(owner, instanceField)
                      }
                    }

                    providerField != null -> {
                      // We have a provider field - get it and invoke if instance is needed
                      val shardInfo = shardFieldRegistry?.findField(binding.typeKey)
                      val owner = resolveOwnerForShard(switchingProviderClass, graphClass, shardInfo?.shardIndex)

                      val providerExpr = if (expressionGenerator != null) {
                        expressionGenerator.safeGetField(owner, providerField, binding.typeKey)
                      } else {
                        irGetField(owner, providerField)
                      }

                      // Invoke the provider to get the instance
                      irCall(symbols.providerInvoke).apply {
                        dispatchReceiver = providerExpr
                      }
                    }

                    else -> {
                      error("GraphDependency with fieldAccess must have field: ${binding.typeKey}")
                    }
                  }
                }

                binding.getter != null -> {
                  // Build irInvoke(getter) using the resolved graph or included graph instance
                  // Create fresh graph expression to avoid duplicate IR nodes
                  val freshGraphExpr = resolveOwnerForShard(switchingProviderClass, graphClass, 0)
                  val getterResult = irCall(binding.getter).apply {
                    // Getters are always on the main graph or included graph
                    dispatchReceiver = freshGraphExpr
                  }

                  // Check if we need to unwrap provider/lazy
                  val returnType = binding.getter.returnType
                  when {
                    // If getter returns Provider<T>, invoke it to get T
                    returnType.isProvider() -> {
                      irCall(symbols.providerInvoke).apply {
                        dispatchReceiver = getterResult
                      }
                    }

                    // If getter returns Lazy<T>, get value to get T
                    returnType.isLazy() -> {
                      irCall(symbols.lazyGetValue).apply {
                        dispatchReceiver = getterResult
                      }
                    }

                    // Otherwise return the direct result
                    else -> getterResult
                  }
                }

                else -> {
                  error("GraphDependency must have either fieldAccess or getter")
                }
              }
            }

            else -> {
              // For all other binding types, generate the instance directly
              // NEVER invoke provider fields as they might recursively call back to SwitchingProvider

              // Use the expression generator to create the instance
              if (expressionGenerator != null) {
                // Generate the instance code directly, bypassing any provider wrappers
                expressionGenerator.generateBindingCode(
                  binding = binding,
                  contextualTypeKey = binding.contextualTypeKey,
                  accessType = IrGraphExpressionGenerator.AccessType.INSTANCE,
                  fieldInitKey = null,
                  bypassProviderFor = binding.typeKey  // Critical: prevent re-routing through SwitchingProvider
                )
              } else {
                // These binding types require field resolution
                // Try to find the field in bindingFieldContext or shardFieldRegistry
                val instanceField = bindingFieldContext?.instanceField(binding.typeKey)
                val shardInfo = shardFieldRegistry?.findField(binding.typeKey)

                when {
                  instanceField != null -> {
                    // Found instance field - use it with proper owner resolution
                    val owner = resolveOwnerForShard(switchingProviderClass, graphClass, shardInfo?.shardIndex)
                    irGetField(owner, instanceField)
                  }

                  shardInfo != null -> {
                    // Field exists in shard registry
                    val owner = resolveOwnerForShard(switchingProviderClass, graphClass, shardInfo.shardIndex)
                    irGetField(owner, shardInfo.field)
                  }

                  else -> {
                    // No field found - this is an error for unsupported inline types
                    error("Binding type ${binding::class.simpleName} requires a field but none found: ${binding.typeKey}")
                  }
                }
              }
            }
        }
      }

      // Create a fresh ID expression for each branch to avoid IR validation errors
      // The IR validator doesn't allow reusing the same field access node in multiple places
      val freshIdExpr = builder.run {
        // We need to get the id field from the SwitchingProvider instance
        // Use the same pattern as the original idExpr but create it fresh
        // The idExpr parameter can serve as a template, but we need to recreate it

        // Find the invoke function to get its dispatch receiver
        val invokeFunction = switchingProviderClass.declarations
          .filterIsInstance<IrSimpleFunction>()
          .firstOrNull { it.name.asString() == "invoke" }
          ?: error("SwitchingProvider must have invoke() function")

        val spThis = invokeFunction.dispatchReceiverParameter
          ?: error("invoke() must have dispatch receiver")

        val idField = switchingProviderClass.declarations.filterIsInstance<IrField>()
          .firstOrNull { it.name == Symbols.Names.id }
          ?: error("SwitchingProvider must have field: id")

        irGetField(irGet(spThis), idField)
      }

      branches += IrBranchImpl(
        UNDEFINED_OFFSET,
        UNDEFINED_OFFSET,
        condition = builder.irEquals(freshIdExpr, builder.irInt(id)),
        result = bindingExpr
      )
    }

    // Default branch: throw error with the unknown id
    val defaultBranch = builder.run {
      val errorMessage = IrStringConcatenationImpl(
        UNDEFINED_OFFSET,
        UNDEFINED_OFFSET,
        symbols.irBuiltIns.stringType
      ).apply {
        arguments.add(irString("Unknown SwitchingProvider id: "))
        arguments.add(idExpr)
      }

      IrBranchImpl(
        UNDEFINED_OFFSET,
        UNDEFINED_OFFSET,
        condition = irTrue(),
        result = irInvoke(
          callee = symbols.stdlibErrorFunction,
          args = listOf(errorMessage)
        )
      )
    }
    branches += defaultBranch

    // Create and return the when expression
    val whenExpr = builder.irWhen(
      type = returnType,
      branches = branches
    )

    return listOf(builder.irReturn(whenExpr))
  }

  /**
   * Generates a split invoke body that delegates to helper methods for large switch statements.
   * Each helper method handles up to STATEMENTS_PER_METHOD cases.
   */
  private fun generateSplitInvokeBody(
    builder: IrBuilderWithScope,
    graphClass: IrClass,
    switchingProviderClass: IrClass,
    idToBinding: List<IrBinding>,
    graphExpr: IrExpression,
    idExpr: IrExpression,
    returnType: IrType
  ): List<IrStatement> {
    // Calculate how many helper methods we need
    val numChunks = (idToBinding.size + STATEMENTS_PER_METHOD - 1) / STATEMENTS_PER_METHOD

    // Generate helper methods
    for (chunkIndex in 0 until numChunks) {
      val startId = chunkIndex * STATEMENTS_PER_METHOD
      val endId = minOf(startId + STATEMENTS_PER_METHOD - 1, idToBinding.size - 1)
      val chunkBindings = idToBinding.subList(startId, endId + 1)

      generateHelperMethod(
        switchingProviderClass,
        chunkIndex,
        startId,
        endId,
        chunkBindings,
        returnType,
        graphClass
      )
    }

    // Generate main invoke body that dispatches to helpers
    return builder.run {
      val branches = mutableListOf<IrBranchImpl>()

      // Add a branch for each chunk
      for (chunkIndex in 0 until numChunks) {
        val startId = chunkIndex * STATEMENTS_PER_METHOD
        val endId = minOf(startId + STATEMENTS_PER_METHOD - 1, idToBinding.size - 1)

        // Find the helper method
        val helperMethod = switchingProviderClass.declarations
          .filterIsInstance<IrSimpleFunction>()
          .firstOrNull { it.name.asString() == "invoke\$chunk$chunkIndex" }
          ?: error("Helper method invoke\$chunk$chunkIndex not found")

        // Create condition: id in startId..endId
        val condition = if (startId == endId) {
          // Single case
          irEquals(idExpr, irInt(startId))
        } else {
          // Range check: id >= startId && id <= endId
          // Create comparison operations - the map expects a classifier symbol, not a type
          val intClassifier = symbols.irBuiltIns.intClass
          val geOp = irCall(symbols.irBuiltIns.greaterOrEqualFunByOperandType[intClassifier]!!).apply {
            arguments[0] = idExpr
            arguments[1] = irInt(startId)
          }
          val leOp = irCall(symbols.irBuiltIns.lessOrEqualFunByOperandType[intClassifier]!!).apply {
            arguments[0] = idExpr
            arguments[1] = irInt(startId)
          }
          // Combine with AND
          irCall(symbols.irBuiltIns.andandSymbol).apply {
            arguments[0] = geOp
            arguments[1] = leOp
          }
        }

        // Call the helper method
        val helperCall = irCall(helperMethod.symbol).apply {
          dispatchReceiver = irGet(switchingProviderClass.thisReceiver!!)
          arguments[0] = idExpr
          arguments[1] = graphExpr
        }

        branches += IrBranchImpl(
          UNDEFINED_OFFSET,
          UNDEFINED_OFFSET,
          condition = condition,
          result = helperCall
        )
      }

      // Default branch: throw error with the unknown id
      val errorMessage = IrStringConcatenationImpl(
        UNDEFINED_OFFSET,
        UNDEFINED_OFFSET,
        symbols.irBuiltIns.stringType
      ).apply {
        arguments.add(irString("Unknown SwitchingProvider id: "))
        arguments.add(idExpr)
      }

      branches += IrBranchImpl(
        UNDEFINED_OFFSET,
        UNDEFINED_OFFSET,
        condition = irTrue(),
        result = irInvoke(
          callee = symbols.stdlibErrorFunction,
          args = listOf(errorMessage)
        )
      )

      val whenExpr = irWhen(
        type = returnType,
        branches = branches
      )

      listOf(irReturn(whenExpr))
    }
  }

  /**
   * Generates a helper method that handles a chunk of switch cases.
   */
  private fun generateHelperMethod(
    switchingProviderClass: IrClass,
    chunkIndex: Int,
    startId: Int,
    endId: Int,
    chunkBindings: List<IrBinding>,
    returnType: IrType,
    graphClass: IrClass
  ) {
    // Create the helper method using the correct pattern
    val helperMethod = switchingProviderClass.addFunction {
      name = Name.identifier("invoke\$chunk$chunkIndex")
      visibility = DescriptorVisibilities.PRIVATE
      modality = Modality.FINAL
      this.returnType = returnType
    }

    // Add parameters: graph and id
    val graphParam = helperMethod.addValueParameter {
      name = Symbols.Names.graph
      type = graphClass.defaultType
    }

    val idParam = helperMethod.addValueParameter {
      name = Symbols.Names.id
      type = symbols.irBuiltIns.intType
    }

    // Add dispatch receiver
    helperMethod.setExtensionReceiver(switchingProviderClass.thisReceiver)

    // Build the method body with the subset of cases
    helperMethod.body = irFactory.createBlockBody(UNDEFINED_OFFSET, UNDEFINED_OFFSET).apply {
      val builder = createIrBuilder(helperMethod.symbol)
      val branches = mutableListOf<IrBranchImpl>()

      // Generate branches for this chunk's bindings
      chunkBindings.forEachIndexed { index, binding ->
        val actualId = startId + index
        val bindingExpr = generateBindingExpression(
          builder,
          binding,
          builder.irGet(graphParam),
          graphClass,
          switchingProviderClass
        )

        branches += IrBranchImpl(
          UNDEFINED_OFFSET,
          UNDEFINED_OFFSET,
          condition = builder.irEquals(builder.irGet(idParam), builder.irInt(actualId)),
          result = bindingExpr
        )
      }

      // Default case (should not happen if main dispatch is correct)
      val errorMessage = builder.run {
        IrStringConcatenationImpl(
          UNDEFINED_OFFSET,
          UNDEFINED_OFFSET,
          symbols.irBuiltIns.stringType
        ).apply {
          arguments.add(irString("Unexpected id in chunk $chunkIndex: "))
          arguments.add(irGet(idParam))
        }
      }

      branches += IrBranchImpl(
        UNDEFINED_OFFSET,
        UNDEFINED_OFFSET,
        condition = builder.irTrue(),
        result = builder.irInvoke(
          callee = symbols.stdlibErrorFunction,
          args = listOf(errorMessage)
        )
      )

      val whenExpr = builder.irWhen(
        type = returnType,
        branches = branches
      )

      statements += builder.irReturn(whenExpr)
    }
  }

  /**
   * Generates the expression for a single binding.
   * This is extracted from the original populateInvokeBody logic.
   */
  private fun generateBindingExpression(
    builder: IrBuilderWithScope,
    binding: IrBinding,
    graphExpr: IrExpression,
    graphClass: IrClass,
    switchingProviderClass: IrClass
  ): IrExpression = builder.run {
    // Strategy: Prefer existing provider fields over inline generation to avoid recursion

    // First, check bindingFieldContext for a provider field
    val providerField = bindingFieldContext?.providerField(binding.typeKey)

    if (providerField != null) {
      // Found a provider field in bindingFieldContext - use it with proper owner resolution
      // Check if this field might be in a shard
      val shardInfo = shardFieldRegistry?.findField(binding.typeKey)
      val owner = resolveOwnerForShard(switchingProviderClass, graphClass, shardInfo?.shardIndex)

      // Get the provider field and invoke it to get the instance
      val providerExpr = irGetField(owner, providerField)

      // Invoke the provider to get the instance
      irCall(symbols.providerInvoke).apply {
        dispatchReceiver = providerExpr
      }
    } else {
      // No provider field found - need to handle special cases or generate inline

      when (binding) {
        // BoundInstance must always be read from fields, never constructed inline
        is IrBinding.BoundInstance -> {
          // Try to resolve the field in order of preference:
          // 1. Instance field (most common for BoundInstance)
          // 2. Provider field (if caller expects a provider)
          // 3. Shard registry (if sharding is active)

          val instanceField = bindingFieldContext?.instanceField(binding.typeKey)
          val providerField = bindingFieldContext?.providerField(binding.typeKey)
          val shardInfo = shardFieldRegistry?.findField(binding.typeKey)

          when {
            instanceField != null -> {
              // Resolve the owner (could be main graph or shard)
              val owner = resolveOwnerForShard(switchingProviderClass, graphClass, shardInfo?.shardIndex)
              // Return the instance directly
              irGetField(owner, instanceField)
            }

            providerField != null -> {
              // If we have a provider field, use it and invoke
              val owner = resolveOwnerForShard(switchingProviderClass, graphClass, shardInfo?.shardIndex)
              // Get the provider and invoke it
              val providerExpr = irGetField(owner, providerField)
              irCall(symbols.providerInvoke).apply {
                dispatchReceiver = providerExpr
              }
            }

            shardInfo != null -> {
              // Only shard registry has the field
              val owner = resolveOwnerForShard(switchingProviderClass, graphClass, shardInfo.shardIndex)
              irGetField(owner, shardInfo.field)
            }

            else -> {
              error("BoundInstance must have a field (instance or provider): ${binding.typeKey}")
            }
          }
        }

        is IrBinding.GraphDependency -> {
          // GraphDependency should read from the appropriate field or call getter
          // NEVER call inline or constructor paths for GraphDependency
          when {
            binding.fieldAccess != null -> {
              // Use safeGetField for proper cross-shard access
              // First try to get the instance field
              val instanceField = bindingFieldContext?.instanceField(binding.typeKey)
              val providerField = bindingFieldContext?.providerField(binding.typeKey)

              when {
                instanceField != null -> {
                  // Check if field might be in a shard
                  val shardInfo = shardFieldRegistry?.findField(binding.typeKey)
                  val owner = resolveOwnerForShard(switchingProviderClass, graphClass, shardInfo?.shardIndex)

                  // Use expressionGenerator's safeGetField if available for proper cross-shard handling
                  if (expressionGenerator != null) {
                    expressionGenerator.safeGetField(owner, instanceField, binding.typeKey)
                  } else {
                    // Fallback to direct field access
                    irGetField(owner, instanceField)
                  }
                }

                providerField != null -> {
                  // We have a provider field - get it and invoke if instance is needed
                  val shardInfo = shardFieldRegistry?.findField(binding.typeKey)
                  val owner = resolveOwnerForShard(switchingProviderClass, graphClass, shardInfo?.shardIndex)

                  val providerExpr = if (expressionGenerator != null) {
                    expressionGenerator.safeGetField(owner, providerField, binding.typeKey)
                  } else {
                    irGetField(owner, providerField)
                  }

                  // Invoke the provider to get the instance
                  irCall(symbols.providerInvoke).apply {
                    dispatchReceiver = providerExpr
                  }
                }

                else -> {
                  error("GraphDependency with fieldAccess must have field: ${binding.typeKey}")
                }
              }
            }

            binding.getter != null -> {
              // Build irInvoke(getter) using the resolved graph or included graph instance
              // For getters, we just use the passed graphExpr since it's fresh in helper methods
              // and we don't need shard resolution for getters (always on main graph)
              val getterResult = irCall(binding.getter).apply {
                // Getters are always on the main graph or included graph
                dispatchReceiver = graphExpr
              }

              // Check if we need to unwrap provider/lazy
              val returnType = binding.getter.returnType
              when {
                // If getter returns Provider<T>, invoke it to get T
                returnType.isProvider() -> {
                  irCall(symbols.providerInvoke).apply {
                    dispatchReceiver = getterResult
                  }
                }

                // If getter returns Lazy<T>, get value to get T
                returnType.isLazy() -> {
                  irCall(symbols.lazyGetValue).apply {
                    dispatchReceiver = getterResult
                  }
                }

                // Otherwise return the direct result
                else -> getterResult
              }
            }

            else -> {
              error("GraphDependency must have either fieldAccess or getter")
            }
          }
        }

        else -> {
          // Only allow inline generation for safe binding types
          // All other types must be resolved via fields to avoid unsupported binding errors
          val canGenerateInline = when (binding) {
            is IrBinding.ConstructorInjected -> !binding.isAssisted // Non-assisted only
            is IrBinding.Provided -> true
            is IrBinding.ObjectClass -> true
            else -> false // Alias, Assisted, MembersInjected, Multibinding, etc. require fields
          }

          if (canGenerateInline) {
            // Safe to generate inline with bypassProviderFor to prevent recursion
            expressionGenerator?.generateBindingCode(
              binding = binding,
              contextualTypeKey = binding.contextualTypeKey,
              accessType = IrGraphExpressionGenerator.AccessType.INSTANCE,
              fieldInitKey = null,
              bypassProviderFor = binding.typeKey  // Prevent re-routing through SwitchingProvider
            ) ?: error("ExpressionGenerator is required for inline generation")
          } else {
            // These binding types require field resolution
            // Try to find the field in bindingFieldContext or shardFieldRegistry
            val instanceField = bindingFieldContext?.instanceField(binding.typeKey)
            val shardInfo = shardFieldRegistry?.findField(binding.typeKey)

            when {
              instanceField != null -> {
                // Found instance field - use it with proper owner resolution
                val owner = resolveOwnerForShard(switchingProviderClass, graphClass, shardInfo?.shardIndex)
                irGetField(owner, instanceField)
              }

              shardInfo != null -> {
                // Field exists in shard registry
                val owner = resolveOwnerForShard(switchingProviderClass, graphClass, shardInfo.shardIndex)
                irGetField(owner, shardInfo.field)
              }

              else -> {
                // No field found - this is an error for unsupported inline types
                error("Binding type ${binding::class.simpleName} requires a field but none found: ${binding.typeKey}")
              }
            }
          }
        }
      }
    }
  }
}
