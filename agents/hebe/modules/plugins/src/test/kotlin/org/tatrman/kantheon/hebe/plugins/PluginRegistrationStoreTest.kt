@file:Suppress("TooGenericExceptionCaught", "NewLineAtEndOfFile")

package org.tatrman.kantheon.hebe.plugins

import org.tatrman.kantheon.hebe.api.Tool
import org.tatrman.kantheon.hebe.api.ToolSpec
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PluginRegistrationStoreTest {
    private lateinit var store: PluginRegistrationStore

    @BeforeEach
    fun setup() {
        store = PluginRegistrationStore()
    }

    @Test
    fun `register and get returns stored registration`() =
        runTest {
            val tools = listOf(createMockTool("hello", "say_hello"))
            store.register("hello-plugin", tools)

            val registration = store.get("hello-plugin")

            assertTrue(registration != null)
            assertEquals("hello-plugin", registration!!.pluginId)
            assertEquals(1, registration.tools.size)
        }

    @Test
    fun `get returns null for non-existent plugin`() {
        val registration = store.get("non-existent")

        assertNull(registration)
    }

    @Test
    fun `unregister removes and returns registration`() {
        val tools = listOf(createMockTool("hello", "say_hello"))
        store.register("hello-plugin", tools)

        val removed = store.unregister("hello-plugin")

        assertTrue(removed != null)
        assertEquals("hello-plugin", removed!!.pluginId)
        assertNull(store.get("hello-plugin"))
    }

    @Test
    fun `unregister returns null for non-existent plugin`() {
        val removed = store.unregister("non-existent")

        assertNull(removed)
    }

    @Test
    fun `all returns all registrations`() {
        val tools1 = listOf(createMockTool("hello", "say_hello"))
        val tools2 = listOf(createMockTool("echo", "echo_message"))
        store.register("hello-plugin", tools1)
        store.register("echo-plugin", tools2)

        val all = store.all()

        assertEquals(2, all.size)
    }

    @Test
    fun `register overwrites existing registration`() {
        val tools1 = listOf(createMockTool("hello", "say_hello"))
        val tools2 = listOf(createMockTool("hello", "greet"))
        store.register("hello-plugin", tools1)
        store.register("hello-plugin", tools2)

        val registration = store.get("hello-plugin")

        assertEquals(1, registration!!.tools.size)
    }

    @Test
    fun `all returns empty when no registrations`() {
        val all = store.all()

        assertTrue(all.isEmpty())
    }

    @Test
    fun `multiple plugins can be registered concurrently`() =
        runTest {
            val threads =
                (1..10).map { i ->
                    Thread {
                        store.register("plugin-$i", listOf(createMockTool("p$i", "tool")))
                    }
                }
            threads.forEach { it.start() }
            threads.forEach { it.join() }

            val all = store.all()
            assertEquals(10, all.size)
        }

    private fun createMockTool(
        pluginId: String,
        toolName: String,
    ): NamespacedTool {
        val tool =
            object : Tool {
                override val spec: ToolSpec =
                    ToolSpec(
                        name = toolName,
                        description = "Test tool",
                        schema = kotlinx.serialization.json.JsonObject(emptyMap()),
                    )
                override val risk = org.tatrman.kantheon.hebe.api.RiskLevel.Low

                override suspend fun invoke(
                    args: kotlinx.serialization.json.JsonObject,
                    ctx: org.tatrman.kantheon.hebe.api.ToolContext,
                ) = org.tatrman.kantheon.hebe.api.ToolResult
                    .Ok(kotlinx.serialization.json.JsonObject(emptyMap()))
            }
        return NamespacedTool(tool, pluginId)
    }
}
