// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import org.jetbrains.kotlin.ir.declarations.IrField

/**
 * Tracks ownership for provider/instance fields across the main graph and shard classes.
 * Enhanced to support the Dagger-inspired approach for fixing field parent metadata issues.
 */
internal class FieldOwnershipRegistry {

  private val mainGraphFields = mutableMapOf<IrTypeKey, IrField>()
  private val shardFields = mutableMapOf<Int, MutableMap<IrTypeKey, IrField>>()
  private val shardByKey = mutableMapOf<IrTypeKey, Int>()
  private val crossShardAccessors = mutableMapOf<CrossShardKey, IrField>()

  fun registerMainGraphField(key: IrTypeKey, field: IrField) {
    mainGraphFields[key] = field
  }

  fun registerShardField(key: IrTypeKey, shardIndex: Int, field: IrField) {
    shardByKey[key] = shardIndex
    shardFields.getOrPut(shardIndex) { mutableMapOf() }[key] = field
  }

  /**
   * Register a field used for cross-shard access (e.g., a constructor parameter field)
   */
  fun registerCrossShardAccessor(fromShard: Int, toShard: Int, typeKey: IrTypeKey, field: IrField) {
    crossShardAccessors[CrossShardKey(fromShard, toShard, typeKey)] = field
  }

  fun getOwnership(key: IrTypeKey): FieldOwnership? {
    val shardIndex = shardByKey[key]
    if (shardIndex != null) {
      val field = shardFields[shardIndex]?.get(key)
      if (field != null) {
        return FieldOwnership.Shard(shardIndex, field)
      }
    }

    val mainField = mainGraphFields[key]
    if (mainField != null) {
      return FieldOwnership.MainGraph(mainField)
    }

    return null
  }

  fun getShardLocalFields(shardIndex: Int): Map<IrTypeKey, IrField> {
    return shardFields[shardIndex] ?: emptyMap()
  }

  fun getMainGraphField(key: IrTypeKey): IrField? = mainGraphFields[key]

  /**
   * Get a cross-shard accessor field if one exists
   */
  fun getCrossShardAccessor(fromShard: Int, toShard: Int, typeKey: IrTypeKey): IrField? {
    return crossShardAccessors[CrossShardKey(fromShard, toShard, typeKey)]
  }

  /**
   * Check if a field is local to a specific shard
   */
  fun isShardLocal(key: IrTypeKey, shardIndex: Int): Boolean {
    return shardByKey[key] == shardIndex
  }

  /**
   * Check if a field belongs to the main graph
   */
  fun isMainGraphField(key: IrTypeKey): Boolean {
    return mainGraphFields.containsKey(key)
  }

  /**
   * Generate a diagnostic report for debugging field ownership
   */
  fun generateOwnershipReport(): String {
    return buildString {
      appendLine("=== Field Ownership Report ===")
      appendLine("Main Graph Fields (${mainGraphFields.size}):")
      mainGraphFields.forEach { (key, _) ->
        appendLine("  $key")
      }
      appendLine()

      shardFields.forEach { (shardIndex, fields) ->
        appendLine("Shard $shardIndex Fields (${fields.size}):")
        fields.forEach { (key, _) ->
          appendLine("  $key")
        }
        appendLine()
      }

      if (crossShardAccessors.isNotEmpty()) {
        appendLine("Cross-Shard Accessors (${crossShardAccessors.size}):")
        crossShardAccessors.forEach { (key, _) ->
          appendLine("  Shard ${key.fromShard} -> Shard ${key.toShard}: ${key.typeKey}")
        }
      }
    }
  }

  data class CrossShardKey(
    val fromShard: Int,
    val toShard: Int,
    val typeKey: IrTypeKey
  )

  sealed class FieldOwnership {
    data class MainGraph(val field: IrField) : FieldOwnership()
    data class Shard(val shardIndex: Int, val field: IrField) : FieldOwnership()
  }
}
