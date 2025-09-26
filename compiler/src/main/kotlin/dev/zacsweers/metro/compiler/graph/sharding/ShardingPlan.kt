// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph.sharding

import dev.zacsweers.metro.compiler.MetroConstants
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.graph.Component
import dev.zacsweers.metro.compiler.graph.TopoSortResult
import dev.zacsweers.metro.compiler.ir.IrBinding
import dev.zacsweers.metro.compiler.ir.IrBindingGraph
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import org.jetbrains.kotlin.backend.jvm.codegen.AnnotationCodegen.Companion.annotationClass
import org.jetbrains.kotlin.ir.util.classId
import java.util.*

/**
 * Optimized sharding plan with performance improvements:
 * - Single-pass processing where possible
 * - Pre-allocated arrays to avoid resizing
 * - Primitive types to reduce boxing overhead
 * - Cache-friendly data structures
 * - Zero unnecessary allocations
 */
internal class ShardingPlan(
  val shards: List<Shard>,
  val bindingToShard: IntArray, // Array indexed by binding ordinal for O(1) lookup
  val keysPerShard: Int,
  val fieldRegistry: ShardFieldRegistry,
  val shardableBindings: BitSet, // Bit set for O(1) membership test
  val mainGraphBindings: BitSet,
  val bindingOrdinals: Map<IrTypeKey, Int>, // Map keys to ordinals for array indexing
) {
  inline val isShardingNeeded: Boolean get() = shards.size > 1

  /**
   * O(1) shard lookup using the pre-computed array.
   */
  fun getShardForBinding(typeKey: IrTypeKey): Shard? {
    val ordinal = bindingOrdinals[typeKey] ?: return null
    val shardIndex = bindingToShard.getOrElse(ordinal) { -1 }
    return if (shardIndex >= 0) shards.getOrNull(shardIndex) else null
  }

  /**
   * O(1) same-shard check.
   */
  fun areInSameShard(key1: IrTypeKey, key2: IrTypeKey): Boolean {
    val ord1 = bindingOrdinals[key1] ?: return false
    val ord2 = bindingOrdinals[key2] ?: return false
    if (ord1 >= bindingToShard.size || ord2 >= bindingToShard.size) return false
    return bindingToShard[ord1] == bindingToShard[ord2]
  }
}

/**
 * Optimized shard representation using primitive arrays.
 */
internal class Shard(
  val index: Int,
  val bindingOrdinals: IntArray, // Store ordinals instead of TypeKey objects
  val dependencies: IntArray, // Ordinals of cross-shard dependencies
  val dependencyAccess: ByteArray, // Compact representation of access patterns
  val exposedBindingsMask: BitSet, // Bit mask for exposed bindings
  val initMethodCount: Byte, // Number of init methods (usually 1-3)
) {
  inline val name: String get() = "Shard$index"
  inline val bindingCount: Int get() = bindingOrdinals.size

  companion object {
    // Compact byte encoding for DependencyAccess
    const val ACCESS_PROVIDER_FIELD: Byte = 0
    const val ACCESS_INSTANCE_FIELD: Byte = 1
    const val ACCESS_DIRECT_SHARD: Byte = 2
    const val ACCESS_DEFERRED: Byte = 3
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is Shard) return false
    return index == other.index &&
        bindingOrdinals.contentEquals(other.bindingOrdinals) &&
        dependencies.contentEquals(other.dependencies)
  }

  override fun hashCode(): Int = 31 * index + bindingOrdinals.contentHashCode()
}

/**
 * Ultra-optimized single-pass sharding algorithm with:
 * - Pre-allocated buffers to avoid resizing
 * - Minimal object allocations
 * - Cache-friendly iteration patterns
 * - Inline functions to reduce call overhead
 */
