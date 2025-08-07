// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.Symbols
import org.jetbrains.kotlin.backend.jvm.codegen.AnnotationCodegen.Companion.annotationClass
import org.jetbrains.kotlin.ir.util.classId

private const val INITIAL_VALUE = 512

/** Computes the set of bindings that must end up in provider fields. */
internal class ProviderFieldCollector(private val graph: IrBindingGraph) {

  private data class Node(val binding: IrBinding, var refCount: Int = 0, var isProviderFieldAccessor: Boolean = false) {
    val needsField: Boolean
      get() {
        // Scoped, graph, and members injector bindings always need provider fields
        if (binding.scope != null) return true
        if (binding is IrBinding.GraphDependency) {
          // GraphDependency bindings that are provider field accessors are handled separately
          return !isProviderFieldAccessor
        }
        if (binding is IrBinding.MembersInjected && !binding.isFromInjectorFunction) return true
        // Multibindings are always created adhoc
        if (binding is IrBinding.Multibinding) return false
        // Assisted types always need to be a single field to ensure use of the same provider
        if (binding is IrBinding.Assisted) return true
        // Constructor injected assisted bindings also need fields
        if (binding is IrBinding.ConstructorInjected && binding.isAssisted) return true

        if (
          binding.typeKey.qualifier?.ir?.annotationClass?.classId ==
            Symbols.ClassIds.MultibindingElement
        ) {
          return true
        }

        // If it's unscoped but used more than once and not into a multibinding,
        // we can generate a reusable field
        return refCount >= 2
      }

    /** @return true if we've referenced this binding before. */
    fun mark(): Boolean {
      refCount++
      return refCount > 1
    }
  }

  private val nodes = HashMap<IrTypeKey, Node>(INITIAL_VALUE)

  fun collect(): Map<IrTypeKey, IrBinding> {
    // Count references for each dependency
    for ((key, binding) in graph.bindingsSnapshot()) {
      // Ensure each key has a node
      val node = nodes.getOrPut(key) { Node(binding) }
      // Mark GraphDependency provider field accessors
      if (binding is IrBinding.GraphDependency && binding.isProviderFieldAccessor) {
        node.isProviderFieldAccessor = true
      }
      for (dependency in binding.dependencies) {
        dependency.mark()
      }
    }

    // Decide which bindings actually need provider fields
    return buildMap(nodes.size) {
      for ((key, node) in nodes) {
        val binding = node.binding
        if (node.needsField) {
          put(key, binding)
        }
      }
    }
  }
  
  /**
   * Returns the set of GraphDependency bindings that are provider field accessors.
   * These need special handling to create delegating providers.
   */
  fun collectProviderFieldAccessors(): Map<IrTypeKey, IrBinding.GraphDependency> {
    return buildMap {
      for ((key, node) in nodes) {
        val binding = node.binding
        if (binding is IrBinding.GraphDependency && node.isProviderFieldAccessor) {
          put(key, binding)
        }
      }
    }
  }

  private fun IrContextualTypeKey.mark(): Boolean {
    val binding = graph.requireBinding(this, IrBindingStack.empty())
    return binding.mark()
  }

  private fun IrBinding.mark(): Boolean {
    val node = nodes.getOrPut(typeKey) { Node(this) }
    return node.mark()
  }
}
