// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.MetroLogger
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
   * IMPORTANT: Due to Kotlin compiler thread-safety limitations, we cannot perform
   * actual IR generation in parallel. Instead, we prepare the shard structure
   * sequentially but maintain the parallel grouping information for future optimization.
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
    
    logger.log("Processing ${shardingResult.shards.size} shards in ${shardingResult.parallelGroups.size} groups")
    logger.log("Note: Due to Kotlin compiler constraints, actual generation is sequential")
    
    // Generate shards sequentially to avoid thread-safety issues with Kotlin compiler internals
    val shards = mutableListOf<IrGraphShard>()
    val generatedShardIndices = mutableSetOf<Int>()
    
    try {
      // First, generate shards that are in parallel groups
      shardingResult.parallelGroups.forEachIndexed { groupIndex, shardIndices ->
        logger.log("Processing group ${groupIndex + 1} with shards: ${shardIndices.sorted().joinToString(", ")}")
        
        for (shardIndex in shardIndices) {
          val shardInfo = shardingResult.shards.find { it.index == shardIndex }
            ?: error("Shard with index $shardIndex not found")
          
          logger.log("Generating shard ${shardInfo.index} (${shardInfo.name})")
          
          val shard = IrGraphShard(
            metroContext = metroContext,
            parentGraph = parentGraph,
            shardName = shardInfo.name,
            shardIndex = shardInfo.index,
            bindings = shardInfo.bindings,
            bindingGenerator = bindingGenerator,
          )
          
          // Generate the shard on the main thread
          shard.generate()
          shards.add(shard)
          generatedShardIndices.add(shardInfo.index)
          
          logger.log("Successfully generated shard ${shardInfo.index}")
        }
      }
      
      // Then, generate any shards that weren't in parallel groups
      // (this can happen if there are isolated shards or circular dependencies)
      for (shardInfo in shardingResult.shards) {
        if (shardInfo.index !in generatedShardIndices) {
          logger.log("Generating standalone shard ${shardInfo.index} (${shardInfo.name})")
          
          val shard = IrGraphShard(
            metroContext = metroContext,
            parentGraph = parentGraph,
            shardName = shardInfo.name,
            shardIndex = shardInfo.index,
            bindings = shardInfo.bindings,
            bindingGenerator = bindingGenerator,
          )
          
          shard.generate()
          shards.add(shard)
          generatedShardIndices.add(shardInfo.index)
          
          logger.log("Successfully generated standalone shard ${shardInfo.index}")
        }
      }
      
      // Sort shards by index to ensure consistent ordering
      shards.sortBy { it.shardIndex }
      
      val endTime = System.currentTimeMillis()
      val duration = endTime - startTime
      
      logger.log("Shard generation completed in ${duration}ms")
      
      return GenerationResult(
        shards = shards,
        generationTimeMs = duration,
        parallelGroupsProcessed = shardingResult.parallelGroups.size,
      )
      
    } catch (e: Exception) {
      logger.log("Error during shard generation: ${e.message}")
      throw RuntimeException("Failed to generate shards: ${e.message}", e)
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
}