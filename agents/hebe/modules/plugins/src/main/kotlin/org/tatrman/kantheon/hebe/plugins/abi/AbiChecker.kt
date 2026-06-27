@file:Suppress("MagicNumber", "NewLineAtEndOfFile", "CyclomaticComplexMethod", "TooManyFunctions")

package org.tatrman.kantheon.hebe.plugins.abi

import org.tatrman.kantheon.hebe.plugin.api.AbiVersion
import org.tatrman.kantheon.hebe.plugin.api.PluginManifest

sealed class AbiResult {
    data object Ok : AbiResult()

    data class Incompatible(
        val pluginVersion: String,
        val hostVersion: String,
        val hint: String,
    ) : AbiResult()
}

object AbiChecker {
    fun check(manifest: PluginManifest): AbiResult {
        val hostVersion = AbiVersion.CURRENT
        val pluginVersion = manifest.hebeApiVersion

        val matches =
            when {
                isExactMatch(pluginVersion, hostVersion) -> true
                isCaretMatch(pluginVersion, hostVersion) -> true
                isRangeMatch(pluginVersion, hostVersion) -> true
                else -> false
            }

        return if (matches) {
            AbiResult.Ok
        } else {
            AbiResult.Incompatible(
                pluginVersion = pluginVersion,
                hostVersion = hostVersion,
                hint = buildHint(pluginVersion, hostVersion),
            )
        }
    }

    private fun isExactMatch(
        manifestVersion: String,
        hostVersion: String,
    ): Boolean = manifestVersion == hostVersion

    private fun isCaretMatch(
        manifestVersion: String,
        hostVersion: String,
    ): Boolean {
        val parts = manifestVersion.split(".")
        val prefix =
            parts
                .takeWhile { it != "x" && it != "*" }
                .joinToString(".")
        if (prefix.isEmpty()) return true
        return hostVersion == prefix || hostVersion.startsWith("$prefix.")
    }

    private fun isRangeMatch(
        manifestVersion: String,
        hostVersion: String,
    ): Boolean {
        val bounds = manifestVersion.trim().split("\\s+".toRegex())
        if (bounds.size < 2) return false

        var minVersion: String? = null
        var maxVersion: String? = null

        for (bound in bounds) {
            val trimmed = bound.trim()
            when {
                trimmed.startsWith(">=") -> minVersion = trimmed.removePrefix(">=").trim()
                trimmed.startsWith(">") -> {
                    val v = trimmed.removePrefix(">").trim()
                    if (minVersion == null || greaterThan(v, minVersion)) {
                        minVersion = v
                    }
                }

                trimmed.startsWith("<=") -> {
                    val v = trimmed.removePrefix("<=").trim()
                    if (maxVersion == null || lessThan(v, maxVersion)) {
                        maxVersion = v
                    }
                }

                trimmed.startsWith("<") -> {
                    val v = trimmed.removePrefix("<").trim()
                    val ltVersion = decrementPatch(v)
                    if (maxVersion == null || lessThan(ltVersion, maxVersion)) {
                        maxVersion = ltVersion
                    }
                }
            }
        }

        if (minVersion != null && !versionCompare(hostVersion, minVersion, ">=")) return false
        if (maxVersion != null && !versionCompare(hostVersion, maxVersion, "<=")) return false

        return minVersion != null || maxVersion != null
    }

    private fun versionCompare(
        version: String,
        bound: String,
        op: String,
    ): Boolean {
        val vParts = parseSemVer(version)
        val bParts = parseSemVer(bound)

        val cmp = compareVersions(vParts, bParts)
        return when (op) {
            ">=" -> cmp >= 0
            "<=" -> cmp <= 0
            ">" -> cmp > 0
            "<" -> cmp < 0
            else -> false
        }
    }

    private fun compareVersions(
        a: List<Int>,
        b: List<Int>,
    ): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val av = a.getOrElse(i) { 0 }
            val bv = b.getOrElse(i) { 0 }
            if (av != bv) return av - bv
        }
        return 0
    }

    private fun parseSemVer(version: String): List<Int> =
        version
            .split(".", "-", "+")
            .first()
            .split(".")
            .mapNotNull { it.toIntOrNull() }

    private fun greaterThan(
        v1: String,
        v2: String,
    ): Boolean = compareVersions(parseSemVer(v1), parseSemVer(v2)) > 0

    private fun lessThan(
        v1: String,
        v2: String,
    ): Boolean = compareVersions(parseSemVer(v1), parseSemVer(v2)) < 0

    private fun decrementPatch(version: String): String {
        val parts = version.split(".")
        if (parts.size < 3) return version
        val patch = parts[2].toIntOrNull() ?: return version
        return "${parts[0]}.${parts[1]}.${patch - 1}"
    }

    private fun buildHint(
        pluginVersion: String,
        hostVersion: String,
    ): String {
        val pluginMajor = parseSemVer(pluginVersion).firstOrNull() ?: 0
        val hostMajor = parseSemVer(hostVersion).firstOrNull() ?: 0

        return when {
            pluginMajor > hostMajor -> {
                "Plugin requires API $pluginVersion but host is $hostVersion. " +
                    "Upgrade hebe to a compatible version."
            }

            else -> {
                "Plugin requires API $pluginVersion but host is $hostVersion. " +
                    "Use a plugin version compatible with hebe $hostVersion."
            }
        }
    }
}
