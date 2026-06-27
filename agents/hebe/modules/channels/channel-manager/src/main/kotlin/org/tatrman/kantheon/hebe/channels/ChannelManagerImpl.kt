package org.tatrman.kantheon.hebe.channels

import org.tatrman.kantheon.hebe.api.Channel
import org.tatrman.kantheon.hebe.api.ChannelHealth
import org.tatrman.kantheon.hebe.api.HandleOutcome
import org.tatrman.kantheon.hebe.api.IncomingMessage
import org.tatrman.kantheon.hebe.api.Observer
import org.tatrman.kantheon.hebe.api.ObserverEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

@Suppress("TooGenericExceptionCaught")
class ChannelManagerImpl(
    private val agent: org.tatrman.kantheon.hebe.core.agent.HebeAgent,
    private val observer: Observer,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val channels = mutableMapOf<String, Channel>()
    private val inject = InjectChannel(capacity = INJECT_CAPACITY)
    private val recentlyTriggeredMissions = mutableMapOf<String, Long>()
    private val missionMutex = Mutex()
    private val channelMutex = Mutex()
    private var managerJob: Job? = null

    val channelCount: Int
        get() = channels.size

    suspend fun register(channel: Channel) {
        channelMutex.withLock {
            logger.info("registering channel: name={}", channel.name)
            if (channels.containsKey(channel.name)) {
                channels[channel.name]?.shutdown()
            }
            channels[channel.name] = channel
        }
    }

    suspend fun unregister(name: String): Channel? =
        channelMutex.withLock {
            logger.info("unregistering channel: name={}", name)
            channels.remove(name)
        }

    fun injectChannel(): InjectChannel = inject

    fun start(scope: CoroutineScope): Job {
        managerJob =
            scope.launch {
                val channelFlowsDeferred =
                    channels.values.map { channel ->
                        logger.debug("starting channel flow: name={}", channel.name)
                        scope.async { channel.start(scope) }
                    }

                val channelFlows = channelFlowsDeferred.awaitAll()

                val mergedFlow =
                    if (channelFlows.isEmpty()) {
                        inject.flow
                    } else {
                        @Suppress("SpreadOperator")
                        merge(*channelFlows.toTypedArray(), inject.flow)
                    }

                mergedFlow.collect { msg ->
                    scope.launch { handleIncomingMessage(msg) }
                }
            }

        return managerJob!!
    }

    suspend fun getAllHealth(): List<Pair<String, ChannelHealth>> =
        channelMutex.withLock {
            channels.map { (name, channel) ->
                try {
                    name to channel.healthCheck()
                } catch (e: Exception) {
                    logger.warn("health check failed for channel: name={}", name, e)
                    name to ChannelHealth.Down
                }
            }
        }

    private suspend fun handleIncomingMessage(msg: IncomingMessage) {
        val now = System.currentTimeMillis()
        missionMutex.withLock {
            recentlyTriggeredMissions.entries.removeIf { (_, timestamp) ->
                now - timestamp > MISSION_GUARD_WINDOW_MS
            }
        }

        if (msg.isAgentBroadcast) {
            logger.debug("dropping agent broadcast message: id={}", msg.id)
            return
        }

        val missionId = msg.triggeringMissionId
        if (missionId != null) {
            missionMutex.withLock {
                val lastTriggered = recentlyTriggeredMissions[missionId]
                if (lastTriggered != null && now - lastTriggered < MISSION_GUARD_WINDOW_MS) {
                    logger.info(
                        "dropping message due to mission guard: missionId={}, id={}",
                        missionId,
                        msg.id,
                    )
                    return
                }
                recentlyTriggeredMissions[missionId] = now
            }
        }

        val effectiveSessionId = msg.sessionId ?: msg.channel
        val turnId =
            java.util.UUID
                .randomUUID()
                .toString()
        observer.event(ObserverEvent.TurnStart(sessionId = effectiveSessionId, turnId = turnId))

        try {
            val outcome = agent.handleMessage(msg)
            handleOutcome(outcome, msg, turnId, effectiveSessionId)
        } catch (e: Throwable) {
            logger.error("error handling message: id={}", msg.id, e)
            observer.event(ObserverEvent.TurnEnd(sessionId = effectiveSessionId, turnId = turnId, outcome = "error"))
        }
    }

    private suspend fun handleOutcome(
        outcome: HandleOutcome,
        msg: IncomingMessage,
        turnId: String,
        effectiveSessionId: String,
    ) {
        when (outcome) {
            is HandleOutcome.Done -> handleDoneOutcome(outcome, msg, turnId, effectiveSessionId)
            is HandleOutcome.Pending -> {
                logger.debug("message pending: id={}", msg.id)
                observer.event(ObserverEvent.TurnEnd(sessionId = effectiveSessionId, turnId = turnId, outcome = "pending"))
            }
            is HandleOutcome.NoResponse -> {
                logger.debug("no response for message: id={}, cause={}", msg.id, outcome.cause)
                observer.event(ObserverEvent.TurnEnd(sessionId = effectiveSessionId, turnId = turnId, outcome = "no_response"))
            }
            is HandleOutcome.Failed -> handleFailedOutcome(outcome, msg, turnId, effectiveSessionId)
        }
    }

    private suspend fun handleDoneOutcome(
        outcome: HandleOutcome.Done,
        msg: IncomingMessage,
        turnId: String,
        effectiveSessionId: String,
    ) {
        val channel = channelMutex.withLock { channels[msg.channel] }
        if (channel != null) {
            val replySpan = observer.span("channel.reply", mapOf("channel.name" to channel.name))
            channel.reply(
                org.tatrman.kantheon.hebe.api.ReplyContext(
                    incomingId = msg.id,
                    sessionId = effectiveSessionId,
                    threadId = msg.threadId,
                ),
                outcome.reply,
            )
            replySpan.close()
        }
        observer.event(ObserverEvent.TurnEnd(sessionId = effectiveSessionId, turnId = turnId, outcome = "done"))
    }

    private suspend fun handleFailedOutcome(
        outcome: HandleOutcome.Failed,
        msg: IncomingMessage,
        turnId: String,
        effectiveSessionId: String,
    ) {
        logger.warn("message failed: id={}, error={}", msg.id, outcome.message)
        val channel = channelMutex.withLock { channels[msg.channel] }
        if (channel != null) {
            channel.reply(
                org.tatrman.kantheon.hebe.api.ReplyContext(
                    incomingId = msg.id,
                    sessionId = effectiveSessionId,
                    threadId = msg.threadId,
                ),
                org.tatrman.kantheon.hebe.api.OutboundMessage(text = "Error: ${outcome.message}"),
            )
        }
        observer.event(ObserverEvent.TurnEnd(sessionId = effectiveSessionId, turnId = turnId, outcome = "failed"))
    }

    suspend fun shutdown() {
        logger.info("shutting down channel manager")
        managerJob?.cancel()
        channelMutex.withLock {
            channels.values.forEach { channel ->
                try {
                    channel.shutdown()
                } catch (e: RuntimeException) {
                    logger.warn("error shutting down channel: name={}", channel.name, e)
                }
            }
            channels.clear()
        }
    }

    companion object {
        const val INJECT_CAPACITY = 64
        const val MISSION_GUARD_WINDOW_MS = 30_000L
    }
}
