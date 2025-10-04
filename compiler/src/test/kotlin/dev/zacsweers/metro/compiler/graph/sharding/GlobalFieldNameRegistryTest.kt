// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph.sharding

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GlobalFieldNameRegistryTest {
    @Test
    fun `generates unique field names`() {
        val registry = GlobalFieldNameRegistry()

        // First occurrence should not have counter
        val name1 = registry.generateUniqueFieldName("appLogger", "ProviderField")
        assertEquals("appLoggerProviderField", name1)

        // Second occurrence should have counter
        val name2 = registry.generateUniqueFieldName("appLogger", "ProviderField")
        assertEquals("appLoggerProviderField$1", name2)

        // Third occurrence increments counter
        val name3 = registry.generateUniqueFieldName("appLogger", "ProviderField")
        assertEquals("appLoggerProviderField$2", name3)

        // Different base name starts fresh
        val name4 = registry.generateUniqueFieldName("cacheManager", "ProviderField")
        assertEquals("cacheManagerProviderField", name4)
    }

    @Test
    fun `handles different type hints correctly`() {
        val registry = GlobalFieldNameRegistry()

        // Provider fields
        val providerField = registry.generateUniqueFieldName("service", "ProviderField")
        assertEquals("serviceProviderField", providerField)

        // Instance fields
        val instanceField = registry.generateUniqueFieldName("service", "InstanceField")
        assertEquals("serviceInstanceField", instanceField)

        // The same base with provider hint again should get counter
        val providerField2 = registry.generateUniqueFieldName("service", "ProviderField")
        assertEquals("serviceProviderField$1", providerField2)
    }

    @Test
    fun `prevents duplicate field names across shards`() {
        val registry = GlobalFieldNameRegistry()
        val generatedNames = mutableSetOf<String>()

        // Simulate multiple shards generating fields
        repeat(10) { shardIndex ->
            repeat(5) { fieldIndex ->
                val name = registry.generateUniqueFieldName(
                    "commonBinding",
                    "ProviderField"
                )
                assertTrue(generatedNames.add(name),
                    "Duplicate name generated: $name in shard $shardIndex")
            }
        }

        // Should have 50 unique names
        assertEquals(50, generatedNames.size)

        // Verify the pattern: first without counter, then with incrementing counters
        assertTrue(generatedNames.contains("commonBindingProviderField"))
        assertTrue(generatedNames.contains("commonBindingProviderField$1"))
        assertTrue(generatedNames.contains("commonBindingProviderField$49"))
    }

    @Test
    fun `reserve method prevents name reuse`() {
        val registry = GlobalFieldNameRegistry()

        // Reserve a name
        registry.reserve("myFieldProviderField")

        // Trying to generate with the same pattern should get counter
        val name = registry.generateUniqueFieldName("myField", "ProviderField")
        assertEquals("myFieldProviderField$1", name)
    }

    @Test
    fun `isUsed method works correctly`() {
        val registry = GlobalFieldNameRegistry()

        // Generate a name
        val name = registry.generateUniqueFieldName("test", "ProviderField")

        // Check it's marked as used
        assertTrue(registry.isUsed(name))
        assertTrue(registry.isUsed("testProviderField"))

        // Check non-generated name
        assertFalse(registry.isUsed("nonExistentField"))
    }

    @Test
    fun `clear method resets registry`() {
        val registry = GlobalFieldNameRegistry()

        // Generate some names
        registry.generateUniqueFieldName("field1", "ProviderField")
        registry.generateUniqueFieldName("field1", "ProviderField")
        registry.generateUniqueFieldName("field2", "ProviderField")

        // Clear the registry
        registry.clear()

        // Should be able to generate the same names again
        val name1 = registry.generateUniqueFieldName("field1", "ProviderField")
        assertEquals("field1ProviderField", name1)

        val name2 = registry.generateUniqueFieldName("field2", "ProviderField")
        assertEquals("field2ProviderField", name2)
    }

    @Test
    fun `stats method provides useful debugging info`() {
        val registry = GlobalFieldNameRegistry()

        // Generate some names
        registry.generateUniqueFieldName("logger", "ProviderField")
        registry.generateUniqueFieldName("logger", "ProviderField")
        registry.generateUniqueFieldName("service", "ProviderField")
        registry.generateUniqueFieldName("repository", "ProviderField")

        val stats = registry.stats()
        assertTrue(stats.contains("4 unique fields"))
        assertTrue(stats.contains("1 base names with counters"))
    }

    @Test
    fun `simulates real sharding scenario`() {
        val registry = GlobalFieldNameRegistry()

        // Simulate the actual bug scenario where appLoggerProviderField$1 was duplicated
        val shard0Names = mutableListOf<String>()
        val shard1Names = mutableListOf<String>()

        // Shard 0 generates some fields
        shard0Names.add(registry.generateUniqueFieldName("appLogger", "ProviderField"))
        shard0Names.add(registry.generateUniqueFieldName("cacheManager", "ProviderField"))
        shard0Names.add(registry.generateUniqueFieldName("appLogger", "ProviderField")) // Duplicate in same shard

        // Shard 1 generates overlapping fields
        shard1Names.add(registry.generateUniqueFieldName("appLogger", "ProviderField"))
        shard1Names.add(registry.generateUniqueFieldName("authService", "ProviderField"))

        // Verify no duplicates across all names
        val allNames = shard0Names + shard1Names
        assertEquals(allNames.size, allNames.toSet().size, "Found duplicate field names!")

        // Verify specific names
        assertTrue(shard0Names.contains("appLoggerProviderField"))
        assertTrue(shard0Names.contains("appLoggerProviderField$1"))
        assertTrue(shard1Names.contains("appLoggerProviderField$2"))
    }

    private fun assertFalse(condition: Boolean, message: String = "") {
        assertTrue(!condition, message)
    }
}
