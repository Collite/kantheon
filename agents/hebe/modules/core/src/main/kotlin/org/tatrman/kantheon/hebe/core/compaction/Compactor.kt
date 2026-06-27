package org.tatrman.kantheon.hebe.core.compaction

import org.tatrman.kantheon.hebe.api.ChatMessage
import org.tatrman.kantheon.hebe.api.ChatRequest
import org.tatrman.kantheon.hebe.api.ConversationMessage
import org.tatrman.kantheon.hebe.api.HebeException
import org.tatrman.kantheon.hebe.api.LlmProvider
import org.tatrman.kantheon.hebe.api.StreamEvent
import org.tatrman.kantheon.hebe.api.workspace.WorkspacePath
import org.tatrman.kantheon.hebe.config.HebeConfig
import org.tatrman.kantheon.hebe.memory.workspace.WorkspaceFs
import java.util.UUID
import kotlinx.coroutines.flow.toList
import org.slf4j.LoggerFactory

class Compactor(
    private val llmProvider: LlmProvider,
    private val workspaceFs: WorkspaceFs,
    private val config: HebeConfig,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    data class CompactionResult(
        val messages: List<ConversationMessage>,
        val compacted: Boolean,
    )

    suspend fun maybeCompact(
        history: List<ConversationMessage>,
        turnId: String,
    ): CompactionResult {
        val threshold = config.cost.compactionThreshold
        val maxTokens = llmProvider.capabilities().maxContextTokens
        val tokenCount = estimateTokens(history)

        if (tokenCount < threshold * maxTokens) {
            return CompactionResult(history, false)
        }

        val (step1Result, step1Compacted) = step1WorkspacePromote(history, turnId)
        if (step1Compacted) {
            val recheckTokens = estimateTokens(step1Result)
            if (recheckTokens < threshold * maxTokens) {
                return CompactionResult(step1Result, true)
            }
        }

        val step2Result = step2Summarise(step1Result, turnId)
        return CompactionResult(step2Result, true)
    }

    private fun step1WorkspacePromote(
        history: List<ConversationMessage>,
        turnId: String,
    ): Pair<List<ConversationMessage>, Boolean> {
        val largeBlobs =
            history.filter { msg ->
                msg.content.length > LARGE_BLOB_THRESHOLD
            }

        if (largeBlobs.isEmpty()) {
            return history to false
        }

        val result =
            history.map { msg ->
                if (msg.content.length > LARGE_BLOB_THRESHOLD) {
                    val docPath = "context/$turnId-${UUID.randomUUID()}.md"
                    workspaceFs.write(WorkspacePath(docPath), msg.content)
                    msg.copy(content = "[See $docPath]")
                } else {
                    msg
                }
            }
        return result to true
    }

    private suspend fun step2Summarise(
        history: List<ConversationMessage>,
        turnId: String,
    ): List<ConversationMessage> {
        val keepWindow = 10
        if (history.size <= keepWindow) {
            return history
        }

        val toSummarise = history.dropLast(keepWindow)
        val toKeep = history.takeLast(keepWindow)

        val summaryPrompt = buildSummarisationPrompt(toSummarise)
        val systemPrompt = "You are a summarisation assistant. Produce a concise summary of the conversation above."
        val messages =
            listOf(
                ChatMessage.System(summaryPrompt),
                ChatMessage.Assistant(
                    content = "Please summarise the above conversation concisely.",
                    toolCalls = emptyList(),
                ),
            )

        val request =
            ChatRequest(
                model = config.llm.defaultModel,
                systemPrompt = systemPrompt,
                messages = messages,
                tools = emptyList(),
                temperature = 0.3,
                maxTokens = 500,
                stream = false,
            )

        return try {
            val events = llmProvider.chat(request).toList()
            val text = events.filterIsInstance<StreamEvent.TextDelta>().joinToString("") { delta -> delta.text }
            val summary =
                ConversationMessage(
                    id = UUID.randomUUID(),
                    role = org.tatrman.kantheon.hebe.api.ChatRole.Assistant,
                    content = "[Summary] $text",
                    toolCalls = emptyList(),
                    ts =
                        kotlin.time.Clock.System
                            .now(),
                )
            listOf(summary) + toKeep
        } catch (e: Exception) {
            logger.error("compaction summarisation failed", e)
            throw HebeException.Memory("compaction failed; refusing to truncate")
        }
    }

    private fun buildSummarisationPrompt(messages: List<ConversationMessage>): String {
        val sb = StringBuilder()
        sb.appendLine("Summarise the following conversation concisely, preserving key facts, decisions, and action items.")
        sb.appendLine()
        for (msg in messages) {
            sb.appendLine("${msg.role.name}: ${msg.content}")
        }
        return sb.toString()
    }

    private fun estimateTokens(messages: List<ConversationMessage>): Int {
        val totalWords =
            messages.sumOf { msg ->
                msg.content.split(" ").size
            }
        return (totalWords * 1.3).toInt()
    }

    companion object {
        private const val LARGE_BLOB_THRESHOLD = 4096
    }
}
