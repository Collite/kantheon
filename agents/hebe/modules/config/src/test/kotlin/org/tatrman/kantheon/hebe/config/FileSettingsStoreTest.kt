@file:Suppress("TooGenericExceptionCaught", "NewLineAtEndOfFile")

package org.tatrman.kantheon.hebe.config

import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FileSettingsStoreTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var settingsStore: FileSettingsStore

    @BeforeEach
    fun setup() {
        val settingsPath = tempDir.resolve("settings.json")
        settingsStore = FileSettingsStore(settingsPath)
    }

    @Test
    fun `setInstalledPlugin and getInstalledPlugins returns stored plugins`() =
        runTest {
            settingsStore.setInstalledPlugin("hello-plugin", "0.1.0", "ghcr.io/test/hello-plugin")
            settingsStore.setInstalledPlugin("echo-plugin", "1.0.0", "ghcr.io/test/echo-plugin")

            val plugins = settingsStore.getInstalledPlugins()

            assertEquals(2, plugins.size)
            val hello = plugins.find { it.name == "hello-plugin" }
            assertEquals("0.1.0", hello?.version)
            assertEquals("ghcr.io/test/hello-plugin", hello?.source)
        }

    @Test
    fun `getInstalledPlugin returns specific plugin`() =
        runTest {
            settingsStore.setInstalledPlugin("hello-plugin", "0.1.0", "test-source")

            val plugin = settingsStore.getInstalledPlugin("hello-plugin")

            assertEquals("hello-plugin", plugin?.name)
            assertEquals("0.1.0", plugin?.version)
            assertEquals("test-source", plugin?.source)
        }

    @Test
    fun `getInstalledPlugin returns null for non-existent plugin`() =
        runTest {
            val plugin = settingsStore.getInstalledPlugin("non-existent")

            assertNull(plugin)
        }

    @Test
    fun `removeInstalledPlugin removes plugin and returns true`() =
        runTest {
            settingsStore.setInstalledPlugin("hello-plugin", "0.1.0", "source")

            val removed = settingsStore.removeInstalledPlugin("hello-plugin")

            assertTrue(removed)
            assertNull(settingsStore.getInstalledPlugin("hello-plugin"))
        }

    @Test
    fun `removeInstalledPlugin returns false for non-existent plugin`() =
        runTest {
            val removed = settingsStore.removeInstalledPlugin("non-existent")

            assertFalse(removed)
        }

    @Test
    fun `getInstalledPlugins returns empty list when no plugins`() =
        runTest {
            val plugins = settingsStore.getInstalledPlugins()

            assertTrue(plugins.isEmpty())
        }

    @Test
    fun `settings persist across store instances`() =
        runTest {
            settingsStore.setInstalledPlugin("persistent-plugin", "2.0.0", "source")

            val newStore = FileSettingsStore(tempDir.resolve("settings.json"))
            val plugins = newStore.getInstalledPlugins()

            assertEquals(1, plugins.size)
            assertEquals("persistent-plugin", plugins[0].name)
        }

    @Test
    fun `setInstalledPlugin overwrites existing plugin with same name`() =
        runTest {
            settingsStore.setInstalledPlugin("hello-plugin", "0.1.0", "source1")
            settingsStore.setInstalledPlugin("hello-plugin", "1.0.0", "source2")

            val plugins = settingsStore.getInstalledPlugins()

            assertEquals(1, plugins.size)
            assertEquals("1.0.0", plugins[0].version)
            assertEquals("source2", plugins[0].source)
        }

    @Test
    fun `getInstalledPlugins returns plugins sorted by installedAt`() =
        runTest {
            // Add with small delays to ensure different timestamps
            settingsStore.setInstalledPlugin("plugin-b", "1.0.0", "source")
            kotlinx.coroutines.delay(10)
            settingsStore.setInstalledPlugin("plugin-a", "1.0.0", "source")
            kotlinx.coroutines.delay(10)
            settingsStore.setInstalledPlugin("plugin-c", "1.0.0", "source")

            val plugins = settingsStore.getInstalledPlugins()

            assertEquals(3, plugins.size)
            // Verify they are sorted by installedAt (oldest first)
            assertTrue(plugins[0].installedAt <= plugins[1].installedAt)
            assertTrue(plugins[1].installedAt <= plugins[2].installedAt)
        }
}
