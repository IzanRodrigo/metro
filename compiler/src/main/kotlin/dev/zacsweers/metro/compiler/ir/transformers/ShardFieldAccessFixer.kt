// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.compiler.ir.generatedGraphExtensionData
import dev.zacsweers.metro.compiler.ir.rawTypeOrNull
import dev.zacsweers.metro.compiler.ir.thisReceiverOrFail
import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid

/**
 * Fixes child component field access expressions to correctly access parent fields that are in shards.
 *
 * This transformer is applied after parent component sharding completes. It rewrites child component
 * expressions that access parent fields from:
 *   `parent.fieldInShard`
 * to:
 *   `rootGraph.shardNInstance.fieldInShard`
 *
 * For nested graph extensions, it uses the rootGraph field to access root shards directly,
 * avoiding intermediate parents that don't have shards.
 *
 * The timing issue this fixes:
 * 1. Child expressions are generated BEFORE parent sharding
 * 2. At that time, BindingFieldContext shows Owner.Root for all fields
 * 3. Child expressions are generated as `parent.field` (incorrect)
 * 4. Parent sharding moves fields to shards (field.parent = shardClass)
 * 5. This transformer post-processes child expressions to fix the access pattern
 */
internal class ShardFieldAccessFixer(
  private val rootGraphWithShards: IrClass,
  private val builder: DeclarationIrBuilder,
  private val currentComponent: IrClass,
) : IrElementTransformerVoidWithContext() {

  /**
   * Maps shard class to its instance field in the root graph.
   * Lazy-initialized on first use.
   */
  private val shardInstanceFields: Map<IrClass, IrField> by lazy {
    buildMap {
      for (declaration in rootGraphWithShards.declarations) {
        if (declaration is IrField) {
          // Check if this is a shard instance field (field type is a nested shard class)
          val shardClass = (declaration.type as? org.jetbrains.kotlin.ir.types.IrSimpleType)?.classifier?.owner as? IrClass
          if (shardClass != null &&
              shardClass.parent == rootGraphWithShards &&
              shardClass.name.asString().startsWith("Shard")) {
            put(shardClass, declaration)
          }
        }
      }
    }
  }

  override fun visitGetField(expression: IrGetField): IrExpression {
    val field = expression.symbol.owner
    val fieldParent = field.parent as? IrClass

    // Check if this field is in a shard class of the root graph
    if (fieldParent != null &&
        fieldParent.parent == rootGraphWithShards &&
        fieldParent.name.asString().startsWith("Shard")) {

      // Find the shard instance field in the root graph
      val shardInstanceField = shardInstanceFields[fieldParent]

      if (shardInstanceField != null) {
        val receiver = expression.receiver

        if (receiver != null) {
          // Transform the receiver recursively first
          val transformedReceiver = receiver.transform(this, null)

          // Check if already accessing a shard (double-transform prevention)
          if (transformedReceiver is IrGetField) {
            val receiverField = transformedReceiver.symbol.owner
            if (receiverField.name.asString().contains("Instance") &&
                receiverField.name.asString().startsWith("shard")) {
              // Already accessing a shard - don't double-transform
              return super.visitGetField(expression)
            }
          }

          // If the receiver is already the shard instance, there's nothing to rewrite
          if (transformedReceiver.type.rawTypeOrNull() == fieldParent) {
            return super.visitGetField(expression)
          }

          // Simply add shard access between the receiver and the field
          // Don't create new receivers - use the existing transformed receiver
          val shardInstance = builder.irGetField(transformedReceiver, shardInstanceField)
          return builder.irGetField(shardInstance, field)
        }
      }
    }

    return super.visitGetField(expression)
  }
}
