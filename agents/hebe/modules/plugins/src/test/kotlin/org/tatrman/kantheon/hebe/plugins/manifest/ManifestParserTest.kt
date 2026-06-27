@file:Suppress("TooGenericExceptionCaught", "MagicNumber", "NewLineAtEndOfFile")

package org.tatrman.kantheon.hebe.plugins.manifest

import org.tatrman.kantheon.hebe.plugin.api.Capability
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ManifestParserTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `parseFromJar reads valid TOML from JAR`() =
        runTest {
            val jarPath =
                createTestJar(
                    """
                    hebe_api_version = "0.1.x"
                    capabilities = ["tool"]
                    permissions = []
                    allowlist_domains = []
                    """.trimIndent(),
                )

            val result = ManifestParser.parseFromJar(jarPath, "plugin.toml")

            assertTrue(result is ManifestResult.Ok, "Expected Ok but got: $result")
            val manifest = (result as ManifestResult.Ok).value
            assertEquals("0.1.x", manifest.hebeApiVersion)
            assertTrue(Capability.Tool in manifest.capabilities)
        }

    @Test
    fun `parseFromJar returns error when JAR is missing`() =
        runTest {
            val nonExistentPath = tempDir.resolve("nonexistent.jar")

            val result = ManifestParser.parseFromJar(nonExistentPath, "plugin.toml")

            assertTrue(result is ManifestResult.Error, "Expected Error but got: $result")
            val errors = (result as ManifestResult.Error).errors
            assertTrue(errors.any { it.message.contains("Failed to read from JAR") })
        }

    @Test
    fun `parseFromJar returns error when TOML file missing in JAR`() =
        runTest {
            val jarPath = createTestJar("")

            val result = ManifestParser.parseFromJar(jarPath, "nonexistent.toml")

            assertTrue(result is ManifestResult.Error, "Expected Error but got: $result")
            val errors = (result as ManifestResult.Error).errors
            assertTrue(errors.any { it.message.contains("No nonexistent.toml found") })
        }

    @Test
    fun `parseFromJar parses all capability types`() =
        runTest {
            val jarPath =
                createTestJar(
                    """
                    hebe_api_version = "0.2.x"
                    capabilities = ["tool", "skill"]
                    permissions = []
                    allowlist_domains = []
                    """.trimIndent(),
                )

            val result = ManifestParser.parseFromJar(jarPath, "plugin.toml")

            assertTrue(result is ManifestResult.Ok)
            val manifest = (result as ManifestResult.Ok).value
            assertTrue(Capability.Tool in manifest.capabilities)
            assertTrue(Capability.Skill in manifest.capabilities)
        }

    @Test
    fun `parseFromJar parses allowlist_domains`() =
        runTest {
            val jarPath =
                createTestJar(
                    """
                    hebe_api_version = "0.1.x"
                    capabilities = ["tool"]
                    permissions = []
                    allowlist_domains = ["example.com", "api.service.io"]
                    """.trimIndent(),
                )

            val result = ManifestParser.parseFromJar(jarPath, "plugin.toml")

            assertTrue(result is ManifestResult.Ok)
            val manifest = (result as ManifestResult.Ok).value
            assertEquals(listOf("example.com", "api.service.io"), manifest.allowlistDomains)
        }

    @Test
    fun `parseFromJar parses with signature and publisher key`() =
        runTest {
            val jarPath =
                createTestJar(
                    """
                    hebe_api_version = "0.1.x"
                    capabilities = ["tool"]
                    permissions = []
                    allowlist_domains = []
                    signature = "base64signature"
                    publisher_key = "abc123"
                    """.trimIndent(),
                )

            val result = ManifestParser.parseFromJar(jarPath, "plugin.toml")

            assertTrue(result is ManifestResult.Ok)
            val manifest = (result as ManifestResult.Ok).value
            assertEquals("base64signature", manifest.signature)
            assertEquals("abc123", manifest.publisherKey)
        }

    @Test
    fun `parseFromJar handles empty permissions array`() =
        runTest {
            val jarPath =
                createTestJar(
                    """
                    hebe_api_version = "0.1.x"
                    capabilities = []
                    permissions = []
                    allowlist_domains = []
                    """.trimIndent(),
                )

            val result = ManifestParser.parseFromJar(jarPath, "plugin.toml")

            assertTrue(result is ManifestResult.Ok)
            val manifest = (result as ManifestResult.Ok).value
            assertTrue(manifest.capabilities.isEmpty())
            assertTrue(manifest.permissions.isEmpty())
        }

    @Test
    fun `parseFromJar returns error for invalid TOML`() =
        runTest {
            val jarPath =
                createTestJar(
                    """
                    hebe_api_version = "0.1.x"
                    capabilities = ["tool"  this is invalid
                    """.trimIndent(),
                )

            val result = ManifestParser.parseFromJar(jarPath, "plugin.toml")

            assertTrue(result is ManifestResult.Error, "Expected Error for invalid TOML")
        }

    @Test
    fun `parsePath reads valid TOML from filesystem`() {
        val tomlFile = tempDir.resolve("plugin.toml")
        java.nio.file.Files.writeString(
            tomlFile,
            """
            hebe_api_version = "0.1.x"
            capabilities = ["tool"]
            permissions = []
            allowlist_domains = []
            """.trimIndent(),
        )

        val result = ManifestParser.parse(tomlFile)

        assertTrue(result is ManifestResult.Ok, "Expected Ok but got: $result")
        val manifest = (result as ManifestResult.Ok).value
        assertEquals("0.1.x", manifest.hebeApiVersion)
    }

    @Test
    fun `parsePath returns error when file missing`() {
        val nonExistentPath = tempDir.resolve("nonexistent.toml")

        val result = ManifestParser.parse(nonExistentPath)

        assertTrue(result is ManifestResult.Error, "Expected Error but got: $result")
    }

    private fun createTestJar(tomlContent: String): Path {
        val jarFile = tempDir.resolve("test-plugin.jar")
        java.util.jar.JarOutputStream(java.io.FileOutputStream(jarFile.toFile())).use { jos ->
            val entry = java.util.jar.JarEntry("plugin.toml")
            jos.putNextEntry(entry)
            jos.write(tomlContent.toByteArray())
            jos.closeEntry()
        }
        return jarFile
    }
}
