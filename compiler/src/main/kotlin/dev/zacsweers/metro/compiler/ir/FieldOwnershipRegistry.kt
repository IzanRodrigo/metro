// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import org.jetbrains.kotlin.ir.declarations.IrField

/**
 * Tracks ownership for provider/instance fields across the main graph and shard classes.
 */
internal class FieldOwnershipRegistry {

  private val mainGraphFields = mutableMapOf<IrTypeKey, IrField>()
  private val shardFields = mutableMapOf<Int, MutableMap<IrTypeKey, IrField>>()
  private val shardByKey = mutableMapOf<IrTypeKey, Int>()

  fun registerMainGraphField(key: IrTypeKey, field: IrField) {
    mainGraphFields[key] = field
  }

  fun registerShardField(key: IrTypeKey, shardIndex: Int, field: IrField) {
    shardByKey[key] = shardIndex
    shardFields.getOrPut(shardIndex) { mutableMapOf() }[key] = field
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

  sealed class FieldOwnership {
    data class MainGraph(val field: IrField) : FieldOwnership()
    data class Shard(val shardIndex: Int, val field: IrField) : FieldOwnership()
  }
}
