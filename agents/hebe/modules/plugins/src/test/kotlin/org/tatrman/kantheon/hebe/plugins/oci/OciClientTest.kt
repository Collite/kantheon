@file:Suppress("UnusedPrivateProperty", "EmptyFunctionBlock", "NewLineAtEndOfFile")

package org.tatrman.kantheon.hebe.plugins.oci

import org.tatrman.kantheon.hebe.config.SecretStoreProvider
import java.net.URI
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class OciClientTest {
    private lateinit var secretStore: SecretStoreProvider

    @BeforeEach
    fun setup() {
        secretStore =
            object : SecretStoreProvider {
                override suspend fun get(key: String): ByteArray? = null

                override suspend fun set(
                    key: String,
                    value: ByteArray,
                ) {}

                override suspend fun delete(key: String): Boolean = false

                override suspend fun list(): List<String> = emptyList()
            }
    }

    @Test
    fun `parseRef extracts host repo and tag from simple ref`() {
        val result = parseRef("ghcr.io/user/hello-plugin:0.1.0")

        assertEquals("ghcr.io", result.first)
        assertEquals("user/hello-plugin", result.second)
        assertEquals("0.1.0", result.third)
    }

    @Test
    fun `parseRef defaults to latest when no tag`() {
        val result = parseRef("ghcr.io/user/hello-plugin")

        assertEquals("ghcr.io", result.first)
        assertEquals("user/hello-plugin", result.second)
        assertEquals("latest", result.third)
    }

    @Test
    fun `parseRef handles registry with subdomain`() {
        val result = parseRef("myregistry.azurecr.io/plugin:v1")

        assertEquals("myregistry.azurecr.io", result.first)
        assertEquals("plugin", result.second)
        assertEquals("v1", result.third)
    }

    @Test
    fun `parseRef handles path with multiple segments`() {
        val result = parseRef("ghcr.io/myorg/myteam/hello-plugin:latest")

        assertEquals("ghcr.io", result.first)
        assertEquals("myorg/myteam/hello-plugin", result.second)
        assertEquals("latest", result.third)
    }

    private fun parseRef(ref: String): Triple<String, String, String> {
        val uri = URI("docker://$ref")
        val host = uri.host ?: throw IllegalArgumentException("Invalid OCI ref: $ref")
        val path = uri.path?.trimStart('/') ?: throw IllegalArgumentException("Invalid OCI ref: $ref")
        val lastColon = path.lastIndexOf(':')
        val repo = if (lastColon > 0) path.substring(0, lastColon) else path
        val tag = if (lastColon > 0) path.substring(lastColon + 1) else "latest"
        return Triple(host, repo, tag)
    }
}
