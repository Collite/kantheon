package org.tatrman.kantheon.hebe.memory.db

import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DbTest {
    @Test
    fun `file-based DB with migrations succeeds`() {
        val tempDir = Files.createTempDirectory("hebe-test")
        val dbPath = tempDir.resolve("test.db")
        try {
            val db = DbFactory.open(dbPath)
            assertEquals(false, db.isInMemory)
            db.dataSource.connection.use { conn ->
                val rs =
                    conn.createStatement().executeQuery(
                        "SELECT count(*) as cnt FROM sqlite_master WHERE type='table' AND name='conversations'",
                    )
                assertEquals(true, rs.next())
                assertEquals(1, rs.getInt("cnt"))
            }
        } finally {
            Files.deleteIfExists(dbPath)
        }
    }

    @Test
    fun `in-memory DB can be queried`() {
        val db = DbFactory.openInMemory()
        db.dataSource.connection.use { conn ->
            val rs = conn.createStatement().executeQuery("SELECT 1 as val")
            assertEquals(true, rs.next())
            assertEquals(1, rs.getInt("val"))
        }
    }

    @Test
    fun `re-opening same file is no-op for migrations`() {
        val tempDir = Files.createTempDirectory("hebe-test")
        val dbPath = tempDir.resolve("test2.db")
        try {
            val db1 = DbFactory.open(dbPath)
            val db2 = DbFactory.open(dbPath)
            db1.dataSource.connection.use { conn ->
                val rs = conn.createStatement().executeQuery("SELECT 1")
                assertEquals(true, rs.next())
            }
            db2.dataSource.connection.use { conn ->
                val rs = conn.createStatement().executeQuery("SELECT 1")
                assertEquals(true, rs.next())
            }
        } finally {
            Files.deleteIfExists(dbPath)
        }
    }
}
