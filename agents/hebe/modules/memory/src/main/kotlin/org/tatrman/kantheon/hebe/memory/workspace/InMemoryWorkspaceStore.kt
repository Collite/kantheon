package org.tatrman.kantheon.hebe.memory.workspace

import org.tatrman.kantheon.hebe.api.WorkspaceEntry
import org.tatrman.kantheon.hebe.api.WorkspaceStore
import org.tatrman.kantheon.hebe.api.WorkspaceWriteResult
import org.tatrman.kantheon.hebe.api.workspace.WorkspacePath
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory [WorkspaceStore] — the reference encoding of the optimistic-concurrency
 * contract (revision bump + stale-revision rejection + `updated_by`) that the
 * Postgres `workspace_files` impl must honour. Used as the "faked-PG" side of
 * [WorkspaceStore]'s contract spec (planning-conventions §4 — no live Postgres in
 * the unit suite). Not a production backend.
 */
class InMemoryWorkspaceStore : WorkspaceStore {
    private val files = ConcurrentHashMap<String, WorkspaceEntry>()
    private val mutex = Mutex()

    override suspend fun read(path: WorkspacePath): WorkspaceEntry? = files[path.value]

    override suspend fun write(
        path: WorkspacePath,
        content: String,
        expectedRevision: Int?,
        updatedBy: String,
    ): WorkspaceWriteResult =
        mutex.withLock {
            val current = files[path.value]
            if (expectedRevision != null && current != null && current.revision != expectedRevision) {
                return WorkspaceWriteResult.StaleRevision(current.revision)
            }
            val nextRevision = (current?.revision ?: 0) + 1
            files[path.value] = WorkspaceEntry(content, nextRevision, updatedBy)
            WorkspaceWriteResult.Ok(nextRevision)
        }

    override suspend fun list(prefix: WorkspacePath): List<WorkspacePath> =
        files.keys
            .filter { it.startsWith(prefix.value) }
            .sorted()
            .map { WorkspacePath(it) }

    override suspend fun delete(path: WorkspacePath) {
        files.remove(path.value)
    }
}
