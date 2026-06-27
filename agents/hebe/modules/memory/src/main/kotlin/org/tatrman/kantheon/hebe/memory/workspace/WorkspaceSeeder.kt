package org.tatrman.kantheon.hebe.memory.workspace

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

object WorkspaceSeeder {
    private val SEED_FILES =
        listOf(
            "README.md" to "workspace-seeds/README.md",
            "IDENTITY.md" to "workspace-seeds/IDENTITY.md",
            "MEMORY.md" to "workspace-seeds/MEMORY.md",
            "HEARTBEAT.md" to "workspace-seeds/HEARTBEAT.md",
            "BOOTSTRAP.md" to "workspace-seeds/BOOTSTRAP.md",
        )

    private val SEED_DIRS =
        listOf(
            "daily",
            "context",
            "projects",
            ".system/settings",
        )

    fun seedIfMissing(
        fs: WorkspaceFs,
        root: Path,
    ) {
        for ((fileName, resourcePath) in SEED_FILES) {
            val path = WorkspacePath(fileName)
            if (!fs.exists(path)) {
                val content = loadResource(resourcePath)
                if (content != null) {
                    fs.write(path, content)
                }
            }
        }
        for (dir in SEED_DIRS) {
            val dirPath = root.resolve(dir)
            if (!Files.exists(dirPath)) {
                Files.createDirectories(dirPath)
            }
        }
    }

    fun completeOnboarding(fs: WorkspaceFs): Boolean = fs.delete(WorkspacePath("BOOTSTRAP.md"))

    private fun loadResource(path: String): String? =
        try {
            WorkspaceSeeder::class.java.getResource("/$path")?.readText()
        } catch (_: IOException) {
            null
        }
}
