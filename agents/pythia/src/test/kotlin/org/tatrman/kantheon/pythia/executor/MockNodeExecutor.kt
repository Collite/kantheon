package org.tatrman.kantheon.pythia.executor

import kotlinx.coroutines.delay
import org.tatrman.kantheon.pythia.v1.PlanNode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** How a mocked node behaves on a given attempt (1-based). */
sealed interface NodeBehavior {
    data object Ok : NodeBehavior

    data class FailKind(
        val kind: FailureKind,
    ) : NodeBehavior

    /** Fail TRANSIENT for the first [times] attempts, then succeed. */
    data class TransientThenOk(
        val times: Int,
    ) : NodeBehavior
}

/**
 * Configurable [NodeExecutor] for executor tests: per-node scripted behaviour,
 * provider tagging, optional per-node delay, and concurrency instrumentation.
 */
class MockNodeExecutor(
    private val delayMs: Long = 0,
    private val provider: String = "default",
    private val behavior: (PlanNode) -> NodeBehavior = { NodeBehavior.Ok },
) : NodeExecutor {
    val executed = CopyOnWriteArrayList<String>()
    private val attempts = ConcurrentHashMap<String, AtomicInteger>()
    private val concurrency = AtomicInteger(0)
    val maxConcurrency = AtomicInteger(0)

    override fun providerOf(node: PlanNode): String = provider

    override suspend fun execute(
        node: PlanNode,
        ctx: NodeContext,
    ): NodeResult {
        val now = concurrency.incrementAndGet()
        maxConcurrency.updateAndGet { maxOf(it, now) }
        try {
            if (delayMs > 0) delay(delayMs)
            val attempt = attempts.computeIfAbsent(node.nodeId) { AtomicInteger(0) }.incrementAndGet()
            return when (val b = behavior(node)) {
                NodeBehavior.Ok -> {
                    executed += node.nodeId
                    NodeResult(outputHandle = null, rowCount = 1, costUsd = 0.05)
                }
                is NodeBehavior.FailKind -> throw NodeExecutionException(b.kind, "boom ${node.nodeId}")
                is NodeBehavior.TransientThenOk ->
                    if (attempt <= b.times) {
                        throw NodeExecutionException(FailureKind.TRANSIENT, "flaky ${node.nodeId} attempt $attempt")
                    } else {
                        executed += node.nodeId
                        NodeResult(outputHandle = null, rowCount = 1, costUsd = 0.05)
                    }
            }
        } finally {
            concurrency.decrementAndGet()
        }
    }
}
