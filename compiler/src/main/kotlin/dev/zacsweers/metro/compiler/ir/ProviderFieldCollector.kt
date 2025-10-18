// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.Symbols
import org.jetbrains.kotlin.backend.jvm.codegen.AnnotationCodegen.Companion.annotationClass
import org.jetbrains.kotlin.ir.util.classId

private const val INITIAL_VALUE = 512

/** Computes the set of bindings that must end up in provider fields. */
internal class ProviderFieldCollector(
  private val graph: IrBindingGraph,
  private val fastInitEnabled: Boolean = false,
) {

  internal data class Result(
    val fieldBindings: Map<IrTypeKey, IrBinding>,
    val switchingBindings: Map<IrTypeKey, IrBinding>,
  ) {
    operator fun get(key: IrTypeKey): IrBinding? =
      fieldBindings[key] ?: switchingBindings[key]

    fun hasField(key: IrTypeKey): Boolean = key in fieldBindings
  }

  private data class Node(val binding: IrBinding, var refCount: Int = 0) {
    private val requiresDedicatedField: Boolean
      get() {
        // Scoped, graph, and members injector bindings always need provider fields
        if (binding.isScoped()) return true

        when (binding) {
          is IrBinding.GraphDependency,
          // Assisted types always need to be a single field to ensure use of the same provider
          is IrBinding.Assisted,
          is IrBinding.MembersInjected -> return true
          // TODO what about assisted but no assisted params? These also don't become providers
          //  we would need to track a set of assisted targets somewhere
          is IrBinding.ConstructorInjected if binding.isAssisted -> return true
          // Multibindings are always created adhoc
          is IrBinding.Multibinding -> return false
          // Custom wrappers are always created adhoc since
          // they are usually simple factories like `Optional.of`
          // and can't be scoped
          is IrBinding.CustomWrapper -> return false
          else -> {
            // Do nothing
          }
        }

        if (
          binding.typeKey.qualifier?.ir?.annotationClass?.classId ==
            Symbols.ClassIds.MultibindingElement
        ) {
          return true
        }
        return false
      }

    val requiresFieldInLegacyMode: Boolean
      get() = requiresDedicatedField || refCount >= 2

    val requiresDedicatedFieldAlways: Boolean
      get() = requiresDedicatedField

    /** @return true if we've referenced this binding before. */
    fun mark(): Boolean {
      refCount++
      return refCount > 1
    }
  }

  private val nodes = HashMap<IrTypeKey, Node>(INITIAL_VALUE)

  fun collect(): Result {
    // Count references for each dependency
    for ((key, binding) in graph.bindingsSnapshot()) {
      // Ensure each key has a node
      nodes.getOrPut(key) { Node(binding) }
      for (dependency in binding.dependencies) {
        dependency.mark()
      }
    }

    val fieldBindings = HashMap<IrTypeKey, IrBinding>(nodes.size)
    val switchingBindings = HashMap<IrTypeKey, IrBinding>()

    // Decide which bindings actually need provider fields
    for ((key, node) in nodes) {
      val binding = node.binding
      val requiresDedicatedField = node.requiresDedicatedFieldAlways
      val requiresFieldInLegacyMode = node.requiresFieldInLegacyMode

      if (!fastInitEnabled) {
        if (requiresFieldInLegacyMode) {
          fieldBindings[key] = binding
        }
        continue
      }

      if (requiresDedicatedField) {
        fieldBindings[key] = binding
      } else if (requiresFieldInLegacyMode) {
        switchingBindings[key] = binding
      }
    }

    return Result(fieldBindings, switchingBindings)
  }

  private fun IrContextualTypeKey.mark(): Boolean {
    val binding = graph.requireBinding(this)
    return binding.mark()
  }

  private fun IrBinding.mark(): Boolean {
    val node = nodes.getOrPut(typeKey) { Node(this) }
    return node.mark()
  }
}