internal fun buildShardingPlan(
  topo: TopoSortResult<IrTypeKey>,
  keysPerShard: Int,
  bindingGraph: IrBindingGraph,
): ShardingPlan? {
  require(keysPerShard > 0) { "keysPerShard must be positive" }

  val allBindings = bindingGraph.bindingsSnapshot()
  val totalBindings = allBindings.size

  // Early exit for small graphs
  if (totalBindings < keysPerShard) {
    // Debug logging
    println("Sharding: Not triggered - totalBindings ($totalBindings) < keysPerShard ($keysPerShard)")
    return null
  }

  // Pre-allocate all data structures with exact sizes
  val bindingOrdinals = HashMap<IrTypeKey, Int>(totalBindings)
  val ordinalToBinding = arrayOfNulls<IrBinding>(totalBindings)
  val shardableBindings = BitSet(totalBindings)
  val mainGraphBindings = BitSet(totalBindings)
  val deferredOrdinals = BitSet(totalBindings)

  // Single pass: assign ordinals and classify bindings
  var ordinal = 0
  var shardableCount = 0
  for ((key, binding) in allBindings) {
    bindingOrdinals[key] = ordinal
    ordinalToBinding[ordinal] = binding

    when {
      binding is IrBinding.Absent -> {} // Skip
      shouldStayInMainGraph(binding, bindingGraph) -> mainGraphBindings.set(ordinal)
      else -> {
        shardableBindings.set(ordinal)
        shardableCount++
      }
    }

    ordinal++
  }

  // Mark deferred types
  for (deferredKey in topo.deferredTypes) {
    bindingOrdinals[deferredKey]?.let { deferredOrdinals.set(it) }
  }

  // Check if sharding is needed
  val estimatedShards = (shardableCount + keysPerShard - 1) / keysPerShard
  if (estimatedShards <= 1) {
    // Debug logging
    val mainGraphCount = mainGraphBindings.cardinality()
    println("Sharding: Not triggered - insufficient shardable bindings")
    println("  Total bindings: $totalBindings")
    println("  Main graph bindings: $mainGraphCount")
    println("  Shardable bindings: $shardableCount")
    println("  Keys per shard: $keysPerShard")
    println("  Estimated shards: $estimatedShards (needs > 1)")
    return null
  }

  // Pre-compute dependency adjacency using arrays for speed
  val dependencyLists = buildDependencyLists(ordinalToBinding, bindingOrdinals, totalBindings)

  // Build shards using an optimized algorithm
  val shardBuilder = ShardBuilder(
    keysPerShard = keysPerShard,
    totalBindings = totalBindings,
    estimatedShards = estimatedShards,
    shardableBindings = shardableBindings,
    deferredOrdinals = deferredOrdinals,
    dependencyLists = dependencyLists,
  )

  // Process SCCs
  shardBuilder.processComponents(topo, bindingOrdinals)

  // Build final shards
  val shards = shardBuilder.buildShards()

  if (shards.isNotEmpty()) {
    // Debug logging
    println("Sharding: TRIGGERED - creating ${shards.size} shards")
    println("  Total bindings: $totalBindings")
    println("  Shardable bindings: $shardableCount")
    println("  Keys per shard: $keysPerShard")
    shards.forEachIndexed { index, shard ->
      println("  Shard $index: ${shard.bindingOrdinals.size} bindings")
    }
  }

  return ShardingPlan(
    shards = shards,
    bindingToShard = shardBuilder.bindingToShard,
    keysPerShard = keysPerShard,
    fieldRegistry = ShardFieldRegistry(),
    shardableBindings = shardableBindings,
    mainGraphBindings = mainGraphBindings,
    bindingOrdinals = bindingOrdinals,
  )
}

/**
 * Builder class that maintains state during shard construction,
 * avoiding allocations and enabling single-pass processing.
 */
