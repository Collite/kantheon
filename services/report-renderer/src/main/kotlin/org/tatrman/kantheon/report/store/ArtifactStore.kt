package org.tatrman.kantheon.report.store

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.listDirectoryEntries

/** A stored render artifact on the local filesystem (v1; S3-backed store is a v1.x follow-up). */
data class Artifact(
    val artifactId: String,
    val path: Path,
    val sizeBytes: Long,
)

/**
 * Filesystem artifact store (Stage 3.4 T6) — writes rendered reports under [baseDir] keyed by
 * a fresh `artifact_id`, streams them back, and deletes on demand. [purgeOlderThan] backs the
 * 7-day retention cron. The file extension is part of the stored name so the MIME type and the
 * download filename are recoverable.
 */
class ArtifactStore(
    private val baseDir: Path,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    init {
        Files.createDirectories(baseDir)
    }

    fun write(
        bytes: ByteArray,
        ext: String,
    ): Artifact {
        val id = newId()
        val path = baseDir.resolve("$id.$ext")
        Files.write(path, bytes)
        return Artifact(id, path, bytes.size.toLong())
    }

    fun read(artifactId: String): ByteArray? = find(artifactId)?.takeIf { it.exists() }?.let { Files.readAllBytes(it) }

    fun delete(artifactId: String): Boolean = find(artifactId)?.let { Files.deleteIfExists(it) } ?: false

    /** Delete artifacts last modified before [cutoff]; returns the count removed (retention cron). */
    fun purgeOlderThan(cutoff: Instant): Int =
        baseDir
            .listDirectoryEntries()
            .filter { it.getLastModifiedTime().toInstant().isBefore(cutoff) }
            .count { Files.deleteIfExists(it) }

    /** The id is always a freshly-minted UUID ([write]); reject anything else *before* it reaches the
     *  filesystem, so an attacker can't smuggle a glob (`*`, `?`, `{…}`) or a path segment through the id. */
    private fun find(artifactId: String): Path? {
        if (!UUID_SHAPE.matches(artifactId)) return null
        return baseDir.listDirectoryEntries("$artifactId.*").firstOrNull()?.takeIf { it.exists() }
    }

    private companion object {
        private val UUID_SHAPE = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }
}
