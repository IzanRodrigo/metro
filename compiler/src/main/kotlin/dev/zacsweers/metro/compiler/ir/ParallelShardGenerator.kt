// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.MetroLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression

/**
 * Manages parallel generation of graph shards to reduce build times.
 * 
 * This class coordinates the concurrent generation of independent shard groups
 * identified by the GraphShardingStrategy.
 */
internal class ParallelShardGenerator(
  private val metroContext: IrMetroContext,
  private val parallelism: Int = Runtime.getRuntime().availableProcessors(),
) {
  
  private val logger = metroContext.loggerFor(MetroLogger.Type.ComponentSharding)
  
  /**
   * Result of parallel shard generation
   */
  data class GenerationResult(
    val shards: List<IrGraphShard>,
    val generationTimeMs: Long,
    val parallelGroupsProcessed: Int,
  )
  
  /**
   * Generates shards in parallel based on the dependency groups.
   * 
   * @param shardingResult The result from GraphShardingStrategy containing shard info and parallel groups
   * @param parentGraph The parent graph class
   * @param bindingGenerator Function to generate binding code for each shard
   * @return Result containing generated shards and timing information
   */
  fun generateShardsInParallel(
    shardingResult: GraphShardingStrategy.ShardingResult,
    parentGraph: IrClass,
    bindingGenerator: (IrBinding, IrValueParameter) -> IrExpression,
  ): GenerationResult {
    val startTime = System.currentTimeMillis()
    
    if (!metroContext.options.enableParallelShardGeneration) {
      logger.log("Parallel shard generation is disabled, falling back to sequential generation")
      return generateSequentially(shardingResult, parentGraph, bindingGenerator, startTime)
    }
    
    logger.log("Starting parallel shard generation with ${shardingResult.parallelGroups.size} groups")
    
    // Thread-safe collection for results
    val generatedShards = ConcurrentHashMap<Int, IrGraphShard>()
    val errors = ConcurrentHashMap<Int, Throwable>()
    
    // Create a thread pool for parallel execution
    val executor = Executors.newFixedThreadPool(
      parallelism.coerceAtMost(shardingResult.shards.size),
      MetroThreadFactory("metro-shard-gen")
    )
    
    try {
      // Process each parallel group
      shardingResult.parallelGroups.forEachIndexed { groupIndex, shardIndices ->
        logger.log("Processing parallel group ${groupIndex + 1} with shards: ${shardIndices.sorted().joinToString(", ")}")
        
        // Submit tasks for each shard in the group
        val futures = mutableListOf<Future<*>>()
        
        for (shardIndex in shardIndices) {
          val shardInfo = shardingResult.shards.find { it.index == shardIndex }
            ?: error("Shard with index $shardIndex not found")
          
          val future = executor.submit {
            try {
              logger.log("Generating shard ${shardInfo.index} (${shardInfo.name}) on thread ${Thread.currentThread().name}")
              
              val shard = IrGraphShard(
                metroContext = metroContext,
                parentGraph = parentGraph,
                shardName = shardInfo.name,
                shardIndex = shardInfo.index,
                bindings = shardInfo.bindings,
                bindingGenerator = bindingGenerator,
              )
              
              // Generate the shard
              shard.generate()
              
              // Store the result
              generatedShards[shardInfo.index] = shard
              
              logger.log("Successfully generated shard ${shardInfo.index}")
            } catch (e: Exception) {
              logger.log("Error generating shard ${shardInfo.index}: ${e.message}")
              errors[shardInfo.index] = e
            }
          }
          
          futures.add(future)
        }
        
        // Wait for all shards in this group to complete before moving to the next group
        futures.forEach { it.get() }
        
        // Check for errors before proceeding
        if (errors.isNotEmpty()) {
          val errorMsg = errors.entries.joinToString("\n") { (index, error) ->
            "Shard $index: ${error.message}"
          }
          throw RuntimeException("Failed to generate ${errors.size} shards:\n$errorMsg", errors.values.first())
        }
      }
      
      // Convert to sorted list
      val shards = shardingResult.shards.map { shardInfo ->
        generatedShards[shardInfo.index] ?: error("Shard ${shardInfo.index} was not generated")
      }
      
      val endTime = System.currentTimeMillis()
      val duration = endTime - startTime
      
      logger.log("Parallel shard generation completed in ${duration}ms")
      
      return GenerationResult(
        shards = shards,
        generationTimeMs = duration,
        parallelGroupsProcessed = shardingResult.parallelGroups.size,
      )
      
    } finally {
      executor.shutdown()
    }
  }
  
  /**
   * Fallback sequential generation for when parallel is disabled
   */
  private fun generateSequentially(
    shardingResult: GraphShardingStrategy.ShardingResult,
    parentGraph: IrClass,
    bindingGenerator: (IrBinding, IrValueParameter) -> IrExpression,
    startTime: Long,
  ): GenerationResult {
    val shards = shardingResult.shards.map { shardInfo ->
      IrGraphShard(
        metroContext = metroContext,
        parentGraph = parentGraph,
        shardName = shardInfo.name,
        shardIndex = shardInfo.index,
        bindings = shardInfo.bindings,
        bindingGenerator = bindingGenerator,
      ).apply {
        generate()
      }
    }
    
    val endTime = System.currentTimeMillis()
    
    return GenerationResult(
      shards = shards,
      generationTimeMs = endTime - startTime,
      parallelGroupsProcessed = 1, // Sequential is one group
    )
  }
  
  /**
   * Custom thread factory for better thread naming
   */
  private class MetroThreadFactory(private val prefix: String) : ThreadFactory {
    private val threadNumber = AtomicInteger(1)
    
    override fun newThread(r: Runnable): Thread {
      return Thread(r, "$prefix-${threadNumber.getAndIncrement()}").apply {
        isDaemon = true
        priority = Thread.NORM_PRIORITY
      }
    }
  }
}