// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.asName
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.DescriptorVisibility
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.declarations.buildField
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.symbols.impl.IrFieldSymbolImpl
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.name.Name

/**
 * Factory for creating fields with correct parent metadata based on context.
 * This replaces the centralized field creation approach with context-aware creation,
 * following the Dagger pattern to avoid field parent metadata issues.
 */
internal class ContextAwareFieldFactory(
  private val ownershipRegistry: FieldOwnershipRegistry,
  private val metroContext: IrMetroContext
) {
  private val symbols = metroContext.symbols

  /**
   * Context for field creation - specifies where the field will be created
   */
  sealed class FieldContext {
    data class MainGraph(val irClass: IrClass) : FieldContext()
    data class Shard(val shardClass: IrClass, val shardIndex: Int) : FieldContext()
  }

  /**
   * Creates a provider field in the specified context with correct parent metadata
   */
  fun createProviderField(
    key: IrTypeKey,
    context: FieldContext,
    fieldName: Name,
    visibility: DescriptorVisibility = DescriptorVisibilities.INTERNAL
  ): IrField {
    return when (context) {
      is FieldContext.MainGraph -> {
        createFieldInClass(
          targetClass = context.irClass,
          fieldName = fieldName,
          fieldType = symbols.metroProvider.typeWith(key.type),
          visibility = visibility
        ).also { field ->
          ownershipRegistry.registerMainGraphField(key, field)
        }
      }
      is FieldContext.Shard -> {
        createFieldInClass(
          targetClass = context.shardClass,
          fieldName = fieldName,
          fieldType = symbols.metroProvider.typeWith(key.type),
          visibility = visibility
        ).also { field ->
          ownershipRegistry.registerShardField(key, context.shardIndex, field)
        }
      }
    }
  }

  /**
   * Creates an instance field in the specified context with correct parent metadata
   */
  fun createInstanceField(
    key: IrTypeKey,
    context: FieldContext,
    fieldName: Name,
    fieldType: IrType,
    visibility: DescriptorVisibility = DescriptorVisibilities.INTERNAL
  ): IrField {
    return when (context) {
      is FieldContext.MainGraph -> {
        createFieldInClass(
          targetClass = context.irClass,
          fieldName = fieldName,
          fieldType = fieldType,
          visibility = visibility
        ).also { field ->
          ownershipRegistry.registerMainGraphField(key, field)
        }
      }
      is FieldContext.Shard -> {
        createFieldInClass(
          targetClass = context.shardClass,
          fieldName = fieldName,
          fieldType = fieldType,
          visibility = visibility
        ).also { field ->
          ownershipRegistry.registerShardField(key, context.shardIndex, field)
        }
      }
    }
  }

  /**
   * Creates a field for storing constructor parameters (e.g., cross-shard dependencies)
   */
  fun createParameterField(
    key: IrTypeKey,
    context: FieldContext.Shard,
    fieldName: Name,
    fieldType: IrType,
    fromContext: FieldContext? = null
  ): IrField {
    val field = createFieldInClass(
      targetClass = context.shardClass,
      fieldName = fieldName,
      fieldType = fieldType,
      visibility = DescriptorVisibilities.PRIVATE,
      isFinal = true
    )

    // Register as shard field
    ownershipRegistry.registerShardField(key, context.shardIndex, field)

    // If this is for cross-shard access, register the accessor
    if (fromContext is FieldContext.Shard) {
      ownershipRegistry.registerCrossShardAccessor(
        fromShard = context.shardIndex,
        toShard = fromContext.shardIndex,
        typeKey = key,
        field = field
      )
    } else if (fromContext is FieldContext.MainGraph) {
      // This is a field storing a main graph dependency in a shard
      // The field is already registered as a shard field, which is what we want
    }

    return field
  }

  /**
   * Gets or creates a field based on context and ownership
   */
  fun getOrCreateField(
    key: IrTypeKey,
    context: FieldContext,
    fieldName: () -> Name,
    fieldType: () -> IrType,
    visibility: DescriptorVisibility = DescriptorVisibilities.INTERNAL,
    isProvider: Boolean = false
  ): IrField {
    // Check if field already exists in the correct context
    val existingOwnership = ownershipRegistry.getOwnership(key)

    return when {
      // Field exists in the correct context - reuse it
      existingOwnership is FieldOwnershipRegistry.FieldOwnership.MainGraph &&
        context is FieldContext.MainGraph -> {
        existingOwnership.field
      }
      existingOwnership is FieldOwnershipRegistry.FieldOwnership.Shard &&
        context is FieldContext.Shard &&
        existingOwnership.shardIndex == context.shardIndex -> {
        existingOwnership.field
      }
      // Field doesn't exist or is in wrong context - create new one
      else -> {
        if (isProvider) {
          createProviderField(key, context, fieldName(), visibility)
        } else {
          createInstanceField(key, context, fieldName(), fieldType(), visibility)
        }
      }
    }
  }

  /**
   * Internal helper to create a field with correct parent
   */
  private fun createFieldInClass(
    targetClass: IrClass,
    fieldName: Name,
    fieldType: IrType,
    visibility: DescriptorVisibility,
    isFinal: Boolean = false
  ): IrField {
    // Create field directly using IR builders
    return targetClass.factory.buildField {
      startOffset = UNDEFINED_OFFSET
      endOffset = UNDEFINED_OFFSET
      origin = Origins.Default
      name = fieldName
      type = fieldType
      this.visibility = visibility
      this.isFinal = isFinal
    }.apply {
      parent = targetClass
      targetClass.declarations += this
    }
  }
}
