package org.tatrman.kantheon.hebe.memory.integration

import org.tatrman.kantheon.hebe.api.MemoryCategory
import org.tatrman.kantheon.hebe.api.MemoryScope
import org.tatrman.kantheon.hebe.memory.SqliteMemoryStore
import org.tatrman.kantheon.hebe.memory.db.DbFactory
import org.tatrman.kantheon.hebe.memory.embeddings.MockEmbeddingProvider
import org.tatrman.kantheon.hebe.memory.hygiene.HygieneScanner
import org.tatrman.kantheon.hebe.memory.workspace.WorkspaceFs
import org.tatrman.kantheon.hebe.memory.workspace.WorkspacePath
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MemoryRoundTripTest {
    @TempDir
    lateinit var tempDir: Path

    private fun createStore(): SqliteMemoryStore {
        val dbPath = tempDir.resolve("memory.db")
        val db = DbFactory.open(dbPath)
        val workspaceFs = WorkspaceFs(tempDir)
        val embeddings = MockEmbeddingProvider(embeddingDim = 1536)
        val hygieneScanner = HygieneScanner()
        return SqliteMemoryStore(db, workspaceFs, embeddings, hygieneScanner, null)
    }

    @Test
    fun `index doc and retrieve via search`() {
        val wsFs = WorkspaceFs(tempDir)
        val store = createStore()

        wsFs.write(WorkspacePath("test-doc.md"), "# Test Document\n\nThis is about Kotlin Coroutines.")
        runBlocking {
            store.appendDoc(
                "test-doc.md",
                "# Test Document\n\nThis is about Kotlin Coroutines.",
                MemoryScope.Default,
                MemoryCategory.Document,
            )
            val hits = store.search("Kotlin coroutines", k = 5)
            assertTrue(hits.isNotEmpty(), "Expected at least one hit for 'Kotlin coroutines'")
            assertTrue(hits.first().docPath.contains("test-doc"), "Expected test-doc in first hit")
        }
    }

    @Test
    fun `snapshot reflects indexed docs`() {
        val store = createStore()

        runBlocking {
            store.appendDoc("doc1.md", "# Doc 1 content here", MemoryScope.Default, MemoryCategory.Fact)
            store.appendDoc("doc2.md", "# Doc 2 content here", MemoryScope.Default, MemoryCategory.Preference)
            val snap = store.snapshot()
            assertEquals(2, snap.docs)
            assertTrue(snap.chunks >= 2)
        }
    }

    @Test
    fun `system prompt assembles from workspace files`() {
        val wsFs = WorkspaceFs(tempDir)
        val store = createStore()

        wsFs.write(WorkspacePath("IDENTITY.md"), "I am a helpful assistant.")
        wsFs.write(WorkspacePath("MEMORY.md"), "User prefers short responses.")
        wsFs.write(WorkspacePath("HEARTBEAT.md"), "Check the daily log.")

        runBlocking {
            val prompt = store.systemPrompt()
            assertTrue(prompt.contains("<identity>"), "Prompt should contain <identity> marker")
            assertTrue(prompt.contains("<memory>"), "Prompt should contain <memory> marker")
            assertTrue(prompt.contains("<heartbeat>"), "Prompt should contain <heartbeat> marker")
            assertTrue(prompt.contains("I am a helpful assistant."))
        }
    }
}
