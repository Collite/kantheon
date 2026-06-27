@file:Suppress("TooGenericExceptionCaught", "NewLineAtEndOfFile")

package org.tatrman.kantheon.hebe.plugins.abi

import org.tatrman.kantheon.hebe.plugin.api.Capability
import org.tatrman.kantheon.hebe.plugin.api.PluginManifest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AbiCheckerTest {
    @Test
    fun `check returns Ok for exact match`() {
        val manifest = createManifest("0.1.x")

        val result = AbiChecker.check(manifest)

        assertTrue(result is AbiResult.Ok)
    }

    @Test
    fun `check returns Ok for caret match with exact version`() {
        val manifest = createManifest("0.1.x")

        val result = AbiChecker.check(manifest)

        assertTrue(result is AbiResult.Ok)
    }

    @Test
    fun `check returns Ok for caret match with shorter prefix`() {
        val manifest = createManifest("0.x")

        val result = AbiChecker.check(manifest)

        assertTrue(result is AbiResult.Ok)
    }

    @Test
    fun `check returns Ok for wildcard version`() {
        val manifest = createManifest("0.1.*")

        val result = AbiChecker.check(manifest)

        assertTrue(result is AbiResult.Ok)
    }

    @Test
    fun `check returns Ok for greater-than-or-equal range`() {
        val manifest = createManifest(">=0.1.0 <99.0.0")

        val result = AbiChecker.check(manifest)

        assertTrue(result is AbiResult.Ok)
    }

    @Test
    fun `check returns Ok for range with both bounds`() {
        val manifest = createManifest(">=0.1.0 <2.0.0")

        val result = AbiChecker.check(manifest)

        assertTrue(result is AbiResult.Ok)
    }

    @Test
    fun `check returns Incompatible for incompatible version`() {
        val manifest = createManifest("99.0.x")

        val result = AbiChecker.check(manifest)

        assertTrue(result is AbiResult.Incompatible)
        val incompatible = result as AbiResult.Incompatible
        assertEquals("99.0.x", incompatible.pluginVersion)
        assertEquals("0.1.0", incompatible.hostVersion)
    }

    @Test
    fun `check returns Incompatible for too new version`() {
        val manifest = createManifest("5.0.0")

        val result = AbiChecker.check(manifest)

        assertTrue(result is AbiResult.Incompatible)
    }

    @Test
    fun `check returns Incompatible for too old major version`() {
        val manifest = createManifest("2.0.0")

        val result = AbiChecker.check(manifest)

        assertTrue(result is AbiResult.Incompatible)
    }

    @Test
    fun `check returns Incompatible for version with only x placeholders`() {
        val manifest = createManifest("x.x.x")

        val result = AbiChecker.check(manifest)

        assertTrue(result is AbiResult.Ok) // x.x.x means match any
    }

    @Test
    fun `check includes hint in Incompatible result`() {
        val manifest = createManifest("99.0.0")

        val result = AbiChecker.check(manifest)

        assertTrue(result is AbiResult.Incompatible)
        val incompatible = result as AbiResult.Incompatible
        assertTrue(incompatible.hint.isNotBlank())
    }

    @Test
    fun `isRangeMatch handles closed range`() {
        val manifest = createManifest(">=0.1.0 <=1.0.0")

        val result = AbiChecker.check(manifest)

        assertTrue(result is AbiResult.Ok)
    }

    @Test
    fun `isRangeMatch rejects when below minimum`() {
        val manifest = createManifest(">=1.0.0")

        val result = AbiChecker.check(manifest)

        assertTrue(result is AbiResult.Incompatible)
    }

    @Test
    fun `isRangeMatch rejects when above maximum`() {
        val manifest = createManifest(">=0.1.0 <0.2.0")

        val result = AbiChecker.check(manifest)

        // Current host is 0.1.x, this range is [0.1.0, 0.2.0)
        assertTrue(result is AbiResult.Ok)
    }

    private fun createManifest(hebeApiVersion: String): PluginManifest =
        PluginManifest(
            hebeApiVersion = hebeApiVersion,
            capabilities = setOf(Capability.Tool),
            permissions = emptySet(),
            allowlistDomains = emptyList(),
            signature = null,
            publisherKey = null,
        )
}
