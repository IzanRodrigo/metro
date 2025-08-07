// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.NameAllocator
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.decapitalizeUS
import dev.zacsweers.metro.compiler.suffixIfNot
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addField
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irDelegatingConstructorCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irSetField
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.defaultType
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
  private val bindingGenerator: (IrBinding, IrValueParameter) -> IrExpression,
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
   * The shard constructor
   */
  lateinit var shardConstructor: IrConstructor
    private set
  
  /**
   * Generates the complete shard class with proper IR initialization
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
      // Ensure proper supertype setup
      superTypes += irBuiltIns.anyType
    }
    
    // Add the shard class to the parent's declarations  
    parentGraph.declarations.add(shardClass)
    
    // Create primary constructor with no parameters for now
    shardConstructor = shardClass.addConstructor {
      visibility = DescriptorVisibilities.INTERNAL // Internal so parent can access
      isPrimary = true
    }
    
    // For now, don't add graph parameter to avoid deprecated API issues
    // In full implementation, we'd need the graph reference for dependency access
    
    // Generate provider fields for each binding in this shard
    bindings.forEach { binding ->
      generateBindingField(binding)
    }
    
    // Set up constructor body
    setupConstructorBody()
    
    // Add field in parent graph to hold this shard instance
    // Create the field after the shard class is fully set up
    shardField = parentGraph.addField {
      name = Name.identifier(shardName.asString().decapitalizeUS())
      // Use typeWith() to ensure the class symbol is properly resolved
      type = shardClass.symbol.typeWith()
      visibility = DescriptorVisibilities.PRIVATE
      isFinal = true
    }
  }
  
  private fun generateBindingField(binding: IrBinding) {
    val fieldType = when (binding) {
      is IrBinding.ConstructorInjected if binding.isAssisted -> {
        binding.classFactory.factoryClass.typeWith()
      }
      else -> {
        symbols.metroProvider.typeWith(binding.typeKey.type)
      }
    }
    
    val suffix = if (binding is IrBinding.ConstructorInjected && binding.isAssisted) "Factory" else "Provider"
    
    val field = shardClass.addField {
      name = Name.identifier(
        fieldNameAllocator.newName(
          binding.nameHint.decapitalizeUS().suffixIfNot(suffix)
        )
      )
      type = fieldType
      visibility = DescriptorVisibilities.INTERNAL // Internal so parent can access
      isFinal = true
    }
    
    providerFields[binding.typeKey] = field
  }
  
  private fun setupConstructorBody() {
    shardConstructor.body = createIrBuilder(shardConstructor.symbol).irBlockBody {
      // Call super constructor (Any())
      +irDelegatingConstructorCall(
        callee = irBuiltIns.anyClass.owner.constructors.single()
      )
      
      // Initialize provider fields
      // For now, create simple placeholder providers - in a full implementation,
      // this would delegate to the actual binding generation logic
      providerFields.forEach { (typeKey, field) ->
        val binding = bindings.find { it.typeKey == typeKey }!!
        +irSetField(
          receiver = irGet(shardClass.thisReceiver!!),
          field = field,
          value = generateBindingProvider(binding)
        )
      }
    }
  }
  
  private fun generateBindingProvider(binding: IrBinding): IrExpression {
    // Use the binding generator provided by the parent graph
    return bindingGenerator(binding, shardClass.thisReceiver!!)
  }
  
  /**
   * Gets the field for a given type key in this shard
   */
  fun getFieldForKey(typeKey: IrTypeKey): IrField? = providerFields[typeKey]
  
  /**
   * Generates an expression to access a binding in this shard from the parent graph
   */
  fun generateAccessorExpression(binding: IrBinding, parentReceiver: IrValueParameter): IrExpression {
    val field = providerFields[binding.typeKey] 
      ?: error("No field found for ${binding.typeKey} in shard $shardName")
    
    return createIrBuilder(parentGraph.symbol).run {
      // For now, just return the field access to the shard provider
      // Access: this.shardField.bindingField
      val shardAccess = irGetField(
        receiver = irGet(parentReceiver),
        field = shardField
      )
      
      irGetField(
        receiver = shardAccess,
        field = field  
      )
    }
  }
}