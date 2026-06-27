package org.tatrman.kantheon.hebe.memory.workspace

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.tatrman.kantheon.hebe.api.WorkspaceEntry
import org.tatrman.kantheon.hebe.api.WorkspaceStore
import org.tatrman.kantheon.hebe.api.WorkspaceWriteResult
import org.tatrman.kantheon.hebe.api.workspace.WorkspacePath
import org.tatrman.kantheon.hebe.memory.db.PgDb
import org.tatrman.kantheon.hebe.memory.db.PgWorkspaceFiles
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Postgres [WorkspaceStore] backed by `workspace_files` (V6, contracts §4.3) for the
 * `fs.durability = ephemeral` profile (`k8s`). Revision-based optimistic concurrency:
 * every write bumps `revision`; a write carrying a stale `expectedRevision` is
 * rejected. Honours the same contract as [InMemoryWorkspaceStore] (the reference);
 * its live behaviour is verified in the integration suite (no Postgres in the unit
 * suite, planning-conventions §4).
 */
class PostgresWorkspaceStore(
    private val db: PgDb,
) : WorkspaceStore {
    // One pod per instance (architecture §5.1), so the read-revision-then-write
    // optimistic-concurrency check is serialised in-process — without this, two
    // concurrent writers (agent + `console:<user>`) can both pass the stale-revision
    // check and clobber each other, or both INSERT a new path and the second hits the
    // PK and throws instead of returning StaleRevision. Mirrors PostgresReceiptsStore.
    private val writeMutex = Mutex()

    override suspend fun read(path: WorkspacePath): WorkspaceEntry? =
        db.query {
            PgWorkspaceFiles
                .selectAll()
                .where { PgWorkspaceFiles.path eq path.value }
                .map {
                    WorkspaceEntry(
                        content = it[PgWorkspaceFiles.content],
                        revision = it[PgWorkspaceFiles.revision],
                        updatedBy = it[PgWorkspaceFiles.updatedBy],
                    )
                }.singleOrNull()
        }

    override suspend fun write(
        path: WorkspacePath,
        content: String,
        expectedRevision: Int?,
        updatedBy: String,
    ): WorkspaceWriteResult =
        writeMutex.withLock {
            db.query {
                val current =
                    PgWorkspaceFiles
                        .selectAll()
                        .where { PgWorkspaceFiles.path eq path.value }
                        .map { it[PgWorkspaceFiles.revision] }
                        .singleOrNull()
                if (expectedRevision != null && current != null && current != expectedRevision) {
                    return@query WorkspaceWriteResult.StaleRevision(current)
                }
                val now = OffsetDateTime.now(ZoneOffset.UTC)
                val next = (current ?: 0) + 1
                if (current == null) {
                    PgWorkspaceFiles.insert {
                        it[this.path] = path.value
                        it[this.content] = content
                        it[revision] = next
                        it[updatedAt] = now
                        it[this.updatedBy] = updatedBy
                    }
                } else {
                    PgWorkspaceFiles.update({ PgWorkspaceFiles.path eq path.value }) {
                        it[this.content] = content
                        it[revision] = next
                        it[updatedAt] = now
                        it[this.updatedBy] = updatedBy
                    }
                }
                WorkspaceWriteResult.Ok(next)
            }
        }

    override suspend fun list(prefix: WorkspacePath): List<WorkspacePath> =
        db.query {
            PgWorkspaceFiles
                .selectAll()
                .map { it[PgWorkspaceFiles.path] }
                .filter { it.startsWith(prefix.value) }
                .sorted()
                .map { WorkspacePath(it) }
        }

    override suspend fun delete(path: WorkspacePath) {
        db.query {
            PgWorkspaceFiles.deleteWhere { PgWorkspaceFiles.path eq path.value }
        }
    }
}
