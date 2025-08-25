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

import dev.zacsweers.metro.compiler.Origins
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.DescriptorVisibility
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addField
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irDelegatingConstructorCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irSetField
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.expressions.impl.IrInstanceInitializerCallImpl
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.name.Name

/**
 * Represents the specification for creating a field in a shard class.
 */
internal data class ShardFieldSpec(
  val name: String,
  val type: IrType,
  val typeKey: IrTypeKey,
  val initializer: FieldInitializer,
  val visibility: DescriptorVisibility = DescriptorVisibilities.PRIVATE
)

/**
 * Generates helper classes that store provider fields for large dependency graphs.
 * This implements the graph sharding strategy to avoid "class too large" errors.
 */
internal class ProviderClassGenerator(
  metroContext: IrMetroContext,
  private val mainGraphClass: IrClass,
  shardIndex: Int,
  private val fieldSpecs: List<ShardFieldSpec> = emptyList(),
) : IrMetroContext by metroContext {

  private val className = Name.identifier("${mainGraphClass.name.asString()}Providers$shardIndex")
  
  data class GeneratedShard(
    val shardClass: IrClass,
    val fields: Map<IrTypeKey, IrField>
  )
  
  fun generate(): GeneratedShard {
    val createdFields = mutableListOf<IrField>()
    
    val shardClass = irFactory.buildClass {
      name = className
      visibility = DescriptorVisibilities.INTERNAL
      modality = Modality.FINAL
      kind = ClassKind.CLASS
      origin = Origins.GeneratedGraphShard
    }.apply {
      // Position it inside the main graph class
      parent = mainGraphClass
      
      // Important: Create the thisReceiver before trying to access defaultType
      createThisReceiverParameter()
      
      // Now we can safely access the main graph's defaultType
      val mainGraphType = mainGraphClass.defaultType
      
      // Add primary constructor with graph parameter
      val ctor = addConstructor {
        isPrimary = true
        visibility = DescriptorVisibilities.INTERNAL
        returnType = this@apply.defaultType
      }
      
      val graphParam = ctor.addValueParameter {
        name = Name.identifier("graph")
        type = mainGraphType
      }
      
      // Store graph parameter as a field for cross-shard access
      val graphField = addField {
        name = Name.identifier("graph")
        type = mainGraphType
        visibility = DescriptorVisibilities.PRIVATE
        isFinal = true
      }
      
      // Create all the fields that belong to this shard
      fieldSpecs.forEach { spec ->
        val field = addField {
          name = Name.identifier(spec.name)
          type = spec.type
          visibility = spec.visibility
          isFinal = true
        }
        createdFields.add(field)
      }
      
      // Initialize the graph field in constructor
      ctor.body = createIrBuilder(ctor.symbol).irBlockBody {
        // Call the super constructor (Any's constructor)
        +irDelegatingConstructorCall(irBuiltIns.anyClass.owner.primaryConstructor!!)
        
        // Initialize the instance
        +IrInstanceInitializerCallImpl(
          UNDEFINED_OFFSET,
          UNDEFINED_OFFSET,
          this@apply.symbol,
          this@apply.defaultType,
        )
        
        // Set the field value from the constructor parameter
        // In a constructor, we use irGet with the thisReceiver of the class being constructed
        +irSetField(
          irGet(this@apply.thisReceiver!!),
          graphField,
          irGet(graphParam)
        )
        
        // Initialize all the shard's fields
        // This must happen after the graph field is set since the initializers may need the graph reference
        fieldSpecs.zip(createdFields).forEach { (spec, field) ->
          val initializedValue = spec.initializer.invoke(this, graphParam, spec.typeKey)
          +irSetField(
            irGet(this@apply.thisReceiver!!),
            field,
            initializedValue
          )
        }
      }
      
      // Add this class as a nested class inside the main graph
      mainGraphClass.addChild(this)
    }
    
    // Create field mapping
    val fieldMap = fieldSpecs.zip(createdFields).associate { (spec, field) -> spec.typeKey to field }
    
    return GeneratedShard(shardClass, fieldMap)
  }
}

/**
 * Specification for a provider class shard.
 */
internal data class ProviderClassSpec(
  val shardIndex: Int,
  val bindings: List<IrBinding>,
  val className: Name,
)

/**
 * Distributes bindings across multiple provider classes to avoid class size limits.
 */
internal class FieldDistributor(
  private val maxFieldsPerShard: Int,
) {
  
  /**
   * Distributes the given bindings across multiple provider class specifications.
   * Each class will have at most [maxFieldsPerShard] fields.
   * Returns empty list if sharding is not needed (bindings fit in main class).
   */
  fun distribute(
    bindings: List<IrBinding>,
    mainGraphClass: IrClass,
  ): List<ProviderClassSpec> {
    if (maxFieldsPerShard == Int.MAX_VALUE || bindings.size <= maxFieldsPerShard) {
      // Sharding disabled or not needed - can fit in main class
      return emptyList()
    }
    
    return bindings
      .chunked(maxFieldsPerShard)
      .mapIndexed { index, chunk ->
        ProviderClassSpec(
          shardIndex = index + 1,
          bindings = chunk,
          className = Name.identifier("${mainGraphClass.name.asString()}Providers${index + 1}")
        )
      }
  }
  
  /**
   * Determines if sharding is needed based on the number of bindings.
   */
  fun shouldShard(bindingCount: Int): Boolean {
    return maxFieldsPerShard != Int.MAX_VALUE && bindingCount > maxFieldsPerShard
  }
}