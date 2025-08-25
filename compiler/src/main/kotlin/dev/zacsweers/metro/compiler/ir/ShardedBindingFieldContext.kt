/*
 * Copyright (C) 2025 Zac Sweers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.zacsweers.metro.compiler.ir

import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction

/**
 * Extended binding field context that supports sharding fields across multiple classes.
 * Tracks which shard (helper class) contains each field for proper cross-shard access.
 */
internal class ShardedBindingFieldContext(
  existingContext: BindingFieldContext? = null
) : BindingFieldContext() {
  
  /**
   * Maps type keys to their containing shard class.
   * Null means the field is in the main graph class.
   */
  private val fieldShards = mutableMapOf<IrTypeKey, IrClass?>()
  
  init {
    // Copy existing fields from the previous context if provided
    existingContext?.let { existing ->
      // Copy all provider fields
      existing.availableProviderKeys.forEach { key ->
        existing.providerField(key)?.let { field ->
          super.putProviderField(key, field)
          // These are from the main graph, so shard is null
          fieldShards[key] = null
        }
      }
      
      // Copy all instance fields
      existing.availableInstanceKeys.forEach { key ->
        existing.instanceField(key)?.let { field ->
          super.putInstanceField(key, field)
        }
      }
    }
  }
  
  /**
   * Maps shard classes to their corresponding field in the main graph class.
   */
  private val shardFields = mutableMapOf<IrClass, IrField>()
  
  /**
   * Maps type keys to their getter methods in shard classes.
   */
  private val fieldGetters = mutableMapOf<IrTypeKey, IrSimpleFunction>()
  
  /**
   * Registers a shard class and its corresponding field in the main graph.
   */
  fun registerShard(shardClass: IrClass, fieldInMainGraph: IrField) {
    shardFields[shardClass] = fieldInMainGraph
  }
  
  /**
   * Registers a getter method for a field in a shard.
   */
  fun registerGetterMethod(key: IrTypeKey, getter: IrSimpleFunction) {
    fieldGetters[key] = getter
  }
  
  /**
   * Puts a provider field and tracks which shard it belongs to.
   */
  fun putProviderField(key: IrTypeKey, field: IrField, shard: IrClass?) {
    super.putProviderField(key, field)
    fieldShards[key] = shard
  }
  
  /**
   * Gets the shard class that contains the field for the given key.
   * Returns null if the field is in the main graph class.
   */
  fun getFieldShard(key: IrTypeKey): IrClass? {
    return fieldShards[key]
  }
  
  /**
   * Gets the field in the main graph that holds the reference to the given shard.
   */
  fun getShardField(shard: IrClass): IrField? {
    return shardFields[shard]
  }
  
  /**
   * Information about a field and its containing shard.
   */
  data class FieldLocation(
    val field: IrField,
    val shard: IrClass?, // null means main graph class
    val shardField: IrField?, // field in main graph that references the shard
    val getter: IrSimpleFunction? = null, // getter method for lazy initialization
  )
  
  /**
   * Gets complete location information for a provider field.
   */
  fun getProviderFieldLocation(key: IrTypeKey): FieldLocation? {
    val field = providerField(key) ?: return null
    val shard = fieldShards[key]
    val shardField = shard?.let { shardFields[it] }
    val getter = fieldGetters[key]
    return FieldLocation(field, shard, shardField, getter)
  }
}