// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.MetroLogger
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
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.constructors
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
  val bindings: List<IrBinding>,
  private val bindingGenerator: (IrBinding, IrValueParameter) -> IrExpression,
) : IrMetroContext by metroContext {
  
  private val fieldNameAllocator = NameAllocator(mode = NameAllocator.Mode.COUNT)
  private val providerFields = mutableMapOf<IrTypeKey, IrField>()
  
  /**
   * Public accessor for shard name
   */
  val name: Name get() = shardName
  
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
    val logger = loggerFor(MetroLogger.Type.ComponentSharding)
    
    try {
      logger.log("Starting generation of shard '${shardName}' for graph '${parentGraph.name}' with ${bindings.size} bindings")
      
      // Validate parent graph before proceeding
      if (!parentGraph.symbol.isBound) {
        throw IllegalStateException("Parent graph symbol is not bound: ${parentGraph.name}")
      }
      
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
      logger.log("Created shard class '${shardClass.name}' with symbol: ${shardClass.symbol}")
      
      // Add the shard class to the parent's declarations  
      parentGraph.declarations.add(shardClass)
      logger.log("Added shard class to parent graph declarations")
      
      // Create primary constructor with no parameters for now
      shardConstructor = shardClass.addConstructor {
        visibility = DescriptorVisibilities.INTERNAL // Internal so parent can access
        isPrimary = true
      }
      logger.log("Created primary constructor for shard class")
      
      // Generate provider fields for each binding in this shard
      bindings.forEachIndexed { index, binding ->
        try {
          logger.log("Generating binding field ${index + 1}/${bindings.size} for ${binding.typeKey}")
          generateBindingField(binding)
        } catch (e: Exception) {
          val errorMsg = "Failed to generate binding field for ${binding.typeKey} in shard '${shardName}': ${e.message}"
          logger.log(errorMsg)
          throw RuntimeException(errorMsg, e)
        }
      }
      
      // Set up constructor body
      try {
        logger.log("Setting up constructor body for shard '${shardName}'")
        setupConstructorBody()
      } catch (e: Exception) {
        val errorMsg = "Failed to setup constructor body for shard '${shardName}': ${e.message}"
        logger.log(errorMsg)
        throw RuntimeException(errorMsg, e)
      }
      
      // Add field in parent graph to hold this shard instance
      // Create the field after the shard class is fully set up
      try {
        logger.log("Creating shard field in parent graph")
        shardField = parentGraph.addField {
          name = Name.identifier(shardName.asString().decapitalizeUS())
          // Use typeWith() to ensure the class symbol is properly resolved
          // Add defensive check to ensure symbol is bound
          if (!shardClass.symbol.isBound) {
            val errorMsg = "Shard class symbol is not bound for ${shardClass.name} - this indicates an IR initialization issue"
            logger.log("ERROR: $errorMsg")
            throw IllegalStateException(errorMsg)
          }
          type = shardClass.symbol.typeWith()
          visibility = DescriptorVisibilities.PRIVATE
          isFinal = true
        }
        logger.log("Successfully created shard field '${shardField.name}' in parent graph")
      } catch (e: Exception) {
        val errorMsg = "Failed to create shard field in parent graph for shard '${shardName}': ${e.message}"
        logger.log("ERROR: $errorMsg")
        throw RuntimeException(errorMsg, e)
      }
      
      logger.log("Successfully completed generation of shard '${shardName}'")
      
      // Generate shard report if debug mode is enabled
      if (debug) {
        generateShardReport()
      }
      
    } catch (e: Exception) {
      val errorMsg = "Critical error during shard generation for '${shardName}' in graph '${parentGraph.name}'"
      logger.log("CRITICAL ERROR: $errorMsg")
      logger.log("Exception details: ${e::class.simpleName}: ${e.message}")
      logger.log("Bindings being processed: ${bindings.map { it.typeKey }.joinToString()}")
      throw RuntimeException("$errorMsg: ${e.message}", e)
    }
  }
  
  private fun generateBindingField(binding: IrBinding) {
    val logger = loggerFor(MetroLogger.Type.ComponentSharding)
    
    try {
      val fieldType = when (binding) {
        is IrBinding.ConstructorInjected if binding.isAssisted -> {
          // Defensive check to prevent NPE
          val factoryClass = binding.classFactory.factoryClass
          if (!factoryClass.symbol.isBound) {
            val errorMsg = "Factory class symbol is not bound for ${binding.typeKey} - factory class: ${factoryClass.name}"
            logger.log("ERROR: $errorMsg")
            throw IllegalStateException(errorMsg)
          }
          logger.log("Using assisted factory type for ${binding.typeKey}: ${factoryClass.name}")
          factoryClass.symbol.typeWith()
        }
        else -> {
          logger.log("Using provider type for ${binding.typeKey}")
          val bindingType = binding.typeKey.type
          // Defensive check to ensure the type is valid
          if (bindingType !is IrSimpleType || bindingType.classifier == null) {
            val errorMsg = "Invalid type for binding ${binding.typeKey}: type=$bindingType, classifier=${(bindingType as? IrSimpleType)?.classifier}"
            logger.log("ERROR: $errorMsg")
            throw IllegalStateException(errorMsg)
          }
          symbols.metroProvider.typeWith(bindingType)
        }
      }
      
      val suffix = if (binding is IrBinding.ConstructorInjected && binding.isAssisted) "Factory" else "Provider"
      val fieldName = fieldNameAllocator.newName(
        binding.nameHint.decapitalizeUS().suffixIfNot(suffix)
      )
      
      logger.log("Creating field '${fieldName}' for binding ${binding.typeKey}")
      
      val field = shardClass.addField {
        name = Name.identifier(fieldName)
        type = fieldType
        visibility = DescriptorVisibilities.INTERNAL // Internal so parent can access
        isFinal = true
      }
      
      providerFields[binding.typeKey] = field
      logger.log("Successfully created field '${field.name}' for binding ${binding.typeKey}")
      
    } catch (e: Exception) {
      val errorMsg = "Failed to generate binding field for ${binding.typeKey}"
      logger.log("ERROR: $errorMsg - ${e.message}")
      throw RuntimeException("$errorMsg: ${e.message}", e)
    }
  }
  
  private fun setupConstructorBody() {
    // Defensive check for thisReceiver
    val thisReceiver = shardClass.thisReceiver 
      ?: error("Shard class ${shardClass.name} has no thisReceiver")
    
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
          receiver = irGet(thisReceiver),
          field = field,
          value = generateBindingProvider(binding)
        )
      }
    }
  }
  
  private fun generateBindingProvider(binding: IrBinding): IrExpression {
    // Use the binding generator provided by the parent graph
    val thisReceiver = shardClass.thisReceiver 
      ?: error("Shard class ${shardClass.name} has no thisReceiver when generating binding for ${binding.typeKey}")
    return bindingGenerator(binding, thisReceiver)
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
  
  /**
   * Generates a detailed report of the shard for debugging purposes
   */
  private fun generateShardReport() {
    val logger = loggerFor(MetroLogger.Type.ComponentSharding)
    
    try {
      val reportContent = buildString {
        appendLine("=== Component Shard Report ===")
        appendLine("Shard Name: ${shardName}")
        appendLine("Shard Index: ${shardIndex}")
        appendLine("Parent Graph: ${parentGraph.name}")
        appendLine("Bindings Count: ${bindings.size}")
        appendLine("Provider Fields Count: ${providerFields.size}")
        appendLine()
        
        appendLine("Shard Class Details:")
        appendLine("  Name: ${shardClass.name}")
        appendLine("  Symbol: ${shardClass.symbol}")
        appendLine("  Symbol Bound: ${shardClass.symbol.isBound}")
        appendLine("  ThisReceiver: ${shardClass.thisReceiver?.let { "Present" } ?: "Missing"}")
        appendLine()
        
        appendLine("Bindings in this shard:")
        bindings.forEachIndexed { index, binding ->
          appendLine("  ${index + 1}. ${binding.typeKey} (${binding::class.simpleName})")
          if (binding is IrBinding.ConstructorInjected && binding.isAssisted) {
            appendLine("     Assisted Factory: ${binding.classFactory.factoryClass.name}")
          }
        }
        appendLine()
        
        appendLine("Generated Provider Fields:")
        providerFields.forEach { (typeKey, field) ->
          appendLine("  ${field.name.asString()}: ${field.type} -> ${typeKey}")
        }
        appendLine()
        
        if (::shardField.isInitialized) {
          appendLine("Shard Field in Parent:")
          appendLine("  Name: ${shardField.name}")
          appendLine("  Type: ${shardField.type}")
          appendLine("  Visibility: ${shardField.visibility}")
        } else {
          appendLine("Shard Field: Not initialized")
        }
        
        appendLine()
        appendLine("=== End Shard Report ===")
      }
      
      writeDiagnostic("shard-${parentGraph.name.asString()}-${shardName.asString()}.txt") {
        reportContent
      }
      
      logger.log("Generated shard report for '${shardName}'")
      
    } catch (e: Exception) {
      logger.log("Failed to generate shard report: ${e.message}")
    }
  }
}