private class ShardBuilder(
  private val keysPerShard: Int,
  private val totalBindings: Int,
  estimatedShards: Int,
  private val shardableBindings: BitSet,
  private val deferredOrdinals: BitSet,
  private val dependencyLists: Array<IntArray>,
) {
  // Pre-allocate arrays
  val bindingToShard = IntArray(totalBindings) { -1 }

  // Current shard accumulator - reused to avoid allocations
  private val currentShardBuffer = IntArray(keysPerShard * 2) // Allow some overflow
  private var currentShardSize = 0
  private var currentShardIndex = 0

  // Track exposed bindings per shard using a single large bit set
  // Each shard gets a "slice" of totalBindings bits
  private val exposedBindings = BitSet(estimatedShards * totalBindings)

  // Shard data - pre-allocated
  private val shardData = ArrayList<ShardData>(estimatedShards)

  private class ShardData(
    val bindings: IntArray,
    val dependencies: IntArray,
    val depAccess: ByteArray,
    val exposedMask: BitSet,
  )

  fun processComponents(topo: TopoSortResult<IrTypeKey>, bindingOrdinals: Map<IrTypeKey, Int>) {
    // Process components in topological order
    for (component in topo.components) {
      processComponent(component, bindingOrdinals)
    }

    // Flush any remaining bindings
    if (currentShardSize > 0) {
      flushCurrentShard()
    }
  }

  private fun processComponent(
    component: Component<IrTypeKey>,
    bindingOrdinals: Map<IrTypeKey, Int>
  ) {
    // Count shardable bindings in this component
    var componentSize = 0
    var hasDeferred = false

    // First pass: count and check for deferred
    for (vertex in component.vertices) {
      val ordinal = bindingOrdinals[vertex] ?: continue
      if (shardableBindings[ordinal]) {
        componentSize++
        if (!hasDeferred && deferredOrdinals[ordinal]) {
          hasDeferred = true
        }
      }
    }

    if (componentSize == 0) return

    // Handle large SCCs
    if (componentSize >= keysPerShard) {
      // Flush current shard if is not empty
      if (currentShardSize > 0) {
        flushCurrentShard()
      }

      if (hasDeferred) {
        // Split large SCC at deferred boundaries
        splitLargeSCC(component, bindingOrdinals)
      } else {
        // Add entire SCC as oversized shard
        for (vertex in component.vertices) {
          val ordinal = bindingOrdinals[vertex] ?: continue
          if (shardableBindings[ordinal]) {
            currentShardBuffer[currentShardSize++] = ordinal
          }
        }
        flushCurrentShard()
      }
    } else {
      // Normal-sized component
      if (currentShardSize + componentSize > keysPerShard) {
        flushCurrentShard()
      }

      // Add to the current shard
      for (vertex in component.vertices) {
        val ordinal = bindingOrdinals[vertex] ?: continue
        if (shardableBindings[ordinal]) {
          currentShardBuffer[currentShardSize++] = ordinal
        }
      }
    }
  }

  private fun splitLargeSCC(
    component: Component<IrTypeKey>,
    bindingOrdinals: Map<IrTypeKey, Int>
  ) {
    // Collect deferred and non-deferred ordinals
    val nonDeferredBuffer = IntArray(component.vertices.size)
    val deferredBuffer = IntArray(component.vertices.size)
    var nonDeferredCount = 0
    var deferredCount = 0

    for (vertex in component.vertices) {
      val ordinal = bindingOrdinals[vertex] ?: continue
      if (!shardableBindings[ordinal]) continue

      if (deferredOrdinals[ordinal]) {
        deferredBuffer[deferredCount++] = ordinal
      } else {
        // Check if depends on deferred
        var dependsOnDeferred = false
        for (dep in dependencyLists[ordinal]) {
          if (deferredOrdinals[dep]) {
            dependsOnDeferred = true
            break
          }
        }

        if (dependsOnDeferred) {
          deferredBuffer[deferredCount++] = ordinal
        } else {
          nonDeferredBuffer[nonDeferredCount++] = ordinal
        }
      }
    }

    // Add non-deferred first
    if (nonDeferredCount > 0) {
      currentShardBuffer.copyInto(currentShardBuffer, 0, 0, nonDeferredCount)
      currentShardSize = nonDeferredCount
      flushCurrentShard()
    }

    // Add deferred
    if (deferredCount > 0) {
      deferredBuffer.copyInto(currentShardBuffer, 0, 0, deferredCount)
      currentShardSize = deferredCount
      flushCurrentShard()
    }
  }

  private fun flushCurrentShard() {
    if (currentShardSize == 0) return

    // Mark bindings as belonging to this shard
    val bindings = IntArray(currentShardSize)
    for (i in 0 until currentShardSize) {
      val ordinal = currentShardBuffer[i]
      bindings[i] = ordinal
      bindingToShard[ordinal] = currentShardIndex
    }

    // Calculate dependencies efficiently using pre-allocated buffers
    val depBuffer = IntArray(currentShardSize * 3) // Estimate 3 deps per binding
    val accessBuffer = ByteArray(currentShardSize * 3)
    var depCount = 0
    val depSet = BitSet() // Track unique dependencies

    for (i in 0 until currentShardSize) {
      val ordinal = currentShardBuffer[i]

      for (depOrdinal in dependencyLists[ordinal]) {
        val depShard = bindingToShard[depOrdinal]
        if (depShard >= 0 && depShard != currentShardIndex && !depSet[depOrdinal]) {
          depSet.set(depOrdinal)
          depBuffer[depCount] = depOrdinal

          // Determine access type inline
          accessBuffer[depCount] = if (deferredOrdinals[depOrdinal]) {
            Shard.ACCESS_DEFERRED
          } else {
            Shard.ACCESS_PROVIDER_FIELD
          }

          // Mark as exposed from its shard
          markExposed(depShard, depOrdinal)
          depCount++
        }
      }
    }

    // Get exposed mask for this shard
    val exposedMask = getExposedMask(currentShardIndex)

    // Store shard data
    shardData.add(
      ShardData(
        bindings = bindings,
        dependencies = if (depCount > 0) depBuffer.copyOf(depCount) else IntArray(0),
        depAccess = if (depCount > 0) accessBuffer.copyOf(depCount) else ByteArray(0),
        exposedMask = exposedMask,
      )
    )

    // Reset for next shard
    currentShardSize = 0
    currentShardIndex++
  }

  private fun markExposed(shardIndex: Int, ordinal: Int) {
    exposedBindings.set(shardIndex * totalBindings + ordinal)
  }

  private fun getExposedMask(shardIndex: Int): BitSet {
    val mask = BitSet(totalBindings)
    val start = shardIndex * totalBindings
    for (i in 0 until totalBindings) {
      if (exposedBindings[start + i]) {
        mask.set(i)
      }
    }
    return mask
  }

  fun buildShards(): List<Shard> {
    return shardData.mapIndexed { index, data ->
      Shard(
        index = index,
        bindingOrdinals = data.bindings,
        dependencies = data.dependencies,
        dependencyAccess = data.depAccess,
        exposedBindingsMask = data.exposedMask,
        initMethodCount = estimateInitMethods(data.bindings.size, data.dependencies.size),
      )
    }
  }

  private fun estimateInitMethods(bindingCount: Int, depCount: Int): Byte {
    val statements = bindingCount * 2 + depCount
    return ((statements + MetroConstants.STATEMENTS_PER_METHOD - 1) / MetroConstants.STATEMENTS_PER_METHOD).toByte()
  }
}

