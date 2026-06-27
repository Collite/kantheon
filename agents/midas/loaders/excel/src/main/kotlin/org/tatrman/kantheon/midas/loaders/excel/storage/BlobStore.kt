package org.tatrman.kantheon.midas.loaders.excel.storage

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Stores the raw uploaded statement bytes between upload and commit (Stage 1.5 T6).
 * The blob is the source of truth for preview/commit (re-parsed each step), keyed by
 * a tenant/portfolio/broker/content-hash path so a byte-identical re-upload maps to
 * the same key (idempotency). `upload_blob_ref` in `loader_runs` is this key.
 */
interface BlobStore {
    fun put(
        key: String,
        bytes: ByteArray,
    )

    fun get(key: String): ByteArray?
}

/** FS-backed blob store (v1; `upload_blob_ref` = FS path, per contracts §6.1). */
class FsBlobStore(
    private val root: File,
) : BlobStore {
    override fun put(
        key: String,
        bytes: ByteArray,
    ) {
        val file = File(root, key)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    override fun get(key: String): ByteArray? = File(root, key).takeIf { it.isFile }?.readBytes()

    /**
     * Delete blobs last modified before [cutoffEpochMillis] (the 24h retention sweep,
     * Stage 1.5 T7 — "cron in v1; S3 lifecycle in v1.x"). Returns the count deleted.
     * A re-upload regenerates the blob, so pruning only loses preview/commit replay
     * for stale runs.
     */
    fun pruneOlderThan(cutoffEpochMillis: Long): Int {
        if (!root.isDirectory) return 0
        return root
            .walkTopDown()
            .filter { it.isFile && it.lastModified() < cutoffEpochMillis }
            .count { it.delete() }
    }
}

/** In-memory blob store (tests). */
class InMemoryBlobStore : BlobStore {
    private val blobs = ConcurrentHashMap<String, ByteArray>()

    override fun put(
        key: String,
        bytes: ByteArray,
    ) {
        blobs[key] = bytes
    }

    override fun get(key: String): ByteArray? = blobs[key]
}
