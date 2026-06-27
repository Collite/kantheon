@file:Suppress("TooGenericExceptionCaught", "MagicNumber", "UseCheckOrError", "SwallowedException", "NestedBlockDepth")

package org.tatrman.kantheon.hebe.plugins.install

import org.tatrman.kantheon.hebe.plugin.api.PluginManifest
import org.tatrman.kantheon.hebe.plugins.abi.AbiChecker
import org.tatrman.kantheon.hebe.plugins.abi.AbiResult
import org.tatrman.kantheon.hebe.plugins.manifest.ManifestError
import org.tatrman.kantheon.hebe.plugins.manifest.ManifestParser
import org.tatrman.kantheon.hebe.plugins.manifest.ManifestResult
import org.tatrman.kantheon.hebe.plugins.oci.OciClient
import org.tatrman.kantheon.hebe.plugins.signature.SignatureResult
import org.tatrman.kantheon.hebe.plugins.signature.SignatureVerifier
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile
import org.slf4j.Logger

sealed class InstallResult {
    data class Ok(
        val name: String,
        val version: String,
        val extractDir: Path,
    ) : InstallResult()

    data class Error(
        val message: String,
    ) : InstallResult()
}

internal object ArchiveUtils {
    fun computeHash(path: Path): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(path.toFile().readBytes())
    }

    fun extractAndParseManifest(archivePath: Path): ManifestResult<PluginManifest> =
        try {
            val tempPath = Files.createTempFile("plugin", ".toml")
            try {
                ZipFile(archivePath.toFile()).use { zip ->
                    val entry = zip.getEntry("plugin.toml")
                    if (entry != null) {
                        val content = zip.getInputStream(entry).readBytes()
                        Files.write(tempPath, content)
                        ManifestParser.parse(tempPath)
                    } else {
                        ManifestResult.Error(
                            listOf(
                                ManifestError(
                                    message = "plugin.toml not found in archive",
                                    source = archivePath.toString(),
                                ),
                            ),
                        )
                    }
                }
            } finally {
                Files.deleteIfExists(tempPath)
            }
        } catch (e: Exception) {
            ManifestResult.Error(
                listOf(
                    ManifestError(
                        message = "Failed to extract manifest: ${e.message}",
                        source = archivePath.toString(),
                    ),
                ),
            )
        }

    fun extractVersion(apiVersion: String): String = apiVersion.split(".").take(3).joinToString(".")

    fun extractArchive(
        archivePath: Path,
        destDir: Path,
        log: Logger,
    ) {
        Files.createDirectories(destDir)
        try {
            ZipFile(archivePath.toFile()).use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    val destPath = destDir.resolve(entry.name)
                    if (entry.isDirectory) {
                        Files.createDirectories(destPath)
                    } else {
                        Files.createDirectories(destPath.parent)
                        Files.copy(zip.getInputStream(entry), destPath)
                    }
                }
            }
        } catch (e: Exception) {
            log.error("Failed to extract archive: {}", e.message)
            throw IllegalStateException("Failed to extract archive: ${e.message}", e)
        }
    }

    fun readPluginId(archivePath: Path): String {
        ZipFile(archivePath.toFile()).use { zip ->
            val entry =
                zip.getEntry("plugin.properties")
                    ?: throw IllegalStateException("plugin.properties not found in archive")
            val props = Properties()
            props.load(zip.getInputStream(entry))
            return props.getProperty("plugin.id")
                ?: throw IllegalStateException("plugin.id missing from plugin.properties")
        }
    }
}

