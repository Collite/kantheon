package org.tatrman.kantheon.hebe.security.estop

import org.tatrman.kantheon.hebe.api.PartialReceipt
import org.tatrman.kantheon.hebe.api.Receipts
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory

class EmergencyStop(
    private val scope: CoroutineScope,
    private val receipts: Receipts? = null,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val stopFlag = AtomicBoolean(false)
    private val stopChannel = Channel<Unit>(Channel.UNLIMITED)

    val isStopRequested: Boolean
        get() = stopFlag.get()

    fun stopFlow(): Flow<Unit> =
        flow {
            stopChannel.receive()
        }

    suspend fun requestStop() {
        logger.warn("Emergency stop requested")
        stopFlag.set(true)
        stopChannel.send(Unit)
        receipts?.append(
            PartialReceipt(
                sessionId = "estop",
                turnId = "estop",
                tool = "_estop",
                argsRedacted = "{}",
                risk = "High",
                durationMs = 0,
                ok = false,
            ),
        )
        scope.cancel()
    }

    fun reset() {
        stopFlag.set(false)
    }
}
