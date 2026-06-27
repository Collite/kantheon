@file:Suppress(
    "TooGenericExceptionCaught",
    "SafeCast",
    "UnusedParameter",
    "LongMethod",
    "NewLineAtEndOfFile",
    "CyclomaticComplexMethod",
    "MaxLineLength",
)

package org.tatrman.kantheon.hebe.plugins.manifest

import org.tatrman.kantheon.hebe.plugin.api.Capability
import org.tatrman.kantheon.hebe.plugin.api.Permission
import org.tatrman.kantheon.hebe.plugin.api.PluginManifest
import java.nio.file.Path
import org.tomlj.Toml
import org.tomlj.TomlParseError
import org.tomlj.TomlParseResult
import org.tomlj.TomlPosition
import org.tomlj.TomlTable

object ManifestParser {
    private const val REQUIRED_HEBE_API_VERSION = "hebe_api_version"
    private const val REQUIRED_CAPABILITIES = "capabilities"
    private const val REQUIRED_PERMISSIONS = "permissions"
    private const val REQUIRED_ALLOWLIST_DOMAINS = "allowlist_domains"
    private const val OPTIONAL_SIGNATURE = "signature"
    private const val OPTIONAL_PUBLISHER_KEY = "publisher_key"

    fun parse(path: Path): ManifestResult<PluginManifest> {
        val errors = mutableListOf<ManifestError>()
        val toml: TomlParseResult =
            try {
                Toml.parse(path)
            } catch (e: Exception) {
                return ManifestResult.Error(
                    listOf(
                        ManifestError(
                            message = "Failed to parse TOML: ${e.message}",
                            source = path.toString(),
                        ),
                    ),
                )
            }

        val parseErrors = toml.errors()
        if (parseErrors.isNotEmpty()) {
            val errorList =
                parseErrors.map { err: TomlParseError ->
                    val pos: TomlPosition = err.position()
                    ManifestError(
                        message = err.message ?: "Unknown error",
                        source = path.toString(),
                        line = pos.line(),
                        column = pos.column(),
                    )
                }
            return ManifestResult.Error(errorList)
        }

        val hebeApiVersion = toml.getString(REQUIRED_HEBE_API_VERSION)
        if (hebeApiVersion == null) {
            errors.add(
                ManifestError(
                    message = "Missing required field '$REQUIRED_HEBE_API_VERSION'",
                    source = path.toString(),
                ),
            )
        }

        val capabilitiesResult = parseCapabilities(toml, path.toString())
        if (capabilitiesResult is ManifestResult.Error) {
            errors.addAll(capabilitiesResult.errors)
        }

        val permissionsResult = parsePermissions(toml, path.toString())
        if (permissionsResult is ManifestResult.Error) {
            errors.addAll(permissionsResult.errors)
        }

        val allowlistDomains = parseAllowlistDomains(toml, path.toString())

        val signature = toml.getString(OPTIONAL_SIGNATURE)
        val publisherKey = toml.getString(OPTIONAL_PUBLISHER_KEY)

        return if (errors.isEmpty()) {
            ManifestResult.Ok(
                PluginManifest(
                    hebeApiVersion = hebeApiVersion ?: "",
                    capabilities =
                        (capabilitiesResult as? ManifestResult.Ok)?.value
                            ?: emptySet(),
                    permissions =
                        (permissionsResult as? ManifestResult.Ok)?.value
                            ?: emptySet(),
                    allowlistDomains = allowlistDomains,
                    signature = signature,
                    publisherKey = publisherKey,
                ),
            )
        } else {
            ManifestResult.Error(errors)
        }
    }

    private fun parseCapabilities(
        toml: TomlTable,
        source: String,
    ): ManifestResult<Set<Capability>> {
        val capabilitiesArray = toml.getArray(REQUIRED_CAPABILITIES)
        if (capabilitiesArray == null) {
            return ManifestResult.Error(
                listOf(
                    ManifestError(
                        message = "Missing required field '$REQUIRED_CAPABILITIES'",
                        source = source,
                    ),
                ),
            )
        }

        val capabilities = mutableSetOf<Capability>()
        val errors = mutableListOf<ManifestError>()

        for (i in 0 until capabilitiesArray.size()) {
            val item = capabilitiesArray.get(i)
            if (item !is String) {
                errors.add(
                    ManifestError(
                        message = "'$REQUIRED_CAPABILITIES' must be an array of strings",
                        source = source,
                    ),
                )
                continue
            }
            val capability =
                Capability.entries.firstOrNull { it.name.equals(item, ignoreCase = true) }
                    ?: run {
                        errors.add(
                            ManifestError(
                                message = "Unknown capability '$item'. Valid values: ${Capability.entries.joinToString()}",
                                source = source,
                            ),
                        )
                        null
                    }
            if (capability != null) {
                capabilities.add(capability)
            }
        }

        return if (errors.isEmpty()) {
            ManifestResult.Ok(capabilities)
        } else {
            ManifestResult.Error(errors)
        }
    }

