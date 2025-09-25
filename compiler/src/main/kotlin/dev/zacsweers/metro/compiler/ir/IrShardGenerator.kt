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
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addField
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irDelegatingConstructorCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irSetField
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrInstanceInitializerCallImpl
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.constructors
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
  private val shardingContext: ShardingContext,
) : IrMetroContext by context {
  data class ShardClassResult(
    val shardClass: IrClass,
    val constructor: IrConstructor,
    val outerField: IrField,
    val outerParameter: IrValueParameter,
  )


  /**
   * Generates the nested shard class.
   * Following Dagger's pattern:
   * - static nested class (not inner)
   * - internal visibility for cross-shard access
   * - receives main graph instance and necessary modules in constructor
   *
   * @param initializeFieldsFunction Optional initialization function to call in constructor
   */
  fun generateShardClass(
    initializeFieldsFunction: IrFunction? = null,
  ): ShardClassResult {

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
    val outerField = shardClass.addField {
      name = Symbols.Names.graph
      type = parentClass.defaultType
      visibility = DescriptorVisibilities.INTERNAL
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

    // Add parameter for main graph
    val graphParameter = constructor.addValueParameter(Symbols.Names.graph, parentClass.defaultType)

    constructor.body = context.createIrBuilder(constructor.symbol).irBlockBody {
      // Call super constructor
      +irDelegatingConstructorCall(irBuiltIns.anyClass.owner.constructors.single())

      // Run instance initializer
      +IrInstanceInitializerCallImpl(
        startOffset = UNDEFINED_OFFSET,
        endOffset = UNDEFINED_OFFSET,
        classSymbol = shardClass.symbol,
        type = shardClass.defaultType
      )

      val thisReceiver = shardClass.thisReceiver!!

      // Store outer graph reference
      +irSetField(
        irGet(thisReceiver),
        outerField,
        irGet(graphParameter)
      )

      // Invoke the initializeFields helper if provided
      if (initializeFieldsFunction != null) {
        +irCall(initializeFieldsFunction.symbol).apply {
          dispatchReceiver = irGet(thisReceiver)
        }
      }
    }

    return ShardClassResult(
      shardClass = shardClass,
      constructor = constructor,
      outerField = outerField,
      outerParameter = graphParameter,
    )
  }
}