class InstallFlow(
    private val ociClient: OciClient,
    private val signatureVerifier: SignatureVerifier,
    private val pluginsDir: Path,
    private val log: Logger,
) {
    suspend fun install(ref: String): InstallResult {
        log.info("Installing plugin from {}", ref)

        val pulled =
            try {
                ociClient.pull(ref)
            } catch (e: Exception) {
                return InstallResult.Error("Failed to pull plugin: ${e.message}")
            }

        val archiveHash = ArchiveUtils.computeHash(pulled.archivePath)
        val manifestResult = ArchiveUtils.extractAndParseManifest(pulled.archivePath)
        if (manifestResult is ManifestResult.Error) {
            return InstallResult.Error("Failed to parse plugin manifest: ${manifestResult.errors}")
        }
        val manifest = (manifestResult as ManifestResult.Ok).value

        val sigResult = signatureVerifier.verify(manifest, archiveHash)
        when (sigResult) {
            is SignatureResult.BadSignature -> {
                return InstallResult.Error("Signature verification failed: ${sigResult.reason}")
            }

            is SignatureResult.Unsigned -> {
                log.warn("Plugin is unsigned: {}", sigResult.reason)
            }

            is SignatureResult.Verified -> {
                log.info("Plugin signature verified (publisher: {})", sigResult.publisherKey)
            }
        }

        val abiResult = AbiChecker.check(manifest)
        if (abiResult is AbiResult.Incompatible) {
            return InstallResult.Error(
                "ABI incompatible: ${abiResult.hint}",
            )
        }

        val name = ArchiveUtils.readPluginId(pulled.archivePath)
        val version = ArchiveUtils.extractVersion(manifest.hebeApiVersion)
        val extractDir = pluginsDir.resolve("$name-$version")
        if (Files.exists(extractDir)) {
            log.info("Plugin already installed at {}, skipping extraction", extractDir)
        } else {
            Files.createDirectories(extractDir.parent)
            ArchiveUtils.extractArchive(pulled.archivePath, extractDir, log)
        }

        log.info("Plugin installed to {}", extractDir)
        return InstallResult.Ok(name, version, extractDir)
    }
}

class SideloadFlow(
    private val signatureVerifier: SignatureVerifier,
    private val pluginsDir: Path,
    private val log: Logger,
) {
    suspend fun sideload(
        path: Path,
        unsigned: Boolean = false,
    ): InstallResult {
        log.info("Sideloading plugin from {}", path)

        if (!Files.exists(path)) {
            return InstallResult.Error("File not found: $path")
        }

        val archiveHash = ArchiveUtils.computeHash(path)
        val manifestResult = ArchiveUtils.extractAndParseManifest(path)
        if (manifestResult is ManifestResult.Error) {
            return InstallResult.Error("Failed to parse plugin manifest: ${manifestResult.errors}")
        }
        val manifest = (manifestResult as ManifestResult.Ok).value

        if (!unsigned) {
            val sigResult = signatureVerifier.verify(manifest, archiveHash)
            when (sigResult) {
                is SignatureResult.BadSignature -> {
                    return InstallResult.Error("Signature verification failed: ${sigResult.reason}")
                }

                is SignatureResult.Unsigned -> {
                    log.warn("Plugin is unsigned, use --unsigned to bypass: {}", sigResult.reason)
                }

                is SignatureResult.Verified -> {
                    log.info("Plugin signature verified (publisher: {})", sigResult.publisherKey)
                }
            }
        }

        val abiResult = AbiChecker.check(manifest)
        if (abiResult is AbiResult.Incompatible) {
            return InstallResult.Error(
                "ABI incompatible: ${abiResult.hint}",
            )
        }

        val name = ArchiveUtils.readPluginId(path)
        val version = ArchiveUtils.extractVersion(manifest.hebeApiVersion)
        val extractDir = pluginsDir.resolve("$name-$version")
        if (Files.exists(extractDir)) {
            log.info("Plugin already installed at {}, skipping extraction", extractDir)
        } else {
            Files.createDirectories(extractDir.parent)
            ArchiveUtils.extractArchive(path, extractDir, log)
        }

        log.info("Plugin sideloaded to {}", extractDir)
        return InstallResult.Ok(name, version, extractDir)
    }
}