    private fun parsePermissions(
        toml: TomlTable,
        source: String,
    ): ManifestResult<Set<Permission>> {
        val permissionsArray = toml.getArray(REQUIRED_PERMISSIONS)
        if (permissionsArray == null) {
            return ManifestResult.Error(
                listOf(
                    ManifestError(
                        message = "Missing required field '$REQUIRED_PERMISSIONS'",
                        source = source,
                    ),
                ),
            )
        }

        val permissions = mutableSetOf<Permission>()
        val errors = mutableListOf<ManifestError>()

        for (i in 0 until permissionsArray.size()) {
            val item = permissionsArray.get(i)
            if (item !is String) {
                errors.add(
                    ManifestError(
                        message = "'$REQUIRED_PERMISSIONS' must be an array of strings",
                        source = source,
                    ),
                )
                continue
            }
            val permission =
                when {
                    item == "http_client" -> Permission.HttpClient
                    item == "env_read" -> Permission.EnvRead
                    item.startsWith("secrets:") -> {
                        val secretName = item.removePrefix("secrets:")
                        if (secretName.isBlank()) {
                            errors.add(
                                ManifestError(
                                    message = "Invalid secrets permission: name cannot be empty",
                                    source = source,
                                ),
                            )
                            null
                        } else {
                            Permission.Secret(secretName)
                        }
                    }

                    else -> {
                        errors.add(
                            ManifestError(
                                message = "Unknown permission '$item'. Valid values: http_client, env_read, secrets:<name>",
                                source = source,
                            ),
                        )
                        null
                    }
                }
            if (permission != null) {
                permissions.add(permission)
            }
        }

        return if (errors.isEmpty()) {
            ManifestResult.Ok(permissions)
        } else {
            ManifestResult.Error(errors)
        }
    }

    private fun parseAllowlistDomains(
        toml: TomlTable,
        source: String,
    ): List<String> {
        val array = toml.getArray(REQUIRED_ALLOWLIST_DOMAINS) ?: return emptyList()
        return (0 until array.size()).mapNotNull { i ->
            val item = array.get(i)
            if (item !is String) {
                null
            } else {
                item
            }
        }
    }

    fun parseFromJar(
        jarPath: Path,
        manifestFileName: String,
    ): ManifestResult<PluginManifest> {
        return try {
            val tomlContent =
                java.util.jar.JarFile(jarPath.toFile()).use { jar ->
                    val entry =
                        jar.getEntry(manifestFileName)
                            ?: return ManifestResult.Error(
                                listOf(
                                    ManifestError(
                                        message = "No $manifestFileName found in JAR",
                                        source = jarPath.toString(),
                                    ),
                                ),
                            )
                    jar.getInputStream(entry).use { it.readBytes().decodeToString() }
                }
            val errors = mutableListOf<ManifestError>()
            val toml: TomlParseResult =
                try {
                    Toml.parse(tomlContent)
                } catch (e: Exception) {
                    return ManifestResult.Error(
                        listOf(
                            ManifestError(
                                message = "Failed to parse TOML: ${e.message}",
                                source = jarPath.toString(),
                            ),
                        ),
                    )
                }

            val parseErrors = toml.errors()
            if (parseErrors.isNotEmpty()) {
                val errorList =
                    parseErrors.map { err: TomlParseError ->
                        val pos: TomlPosition = err.position()
                        ManifestError(
                            message = err.message ?: "Unknown error",
                            source = jarPath.toString(),
                            line = pos.line(),
                            column = pos.column(),
                        )
                    }
                return ManifestResult.Error(errorList)
            }

            val hebeApiVersion = toml.getString(REQUIRED_HEBE_API_VERSION)
            if (hebeApiVersion == null) {
                errors.add(
                    ManifestError(
                        message = "Missing required field '$REQUIRED_HEBE_API_VERSION'",
                        source = jarPath.toString(),
                    ),
                )
            }

            val capabilitiesResult = parseCapabilities(toml, jarPath.toString())
            if (capabilitiesResult is ManifestResult.Error) {
                errors.addAll(capabilitiesResult.errors)
            }

            val permissionsResult = parsePermissions(toml, jarPath.toString())
            if (permissionsResult is ManifestResult.Error) {
                errors.addAll(permissionsResult.errors)
            }

            val allowlistDomains = parseAllowlistDomains(toml, jarPath.toString())

            val signature = toml.getString(OPTIONAL_SIGNATURE)
            val publisherKey = toml.getString(OPTIONAL_PUBLISHER_KEY)

            if (errors.isNotEmpty()) {
                ManifestResult.Error(errors)
            } else {
                ManifestResult.Ok(
                    PluginManifest(
                        hebeApiVersion = hebeApiVersion ?: "unknown",
                        capabilities = (capabilitiesResult as? ManifestResult.Ok)?.value ?: emptySet(),
                        permissions = (permissionsResult as? ManifestResult.Ok)?.value ?: emptySet(),
                        allowlistDomains = allowlistDomains,
                        signature = signature,
                        publisherKey = publisherKey,
                    ),
                )
            }
        } catch (e: Exception) {
            ManifestResult.Error(
                listOf(
                    ManifestError(
                        message = "Failed to read from JAR: ${e.message}",
                        source = jarPath.toString(),
                    ),
                ),
            )
        }
    }
}
