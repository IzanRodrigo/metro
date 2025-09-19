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
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrBranch
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrBranchImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrInstanceInitializerCallImpl
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.constructors
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

    // Add outer class reference field (this is created automatically for inner classes)
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
      isInner = true // Inner class to access shard fields
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
    // For inner class constructors, we need to handle the dispatch receiver properly
    // The dispatch receiver represents the outer class instance

    // Add parameters to the constructor
    // Note: For inner classes, the dispatch receiver is handled differently in K2
    val parameters = mutableListOf<IrValueParameter>()

    // Add outer class parameter for inner class constructor
    val outerParam = constructor.addValueParameter {
      name = "\$outer".asName()
      type = shardClass.defaultType
      origin = Origins.Default
    }
    parameters.add(outerParam)

    // Add id parameter
    val idParam = constructor.addValueParameter {
      name = Symbols.Names.id
      type = irBuiltIns.intType
      origin = Origins.Default
    }
    parameters.add(idParam)

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

      // Set outer class field using the class's this receiver
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
      name = "get".asName()
      returnType = irBuiltIns.anyNType
      modality = Modality.OPEN
      visibility = DescriptorVisibilities.PUBLIC
    }

    // Override the Provider.get() method
    getMethod.overriddenSymbols = listOf(symbols.providerInvoke)

    getMethod.body = createIrBuilder(getMethod.symbol).irBlockBody {
      // Generate switch on id
      val branches = mutableListOf<IrBranch>()

      // Use the class's thisReceiver instead of the method's dispatchReceiverParameter
      // since we're in an inner class context
      val thisReceiver = switchingProviderClass.thisReceiver!!

      // Filter to only process shardable bindings
      val validOrdinals = shard.bindingOrdinals.filter { ordinal ->
        shardingContext.plan.shardableBindings[ordinal]
      }

      validOrdinals.forEachIndexed { index, ordinal ->
        val condition = irEquals(
          irGetField(irGet(thisReceiver), idField),
          irInt(index)
        )

        val result = generateProviderCall(ordinal, thisReceiver)
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
          irCall(symbols.stdlibErrorFunction).apply {
            arguments[0] = irString("Invalid SwitchingProvider id")
          },
        )
      )

      +irReturn(irWhen(irBuiltIns.anyNType, branches))
    }
  }

  private fun IrBuilderWithScope.generateProviderCall(
    ordinal: Int,
    thisReceiver: IrValueParameter  // Change this parameter type
  ): IrExpression {
    val typeKey = getTypeKeyForOrdinal(ordinal)

    val field = bindingFieldContext.providerField(typeKey)
      ?: error("No provider field found for $typeKey in shard ${shard.index}")

    // Get the outer shard instance through the outerThisField
    val shardInstance = irGetField(irGet(thisReceiver), outerThisField)

    // Get the provider field from the shard
    val providerFieldAccess = irGetField(shardInstance, field)

    // Call get() on the provider
    return irCall(symbols.providerInvoke).apply {
      dispatchReceiver = providerFieldAccess
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
