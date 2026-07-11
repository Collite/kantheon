package org.tatrman.kantheon.pythia.executor

import org.tatrman.kantheon.pythia.handles.HandleTable
import org.tatrman.kantheon.pythia.v1.Handle
import org.tatrman.kantheon.pythia.v1.PlanNode

/** Failure tiers (architecture §5): transient → retry; permanent → INCONCLUSIVE; systemic → HALT. */
enum class FailureKind { TRANSIENT, PERMANENT, SYSTEMIC }

/** Raised by a [NodeExecutor]; the executor's retry policy classifies on [kind]. */
class NodeExecutionException(
    val kind: FailureKind,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Raised when a downstream call rejects the OBO bearer mid-run (token expiry,
 * kantheon-security §2.1). The executor surfaces it as `ExecOutcome.NeedsReauth`
 * so the orchestrator parks AWAITING_USER_INPUT and resumes under a fresh bearer.
 */
class TokenExpiredException(
    message: String,
) : RuntimeException(message)

/**
 * Per-node execution context: the investigation's handle table + OBO bearer + locale
 * + the per-investigation worker [sessionId] (Phase 4 — sticky affinity / the IN-list
 * materialise path stage DFs into this session).
 */
data class NodeContext(
    val handles: HandleTable,
    val bearer: String,
    val locale: String = "en",
    val sessionId: String = "",
)

/** The product of running a node — the output handle (if any), accounting, and any forwarded warnings. */
data class NodeResult(
    val outputHandle: Handle?,
    val rowCount: Long,
    val costUsd: Double,
    val warnings: List<String> = emptyList(),
)

/**
 * Executes a single plan node. Stage 2.3 lands the QueryNode executor (query-mcp);
 * DataFrame/Model at P4. Throws [NodeExecutionException] (classified) or
 * [TokenExpiredException] on a bearer rejection.
 */
interface NodeExecutor {
    /** The provider this node hits (for per-provider cap accounting): "query" / "metis" / "worker" / "llm". */
    fun providerOf(node: PlanNode): String = "default"

    suspend fun execute(
        node: PlanNode,
        ctx: NodeContext,
    ): NodeResult
}
