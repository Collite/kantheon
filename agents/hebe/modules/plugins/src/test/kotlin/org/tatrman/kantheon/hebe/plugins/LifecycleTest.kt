@file:Suppress("TooGenericExceptionCaught", "NewLineAtEndOfFile")

package org.tatrman.kantheon.hebe.plugins

import org.tatrman.kantheon.hebe.api.Observer
import org.tatrman.kantheon.hebe.api.ObserverEvent
import org.tatrman.kantheon.hebe.api.RiskLevel
import org.tatrman.kantheon.hebe.api.Span
import org.tatrman.kantheon.hebe.api.Tool
import org.tatrman.kantheon.hebe.api.ToolContext
import org.tatrman.kantheon.hebe.api.ToolResult
import org.tatrman.kantheon.hebe.api.ToolSpec
import org.tatrman.kantheon.hebe.api.security.ArgsRedactor
import org.tatrman.kantheon.hebe.config.PluginSignatureMode
import org.tatrman.kantheon.hebe.plugins.host.HostFactory
import org.tatrman.kantheon.hebe.plugins.signature.SignatureVerifier
import io.mockk.mockk
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class LifecycleTest {
    private val log = LoggerFactory.getLogger(LifecycleTest::class.java)

    private fun noOpObserver(): Observer =
        object : Observer {
            override fun event(e: ObserverEvent) {}

            override fun span(
                name: String,
                attrs: Map<String, Any>,
            ): Span =
                object : Span {
                    override fun setAttribute(
                        key: String,
                        value: Any,
                    ) {}

                    override fun recordError(t: Throwable) {}

                    override fun close() {}
                }
        }

    private fun buildLifecycle(
        tempDir: java.nio.file.Path,
        toolRegistry: Lifecycle.ToolRegistryWrapper,
        store: PluginRegistrationStore,
    ): Lifecycle {
        val observer = noOpObserver()
        return Lifecycle(
            pluginManager = mockk(relaxed = true),
            pluginDir = tempDir,
            toolRegistry = toolRegistry,
            hostFactory = HostFactory({ null }, observer, log),
            signatureVerifier =
                SignatureVerifier(
                    signatureMode = PluginSignatureMode.DISABLED,
                    trustedPublisherKeys = emptyList(),
                    log = log,
                ),
            observer = observer,
            pluginStore = store,
            secretResolver = { null },
            log = log,
        )
    }

    @Test
    fun `afterStart registers tools with namespaced names in registry and store`() {
        val tempDir = Files.createTempDirectory("lifecycle-test")
        val pluginJar = tempDir.resolve("hello-plugin.jar")
        pluginJar.toFile().writeBytes(
            javaClass.getResourceAsStream("/hello-plugin.jar")!!.readBytes(),
        )

        val registered = mutableListOf<String>()
        val toolRegistry =
            object : Lifecycle.ToolRegistryWrapper {
                override fun register(
                    name: String,
                    tool: Tool,
                ) {
                    registered += name
                }

                override fun unregister(name: String) {}
            }

        val store = PluginRegistrationStore()
        val lifecycle = buildLifecycle(tempDir, toolRegistry, store)
        val pf4j = HebePluginManager(tempDir, lifecycle)
        pf4j.loadPlugins()
        pf4j.startPlugins()

        try {
            assertTrue(
                registered.any { it == "hello:say_hello" },
                "Expected hello:say_hello in registry, got: $registered",
            )
            assertNotNull(store.get("hello"), "Expected 'hello' in store after start")
            val registration = store.get("hello")!!
            assertTrue(registration.tools.any { it.fullName == "hello:say_hello" })
        } finally {
            try {
                pf4j.stopPlugin("hello")
            } catch (_: Exception) {
            }
        }
    }

    @Test
    fun `beforeStop unregisters tools from registry and clears store`() {
        val tempDir = Files.createTempDirectory("lifecycle-test")
        val pluginJar = tempDir.resolve("hello-plugin.jar")
        pluginJar.toFile().writeBytes(
            javaClass.getResourceAsStream("/hello-plugin.jar")!!.readBytes(),
        )

        val unregistered = mutableListOf<String>()
        val toolRegistry =
            object : Lifecycle.ToolRegistryWrapper {
                override fun register(
                    name: String,
                    tool: Tool,
                ) {}

                override fun unregister(name: String) {
                    unregistered += name
                }
            }

        val store = PluginRegistrationStore()
        val lifecycle = buildLifecycle(tempDir, toolRegistry, store)
        val pf4j = HebePluginManager(tempDir, lifecycle)
        pf4j.loadPlugins()
        pf4j.startPlugins()
        pf4j.stopPlugin("hello")

        assertTrue(
            unregistered.any { it == "hello:say_hello" },
            "Expected hello:say_hello to be unregistered, got: $unregistered",
        )
        assertNull(store.get("hello"), "Expected store to be empty after stop")
    }

    @Test
    fun `NamespacedToolWrapper invoke passes args unchanged to delegate`() =
        runTest {
            val capturedArgs = mutableListOf<JsonObject>()
            val fakeTool =
                object : Tool {
                    override val spec = ToolSpec("say_hello", "says hello", JsonObject(emptyMap()))
                    override val risk = RiskLevel.Low

                    override suspend fun invoke(
                        args: JsonObject,
                        ctx: ToolContext,
                    ): ToolResult {
                        capturedArgs += args
                        return ToolResult.Ok(JsonPrimitive("ok"))
                    }
                }

            val namespacedTool = NamespacedTool(fakeTool, "hello")
            val wrapper = NamespacedToolWrapper(namespacedTool, ArgsRedactor.INSTANCE)

            val testArgs =
                buildJsonObject {
                    put("name", "world")
                    put("extra", "value")
                }
            wrapper.invoke(testArgs, mockk(relaxed = true))

            assertEquals(1, capturedArgs.size)
            assertEquals(testArgs, capturedArgs.single())
        }
}
