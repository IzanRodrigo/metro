// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph.sharding

/**
 * Registry that ensures field name uniqueness across all shards in a component.
 *
 * This solves the duplicate field name issue where counters were incorrectly
 * shared or reset between shards, causing ClassFormatError at runtime.
 */
internal class GlobalFieldNameRegistry {
    private val usedFieldNames = mutableSetOf<String>()
    private val fieldCounters = mutableMapOf<String, Int>()

    /**
     * Generates a unique field name across all shards.
     *
     * @param baseName The base name for the field (e.g., "appLogger")
     * @param typeHint Type suffix (e.g., "ProviderField", "InstanceField")
     * @param shardIndex Optional shard index for debugging
     * @return A unique field name guaranteed not to conflict
     */
    internal fun generateUniqueFieldName(
        baseName: String,
        typeHint: String = "ProviderField",
        shardIndex: Int? = null
    ): String {
        val baseFieldName = "${baseName}${typeHint}"
        val shardInfo = shardIndex?.let { " [Shard$it]" } ?: ""

        // Enhanced debug output for tracking duplicate generation
        if (baseFieldName.contains("appLogger") || baseFieldName.contains("connectivity")) {
            val debugInfo = buildString {
                appendLine("\n[GlobalFieldNameRegistry] Request for: $baseFieldName$shardInfo")
                appendLine("  BaseName: $baseName")
                appendLine("  TypeHint: $typeHint")
                appendLine("  Current usedFieldNames size: ${usedFieldNames.size}")
                appendLine("  Already used: ${baseFieldName in usedFieldNames}")

                // Show all currently used names that contain appLogger
                if (baseFieldName.contains("appLogger")) {
                    appendLine("  All appLogger-related fields already registered:")
                    usedFieldNames.filter { it.contains("appLogger") }.forEach { name ->
                        appendLine("    - $name")
                    }
                }

                // Print stack trace to understand where the request comes from
                val stackTrace = Thread.currentThread().stackTrace
                appendLine("  Called from:")
                stackTrace.take(10).drop(2).forEach { frame ->
                    if (frame.className.contains("metro")) {
                        appendLine("    at ${frame.className}.${frame.methodName}(${frame.fileName}:${frame.lineNumber})")
                    }
                }
            }

            // Write to a debug file
            val debugFile = java.io.File("/tmp/metro_debug.log")
            debugFile.appendText(debugInfo)
            println(debugInfo)  // Also print for potential visibility
        }

        // Try without counter first
        if (baseFieldName !in usedFieldNames) {
            usedFieldNames.add(baseFieldName)
            // Debug output
            if (baseFieldName.contains("appLogger") || baseFieldName.contains("connectivity")) {
                println("  --> Generated: $baseFieldName (first use)$shardInfo")
                println("  Total fields registered: ${usedFieldNames.size}")
            }
            return baseFieldName
        }

        // Add incrementing counter for uniqueness
        var counter = fieldCounters.getOrPut(baseFieldName) { 1 }
        while (true) {
            val candidate = "${baseFieldName}$${counter}"
            if (candidate !in usedFieldNames) {
                usedFieldNames.add(candidate)
                fieldCounters[baseFieldName] = counter + 1
                // Debug output
                if (baseFieldName.contains("appLogger") || baseFieldName.contains("connectivity")) {
                    println("  --> Generated: $candidate (counter: $counter)$shardInfo")
                    println("  Total fields registered: ${usedFieldNames.size}")
                    println("  WARNING: Field name collision detected! Original '$baseFieldName' already used.")
                }
                return candidate
            }
            counter++
        }
    }

    /**
     * Check if a field name is already used.
     */
    internal fun isUsed(fieldName: String): Boolean = fieldName in usedFieldNames

    /**
     * Reserve a field name without generating it.
     */
    internal fun reserve(fieldName: String) {
        usedFieldNames.add(fieldName)
    }

    /**
     * Clear all registered names (for testing).
     */
    internal fun clear() {
        usedFieldNames.clear()
        fieldCounters.clear()
    }

    /**
     * Get statistics for debugging.
     */
    internal fun stats(): String {
        return "GlobalFieldNameRegistry: ${usedFieldNames.size} unique fields, " +
               "${fieldCounters.size} base names with counters"
    }
}
