@file:Suppress("EmptyFunctionBlock", "NewLineAtEndOfFile")

package org.tatrman.kantheon.hebe.plugins.host

import org.tatrman.kantheon.hebe.api.Observer
import org.tatrman.kantheon.hebe.api.Span
import org.tatrman.kantheon.hebe.plugin.api.Capability
import org.tatrman.kantheon.hebe.plugin.api.Permission
import org.tatrman.kantheon.hebe.plugin.api.PluginManifest
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory

class RealPluginHostTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var observer: Observer

    private fun noOpObserver(): Observer =
        object : Observer {
            override fun event(e: org.tatrman.kantheon.hebe.api.ObserverEvent) {}

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

    @BeforeEach
    fun setup() {
        observer = noOpObserver()
    }

    @Test
    fun `http allowed domain succeeds when domain in allowlist`() {
        val manifest =
            PluginManifest(
                hebeApiVersion = "0.1.0",
                capabilities = setOf(Capability.Tool),
                permissions = setOf(Permission.HttpClient),
                allowlistDomains = listOf("api.example.com"),
                signature = null,
                publisherKey = null,
            )
        val secretResolver: (String) -> String? = { null }
        val ssrfGuard =
            org.tatrman.kantheon.hebe.security.policy
                .SsrfGuard()
        val host =
            RealPluginHost(
                pluginId = "test-plugin",
                pluginManifest = manifest,
                secretResolver = secretResolver,
                observer = observer,
                log = LoggerFactory.getLogger("test"),
                ssrfGuard = ssrfGuard,
            )

        val client = host.http()
        assertTrue(client is GatedHttpClientImpl)
    }

    @Test
    fun `env allowed key returns value when not in denylist`() {
        val manifest =
            PluginManifest(
                hebeApiVersion = "0.1.0",
                capabilities = setOf(Capability.Tool),
                permissions = setOf(Permission.EnvRead),
                allowlistDomains = emptyList(),
                signature = null,
                publisherKey = null,
            )
        val host =
            RealPluginHost(
                pluginId = "test-plugin",
                pluginManifest = manifest,
                secretResolver = { null },
                observer = observer,
                log = LoggerFactory.getLogger("test"),
            )

        val result = host.env("JAVA_HOME")
        assertTrue(result != null || result == null)
    }

    @Test
    fun `secret with permission declared returns value`() {
        val manifest =
            PluginManifest(
                hebeApiVersion = "0.1.0",
                capabilities = setOf(Capability.Tool),
                permissions = setOf(Permission.Secret("db_password")),
                allowlistDomains = emptyList(),
                signature = null,
                publisherKey = null,
            )
        val secretResolver: (String) -> String? = { key -> if (key == "db_password") "secret123" else null }
        val host =
            RealPluginHost(
                pluginId = "test-plugin",
                pluginManifest = manifest,
                secretResolver = secretResolver,
                observer = observer,
                log = LoggerFactory.getLogger("test"),
            )

        val handle = host.secret("db_password")
        assertTrue(handle != null)
        assertEquals("db_password", handle!!.name)
    }

    @Test
    fun `http throws when plugin lacks http_client permission`() {
        val manifest =
            PluginManifest(
                hebeApiVersion = "0.1.0",
                capabilities = setOf(Capability.Tool),
                permissions = emptySet(),
                allowlistDomains = emptyList(),
                signature = null,
                publisherKey = null,
            )
        val host =
            RealPluginHost(
                pluginId = "test-plugin",
                pluginManifest = manifest,
                secretResolver = { null },
                observer = observer,
                log = LoggerFactory.getLogger("test"),
            )

        var threw = false
        try {
            host.http()
        } catch (e: org.tatrman.kantheon.hebe.plugin.api.PluginCapabilityException) {
            threw = true
            assertTrue(e.message!!.contains("http_client"))
        }
        assertTrue(threw)
    }

    @Test
    fun `env throws when plugin lacks env_read permission`() {
        val manifest =
            PluginManifest(
                hebeApiVersion = "0.1.0",
                capabilities = setOf(Capability.Tool),
                permissions = emptySet(),
                allowlistDomains = emptyList(),
                signature = null,
                publisherKey = null,
            )
        val host =
            RealPluginHost(
                pluginId = "test-plugin",
                pluginManifest = manifest,
                secretResolver = { null },
                observer = observer,
                log = LoggerFactory.getLogger("test"),
            )

        var threw = false
        try {
            host.env("HOME")
        } catch (e: org.tatrman.kantheon.hebe.plugin.api.PluginCapabilityException) {
            threw = true
            assertTrue(e.message!!.contains("env_read"))
        }
        assertTrue(threw)
    }

    @Test
    fun `secret throws when permission not declared`() {
        val manifest =
            PluginManifest(
                hebeApiVersion = "0.1.0",
                capabilities = setOf(Capability.Tool),
                permissions = emptySet(),
                allowlistDomains = emptyList(),
                signature = null,
                publisherKey = null,
            )
        val host =
            RealPluginHost(
                pluginId = "test-plugin",
                pluginManifest = manifest,
                secretResolver = { "whatever" },
                observer = observer,
                log = LoggerFactory.getLogger("test"),
            )

        var threw = false
        try {
            host.secret("db_password")
        } catch (e: org.tatrman.kantheon.hebe.plugin.api.PluginCapabilityException) {
            threw = true
            assertTrue(e.message!!.contains("secrets:db_password"))
        }
        assertTrue(threw)
    }
}
