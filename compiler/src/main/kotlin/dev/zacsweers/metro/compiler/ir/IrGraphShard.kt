// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.NameAllocator
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.decapitalizeUS
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.name.Name

/**
 * Represents a shard of a graph that contains a subset of bindings.
 * This helps avoid "class too large" errors by distributing bindings across multiple classes.
 */
internal class IrGraphShard(
  override val metroContext: IrMetroContext,
  private val parentGraph: IrClass,
  private val shardName: Name,
  private val shardIndex: Int,
  private val bindings: List<IrBinding>,
) : IrMetroContext by metroContext {
  
  private val fieldNameAllocator = NameAllocator(mode = NameAllocator.Mode.COUNT)
  private val providerFields = mutableMapOf<IrTypeKey, IrField>()
  
  /**
   * The generated shard class
   */
  lateinit var shardClass: IrClass
    private set
  
  /**
   * Field in the parent graph that holds this shard instance
   */
  lateinit var shardField: IrField
    private set
  
  /**
   * Simplified generation - just create the class structure
   */
  fun generate() {
    // Create the shard class as a nested class in the graph
    shardClass = pluginContext.irFactory.buildClass {
      name = shardName
      visibility = DescriptorVisibilities.PRIVATE
      kind = ClassKind.CLASS
      origin = Origins.GraphShard
    }.apply {
      parent = parentGraph
    }
    
    // Add the shard class to the parent's declarations
    parentGraph.declarations.add(shardClass)
    
    // For now, just log that sharding is happening
    // Full implementation would create constructor, fields, etc.
  }
  
  /**
   * Generates an expression to access a binding in this shard from the parent graph
   */
  fun generateAccessorExpression(binding: IrBinding, parentReceiver: IrValueParameter): IrExpression {
    // For now, return a simple get expression
    // In full implementation, this would access the shard field and then the binding field
    return createIrBuilder(parentGraph.symbol).run {
      irGet(parentReceiver)
    }
  }
}