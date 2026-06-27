package org.tatrman.kantheon.hebe.memory

import org.tatrman.kantheon.hebe.memory.workspace.WorkspaceFs
import org.tatrman.kantheon.hebe.memory.workspace.WorkspacePath

class SystemPromptAssembler(
    private val workspaceFs: WorkspaceFs,
    private val cacheTtlMs: Long = 60_000,
) {
    private var cachedPrompt: String? = null
    private var cacheTimestamp: Long = 0

    suspend fun assemble(isGroup: Boolean = false): String {
        val now = System.currentTimeMillis()
        if (cachedPrompt != null && now - cacheTimestamp < cacheTtlMs) {
            return cachedPrompt!!
        }
        val identity = workspaceFs.read(WorkspacePath("IDENTITY.md")) ?: ""
        val memory = if (isGroup) "" else (workspaceFs.read(WorkspacePath("MEMORY.md")) ?: "")
        val heartbeat = workspaceFs.read(WorkspacePath("HEARTBEAT.md")) ?: ""
        val prompt =
            buildString {
                append("<identity>\n")
                append(identity)
                append("\n</identity>\n\n")
                if (memory.isNotEmpty() || !isGroup) {
                    append("<memory>\n")
                    append(memory)
                    append("\n</memory>\n\n")
                }
                append("<heartbeat>\n")
                append(heartbeat)
                append("\n</heartbeat>")
            }
        cachedPrompt = prompt
        cacheTimestamp = now
        return prompt
    }

    fun invalidateCache() {
        cachedPrompt = null
    }
}
