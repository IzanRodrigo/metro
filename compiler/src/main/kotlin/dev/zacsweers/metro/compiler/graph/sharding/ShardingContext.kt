package dev.zacsweers.metro.compiler.graph.sharding

import dev.zacsweers.metro.compiler.ir.IrTypeKey
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrField
import java.util.*

// Shared context to pass between generators
internal class ShardingContext(
  val plan: ShardingPlan,
  val fieldRegistry: ShardFieldRegistry,
  val mainGraphClass: IrClass,
  val shardClasses: MutableList<IrClass> = mutableListOf(),
  val switchingProviders: MutableMap<Int, IrClass> = mutableMapOf(),
  val shardFields: MutableMap<Int, IrField> = mutableMapOf(),
  val mainGraphFields: MutableMap<IrTypeKey, IrField> = mutableMapOf(),
  val outerFields: MutableMap<Int, IrField> = mutableMapOf(), // Track outer reference fields for static nested shards
) {
  // Helper to get shard class by index
  fun getShardClass(index: Int): IrClass = shardClasses[index]

  // Helper to get shard field by index
  fun getShardField(index: Int): IrField? = shardFields[index]

  // Track which bindings have been processed
  private val processedBindings = BitSet()

  fun markProcessed(ordinal: Int) = processedBindings.set(ordinal)
  fun isProcessed(ordinal: Int) = processedBindings[ordinal]
}
