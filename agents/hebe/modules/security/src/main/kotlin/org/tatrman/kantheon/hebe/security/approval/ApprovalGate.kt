@file:Suppress("MagicNumber")

package org.tatrman.kantheon.hebe.security.approval

import org.tatrman.kantheon.hebe.api.ApprovalStatus
import org.tatrman.kantheon.hebe.api.Tool
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

class ApprovalGate(
    private val repo: PendingApprovalsRepo,
    private val ttlMillis: Long = 24 * 3600 * 1000L,
) : org.tatrman.kantheon.hebe.api.ApprovalGate {
    private val waiters = ConcurrentHashMap<String, Channel<ApprovalStatus>>()

    override fun requestIfNeeded(
        tool: Tool,
        args: JsonObject,
        turnId: String,
        channel: String,
        threadExtId: String?,
    ): Flow<ApprovalStatus> =
        flow {
            val approvalId = UUID.randomUUID().toString()
            val createdAt = Instant.now()
            val expiresAt = createdAt.plusMillis(ttlMillis)

            val approval =
                PendingApproval(
                    id = approvalId,
                    turnId = turnId,
                    tool = tool.spec.name,
                    argsRedacted = args.toString(),
                    prompt = "Approval required for tool: ${tool.spec.name}",
                    channel = channel,
                    threadExtId = threadExtId,
                    createdAt = createdAt,
                    expiresAt = expiresAt,
                )

            repo.insert(approval)

            val waiter = Channel<ApprovalStatus>(Channel.BUFFERED)
            waiters[approvalId] = waiter

            emit(ApprovalStatus.Pending)

            val decision =
                withTimeoutOrNull(ttlMillis) {
                    waiter.receiveCatching().getOrNull()
                } ?: run {
                    repo.markExpired(approvalId)
                    ApprovalStatus.Expired
                }

            waiters.remove(approvalId)
            emit(decision)
        }

    override suspend fun awaitApproval(
        tool: Tool,
        args: JsonObject,
        turnId: String,
        channel: String,
        threadExtId: String?,
    ): Boolean {
        val flow = requestIfNeeded(tool, args, turnId, channel, threadExtId)
        val status = flow.last()
        return status == ApprovalStatus.Approved
    }

    override fun resolve(
        approvalId: String,
        approved: Boolean,
    ): Boolean {
        val decision = if (approved) ApprovalStatus.Approved else ApprovalStatus.Denied

        val waiter = waiters[approvalId]
        if (waiter != null) {
            waiter.trySend(decision)
            return true
        }

        return repo.resolve(approvalId, approved)
    }

    fun scanAndExpire(): List<PendingApproval> {
        val expired = repo.findUnresolved()
        for (approval in expired) {
            repo.markExpired(approval.id)
        }
        return expired
    }

    fun getUnresolved(): List<PendingApproval> = repo.scanUnresolved()

    @Volatile
    var estopTriggered: Boolean = false

    fun triggerEstop() {
        estopTriggered = true
        waiters.forEach { (_, channel) ->
            channel.trySend(ApprovalStatus.Denied)
        }
    }
}
