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
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irDelegatingConstructorCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irSetField
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.dumpKotlinLike
import org.jetbrains.kotlin.name.Name

/**
 * Represents a shard of a graph that contains a subset of bindings.
 * This helps avoid "class too large" errors by distributing bindings across multiple classes.
 */
internal class IrGraphShard(
  override val metroContext: IrMetroContext,
  private val parentGraph: IrClass,
  private val shardName: Name,
  internal val shardIndex: Int,
  val bindings: List<IrBinding>,
  private val bindingGraph: IrBindingGraph,
  private val bindingGenerator: (IrBinding, IrValueParameter, Map<IrTypeKey, IrField>) -> IrExpression,
) : IrMetroContext by metroContext {
  
  // Fixed value for chunking - we handle large multibindings separately now
  // Reduced from 25 to handle cases where individual bindings generate significant bytecode
  private val STATEMENTS_PER_METHOD = 10
  
  private val fieldNameAllocator = NameAllocator(mode = NameAllocator.Mode.COUNT)
  private val providerFields = mutableMapOf<IrTypeKey, IrField>()
  
  // Performance optimization: Create a map for O(1) binding lookups
  private val bindingsByKey = bindings.associateBy { it.typeKey }
  
  // Cache for binding complexity analysis to avoid repeated calculations
  private val bindingComplexityCache = mutableMapOf<IrTypeKey, Int>()
  
  // Cache topological sort result
  private var sortedBindingsCache: List<IrBinding>? = null
  
  // Field to store the parent graph reference
  lateinit var parentGraphField: IrField
    private set
  
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
    generateCore()
  }
  
  /**
   * Core generation without debug operations for better performance
   */
  private fun generateCore() {
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
        // Important: Create the this receiver for the class
        // This is crucial for nested classes
        createThisReceiverParameter()
      }
      logger.log("Created shard class '${shardClass.name}' with symbol: ${shardClass.symbol}")
      
      // Add the shard class to the parent's declarations  
      logger.log("About to add shard class to parent graph declarations")
      parentGraph.declarations.add(shardClass)
      logger.log("Added shard class to parent graph declarations - parent now has ${parentGraph.declarations.size} declarations")
      
      // Create primary constructor with parent graph parameter
      try {
        shardConstructor = shardClass.addConstructor {
          visibility = DescriptorVisibilities.INTERNAL // Internal so parent can access
          isPrimary = true
        }
        
        // Add parent graph parameter for accessing bound instances
        val parentGraphParam = shardConstructor.addValueParameter {
          name = Name.identifier("parentGraph")
          type = parentGraph.symbol.typeWith()
        }
        
        // Create field to store parent graph reference
        parentGraphField = shardClass.addField {
          name = Name.identifier("parentGraph")
          type = parentGraph.symbol.typeWith()
          visibility = DescriptorVisibilities.PRIVATE
          isFinal = true
        }
        
        logger.log("Created primary constructor for shard class with parent graph parameter")
      } catch (e: Exception) {
        val errorMsg = "Failed to create constructor for shard class '${shardClass.name}'"
        logger.log("ERROR: $errorMsg - ${e.message}")
        logger.log("Shard class details: symbol=${shardClass.symbol}, bound=${shardClass.symbol.isBound}")
        throw RuntimeException(errorMsg, e)
      }
      
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
          try {
            type = shardClass.symbol.typeWith()
          } catch (e: Exception) {
            val detailedError = buildString {
              appendLine("Failed to create type for shard field")
              appendLine("Shard class: ${shardClass.name}")
              appendLine("Symbol: ${shardClass.symbol}")
              appendLine("Symbol bound: ${shardClass.symbol.isBound}")
              appendLine("Symbol owner: ${shardClass.symbol.owner}")
              appendLine("Exception: ${e::class.simpleName}: ${e.message}")
            }
            logger.log("CRITICAL ERROR: $detailedError")
            throw RuntimeException(detailedError, e)
          }
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
      
    } catch (e: Exception) {
      val errorMsg = "Critical error during shard generation for '${shardName}' in graph '${parentGraph.name}'"
      logger.log("CRITICAL ERROR: $errorMsg")
      logger.log("Exception details: ${e::class.simpleName}: ${e.message}")
      logger.log("Bindings being processed: ${bindings.map { it.typeKey }.joinToString()}")
      
      // Generate emergency diagnostic report only if explicitly in debug mode
      if (debug) {
        try {
          generateEmergencyShardReport(e)
          generateKotlinSourceReport()
        } catch (reportError: Exception) {
          logger.log("Failed to generate emergency report: ${reportError.message}")
        }
      }
      
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
          try {
            factoryClass.symbol.typeWith()
          } catch (e: Exception) {
            val detailedError = buildString {
              appendLine("Failed to create type for assisted factory")
              appendLine("Binding: ${binding.typeKey}")
              appendLine("Factory class: ${factoryClass.name}")
              appendLine("Symbol: ${factoryClass.symbol}")
              appendLine("Symbol bound: ${factoryClass.symbol.isBound}")
              appendLine("Exception: ${e::class.simpleName}: ${e.message}")
            }
            logger.log("ERROR: $detailedError")
            throw RuntimeException(detailedError, e)
          }
        }
        else -> {
          logger.log("Using provider type for ${binding.typeKey}")
          val bindingType = binding.typeKey.type
          // Defensive check to ensure the type is valid
          if (bindingType !is IrSimpleType) {
            val errorMsg = "Invalid type for binding ${binding.typeKey}: type=$bindingType is not IrSimpleType"
            logger.log("ERROR: $errorMsg")
            throw IllegalStateException(errorMsg)
          }
          try {
            symbols.metroProvider.typeWith(bindingType)
          } catch (e: Exception) {
            val detailedError = buildString {
              appendLine("Failed to create provider type")
              appendLine("Binding: ${binding.typeKey}")
              appendLine("Binding type: $bindingType")
              appendLine("Provider symbol: ${symbols.metroProvider}")
              appendLine("Provider symbol bound: ${symbols.metroProvider.isBound}")
              appendLine("Exception: ${e::class.simpleName}: ${e.message}")
            }
            logger.log("ERROR: $detailedError")
            throw RuntimeException(detailedError, e)
          }
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
    val logger = loggerFor(MetroLogger.Type.ComponentSharding)
    
    // Defensive check for thisReceiver
    logger.log("Setting up constructor body - checking thisReceiver")
    logger.log("Shard class thisReceiver: ${shardClass.thisReceiver}")
    
    // For nested classes, we might need to use the constructor's dispatch receiver
    val thisReceiver = shardConstructor.dispatchReceiverParameter
      ?: shardClass.thisReceiver 
      ?: error("Shard class ${shardClass.name} has no thisReceiver or dispatch receiver")
    
    logger.log("Creating constructor body with ${providerFields.size} provider fields")
    
    // IMPORTANT: Initialize fields in topological order to ensure dependencies are available
    // This prevents recursive generation and improves performance from O(n²) to O(n)
    val sortedBindings = getSortedBindings()
    
    // Check if we need to chunk the initialization
    // For shards, we need very small chunks because each binding can generate significant bytecode
    // Some bindings with many dependencies can generate thousands of bytecode instructions
    val needsChunking = sortedBindings.size > STATEMENTS_PER_METHOD
    
    if (!needsChunking) {
      // Small shard, initialize directly in constructor
      shardConstructor.body = createIrBuilder(shardConstructor.symbol).irBlockBody {
        // Call super constructor (Any())
        +irDelegatingConstructorCall(
          callee = irBuiltIns.anyClass.owner.constructors.single()
        )
        
        // Initialize parent graph field
        val parentGraphParam = shardConstructor.regularParameters.first()
        +irSetField(
          receiver = irGet(thisReceiver),
          field = parentGraphField,
          value = irGet(parentGraphParam)
        )
        
        sortedBindings.forEach { binding ->
          val field = providerFields[binding.typeKey]!!
          +irSetField(
            receiver = irGet(thisReceiver),
            field = field,
            value = generateBindingProvider(binding)
          )
        }
      }
    } else {
      // Large shard, need to chunk initialization across multiple methods
      logger.log("Shard $shardName requires chunking: ${sortedBindings.size} bindings > $STATEMENTS_PER_METHOD")
      
      // Create init functions for chunks
      val initFunctions = mutableListOf<IrSimpleFunction>()
      
      // For very large shards, we need even smaller chunks to avoid method size limits
      // Analyze binding complexity to determine optimal chunk size
      val effectiveChunkSize = if (sortedBindings.size > 50) {
        // For shards with many bindings, use smaller chunks
        val avgComplexity = sortedBindings.map { getCachedBindingComplexity(it) }.average()
        when {
          avgComplexity > 30 -> 3  // Very complex bindings
          avgComplexity > 20 -> 5  // Complex bindings
          else -> STATEMENTS_PER_METHOD
        }
      } else {
        STATEMENTS_PER_METHOD
      }
      
      // Create chunks that respect dependency order
      // We can't just use .chunked() because it might put dependencies in later chunks
      val chunks = createDependencyRespectingChunks(sortedBindings, effectiveChunkSize)
      logger.log("Creating ${chunks.size} init functions for shard $shardName ($effectiveChunkSize statements per chunk, avg complexity: ${sortedBindings.map { getCachedBindingComplexity(it) }.average()})")
      
      chunks.forEachIndexed { index, chunk ->
        val initFunction = shardClass.addFunction {
          name = Name.identifier("initChunk$index")
          visibility = DescriptorVisibilities.PRIVATE
          returnType = irBuiltIns.unitType
        }.apply {
          // Set the dispatch receiver parameter for this member function
          val localReceiver = shardClass.thisReceiver!!.copyTo(this)
          setDispatchReceiver(localReceiver)
        }
        
        // We need to generate the body after the function is fully set up
        initFunction.body = createIrBuilder(initFunction.symbol).irBlockBody {
          chunk.forEach { binding ->
            val field = providerFields[binding.typeKey]!!
            
            // Check if this is a particularly complex binding that might generate too much bytecode
            val complexity = getCachedBindingComplexity(binding)
            if (complexity > 100 && binding is IrBinding.Multibinding && binding.sourceBindings.size > 50) {
              // For extremely large multibindings, generate a separate helper method
              logger.log("Binding ${binding.typeKey} has complexity $complexity with ${binding.sourceBindings.size} sources - generating helper method")
              
              val helperFunction = shardClass.addFunction {
                name = Name.identifier("create${binding.nameHint}")
                visibility = DescriptorVisibilities.PRIVATE
                returnType = field.type
              }.apply {
                val localReceiver = shardClass.thisReceiver!!.copyTo(this)
                setDispatchReceiver(localReceiver)
              }
              
              helperFunction.body = createIrBuilder(helperFunction.symbol).irBlockBody {
                val helperValue = generateBindingProvider(binding, helperFunction.dispatchReceiverParameter!!)
                +irReturn(helperValue)
              }
              
              // Call the helper function
              val helperCall = irCall(helperFunction).apply {
                dispatchReceiver = irGet(initFunction.dispatchReceiverParameter!!)
              }
              
              +irSetField(
                receiver = irGet(initFunction.dispatchReceiverParameter!!),
                field = field,
                value = helperCall
              )
            } else {
              // Normal case - generate inline
              val bindingValue = createIrBuilder(initFunction.symbol).run {
                val initFunctionReceiver = initFunction.dispatchReceiverParameter!!
                generateBindingProvider(binding, initFunctionReceiver)
              }
              +irSetField(
                receiver = irGet(initFunction.dispatchReceiverParameter!!),
                field = field,
                value = bindingValue
              )
            }
          }
        }
        
        initFunctions.add(initFunction)
      }
      
      // Constructor calls the init functions
      shardConstructor.body = createIrBuilder(shardConstructor.symbol).irBlockBody {
        // Call super constructor (Any())
        +irDelegatingConstructorCall(
          callee = irBuiltIns.anyClass.owner.constructors.single()
        )
        
        // Initialize parent graph field
        val parentGraphParam = shardConstructor.regularParameters.first()
        +irSetField(
          receiver = irGet(thisReceiver),
          field = parentGraphField,
          value = irGet(parentGraphParam)
        )
        
        // Call each init function
        initFunctions.forEach { initFunction ->
          +irCall(initFunction).apply {
            dispatchReceiver = irGet(thisReceiver)
          }
        }
      }
    }
  }
  
  /**
   * Creates chunks of bindings that respect dependency order.
   * Unlike simple chunking, this ensures that no binding in a chunk depends on a binding in a later chunk.
   */
  private fun createDependencyRespectingChunks(sortedBindings: List<IrBinding>, targetChunkSize: Int): List<List<IrBinding>> {
    val chunks = mutableListOf<MutableList<IrBinding>>()
    val bindingToChunkIndex = mutableMapOf<IrTypeKey, Int>()
    
    sortedBindings.forEach { binding ->
      // Find the minimum chunk index where this binding can be placed
      // It must be after all its dependencies
      var minChunkIndex = 0
      
      // Check all dependencies to find the latest chunk they're in
      getAllDependencies(binding).forEach { dep ->
        // Resolve alias to actual binding if needed
        val actualDepKey = resolveAliasTypeKey(dep)
        
        bindingToChunkIndex[actualDepKey]?.let { depChunkIndex ->
          // This binding must be in the same chunk or a later chunk than its dependency
          minChunkIndex = maxOf(minChunkIndex, depChunkIndex)
        }
      }
      
      // Try to place in the minimum required chunk, or create a new one if needed
      val targetChunk = if (minChunkIndex < chunks.size) {
        val chunk = chunks[minChunkIndex]
        // If the chunk is full, we need to use the next chunk
        if (chunk.size >= targetChunkSize) {
          // Create a new chunk
          minChunkIndex = chunks.size
          mutableListOf<IrBinding>().also { chunks.add(it) }
        } else {
          chunk
        }
      } else {
        // Need to create a new chunk
        mutableListOf<IrBinding>().also { chunks.add(it) }
      }
      
      targetChunk.add(binding)
      bindingToChunkIndex[binding.typeKey] = minChunkIndex
    }
    
    return chunks
  }
  
  /**
   * Helper function to get all dependencies including transitive ones from multibindings
   */
  private fun getAllDependencies(binding: IrBinding): List<IrContextualTypeKey> {
    val directDeps = binding.dependencies.toMutableList()
    
    // For each dependency, check if it's a multibinding and add its source bindings
    binding.dependencies.forEach { dep ->
      val depBinding = try {
        bindingGraph.requireBinding(dep, IrBindingStack.empty())
      } catch (e: Exception) {
        null
      }
      
      if (depBinding is IrBinding.Multibinding) {
        // Add the source bindings of the multibinding as transitive dependencies
        depBinding.sourceBindings.forEach { sourceKey ->
          directDeps.add(IrContextualTypeKey(sourceKey))
        }
      }
    }
    
    return directDeps
  }
  
  /**
   * Resolves an alias type key to its actual binding type key
   */
  private fun resolveAliasTypeKey(dep: IrContextualTypeKey): IrTypeKey {
    val depBinding = try {
      bindingGraph.requireBinding(dep, IrBindingStack.empty())
    } catch (e: Exception) {
      return dep.typeKey
    }
    
    if (depBinding is IrBinding.Alias) {
      // Resolve the alias to find the actual binding's type key
      var currentBinding: IrBinding = depBinding
      val seen = mutableSetOf<IrBinding>()
      while (currentBinding is IrBinding.Alias) {
        if (!seen.add(currentBinding)) {
          // Circular alias detected, return original
          return dep.typeKey
        }
        currentBinding = currentBinding.aliasedBinding(bindingGraph, IrBindingStack.empty())
      }
      return currentBinding.typeKey
    }
    
    return dep.typeKey
  }
  
  /**
   * Gets the cached sorted bindings or computes them if not cached
   */
  private fun getSortedBindings(): List<IrBinding> {
    return sortedBindingsCache ?: sortBindingsTopologically().also { sortedBindingsCache = it }
  }
  
  /**
   * Sorts bindings in topological order based on their dependencies within this shard.
   * This ensures that when we initialize a binding, all its dependencies are already initialized.
   */
  private fun sortBindingsTopologically(): List<IrBinding> {
    val logger = loggerFor(MetroLogger.Type.ComponentSharding)
    
    // Build adjacency list for bindings within this shard
    val adjacency = mutableMapOf<IrTypeKey, MutableSet<IrTypeKey>>()
    val inDegree = mutableMapOf<IrTypeKey, Int>()
    
    // Initialize
    bindings.forEach { binding ->
      adjacency[binding.typeKey] = mutableSetOf()
      inDegree[binding.typeKey] = 0
    }
    
    // Build edges - only for dependencies within this shard
    bindings.forEach { binding ->
      getAllDependencies(binding).forEach { dep ->
        // Resolve alias to actual binding if needed
        val actualDepKey = resolveAliasTypeKey(dep)
        
        if (bindingsByKey.containsKey(actualDepKey)) {
          adjacency[actualDepKey]!!.add(binding.typeKey)
          inDegree[binding.typeKey] = inDegree[binding.typeKey]!! + 1
        }
      }
    }
    
    // Kahn's algorithm for topological sort
    val queue = ArrayDeque<IrBinding>()
    val sorted = mutableListOf<IrBinding>()
    
    // Start with nodes that have no dependencies
    bindings.forEach { binding ->
      if (inDegree[binding.typeKey] == 0) {
        queue.add(binding)
      }
    }
    
    while (queue.isNotEmpty()) {
      val current = queue.removeFirst()
      sorted.add(current)
      
      // Process all nodes that depend on current
      adjacency[current.typeKey]!!.forEach { dependentKey ->
        inDegree[dependentKey] = inDegree[dependentKey]!! - 1
        if (inDegree[dependentKey] == 0) {
          queue.add(bindingsByKey[dependentKey]!!)
        }
      }
    }
    
    // Check for cycles (shouldn't happen with proper SCC grouping)
    if (sorted.size != bindings.size) {
      logger.log("WARNING: Cycle detected in shard $shardName. Using original order.")
      return bindings
    }
    
    return sorted
  }
  
  private fun generateBindingProvider(binding: IrBinding, receiver: IrValueParameter? = null): IrExpression {
    // Use the provided receiver or fall back to the constructor's receiver
    val thisReceiver = receiver 
      ?: shardConstructor.dispatchReceiverParameter
      ?: shardClass.thisReceiver 
      ?: error("Shard class ${shardClass.name} has no thisReceiver when generating binding for ${binding.typeKey}")
    
    // Pass the provider fields map for O(1) lookups
    return bindingGenerator(binding, thisReceiver, providerFields)
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
   * Generates debug reports for this shard (called after all shards are generated)
   */
  fun generateDebugReports() {
    if (!debug) return
    
    try {
      generateShardReport()
      generateKotlinSourceReport()
    } catch (e: Exception) {
      val logger = loggerFor(MetroLogger.Type.ComponentSharding)
      logger.log("Failed to generate debug reports for shard '${shardName}': ${e.message}")
    }
  }
  
  /**
   * Generates a Kotlin source representation of the shard
   */
  private fun generateKotlinSourceReport() {
    val logger = loggerFor(MetroLogger.Type.ComponentSharding)
    
    try {
      val kotlinSource = shardClass.dumpKotlinLike()
      
      writeDiagnostic("kotlin-shard-${parentGraph.name.asString()}-${shardName.asString()}.kt") {
        buildString {
          appendLine("// Generated Kotlin representation of ${shardName}")
          appendLine("// Parent Graph: ${parentGraph.name}")
          appendLine("// Bindings: ${bindings.size}")
          appendLine("// Statements Per Method: $STATEMENTS_PER_METHOD")
          appendLine()
          append(kotlinSource)
        }
      }
      
      logger.log("Generated Kotlin source report for '${shardName}'")
    } catch (e: Exception) {
      logger.log("Failed to generate Kotlin source report: ${e.message}")
    }
  }
  
  /**
   * Generates a detailed report of the shard for debugging purposes
   */
  private fun generateShardReport() {
    val logger = loggerFor(MetroLogger.Type.ComponentSharding)
    
    try {
      val reportContent = buildString {
        appendLine("=== DETAILED Component Shard Report ===")
        appendLine("Shard Name: ${shardName}")
        appendLine("Shard Index: ${shardIndex}")
        appendLine("Parent Graph: ${parentGraph.name}")
        appendLine("Bindings Count: ${bindings.size}")
        appendLine("Provider Fields Count: ${providerFields.size}")
        appendLine("Statements Per Method: $STATEMENTS_PER_METHOD")
        appendLine()
        
        appendLine("Shard Class Details:")
        appendLine("  Name: ${shardClass.name}")
        appendLine("  Symbol: ${shardClass.symbol}")
        appendLine("  Symbol Bound: ${shardClass.symbol.isBound}")
        appendLine("  ThisReceiver: ${shardClass.thisReceiver?.let { "Present" } ?: "Missing"}")
        appendLine()
        
        // Calculate chunking info
        val sortedBindings = getSortedBindings()
        val needsChunking = sortedBindings.size > STATEMENTS_PER_METHOD
        val chunks = if (needsChunking) sortedBindings.chunked(STATEMENTS_PER_METHOD) else listOf(sortedBindings)
        
        appendLine("Initialization Chunking:")
        appendLine("  Needs Chunking: $needsChunking")
        appendLine("  Number of Init Methods: ${chunks.size}")
        appendLine("  Bindings per Init Method: ${chunks.map { it.size }.joinToString(", ")}")
        appendLine()
        
        appendLine("=== DETAILED BINDING ANALYSIS ===")
        bindings.forEachIndexed { index, binding ->
          appendLine()
          appendLine("Binding ${index + 1}/${bindings.size}:")
          appendLine("  Type Key: ${binding.typeKey}")
          appendLine("  Binding Type: ${binding::class.simpleName}")
          appendLine("  Name Hint: ${binding.nameHint}")
          
          when (binding) {
            is IrBinding.ConstructorInjected -> {
              appendLine("  Is Assisted: ${binding.isAssisted}")
              if (binding.isAssisted) {
                appendLine("  Factory Class: ${binding.classFactory.factoryClass.name}")
              }
              appendLine("  Constructor Parameters: ${binding.parameters.regularParameters.size}")
              binding.parameters.regularParameters.forEachIndexed { paramIndex, param ->
                appendLine("    Param $paramIndex: ${param.typeKey} (${param.contextualTypeKey})")
              }
            }
            is IrBinding.Provided -> {
              appendLine("  Provider Function: ${binding.providerFactory.function.name}")
              appendLine("  Parameters: ${binding.parameters.regularParameters.size}")
              binding.parameters.regularParameters.forEachIndexed { paramIndex, param ->
                appendLine("    Param $paramIndex: ${param.typeKey} (${param.contextualTypeKey})")
              }
            }
            is IrBinding.MembersInjected -> {
              appendLine("  Target Class: ${binding.targetClassId}")
              appendLine("  Parameters: ${binding.parameters.regularParameters.size}")
              binding.parameters.regularParameters.forEachIndexed { paramIndex, param ->
                appendLine("    Param $paramIndex: ${param.typeKey} (${param.contextualTypeKey})")
              }
            }
            is IrBinding.Multibinding -> {
              appendLine("  Multibinding Type: ${binding::class.simpleName}")
              appendLine("  Dependencies: ${binding.dependencies.size}")
            }
            is IrBinding.Alias -> {
              appendLine("  Target Type: Alias")
            }
            is IrBinding.BoundInstance -> {
              appendLine("  Bound Instance Type")
            }
            else -> {
              // Other binding types
            }
          }
          
          appendLine("  Dependencies: ${binding.dependencies.size}")
          binding.dependencies.forEachIndexed { depIndex, dep ->
            appendLine("    Dep $depIndex: ${dep.typeKey}")
          }
        }
        appendLine()
        
        // Chunk details
        appendLine("=== INITIALIZATION CHUNKS ===")
        chunks.forEachIndexed { chunkIndex, chunk ->
          appendLine()
          appendLine("Init Chunk $chunkIndex (initChunk$chunkIndex method):")
          appendLine("  Bindings in this chunk: ${chunk.size}")
          chunk.forEachIndexed { bindingIndex, binding ->
            val totalDeps = binding.dependencies.size
            val paramCount = when (binding) {
              is IrBinding.ConstructorInjected -> binding.parameters.regularParameters.size
              is IrBinding.Provided -> binding.parameters.regularParameters.size
              is IrBinding.MembersInjected -> binding.parameters.regularParameters.size
              else -> 0
            }
            appendLine("    ${bindingIndex + 1}. ${binding.typeKey} - ${binding::class.simpleName} (${totalDeps} deps, $paramCount params)")
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
        appendLine("=== End Detailed Shard Report ===")
      }
      
      writeDiagnostic("shard-${parentGraph.name.asString()}-${shardName.asString()}-detailed.txt") {
        reportContent
      }
      
      logger.log("Generated detailed shard report for '${shardName}'")
      
    } catch (e: Exception) {
      logger.log("Failed to generate shard report: ${e.message}")
    }
  }
  
  /**
   * Generates an emergency diagnostic report when shard generation fails
   */
  private fun generateEmergencyShardReport(error: Exception) {
    val logger = loggerFor(MetroLogger.Type.ComponentSharding)
    
    try {
      val reportContent = buildString {
        appendLine("=== EMERGENCY SHARD DIAGNOSTIC REPORT ===")
        appendLine("Error Type: ${error::class.simpleName}")
        appendLine("Error Message: ${error.message}")
        appendLine("Shard Name: ${shardName}")
        appendLine("Shard Index: ${shardIndex}")
        appendLine("Parent Graph: ${parentGraph.name}")
        appendLine("Bindings Count: ${bindings.size}")
        appendLine("Statements Per Method: $STATEMENTS_PER_METHOD")
        appendLine()
        
        // Check if this is a "Method too large" error
        val isMethodTooLarge = error.message?.contains("Method too large") == true || 
                              error.cause?.message?.contains("Method too large") == true
        
        if (isMethodTooLarge) {
          appendLine("!!! METHOD TOO LARGE ERROR DETECTED !!!")
          appendLine()
          
          // Analyze which method is too large
          val errorMessage = error.message ?: error.cause?.message ?: ""
          val methodMatch = Regex("Method too large: .+\\.(.+) \\(\\)V").find(errorMessage)
          val problemMethod = methodMatch?.groupValues?.getOrNull(1) ?: "unknown"
          appendLine("Problem Method: $problemMethod")
          appendLine()
        }
        
        // Analyze bindings in detail
        appendLine("=== BINDING COMPLEXITY ANALYSIS ===")
        val sortedBindings = try {
          getSortedBindings()
        } catch (e: Exception) {
          bindings
        }
        
        val chunks = sortedBindings.chunked(STATEMENTS_PER_METHOD)
        appendLine("Total Chunks: ${chunks.size}")
        appendLine()
        
        chunks.forEachIndexed { chunkIndex, chunk ->
          appendLine("Chunk $chunkIndex (initChunk$chunkIndex):")
          chunk.forEach { binding ->
            val complexity = getCachedBindingComplexity(binding)
            appendLine("  ${binding.typeKey}:")
            appendLine("    Type: ${binding::class.simpleName}")
            appendLine("    Dependencies: ${binding.dependencies.size}")
            appendLine("    Estimated Complexity: $complexity")
            
            if (complexity > 50) {
              appendLine("    !!! HIGH COMPLEXITY BINDING !!!")
              // List all dependencies
              binding.dependencies.forEachIndexed { depIndex, dep ->
                appendLine("      Dep $depIndex: ${dep.typeKey}")
              }
            }
          }
          appendLine()
        }
        
        // Try to dump the partial Kotlin source
        appendLine("=== PARTIAL KOTLIN SOURCE ===")
        try {
          if (::shardClass.isInitialized) {
            val kotlinSource = shardClass.dumpKotlinLike()
            appendLine(kotlinSource)
          } else {
            appendLine("Shard class not yet initialized")
          }
        } catch (dumpError: Exception) {
          appendLine("Failed to dump Kotlin source: ${dumpError.message}")
        }
        appendLine()
        
        // Stack trace
        appendLine("=== STACK TRACE ===")
        error.printStackTrace(java.io.PrintWriter(java.io.StringWriter().also { sw ->
          append(sw.toString())
        }))
        
        appendLine()
        appendLine("=== RECOMMENDATIONS ===")
        if (isMethodTooLarge) {
          appendLine("1. Check for extremely large multibindings (this should be handled automatically)")
          appendLine("2. Reduce bindingsPerGraphShard to create smaller shards")
          appendLine("3. Consider refactoring bindings with many dependencies")
          
          val highComplexityBindings = bindings.filter { getCachedBindingComplexity(it) > 50 }
          if (highComplexityBindings.isNotEmpty()) {
            appendLine()
            appendLine("High complexity bindings that should be refactored:")
            highComplexityBindings.forEach { binding ->
              appendLine("  - ${binding.typeKey} (${binding.dependencies.size} dependencies)")
            }
          }
        }
        
        appendLine()
        appendLine("=== End Emergency Report ===")
      }
      
      writeDiagnostic("EMERGENCY-shard-${parentGraph.name.asString()}-${shardName.asString()}.txt") {
        reportContent
      }
      
      logger.log("Generated emergency shard diagnostic report")
      
    } catch (e: Exception) {
      logger.log("Failed to generate emergency report: ${e.message}")
    }
  }
  
  /**
   * Gets cached binding complexity or calculates and caches it
   */
  private fun getCachedBindingComplexity(binding: IrBinding): Int {
    return bindingComplexityCache.getOrPut(binding.typeKey) {
      analyzeBindingComplexity(binding)
    }
  }
  
  /**
   * Estimates the complexity of a binding based on its dependencies and type
   */
  private fun analyzeBindingComplexity(binding: IrBinding): Int {
    var complexity = 0
    
    // Base complexity by type
    complexity += when (binding) {
      is IrBinding.ConstructorInjected -> {
        10 + (binding.parameters.regularParameters.size * 5)
      }
      is IrBinding.Provided -> {
        10 + (binding.parameters.regularParameters.size * 5)
      }
      is IrBinding.MembersInjected -> {
        15 + (binding.parameters.regularParameters.size * 5)
      }
      is IrBinding.Multibinding -> {
        20 + (binding.dependencies.size * 3)
      }
      is IrBinding.Assisted -> 15
      is IrBinding.Alias -> 5
      is IrBinding.BoundInstance -> 3
      is IrBinding.ObjectClass -> 3
      is IrBinding.Absent -> 0
      is IrBinding.GraphDependency -> 5
    }
    
    // Add complexity for dependencies
    complexity += binding.dependencies.size * 5
    
    // Add extra complexity for deep dependency chains
    if (binding.dependencies.size > 10) {
      complexity += (binding.dependencies.size - 10) * 3
    }
    
    return complexity
  }
}