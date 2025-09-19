// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.graph.sharding.Shard
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addField
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.primaryConstructor

/**
 * Generates IR for shard classes following Dagger's pattern.
 *
 * Shards are static nested classes within the main component that contain
 * a subset of the bindings to avoid method size limits.
 */
internal class IrShardGenerator(
  private val context: IrMetroContext,
  private val parentClass: IrClass,
  private val shard: Shard,
) : IrMetroContext by context {

  /**
   * Generates the nested shard class.
   * Following Dagger's pattern:
   * - static nested class (not inner)
   * - internal visibility for cross-shard access
   * - receives main graph instance and necessary modules in constructor
   *
   * @param initializeFieldsFunction Optional initialization function to call in constructor
   * @param moduleParameters List of module parameters that this shard requires
   */
  fun generateShardClass(
    initializeFieldsFunction: IrFunction? = null,
    moduleParameters: List<IrValueParameter> = emptyList()
  ): IrClass {

    // Create the shard class using the IR factory
    val shardClass = pluginContext.irFactory.buildClass {
      name = shard.name.asName()
      kind = ClassKind.CLASS
      visibility = DescriptorVisibilities.INTERNAL
      modality = Modality.FINAL
      origin = Origins.Default
    }

    // Make it a nested class of the parent component
    parentClass.addChild(shardClass)

    // Ensure thisReceiver is created
    if (shardClass.thisReceiver == null) {
      shardClass.createThisReceiverParameter()
    }

    // Add backing field for graph parameter
    val graphField = shardClass.addField {
      name = Symbols.Names.graph
      type = parentClass.defaultType
      visibility = DescriptorVisibilities.PRIVATE
      isFinal = true
      origin = Origins.Default
    }

    // Generate initializeFields method FIRST (if we have the function)
    // This ensures it exists before we reference it in the constructor
    if (initializeFieldsFunction != null) {
      // The function is already created, just ensure it's added to the class
      shardClass.addChild(initializeFieldsFunction)
    }

    // Add primary constructor
    val constructor = shardClass.addConstructor {
      visibility = DescriptorVisibilities.PUBLIC
      isPrimary = true
      returnType = shardClass.defaultType
    }

    // Add parameters for main graph and modules
    val graphParameter = constructor.addValueParameter(Symbols.Names.graph, parentClass.defaultType)

    // Add module parameters to constructor and create backing fields
    val moduleFields = mutableListOf<IrField>()
    val moduleCtorParams = mutableListOf<IrValueParameter>()
    for (moduleParam in moduleParameters) {
      // Add parameter to constructor
      val ctorParam = constructor.addValueParameter {
        name = moduleParam.name
        type = moduleParam.type
        origin = Origins.Default
      }
      moduleCtorParams.add(ctorParam)

      // Add backing field for the module
      val moduleField = shardClass.addField {
        name = moduleParam.name
        type = moduleParam.type
        visibility = DescriptorVisibilities.PRIVATE
        isFinal = true
        origin = Origins.Default
      }
      moduleFields.add(moduleField)
    }

    // TODO: Generate the rest of the code.

    return shardClass
  }

  /**
   * Generates a field in the parent class to hold this shard instance.
   * The field has internal visibility to allow cross-shard access.
   */
  private fun generateShardField(shardClass: IrClass): IrField {
    val fieldName = "shard${shard.index}"

    return parentClass.addField {
      name = fieldName.asName()
      type = shardClass.defaultType
      visibility = DescriptorVisibilities.INTERNAL
      isFinal = true
      origin = Origins.Default
    }
  }

  /**
   * Generates the initialization expression for this shard.
   * Creates a call to the shard constructor: ShardN(this, module1, module2, ...)
   */
  private fun generateShardInitialization(
    shardClass: IrClass,
    thisReceiver: IrValueParameter,
    moduleParameters: List<IrValueParameter>,
  ): IrExpression {
    return with(context.createIrBuilder(parentClass.symbol)) {
      val constructor = shardClass.primaryConstructor
        ?: error("Shard class missing primary constructor")

      // Build arguments list: main graph + module parameters
      val args = buildList {
        add(irGet(thisReceiver)) // Pass 'this' (the main graph) as first parameter
        moduleParameters.forEach { param ->
          add(irGet(param)) // Pass module parameters
        }
      }

      // Use irCall with arguments array instead of deprecated putValueArgument
      irCall(constructor.symbol).apply {
        args.forEachIndexed { index, arg ->
          arguments[index] = arg
        }
      }
    }
  }
}
