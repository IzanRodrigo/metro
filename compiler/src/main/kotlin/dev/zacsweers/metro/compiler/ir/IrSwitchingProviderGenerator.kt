// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.graph.sharding.Shard
import dev.zacsweers.metro.compiler.graph.sharding.ShardingContext
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.builders.declarations.*
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrBranch
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrBranchImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrInstanceInitializerCallImpl
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.defaultType

/**
 * Generates a SwitchingProvider inner class for efficient field initialization.
 *
 * This follows Dagger's pattern of using an integer ID to switch between different
 * provider implementations, avoiding the overhead of creating many small provider
 * instances during graph initialization.
 */
internal class IrSwitchingProviderGenerator(
  private val context: IrMetroContext,
  private val shardClass: IrClass,
  private val shard: Shard,
  private val shardingContext: ShardingContext,
  private val bindingFieldContext: BindingFieldContext,
) : IrMetroContext by context {

  private lateinit var switchingProviderClass: IrClass
  private lateinit var outerThisField: IrField

  fun generate(): IrClass {
    switchingProviderClass = createSwitchingProviderClass()

    // For inner classes, we still need a field to hold the outer reference
    outerThisField = switchingProviderClass.addField {
      name = "this\$0".asName()
      type = shardClass.defaultType
      visibility = DescriptorVisibilities.PRIVATE
      isFinal = true
    }

    // Add id field
    val idField = createIdField(switchingProviderClass)

    // Add constructor
    val constructor = createConstructor(switchingProviderClass)

    // Generate constructor body
    generateConstructorBody(constructor, idField)

    // Generate get() method with switch
    generateGetMethod(switchingProviderClass, idField)

    // Register in context for other shards to reference
    shardingContext.switchingProviders[shard.index] = switchingProviderClass

    return switchingProviderClass
  }

  private fun createSwitchingProviderClass(): IrClass {
    val clazz = pluginContext.irFactory.buildClass {
      name = Symbols.Names.SwitchingProvider
      kind = ClassKind.CLASS
      visibility = DescriptorVisibilities.PRIVATE
      modality = Modality.FINAL
      isInner = false // Changed to static nested class to avoid K2 dispatch receiver issues
      origin = Origins.Default
    }

    shardClass.addChild(clazz)

    // Create this receiver
    clazz.createThisReceiverParameter()

    // Implement Provider<Object> interface
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

  private fun generateConstructorBody(constructor: IrConstructor, idField: IrField) {
    // For static nested class, we need explicit parameters for outer reference and id

    // Add outer reference parameter (replaces implicit dispatch receiver of inner class)
    val outerParam = constructor.addValueParameter {
      name = "outer".asName()
      type = shardClass.defaultType
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

      // Initialize instance
      +IrInstanceInitializerCallImpl(
        UNDEFINED_OFFSET,
        UNDEFINED_OFFSET,
        switchingProviderClass.symbol,
        switchingProviderClass.defaultType
      )

      // Get the this receiver for the constructor
      val thisReceiver = switchingProviderClass.thisReceiver!!

      // Set the outer class field - now explicitly passed as parameter
      +irSetField(
        irGet(thisReceiver),
        outerThisField,
        irGet(outerParam)
      )

      // Set id field
      +irSetField(
        irGet(thisReceiver),
        idField,
        irGet(idParam)
      )
    }
  }

  private fun generateGetMethod(providerClass: IrClass, idField: IrField) {
    val getMethod = providerClass.addFunction {
      name = "invoke".asName()
      returnType = irBuiltIns.anyNType
      modality = Modality.OPEN
      visibility = DescriptorVisibilities.PUBLIC
      isOperator = true
    }

    // Override the Provider.invoke() method
    getMethod.overriddenSymbols = listOf(symbols.providerInvoke)

    // For member functions, we need to explicitly add the dispatch receiver parameter
    // This is required by the new IR parameter API in K2
    val thisReceiver = getMethod.addValueParameter {
      name = "<this>".asName()
      type = providerClass.defaultType
      origin = Origins.Default
      kind = IrParameterKind.DispatchReceiver
    }

    getMethod.body = createIrBuilder(getMethod.symbol).irBlockBody {
      // Generate switch on id
      val branches = mutableListOf<IrBranch>()

      // For accessing 'this' in a static nested class method

      // Filter to only process shardable bindings that also have provider fields
      val validOrdinals = shard.bindingOrdinals.filter { ordinal ->
        shardingContext.plan.shardableBindings[ordinal] && ordinal in shardingContext.ordinalsWithProviderFields
      }

      // If there are no shardable bindings, return error immediately
      if (validOrdinals.isEmpty()) {
        +irReturn(
          irInvoke(
            callee = symbols.stdlibErrorFunction,
            args = listOf(irString("SwitchingProvider for shard ${shard.index} has no shardable bindings"))
          )
        )
        return@irBlockBody
      }

      validOrdinals.forEachIndexed { index, ordinal ->
        // Access id field using thisReceiver for static nested class
        val condition = irEquals(
          irGetField(irGet(thisReceiver), idField),
          irInt(index)
        )

        val result = try {
          generateProviderCall(ordinal, thisReceiver)
        } catch (e: Exception) {
          // Log the error for debugging
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

      // Default case (else branch) - should never happen but throw an error for safety
      branches.add(
        IrBranchImpl(
          UNDEFINED_OFFSET,
          UNDEFINED_OFFSET,
          irTrue(),
          irInvoke(
            callee = symbols.stdlibErrorFunction,
            // Note: We show the shard index in the error message since we can't easily convert
            // the runtime id to a string at compile time. The id will be visible in the stack trace.
            args = listOf(irString("Invalid SwitchingProvider id for shard ${shard.index}"))
          )
        )
      )

      +irReturn(irWhen(irBuiltIns.anyNType, branches))
    }
  }

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

    val descriptor = bindingFieldContext.providerFieldDescriptor(typeKey)
      ?: error("No provider field descriptor found for $typeKey in shard ${shard.index}")

    val providerFieldAccess = resolveProviderField(typeKey, descriptor, thisReceiver)

    return irCall(symbols.providerInvoke).apply {
      dispatchReceiver = providerFieldAccess
    }
  }


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
