package org.tatrman.kantheon.hebe.memory.workspace

import org.tatrman.kantheon.hebe.api.HebeException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

class WorkspaceFs(
    private val root: Path,
) {
    init {
        require(Files.isDirectory(root)) { "Workspace root must exist: $root" }
    }

    val workspaceRoot: Path get() = root

    fun read(path: WorkspacePath): String? {
        val resolved = resolveForRead(path) ?: return null
        return if (Files.exists(resolved) && Files.isRegularFile(resolved)) {
            Files.readString(resolved)
        } else {
            null
        }
    }

    fun write(
        path: WorkspacePath,
        content: String,
    ) {
        val resolved = resolveForWrite(path)
        val parentDir = resolved.parent
        if (!Files.exists(parentDir)) {
            Files.createDirectories(parentDir)
        }
        val tmpPath = resolved.resolveSibling("${resolved.fileName}.tmp")
        Files.writeString(tmpPath, content)
        Files.move(
            tmpPath,
            resolved,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        )
    }

    @Suppress("SpreadOperator")
    fun append(
        path: WorkspacePath,
        content: String,
    ) {
        val resolved = resolveForWrite(path)
        val parentDir = resolved.parent
        if (!Files.exists(parentDir)) {
            Files.createDirectories(parentDir)
        }
        val openOptions =
            arrayOf(
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND,
            )
        Files.writeString(resolved, content, *openOptions)
    }

    fun list(prefix: WorkspacePath = WorkspacePath("")): List<WorkspacePath> {
        val base = root.resolve(prefix.value).normalize()
        if (!Files.exists(base)) return emptyList()
        return Files
            .walk(base, 1)
            .filter { it != base }
            .filter { Files.isRegularFile(it) }
            .map { base.relativize(it) }
            .map { it.toString() }
            .filter { !it.contains("..") }
            .map { WorkspacePath(it) }
            .toList()
    }

    fun exists(path: WorkspacePath): Boolean {
        return try {
            Files.exists(resolveForRead(path) ?: return false)
        } catch (_: NoSuchFileException) {
            false
        }
    }

    fun delete(path: WorkspacePath): Boolean {
        val resolved = resolveForWrite(path)
        return try {
            Files.deleteIfExists(resolved)
        } catch (e: IOException) {
            logger.warn("Failed to delete workspace path: ${path.value}", e)
            false
        }
    }

    data class FileMetadata(
        val size: Long,
        val modifiedMs: Long,
    )

    fun readBytes(path: WorkspacePath): ByteArray? {
        val resolved = resolveForRead(path) ?: return null
        if (!Files.exists(resolved) || !Files.isRegularFile(resolved)) return null
        return Files.readAllBytes(resolved)
    }

    fun stat(path: WorkspacePath): FileMetadata? {
        val resolved = resolveForRead(path) ?: return null
        return try {
            FileMetadata(
                size = Files.size(resolved),
                modifiedMs = Files.getLastModifiedTime(resolved).toMillis(),
            )
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("ReturnCount")
    private fun resolveForRead(path: WorkspacePath): Path? {
        val absCandidate = root.resolve(path.value).normalize().toAbsolutePath()
        val absRoot = root.toAbsolutePath()
        if (!absCandidate.startsWith(absRoot)) {
            return null
        }
        return try {
            val realPath = absCandidate.toRealPath()
            val realRoot = absRoot.toRealPath()
            if (!realPath.startsWith(realRoot)) {
                return null
            }
            absCandidate
        } catch (_: NoSuchFileException) {
            absCandidate
        }
    }

    private fun resolveForWrite(path: WorkspacePath): Path {
        val absCandidate = root.resolve(path.value).normalize().toAbsolutePath()
        val absRoot = root.toAbsolutePath()
        if (!absCandidate.startsWith(absRoot)) {
            throw HebeException.Security("Workspace path escape attempt: ${path.value}")
        }
        return absCandidate
    }

    companion object {
        private val logger = org.slf4j.LoggerFactory.getLogger(WorkspaceFs::class.java)
    }
}
