package org.tatrman.kantheon.hebe.memory.workspace

import org.tatrman.kantheon.hebe.api.WorkspaceStore
import org.tatrman.kantheon.hebe.api.WorkspaceWriteResult
import org.tatrman.kantheon.hebe.api.workspace.WorkspacePath
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Shared [WorkspaceStore] contract (Hebe P3 S3.2 T1), run against the filesystem impl
 * and the in-memory ("faked-PG") impl — the reference for the Postgres
 * `workspace_files` semantics (read/write/list; revision-based optimistic
 * concurrency; `updated_by`). The Postgres impl honours the same contract; its live
 * behaviour is the integration suite (planning-conventions §4).
 */
class WorkspaceStoreContractTest {
    @TempDir
    lateinit var tempDir: Path

    private fun runContract(store: WorkspaceStore) =
        runBlocking {
            val path = WorkspacePath("MEMORY.md")

            assertNull(store.read(path), "unknown path reads null")

            // First write: revision starts at 1, updated_by recorded.
            val first = store.write(path, "v1", expectedRevision = null, updatedBy = "agent")
            assertEquals(WorkspaceWriteResult.Ok(1), first)
            store.read(path)!!.let {
                assertEquals("v1", it.content)
                assertEquals(1, it.revision)
                assertEquals("agent", it.updatedBy)
            }

            // Unconditional write bumps the revision and the writer.
            val second = store.write(path, "v2", expectedRevision = null, updatedBy = "console:bora")
            assertEquals(WorkspaceWriteResult.Ok(2), second)
            assertEquals("console:bora", store.read(path)!!.updatedBy)

            // Stale optimistic write is rejected (current is 2).
            val stale = store.write(path, "vX", expectedRevision = 1, updatedBy = "agent")
            assertInstanceOf(WorkspaceWriteResult.StaleRevision::class.java, stale)
            assertEquals(2, (stale as WorkspaceWriteResult.StaleRevision).current)
            assertEquals("v2", store.read(path)!!.content, "rejected write must not mutate content")

            // Fresh optimistic write (matching revision) applies and bumps.
            assertEquals(WorkspaceWriteResult.Ok(3), store.write(path, "v3", expectedRevision = 2, updatedBy = "agent"))

            // list + delete.
            store.write(WorkspacePath("daily/2026-06-26.md"), "log", expectedRevision = null, updatedBy = "agent")
            assertTrue(store.list(WorkspacePath("daily/")).any { it.value == "daily/2026-06-26.md" })
            store.delete(path)
            assertNull(store.read(path))
        }

    @Test
    fun `filesystem workspace store honours the contract`() {
        runContract(FilesystemWorkspaceStore(WorkspaceFs(tempDir)))
    }

    @Test
    fun `in-memory workspace store honours the contract`() {
        runContract(InMemoryWorkspaceStore())
    }
}
