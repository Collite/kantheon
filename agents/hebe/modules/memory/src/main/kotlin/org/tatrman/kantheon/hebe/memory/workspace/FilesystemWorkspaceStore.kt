package org.tatrman.kantheon.hebe.memory.workspace

import org.tatrman.kantheon.hebe.api.WorkspaceEntry
import org.tatrman.kantheon.hebe.api.WorkspaceStore
import org.tatrman.kantheon.hebe.api.WorkspaceWriteResult
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tatrman.kantheon.hebe.api.workspace.WorkspacePath as ApiWorkspacePath

/**
 * Filesystem [WorkspaceStore] for the persistent-FS profiles (`local`/`personal`/
 * `server`; architecture §5.3) — the human-editable `~/.hebe/workspace/` story,
 * unchanged. Content lives on disk via [WorkspaceFs]; the optimistic-concurrency
 * revision + `updatedBy` are tracked in-process (sufficient for the real concurrency
 * case — the agent loop and the web console share one process). A human editing a
 * file out-of-band does not bump the in-process revision; the file content remains
 * the source of truth on read. The Postgres backend persists revisions instead.
 */
class FilesystemWorkspaceStore(
    private val fs: WorkspaceFs,
) : WorkspaceStore {
    private data class RevMeta(
        val revision: Int,
        val updatedBy: String,
    )

    private val meta = ConcurrentHashMap<String, RevMeta>()
    private val mutex = Mutex()

    override suspend fun read(path: ApiWorkspacePath): WorkspaceEntry? {
        val content = fs.read(WorkspacePath(path.value)) ?: return null
        val m = meta[path.value]
        return WorkspaceEntry(content, m?.revision ?: 1, m?.updatedBy ?: "agent")
    }

    override suspend fun write(
        path: ApiWorkspacePath,
        content: String,
        expectedRevision: Int?,
        updatedBy: String,
    ): WorkspaceWriteResult =
        mutex.withLock {
            val current = meta[path.value]?.revision ?: if (fs.exists(WorkspacePath(path.value))) 1 else 0
            if (expectedRevision != null && current != 0 && current != expectedRevision) {
                return WorkspaceWriteResult.StaleRevision(current)
            }
            fs.write(WorkspacePath(path.value), content)
            val nextRevision = current + 1
            meta[path.value] = RevMeta(nextRevision, updatedBy)
            WorkspaceWriteResult.Ok(nextRevision)
        }

    override suspend fun list(prefix: ApiWorkspacePath): List<ApiWorkspacePath> {
        // WorkspaceFs.list relativizes to the prefix dir (child names); the
        // WorkspaceStore contract returns prefix-qualified paths (as the PG/in-memory
        // impls do), so re-qualify each child with the prefix.
        val base = prefix.value.let { if (it.isEmpty() || it.endsWith("/")) it else "$it/" }
        return fs.list(WorkspacePath(prefix.value)).map { ApiWorkspacePath(base + it.value) }
    }

    override suspend fun delete(path: ApiWorkspacePath) {
        fs.delete(WorkspacePath(path.value))
        meta.remove(path.value)
    }
}
