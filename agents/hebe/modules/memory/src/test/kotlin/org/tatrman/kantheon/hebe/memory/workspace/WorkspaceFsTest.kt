package org.tatrman.kantheon.hebe.memory.workspace

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class WorkspaceFsTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `write and read round-trip`() {
        val ws = WorkspaceFs(tempDir)
        ws.write(WorkspacePath("test.md"), "# Hello\n\nWorld")
        assertEquals("# Hello\n\nWorld", ws.read(WorkspacePath("test.md")))
    }

    @Test
    fun `read non-existent returns null`() {
        val ws = WorkspaceFs(tempDir)
        assertNull(ws.read(WorkspacePath("nonexistent.md")))
    }

    @Test
    fun `exists returns true for existing files`() {
        val ws = WorkspaceFs(tempDir)
        ws.write(WorkspacePath("exists.md"), "content")
        assertTrue(ws.exists(WorkspacePath("exists.md")))
    }

    @Test
    fun `exists returns false for non-existent files`() {
        val ws = WorkspaceFs(tempDir)
        assertFalse(ws.exists(WorkspacePath("nonexistent.md")))
    }

    @Test
    fun `list returns written files`() {
        val ws = WorkspaceFs(tempDir)
        ws.write(WorkspacePath("file1.md"), "content1")
        ws.write(WorkspacePath("file2.md"), "content2")
        val files = ws.list()
        assertTrue(files.any { it.value == "file1.md" })
        assertTrue(files.any { it.value == "file2.md" })
    }

    @Test
    fun `delete removes file`() {
        val ws = WorkspaceFs(tempDir)
        ws.write(WorkspacePath("todelete.md"), "content")
        assertTrue(ws.delete(WorkspacePath("todelete.md")))
        assertFalse(ws.exists(WorkspacePath("todelete.md")))
    }

    @Test
    fun `delete non-existent returns false`() {
        val ws = WorkspaceFs(tempDir)
        assertFalse(ws.delete(WorkspacePath("nonexistent.md")))
    }

    @Test
    fun `absolute path in constructor throws`() {
        assertThrows<IllegalArgumentException> {
            WorkspacePath("/absolute/path")
        }
    }

    @Test
    fun `path with double dots throws`() {
        assertThrows<IllegalArgumentException> {
            WorkspacePath("../escape")
        }
    }

    @Test
    fun `symlink outside workspace is not accessible`() {
        val ws = WorkspaceFs(tempDir)
        Files.createSymbolicLink(tempDir.resolve("escape"), tempDir.parent)
        assertFalse(ws.exists(WorkspacePath("escape")))
    }

    @Test
    fun `resolve creates valid child path`() {
        val parent = WorkspacePath("docs")
        val child = parent.resolve("notes.md")
        assertEquals("docs/notes.md", child.value)
    }

    @Test
    fun `resolve rejects absolute child`() {
        val parent = WorkspacePath("docs")
        assertThrows<IllegalArgumentException> {
            parent.resolve("/absolute.md")
        }
    }

    @Test
    fun `append adds content to file`() {
        val ws = WorkspaceFs(tempDir)
        ws.write(WorkspacePath("append.txt"), "Initial")
        ws.append(WorkspacePath("append.txt"), " More")
        assertEquals("Initial More", ws.read(WorkspacePath("append.txt")))
    }

    @Test
    fun `append creates file if not exists`() {
        val ws = WorkspaceFs(tempDir)
        ws.append(WorkspacePath("new.txt"), "Brand new")
        assertEquals("Brand new", ws.read(WorkspacePath("new.txt")))
    }

    @Test
    fun `list with prefix filters correctly`() {
        val ws = WorkspaceFs(tempDir)
        ws.write(WorkspacePath("docs/guide.md"), "# Guide")
        ws.write(WorkspacePath("docs/api.md"), "# API")
        ws.write(WorkspacePath("other.txt"), "other")
        val docsFiles = ws.list(WorkspacePath("docs"))
        assertTrue(docsFiles.any { it.value == "guide.md" })
        assertTrue(docsFiles.any { it.value == "api.md" })
        assertEquals(2, docsFiles.size)
    }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected ${T::class.java} to be thrown")
        } catch (e: Throwable) {
            if (e !is T) throw e
        }
    }
}