/**
 * Builds dependency adjacency lists using arrays for maximum speed.
 */
private fun buildDependencyLists(
  ordinalToBinding: Array<IrBinding?>,
  bindingOrdinals: Map<IrTypeKey, Int>,
  totalBindings: Int,
): Array<IntArray> {
  val adjacency = Array(totalBindings) { IntArray(0) }

  for (ordinal in 0 until totalBindings) {
    val binding = ordinalToBinding[ordinal] ?: continue
    val deps = binding.dependencies

    if (deps.isNotEmpty()) {
      val depArray = IntArray(deps.size)
      var count = 0

      for (dep in deps) {
        bindingOrdinals[dep.typeKey]?.let {
          depArray[count++] = it
        }
      }

      adjacency[ordinal] = if (count < deps.size) {
        depArray.copyOf(count) // Trim if some deps were missing
      } else {
        depArray
      }
    }
  }

  return adjacency
}

/**
 * Determines if a binding should stay in the main graph.
 * Only bindings that must be in the main graph for structural reasons should return true.
 */
private fun shouldStayInMainGraph(binding: IrBinding, bindingGraph: IrBindingGraph): Boolean {
  return when (binding) {
    is IrBinding.BoundInstance -> true // Constructor parameters need main graph access
    is IrBinding.ObjectClass -> true // Module containers (singletons) stay in main graph
    is IrBinding.Alias -> true // Simple type aliases stay in main
    is IrBinding.GraphDependency -> binding.fieldAccess != null // External graph references
    is IrBinding.GraphExtensionFactory -> true // Extension factories are handled specially
    is IrBinding.Multibinding -> true // Multibinding containers need aggregation logic in main graph
    is IrBinding.Provided -> true // @Provides methods stay in main graph to avoid cross-shard dependency issues
    else -> {
      // Check if this is a MultibindingElement contribution
      if (binding.typeKey.qualifier?.ir?.annotationClass?.classId == Symbols.ClassIds.MultibindingElement) {
        return true // MultibindingElement contributions must co-locate with their containers in main graph
      }

      // Check if this binding is aliased by any MultibindingElement-qualified alias
      // This handles cases like NavigationFinder.Stub which is aliased by @ContributesIntoSet
      val allBindings = bindingGraph.bindingsSnapshot()
      for ((aliasTypeKey, aliasBinding) in allBindings) {
        if (aliasBinding is IrBinding.Alias &&
            aliasBinding.aliasedType == binding.typeKey &&
            aliasBinding.typeKey.qualifier?.ir?.annotationClass?.classId == Symbols.ClassIds.MultibindingElement) {
          return true // This binding is aliased by a MultibindingElement, so it should stay in main graph
        }
      }

      false // Other bindings can be sharded (ConstructorInjected, etc.)
    }
  }
}
