package org.tatrman.kantheon.hebe.api

import org.tatrman.kantheon.hebe.api.workspace.WorkspacePath

/** A workspace file with its optimistic-concurrency revision and last writer. */
data class WorkspaceEntry(
    val content: String,
    val revision: Int,
    /** "agent" | "console:<user>" (contracts §4.3). */
    val updatedBy: String,
)

/** Outcome of a [WorkspaceStore.write] — optimistic concurrency (contracts §4.3). */
sealed interface WorkspaceWriteResult {
    /** Write applied; [revision] is the new (bumped) revision. */
    data class Ok(
        val revision: Int,
    ) : WorkspaceWriteResult

    /** Rejected: the caller's `expectedRevision` did not match [current]. */
    data class StaleRevision(
        val current: Int,
    ) : WorkspaceWriteResult
}

/**
 * The workspace seam (Hebe arc P3 S3.2). The markdown workspace (MEMORY.md,
 * IDENTITY.md, HEARTBEAT.md, daily notes) is read/written through this single entry
 * point. The backend follows the **`fs.durability`** axis (architecture §5.3), not
 * the profile: persistent FS (`local`/`personal`/`server`) keeps the human-editable
 * file workspace; `ephemeral` (`k8s`) moves it into the `workspace_files` table.
 * Every write carries an `updatedBy` and supports optimistic concurrency (a stale
 * `expectedRevision` is rejected; a fresh write bumps the revision).
 */
interface WorkspaceStore {
    suspend fun read(path: WorkspacePath): WorkspaceEntry?

    /**
     * Writes [content] at [path]. [expectedRevision] = `null` writes
     * unconditionally (and bumps the revision); a non-null value applies the write
     * only if it matches the current revision, else returns [WorkspaceWriteResult.StaleRevision].
     */
    suspend fun write(
        path: WorkspacePath,
        content: String,
        expectedRevision: Int?,
        updatedBy: String,
    ): WorkspaceWriteResult

    suspend fun list(prefix: WorkspacePath): List<WorkspacePath>

    suspend fun delete(path: WorkspacePath)
}
