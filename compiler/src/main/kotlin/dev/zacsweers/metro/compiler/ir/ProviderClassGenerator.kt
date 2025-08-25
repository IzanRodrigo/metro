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

import dev.zacsweers.metro.compiler.ExitProcessingException
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.exitProcessing
import org.jetbrains.kotlin.com.intellij.ide.plugins.PluginManagerCore.logger
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addField
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irSetField
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.name.Name

/**
 * Generates helper classes that store provider fields for large dependency graphs.
 * This implements the graph sharding strategy to avoid "class too large" errors.
 */
internal class ProviderClassGenerator(
  metroContext: IrMetroContext,
  private val mainGraphClass: IrClass,
  shardIndex: Int,
) : IrMetroContext by metroContext {

  private val className = Name.identifier("${mainGraphClass.name.asString()}Providers$shardIndex")
  
  /**
   * Generates a provider helper class that stores a subset of provider fields.
   * The class has a constructor that takes the main graph instance.
   */
  fun generate(): IrClass {
    val mainGraphType = try {
      mainGraphClass.defaultType
    } catch (e: IllegalStateException) {
        // This can happen if the main graph class is malformed
        logger.error("Failed to get type for main graph class ${mainGraphClass.name}", e)
        exitProcessing()
    }
    return irFactory.buildClass {
      name = className
      visibility = DescriptorVisibilities.INTERNAL
      modality = Modality.FINAL
      kind = ClassKind.CLASS
      origin = Origins.GeneratedGraphShard
    }.apply {
      // Position it inside the main graph class
      parent = mainGraphClass
      
      // Add primary constructor with graph parameter
      val ctor = addConstructor {
        isPrimary = true
        visibility = DescriptorVisibilities.INTERNAL
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
      
      // Initialize the graph field in constructor
      ctor.body = createIrBuilder(ctor.symbol).irBlockBody {
        +irSetField(irGet(thisReceiver!!), graphField, irGet(graphParam))
      }
      
      // Add this class as a nested class inside the main graph
      mainGraphClass.addChild(this)
    }
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