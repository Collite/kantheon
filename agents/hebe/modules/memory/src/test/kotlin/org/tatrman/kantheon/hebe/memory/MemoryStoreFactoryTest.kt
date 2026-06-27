package org.tatrman.kantheon.hebe.memory

import io.mockk.mockk
import org.tatrman.kantheon.hebe.memory.db.DbFactory
import org.tatrman.kantheon.hebe.memory.db.PgDb
import org.tatrman.kantheon.hebe.memory.embeddings.MockEmbeddingProvider
import org.tatrman.kantheon.hebe.memory.hygiene.HygieneScanner
import org.tatrman.kantheon.hebe.memory.workspace.WorkspaceFs
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Backend-selection wiring (Hebe P3 S3.1 T6). `storage.backend=postgres` selects the
 * PG impl — asserted with a **mocked** [PgDb] (the PostgresMemoryStore constructor
 * issues no queries); a real-Postgres boot is the integration suite
 * (planning-conventions §4).
 */
class MemoryStoreFactoryTest {
    @TempDir
    lateinit var tempDir: Path

    private fun ws() = WorkspaceFs(tempDir)

    private fun embeddings() = MockEmbeddingProvider(embeddingDim = 1536)

    @Test
    fun `sqlite backend selects SqliteMemoryStore`() {
        val store =
            MemoryStoreFactory.create(
                backend = MemoryStoreFactory.Backend.SQLITE,
                sqliteDb = DbFactory.openInMemory(),
                pgDb = null,
                workspaceFs = ws(),
                embeddings = embeddings(),
                hygieneScanner = HygieneScanner(),
                observer = null,
            )
        assertTrue(store is SqliteMemoryStore)
    }

    @Test
    fun `postgres backend selects PostgresMemoryStore (mocked driver)`() {
        val store =
            MemoryStoreFactory.create(
                backend = MemoryStoreFactory.Backend.POSTGRES,
                sqliteDb = null,
                pgDb = mockk<PgDb>(relaxed = true),
                workspaceFs = ws(),
                embeddings = embeddings(),
                hygieneScanner = HygieneScanner(),
                observer = null,
            )
        assertTrue(store is PostgresMemoryStore)
    }

    @Test
    fun `postgres backend without a PgDb fails fast`() {
        assertThrows(IllegalArgumentException::class.java) {
            MemoryStoreFactory.create(
                backend = MemoryStoreFactory.Backend.POSTGRES,
                sqliteDb = null,
                pgDb = null,
                workspaceFs = ws(),
                embeddings = embeddings(),
                hygieneScanner = HygieneScanner(),
                observer = null,
            )
        }
    }
}
