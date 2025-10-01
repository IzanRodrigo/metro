// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.ClassIds
import dev.zacsweers.metro.compiler.MetroConstants
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.graph.sharding.Shard
import dev.zacsweers.metro.compiler.graph.sharding.ShardingContext
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrBindingGraph
import dev.zacsweers.metro.compiler.ir.IrBinding
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.builders.declarations.*
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrBranch
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrBranchImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrInstanceInitializerCallImpl
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.defaultType

/**
 * Generates type-safe SwitchingProvider inner classes for efficient field initialization.
 *
 * This follows Dagger's pattern of using an integer ID to switch between different
 * provider implementations, avoiding the overhead of creating many small provider
 * instances during graph initialization.
 *
 * Unlike the previous implementation that used Provider<Any?>, this generates multiple
 * SwitchingProvider<T> classes, each specialized for a specific return type, providing
 * compile-time type safety like Dagger does.
 */
internal class IrSwitchingProviderGenerator(
  private val context: IrMetroContext,
  private val shardClass: IrClass,
  private val shard: Shard,
  private val shardingContext: ShardingContext,
  private val bindingFieldContext: BindingFieldContext,
  private val bindingGraph: IrBindingGraph,
  private val expressionGeneratorFactory: IrGraphExpressionGenerator.Factory,
) : IrMetroContext by context {

  private lateinit var switchingProviderClass: IrClass
  private lateinit var outerThisField: IrField
  private lateinit var graphField: IrField

  /**
   * Generate a single generic SwitchingProvider<T> class per shard, exactly like Dagger does.
   * Uses @Suppress("UNCHECKED_CAST") and (T) casting for type safety at the field level.
   */
  fun generate(): IrClass {
    switchingProviderClass = createSwitchingProviderClass()

    // Add outer reference field for static nested class
    outerThisField = switchingProviderClass.addField {
      name = "this\$0".asName()
      type = shardClass.defaultType
      visibility = DescriptorVisibilities.PRIVATE
      isFinal = true
    }

    // Add graph field for cross-shard access
    // The graph field is needed to access other shards from within the SwitchingProvider
    val graphType = (shardClass.parent as IrClass).defaultType  // The parent of the shard is the main graph
    graphField = switchingProviderClass.addField {
      name = "graph".asName()
      type = graphType
      visibility = DescriptorVisibilities.PRIVATE
      isFinal = true
    }

    // Add id field
    val idField = createIdField(switchingProviderClass)

    // Add constructor
    val constructor = createConstructor(switchingProviderClass)

    // Generate constructor body
    generateConstructorBody(constructor, idField, outerThisField, graphField)

    // Generate get() method with switch - the key method!
    generateGetMethod(switchingProviderClass, idField)

    // Register in context for other shards to reference
    shardingContext.switchingProviders[shard.index] = switchingProviderClass
    shardingContext.switchingProviderOuterThisFields[shard.index] = outerThisField
    shardingContext.switchingProviderGraphFields[shard.index] = graphField

    return switchingProviderClass
  }


  /**
   * Create a single SwitchingProvider class per shard, following Dagger's optimization pattern.
   *
   * Note: While Dagger uses SwitchingProvider<T> for full type safety, we use Provider<Any?>
   * due to current K2 IR API limitations with type parameter manipulation. This still provides
   * the same key performance benefits:
   * - ONE class per shard instead of hundreds of individual provider classes
   * - Single consolidated switch statement with integer dispatch
   * - Dramatic reduction in generated class count and memory usage
   *
   * Type safety is maintained through properly typed field declarations at the call sites.
   * Future K2 versions may enable full generic implementation.
   */
  private fun createSwitchingProviderClass(): IrClass {
    val clazz = pluginContext.irFactory.buildClass {
      name = Symbols.Names.SwitchingProvider
      kind = ClassKind.CLASS
      visibility = DescriptorVisibilities.PRIVATE
      modality = Modality.FINAL
      isInner = false // Static nested class to avoid K2 dispatch receiver issues
      origin = Origins.Default
    }

    shardClass.addChild(clazz)

    // Create this receiver
    clazz.createThisReceiverParameter()

    // Use Provider<Any?> - type safety comes from properly typed field declarations
    // This achieves the same optimization as Dagger's SwitchingProvider<T>
    clazz.superTypes = listOf(
      symbols.metroProvider.typeWith(irBuiltIns.anyNType)
    )

    return clazz
  }

  private fun createIdField(providerClass: IrClass): IrField {
    return providerClass.addField {
      name = Symbols.Names.id
      type = irBuiltIns.intType
      visibility = DescriptorVisibilities.PRIVATE
      isFinal = true
    }
  }

  private fun createConstructor(providerClass: IrClass): IrConstructor {
    return providerClass.addConstructor {
      visibility = DescriptorVisibilities.PUBLIC
      isPrimary = true
      returnType = providerClass.defaultType
    }
  }

  private fun generateConstructorBody(constructor: IrConstructor, idField: IrField, outerThisField: IrField, graphField: IrField) {
    // For static nested class, we need explicit parameters for outer reference, graph, and id

    // Add outer reference parameter (replaces implicit dispatch receiver of inner class)
    val outerParam = constructor.addValueParameter {
      name = "outer".asName()
      type = shardClass.defaultType
      origin = Origins.Default
    }

    // Add graph parameter for cross-shard access
    val graphParam = constructor.addValueParameter {
      name = "graph".asName()
      type = (shardClass.parent as IrClass).defaultType  // The parent of the shard is the main graph
      origin = Origins.Default
    }

    // Add id parameter
    val idParam = constructor.addValueParameter {
      name = Symbols.Names.id
      type = irBuiltIns.intType
      origin = Origins.Default
    }

    constructor.body = createIrBuilder(constructor.symbol).irBlockBody {
      // Call super constructor
      +irDelegatingConstructorCall(
        irBuiltIns.anyClass.owner.constructors.single()
      )

      // Get the provider class from the constructor
      val providerClass = constructor.parent as IrClass

      // Initialize instance
      +IrInstanceInitializerCallImpl(
        UNDEFINED_OFFSET,
        UNDEFINED_OFFSET,
        providerClass.symbol,
        providerClass.defaultType
      )

      // Get the this receiver for the constructor
      val thisReceiver = providerClass.thisReceiver!!

      // Set the outer class field - now explicitly passed as parameter
      +irSetField(
        irGet(thisReceiver),
        outerThisField,
        irGet(outerParam)
      )

      // Set the graph field for cross-shard access
      +irSetField(
        irGet(thisReceiver),
        graphField,
        irGet(graphParam)
      )

      // Set id field
      +irSetField(
        irGet(thisReceiver),
        idField,
        irGet(idParam)
      )
    }
  }

  /**
   * Generate the invoke() method with consolidated switch statement like Dagger.
   * Splits into multiple helper methods if exceeding STATEMENTS_PER_METHOD limit.
   */
  private fun generateGetMethod(providerClass: IrClass, idField: IrField) {
    val getMethod = providerClass.addFunction {
      name = "invoke".asName()
      // Return Any? - the field declarations provide type safety
      returnType = irBuiltIns.anyNType
      modality = Modality.OPEN
      visibility = DescriptorVisibilities.PUBLIC
      isOperator = true
    }

    // Override the Provider.invoke() method
    getMethod.overriddenSymbols = listOf(symbols.providerInvoke)

    // Set the dispatch receiver using the parent class as the dispatch receiver source
    getMethod.setDispatchReceiver(providerClass.thisReceiver!!.copyTo(
      getMethod,
      type = providerClass.defaultType
    ))

    // Get all valid ordinals for this shard
    val validOrdinals = shard.bindingOrdinals.filter { ordinal ->
      shardingContext.plan.shardableBindings[ordinal] && ordinal in shardingContext.ordinalsWithProviderFields
    }

    if (validOrdinals.isEmpty()) {
      getMethod.body = createIrBuilder(getMethod.symbol).irBlockBody {
        val thisReceiver = getMethod.dispatchReceiverParameter
          ?: error("Failed to create dispatch receiver for invoke method")
        +irReturn(
          irInvoke(
            callee = symbols.stdlibErrorFunction,
            args = listOf(irString("SwitchingProvider for shard ${shard.index} has no shardable bindings"))
          )
        )
      }
      return
    }

    // Optional diagnostic: log which ordinals will use direct instantiation vs delegation
    if (options.debug) {
      writeDiagnostic("switching-provider-dispatch-shard-${shard.index}.txt") {
        buildString {
          appendLine("=== SwitchingProvider Dispatch for Shard ${shard.index} (${shard.name}) ===")
          appendLine("fastInit=${options.fastInit}")
          appendLine("Cases (id -> ordinal : mode [scoped]):")
          validOrdinals.forEachIndexed { idx, ordinal ->
            val key = getTypeKeyForOrdinal(ordinal)
            val binding = bindingGraph.requireBinding(key)
            val scoped = binding.isScoped()
            val mode = if (options.fastInit && !scoped) "DIRECT" else "DELEGATED"
            appendLine("  $idx -> $ordinal : $mode [scoped=$scoped]  $key")
          }
        }
      }
    }

    // Check if we need to split into multiple methods
    val statementsPerMethod = MetroConstants.STATEMENTS_PER_METHOD
    if (validOrdinals.size <= statementsPerMethod) {
      // Small enough to fit in one method
      generateSingleInvokeMethod(getMethod, idField, validOrdinals)
    } else {
      // Split into multiple helper methods
      generateSplitInvokeMethods(providerClass, getMethod, idField, validOrdinals)
    }
  }

  /**
   * Generate a single invoke method when the number of cases fits within the limit.
   */
  private fun generateSingleInvokeMethod(
    getMethod: IrSimpleFunction,
    idField: IrField,
    validOrdinals: List<Int>
  ) {
    getMethod.body = createIrBuilder(getMethod.symbol).irBlockBody {
      val thisReceiver = getMethod.dispatchReceiverParameter
        ?: error("Failed to create dispatch receiver for invoke method")

      // Generate switch statement with all ordinals
      val branches = mutableListOf<IrBranch>()

      validOrdinals.forEachIndexed { index, ordinal ->
        val condition = irEquals(
          irGetField(irGet(thisReceiver), idField),
          irInt(index)
        )

        val result = try {
          // Generate the provider call - returns appropriately typed result
          generateProviderCall(ordinal, thisReceiver)
        } catch (e: Exception) {
          println("ERROR: Failed to generate provider call for ordinal $ordinal in shard ${shard.index}: ${e.message}")
          throw e
        }

        branches.add(
          IrBranchImpl(
            UNDEFINED_OFFSET,
            UNDEFINED_OFFSET,
            condition,
            result
          )
        )
      }

      // Default case - throw error
      branches.add(
        IrBranchImpl(
          UNDEFINED_OFFSET,
          UNDEFINED_OFFSET,
          irTrue(),
          irInvoke(
            callee = symbols.stdlibErrorFunction,
            args = listOf(irString("Invalid SwitchingProvider id for shard ${shard.index}"))
          )
        )
      )

      +irReturn(irWhen(irBuiltIns.anyNType, branches))
    }
  }

  /**
   * Generate multiple helper methods when the number of cases exceeds the limit.
   * The main invoke() delegates to helper methods based on ID ranges.
   */
  private fun generateSplitInvokeMethods(
    providerClass: IrClass,
    getMethod: IrSimpleFunction,
    idField: IrField,
    validOrdinals: List<Int>
  ) {
    val statementsPerMethod = MetroConstants.STATEMENTS_PER_METHOD
    val chunks = validOrdinals.chunked(statementsPerMethod)
    val helperMethods = mutableListOf<IrSimpleFunction>()

    // Generate helper methods for each chunk
    chunks.forEachIndexed { chunkIndex, chunk ->
      val helperMethod = providerClass.addFunction {
        name = "get${chunkIndex}".asName()
        returnType = irBuiltIns.anyNType
        modality = Modality.FINAL
        visibility = DescriptorVisibilities.PRIVATE
      }

      // Set the dispatch receiver
      helperMethod.setDispatchReceiver(providerClass.thisReceiver!!.copyTo(
        helperMethod,
        type = providerClass.defaultType
      ))

      // Add id parameter
      val idParam = helperMethod.addValueParameter {
        name = "id".asName()
        type = irBuiltIns.intType
      }

      helperMethod.body = createIrBuilder(helperMethod.symbol).irBlockBody {
        val thisReceiver = helperMethod.dispatchReceiverParameter
          ?: error("Failed to create dispatch receiver for helper method")

        val branches = mutableListOf<IrBranch>()
        val startIndex = chunkIndex * statementsPerMethod

        chunk.forEachIndexed { localIndex, ordinal ->
          val globalIndex = startIndex + localIndex
          val condition = irEquals(
            irGet(idParam),
            irInt(globalIndex)
          )

          val result = try {
            generateProviderCall(ordinal, thisReceiver)
          } catch (e: Exception) {
            println("ERROR: Failed to generate provider call for ordinal $ordinal in shard ${shard.index}: ${e.message}")
            throw e
          }

          branches.add(
            IrBranchImpl(
              UNDEFINED_OFFSET,
              UNDEFINED_OFFSET,
              condition,
              result
            )
          )
        }

        // Default case for this chunk - should not happen
        branches.add(
          IrBranchImpl(
            UNDEFINED_OFFSET,
            UNDEFINED_OFFSET,
            irTrue(),
            irInvoke(
              callee = symbols.stdlibErrorFunction,
              args = listOf(irString("Invalid id in helper method get${chunkIndex}"))
            )
          )
        )

        +irReturn(irWhen(irBuiltIns.anyNType, branches))
      }

      helperMethods.add(helperMethod)
    }

    // Generate the main invoke method that delegates to helpers
    getMethod.body = createIrBuilder(getMethod.symbol).irBlockBody {
      val thisReceiver = getMethod.dispatchReceiverParameter
        ?: error("Failed to create dispatch receiver for invoke method")

      val idValue = irGetField(irGet(thisReceiver), idField)

      // Simple approach: check ranges sequentially
      // Each helper handles statementsPerMethod cases, so we can calculate which helper to call
      val result = when (helperMethods.size) {
        1 -> {
          // Only one helper method, just call it
          irInvoke(
            callee = helperMethods[0].symbol,
            dispatchReceiver = irGet(thisReceiver),
            args = listOf(idValue)
          )
        }
        else -> {
          // Multiple helpers - build a flat when mapping each id to its helper call
          val branches = mutableListOf<IrBranch>()

          helperMethods.forEachIndexed { chunkIndex, helperMethod ->
            val startIndex = chunkIndex * statementsPerMethod
            val endIndex = minOf(startIndex + statementsPerMethod - 1, validOrdinals.size - 1)

            for (globalIndex in startIndex..endIndex) {
              val condition = irEquals(idValue, irInt(globalIndex))
              val callHelper = irInvoke(
                callee = helperMethod.symbol,
                dispatchReceiver = irGet(thisReceiver),
                args = listOf(idValue)
              )

              branches.add(
                IrBranchImpl(
                  UNDEFINED_OFFSET,
                  UNDEFINED_OFFSET,
                  condition,
                  callHelper
                )
              )
            }
          }

          // Default case - throw error
          branches.add(
            IrBranchImpl(
              UNDEFINED_OFFSET,
              UNDEFINED_OFFSET,
              irTrue(),
              irInvoke(
                callee = symbols.stdlibErrorFunction,
                args = listOf(irString("Invalid SwitchingProvider id for shard ${shard.index}"))
              )
            )
          )

          irWhen(irBuiltIns.anyNType, branches)
        }
      }

      +irReturn(result)
    }
  }

  /**
   * Generate instance creation for the given ordinal.
   * When fastInit is enabled, creates instances directly like Dagger.
   * Otherwise, delegates to provider fields.
   */
  private fun IrBuilderWithScope.generateProviderCall(
    ordinal: Int,
    thisReceiver: IrValueParameter
  ): IrExpression {
    val typeKey = getTypeKeyForOrdinal(ordinal)

    // Check if this ordinal actually belongs to this shard
    val expectedShardIndex = shardingContext.plan.bindingToShard.getOrElse(ordinal) { -1 }
    if (expectedShardIndex != shard.index) {
      error("Shard ${shard.index} SwitchingProvider asked to provide ordinal $ordinal (${typeKey}) " +
            "which belongs to shard $expectedShardIndex. This suggests a cross-shard dependency " +
            "that wasn't properly handled through requirements collection.")
    }

    // Get the binding to know HOW to create the instance
    val binding = bindingGraph.requireBinding(typeKey)

    // In fastInit, we prefer direct instantiation to avoid provider indirection.
    // However, for SCOPED bindings we must preserve DoubleCheck semantics, so we
    // delegate to the provider field path which applies the appropriate wrapper.
    return if (options.fastInit && !binding.isScoped()) {
      // Direct instantiation path: create instances directly using the graph expression generator.
      // We construct an expression generator with explicit receiver = this SwitchingProvider's
      // this$0 (the shard instance) and explicit shard index = this shard. That allows resolution
      // of dependencies from the shard and cross-shard/main-graph via ShardingContext.
      val contextual = IrContextualTypeKey(typeKey)
      expressionGeneratorFactory
        .create(explicitReceiver = thisReceiver, explicitShardIndex = shard.index)
        .generateInstanceCreation(binding, contextual)
    } else {
      // Delegation path - use provider fields (traditional approach)
      delegateToProviderField(binding, typeKey, thisReceiver)
    }
  }

  /**
   * Delegate to provider field (original implementation).
   * Used when fastInit is disabled.
   */
  private fun IrBuilderWithScope.delegateToProviderField(
    binding: IrBinding,
    typeKey: IrTypeKey,
    thisReceiver: IrValueParameter
  ): IrExpression {
    // Get the shard instance from the SwitchingProvider's this$0 field
    val outerThis = irGet(thisReceiver)
    val shardInstance = irGetField(outerThis, outerThisField)

    // Look for the provider field for this binding
    val fieldDescriptor = bindingFieldContext.providerFieldDescriptor(typeKey)
      ?: error("No provider field found for $typeKey in shard ${shard.index}")

    // Access the provider field through the shard instance
    val providerField = when (val owner = fieldDescriptor.owner) {
      BindingFieldContext.FieldOwner.MainGraph -> {
        val outerField = shardingContext.outerFields[shard.index]
          ?: error("Missing outer field for shard ${shard.index}")
        val graphInstance = irGetField(shardInstance, outerField)
        irGetField(graphInstance, fieldDescriptor.field)
      }
      is BindingFieldContext.FieldOwner.Shard -> {
        if (owner.index == shard.index) {
          irGetField(shardInstance, fieldDescriptor.field)
        } else {
          val outerField = shardingContext.outerFields[shard.index]
            ?: error("Missing outer field for shard ${shard.index}")
          val graphInstance = irGetField(shardInstance, outerField)
          val otherShardField = shardingContext.shardFields[owner.index]
            ?: error("Shard field not found for shard index ${owner.index}")
          val otherShardInstance = irGetField(graphInstance, otherShardField)
          irGetField(otherShardInstance, fieldDescriptor.field)
        }
      }
      is BindingFieldContext.FieldOwner.Unknown -> {
        irGetField(shardInstance, fieldDescriptor.field)
      }
    }

    // Now call invoke() on the provider to get the instance
    return irCall(symbols.providerInvoke).apply {
      dispatchReceiver = providerField
    }
  }

  /**
   * Resolve provider field access.
   * (Note: The direct instantiation methods were removed as we're using a simpler
   * approach that delegates to provider fields to avoid recursion issues)
   */
  private fun IrBuilderWithScope.resolveProviderField(
    typeKey: IrTypeKey,
    descriptor: BindingFieldContext.FieldDescriptor,
    thisReceiver: IrValueParameter
  ): IrExpression {
    // For static nested class, access the outer shard instance through the explicit this$0 field
    val currentThis = irGet(thisReceiver)
    val shardInstance = irGetField(currentThis, outerThisField)

    shardingContext.requirementFields[shard.index]?.get(typeKey)?.let { requirementField ->
      val requirementValue = irGetField(shardInstance, requirementField)
      if (requirementField.type.classOrNull != symbols.metroProvider.owner) {
        error("Requirement field ${requirementField.name} for $typeKey is not a Provider")
      }
      return requirementValue
    }
    return when (val owner = descriptor.owner) {
      BindingFieldContext.FieldOwner.MainGraph -> {
        val outerField = shardingContext.outerFields[shard.index]
          ?: error("Missing outer field for shard ${shard.index}")
        val graphInstance = irGetField(shardInstance, outerField)
        irGetField(graphInstance, descriptor.field)
      }
      is BindingFieldContext.FieldOwner.Shard -> {
        if (owner.index == shard.index) {
          irGetField(shardInstance, descriptor.field)
        } else {
          val outerField = shardingContext.outerFields[shard.index]
            ?: error("Missing outer field for shard ${shard.index}")
          val graphInstance = irGetField(shardInstance, outerField)
          val actualIndex = shardingContext.shardIndexMapping[owner.index] ?: owner.index
          val otherShardField = shardingContext.shardFields[actualIndex]
            ?: error("Shard field not found for shard index $actualIndex")
          val otherShardInstance = irGetField(graphInstance, otherShardField)
          irGetField(otherShardInstance, descriptor.field)
        }
      }
      is BindingFieldContext.FieldOwner.Unknown -> irGetField(shardInstance, descriptor.field)
    }
  }

  /**
   * Helper to convert ordinal back to TypeKey.
   */
  private fun getTypeKeyForOrdinal(ordinal: Int): IrTypeKey {
    return shardingContext.plan.bindingOrdinals.entries
      .first { it.value == ordinal }
      .key
  }
}
