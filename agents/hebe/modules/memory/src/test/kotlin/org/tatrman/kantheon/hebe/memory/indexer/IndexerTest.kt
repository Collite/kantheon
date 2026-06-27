package org.tatrman.kantheon.hebe.memory.indexer

import org.tatrman.kantheon.hebe.memory.db.DbFactory
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class IndexerTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `file-based DB has memory tables`() {
        val dbPath = tempDir.resolve("test.db")
        val db = DbFactory.open(dbPath)

        db.dataSource.connection.use { conn ->
            val rs =
                conn.createStatement().executeQuery(
                    "SELECT name FROM sqlite_master WHERE type='table'",
                )
            val tables = mutableListOf<String>()
            while (rs.next()) {
                tables.add(rs.getString(1))
            }
            assertTrue(tables.contains("memory_docs"))
            assertTrue(tables.contains("memory_chunks"))
        }
    }
}
