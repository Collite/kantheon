@file:Suppress("MagicNumber", "NewLineAtEndOfFile", "TooGenericExceptionCaught")

package org.tatrman.kantheon.hebe.scheduler.maintenance

import org.tatrman.kantheon.hebe.api.ApprovalGate
import org.tatrman.kantheon.hebe.api.Channel
import org.tatrman.kantheon.hebe.api.ChatRole
import org.tatrman.kantheon.hebe.api.ConversationMessage
import org.tatrman.kantheon.hebe.api.LoopConfig
import org.tatrman.kantheon.hebe.api.LoopOutcome
import org.tatrman.kantheon.hebe.api.Observer
import org.tatrman.kantheon.hebe.api.OutboundMessage
import org.tatrman.kantheon.hebe.api.Reasoning
import org.tatrman.kantheon.hebe.api.ReasoningContext
import org.tatrman.kantheon.hebe.api.SecretLookup
import org.tatrman.kantheon.hebe.api.workspace.WorkspacePath
import org.tatrman.kantheon.hebe.core.delegate.JobDelegate
import org.tatrman.kantheon.hebe.scheduler.DenyAllApprovalGate
import org.tatrman.kantheon.hebe.scheduler.Services
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory

class Heartbeat(
    private val services: Services,
    private val modelName: String,
    private val systemPrompt: String,
    private val notifyChannel: NotifyChannel,
    private val heartbeatFilePath: String = "HEARTBEAT.md",
) {
    private val memory get() = services.memory
    private val dispatcher get() = services.dispatcher
    private val llmProvider get() = services.llmProvider
    private val costGuard get() = services.costGuard
    private val compactor get() = services.compactor
    private val observer get() = services.observer

    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        const val DEFAULT_CRON = "0 */6 * * *"
        const val HEARTBEAT_FILE = "HEARTBEAT.md"
    }

    interface NotifyChannel {
        suspend fun notify(
            title: String,
            body: String,
        )
    }

    data class Config(
        val cron: String = DEFAULT_CRON,
        val heartbeatFilePath: String = HEARTBEAT_FILE,
    )

    suspend fun run(): Result<Boolean> {
        return try {
            val heartbeatContent = readHeartbeatFile()
            if (heartbeatContent.isBlank()) {
                logger.warn("heartbeat file is empty, skipping")
                return Result.success(false)
            }

            val response = runHeartbeatTurn(heartbeatContent)
            val trimmed = response.trim()

            if (trimmed == "OK") {
                logger.debug("heartbeat OK, no notification")
                Result.success(false)
            } else {
                logger.info("heartbeat needs attention: {}", trimmed.take(200))
                notifyChannel.notify("Hebe Heartbeat Alert", trimmed)
                Result.success(true)
            }
        } catch (e: Exception) {
            logger.error("heartbeat failed: {}", e.message, e)
            Result.failure(e)
        }
    }

    private fun readHeartbeatFile(): String {
        val path = Path.of(heartbeatFilePath)
        return if (Files.exists(path)) Files.readString(path) else ""
    }

    private suspend fun runHeartbeatTurn(heartbeatContent: String): String {
        val jobId = "heartbeat-${UUID.randomUUID()}"
        val sessionId = "heartbeat"

        val delegate =
            JobDelegate(
                jobId = jobId,
                memory = memory,
                dispatcher = dispatcher,
                llmProvider = llmProvider,
                costGuard = costGuard,
                compactor = compactor,
                observer = observer,
                systemPrompt = buildHeartbeatSystemPrompt(heartbeatContent),
                tools = emptyList(),
                modelName = modelName,
            )

        val ctx = makeReasoningContext(sessionId)
        val reasoning =
            object : Reasoning {
                override val systemPrompt: String = this@Heartbeat.systemPrompt
                override val activeSkills: List<String> = emptyList()
                override val latestUserMessage: String = heartbeatContent
            }

        memory.appendMessage(
            sessionId,
            ConversationMessage(
                id = UUID.randomUUID(),
                role = ChatRole.User,
                content = heartbeatContent,
                toolCalls = emptyList(),
                ts = Clock.System.now(),
            ),
        )

        val outcome = delegate.run(reasoning, ctx, LoopConfig(maxIterations = 5))

        return when (outcome) {
            is LoopOutcome.Response -> outcome.text
            is LoopOutcome.Stopped -> "stopped"
            is LoopOutcome.MaxIterations -> "max iterations reached"
            is LoopOutcome.Failure -> "failure: ${outcome.message}"
            is LoopOutcome.NeedApproval -> "approval needed"
            is LoopOutcome.AuthPending -> "auth pending"
        }
    }

    private fun buildHeartbeatSystemPrompt(heartbeatContent: String): String =
        """
        You are hebe's heartbeat agent. Read HEARTBEAT.md (provided below).
        For each item, perform the check. If everything is OK, reply with literally OK (nothing else).
        Otherwise reply with a short summary of what needs attention.
        Do not add any formatting, just the text response.

        HEARTBEAT.md content:
        $heartbeatContent
        """.trimIndent()

    private fun makeReasoningContext(sessionId: String): ReasoningContext =
        object : ReasoningContext {
            override val sessionId: String = sessionId
            override val turnId: String = UUID.randomUUID().toString()
            override val userId: String = "heartbeat"
            override val requestor: Channel =
                object : Channel {
                    override val name: String = "heartbeat"

                    override suspend fun start(scope: CoroutineScope): Flow<org.tatrman.kantheon.hebe.api.IncomingMessage> = flow { }

                    @Suppress("EmptyFunctionBlock")
                    override suspend fun reply(
                        ctx: org.tatrman.kantheon.hebe.api.ReplyContext,
                        msg: org.tatrman.kantheon.hebe.api.OutboundMessage,
                    ) {}

                    override suspend fun healthCheck(): org.tatrman.kantheon.hebe.api.ChannelHealth =
                        org.tatrman.kantheon.hebe.api.ChannelHealth.Up

                    @Suppress("EmptyFunctionBlock")
                    override suspend fun shutdown() {}
                }
            override val workspace: WorkspacePath = WorkspacePath("hebe")
            override val approvalGate: ApprovalGate = org.tatrman.kantheon.hebe.scheduler.DenyAllApprovalGate
            override val observer: Observer = this@Heartbeat.observer
            override val secretLookup: SecretLookup =
                object : SecretLookup {
                    override fun secret(name: String): String? = null
                }
        }

    class ConsoleNotifyChannel : NotifyChannel {
        private val logger = org.slf4j.LoggerFactory.getLogger(ConsoleNotifyChannel::class.java)

        override suspend fun notify(
            title: String,
            body: String,
        ) {
            logger.warn("[HEARTBEAT ALERT] {}: {}", title, body)
        }
    }
}
