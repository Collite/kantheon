@file:Suppress(
    "TooGenericExceptionCaught",
    "UnusedPrivateProperty",
    "SwallowedException",
    "LoopWithTooManyJumpStatements",
)

package org.tatrman.kantheon.hebe.channels.cli

import org.tatrman.kantheon.hebe.api.ChannelHealth
import org.tatrman.kantheon.hebe.api.IncomingMessage
import org.tatrman.kantheon.hebe.api.OutboundMessage
import org.tatrman.kantheon.hebe.api.ReplyContext
import java.util.UUID
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.slf4j.LoggerFactory

class CliChannel(
    private val prompt: String = DEFAULT_PROMPT,
    private val terminalFactory: () -> Terminal = {
        TerminalBuilder
            .builder()
            .jna(true)
            .jansi(true)
            .build()
    },
    private val lineReaderFactory: ((Terminal) -> LineReader)? = null,
) : org.tatrman.kantheon.hebe.api.Channel {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val inputChannel = Channel<IncomingMessage>(Channel.UNLIMITED)
    private var reader: LineReader? = null
    private var terminal: Terminal? = null
    private var replJob: kotlinx.coroutines.Job? = null
    private var lastCtrlCTime: Long = 0
    private val cancelFlag =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    override val name: String = CHANNEL_NAME

    override suspend fun start(scope: CoroutineScope): Flow<IncomingMessage> {
        terminal = terminalFactory()

        reader =
            lineReaderFactory?.invoke(terminal!!)
                ?: LineReaderBuilder.builder().terminal(terminal!!).build()

        replJob =
            scope.launch {
                runRepl()
            }

        val flow =
            kotlinx.coroutines.flow.flow {
                for (msg in inputChannel) {
                    emit(msg)
                }
            }

        return flow.onCompletion {
            logger.debug("CLI input flow completed")
        }
    }

    private suspend fun runRepl() {
        while (true) {
            try {
                val line = reader?.readLine(prompt) ?: break
                cancelFlag.set(false)

                if (line.isNotBlank()) {
                    val msg = parseInput(line)
                    inputChannel.send(msg)
                }
            } catch (e: org.jline.reader.UserInterruptException) {
                handleCtrlC()
            } catch (e: org.jline.reader.EndOfFileException) {
                logger.info("EOF received, exiting REPL")
                break
            } catch (e: java.io.EOFException) {
                logger.info("EOF received, exiting REPL")
                break
            } catch (e: Exception) {
                logger.error("REPL error", e)
                try {
                    terminal?.writer()?.println("Error: ${e.message}")
                    terminal?.writer()?.flush()
                } catch (ignored: Exception) {
                    logger.debug("failed to print error", ignored)
                }
            }
        }
    }

    private fun handleCtrlC() {
        val now = System.currentTimeMillis()
        if (now - lastCtrlCTime < DOUBLE_CTRL_C_WINDOW_MS) {
            logger.info("double Ctrl-C detected, exiting REPL")
            replJob?.cancel()
            inputChannel.close()
        } else {
            lastCtrlCTime = now
            logger.debug("Ctrl-C pressed, canceling current turn")
            cancelFlag.set(true)
            try {
                terminal?.writer()?.println("^C")
                terminal?.writer()?.flush()
            } catch (ignored: Exception) {
                logger.debug("failed to print Ctrl-C", ignored)
            }
        }
    }

    private fun parseInput(line: String): IncomingMessage =
        IncomingMessage(
            id = UUID.randomUUID(),
            channel = name,
            userId = OPERATOR_USER_ID,
            senderId = TTY_SENDER_ID,
            content = line.trimStart(),
            attachments = emptyList(),
            threadId = null,
            metadata = kotlinx.serialization.json.JsonObject(emptyMap()),
            receivedAt = Clock.System.now(),
            isInternal = false,
            isAgentBroadcast = false,
        )

    override suspend fun reply(
        ctx: ReplyContext,
        msg: OutboundMessage,
    ) {
        try {
            terminal?.writer()?.println()
            terminal?.writer()?.println(msg.text)
            terminal?.writer()?.flush()
        } catch (e: Exception) {
            logger.error("failed to print reply", e)
        }
    }

    override fun supportsDraftUpdates(): Boolean = true

    override suspend fun updateDraft(
        ctx: ReplyContext,
        partial: String,
    ) {
        try {
            val readerInstance = reader ?: return
            readerInstance.callWidget(LineReader.CLEAR)
            terminal?.writer()?.println(prompt + partial)
            terminal?.writer()?.flush()
        } catch (e: Exception) {
            logger.debug("failed to update draft", e)
        }
    }

    override suspend fun broadcast(
        userId: String,
        msg: OutboundMessage,
    ) {
        logger.debug("broadcast called but CLI doesn't support broadcasting: userId={}", userId)
    }

    override suspend fun healthCheck(): ChannelHealth = ChannelHealth.Up

    override suspend fun shutdown() {
        logger.info("shutting down CLI channel")
        replJob?.cancel()
        try {
            terminal?.close()
        } catch (e: Exception) {
            logger.warn("error closing terminal", e)
        }
        inputChannel.close()
    }

    fun isCancelRequested(): Boolean = cancelFlag.get()

    fun clearCancelFlag() {
        cancelFlag.set(false)
    }

    companion object {
        const val CHANNEL_NAME = "cli"
        const val OPERATOR_USER_ID = "operator"
        const val TTY_SENDER_ID = "tty"
        const val DEFAULT_PROMPT = "hebe> "
        const val DOUBLE_CTRL_C_WINDOW_MS = 2000L
    }
}
