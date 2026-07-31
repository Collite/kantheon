package org.tatrman.kantheon.iris.protocol.sources

import kotlinx.serialization.Serializable

/**
 * The federated read sources, normalised (architecture §2). Stage 2.1 builds the
 * document from these as **plain data**; Stage 2.2 supplies them from live
 * gateway/Loki/Tempo/Explain clients. Keeping the seam at a data type rather than
 * at a client interface is what lets the whole model pipeline be golden-fixture
 * tested with no network at all (PT-22).
 *
 * **Every source is optional and every one carries its own [SourceStatus].** A
 * source that could not be reached is not an error — it is a fact the document
 * reports in its receipts (P-4). There is no "throw on missing source" path
 * anywhere below this type.
 */
@Serializable
data class ProtocolSources(
    val gateway: GatewaySource = GatewaySource(),
    val loki: LokiSource = LokiSource(),
    val tempo: TempoSource = TempoSource(),
    val explain: ExplainSource = ExplainSource(),
) {
    /** Per-source outcomes, in the order receipts should list them. */
    fun statuses(): List<Pair<String, SourceStatus>> =
        listOf(
            "llm-gateway" to gateway.status,
            "loki" to loki.status,
            "tempo" to tempo.status,
            "translate-explain" to explain.status,
        )
}

/**
 * Why a source contributed what it did. `SKIPPED_BY_CONFIG` is deliberately
 * distinct from `DEGRADED`: "the operator turned this off" and "we tried and
 * failed" are different facts about a document, and a reader deciding whether to
 * trust a thin section needs to tell them apart.
 */
@Serializable
enum class SourceStatus {
    OK,
    DEGRADED,
    SKIPPED_BY_CONFIG,
    ;

    /** contracts §1 `SourceReceipt.status` wire values. */
    val wire: String
        get() =
            when (this) {
                OK -> "ok"
                DEGRADED -> "degraded"
                SKIPPED_BY_CONFIG -> "skipped-by-config"
            }
}

/** llm-gateway `prompt_logs` rows (contracts §5). */
@Serializable
data class GatewaySource(
    val status: SourceStatus = SourceStatus.SKIPPED_BY_CONFIG,
    val detail: String = "",
    val items: List<GatewayCall> = emptyList(),
)

@Serializable
data class GatewayCall(
    val id: String = "",
    val turnRef: String = "",
    val traceId: String = "",
    val requestedModel: String = "",
    val servedModel: String = "",
    val servedProvider: String = "",
    val fallbackFrom: String? = null,
    val cached: Boolean = false,
    val tokensPrompt: Int = 0,
    val tokensCompletion: Int = 0,
    val durationMs: Long = 0,
    val ttfbMs: Long = 0,
    val costUsd: Double = 0.0,
    val status: String = "",
    val createdAt: String = "",
    val promptText: String = "",
    val responseText: String = "",
    /**
     * Node/agent-supplied label. Sourced from Tempo's `call.purpose` span
     * attribute rather than from the gateway row — `X-Call-Purpose` is not
     * settable from golem (PT-24, S0.1 T6), so the gateway never stores it.
     */
    val purpose: String = "",
)

/** Loki lines, already grouped by service and capped. */
@Serializable
data class LokiSource(
    val status: SourceStatus = SourceStatus.SKIPPED_BY_CONFIG,
    val detail: String = "",
    val groups: List<LogGroup> = emptyList(),
)

@Serializable
data class LogGroup(
    val serviceName: String = "",
    val lines: List<LogLineData> = emptyList(),
    val droppedByCap: Int = 0,
)

@Serializable
data class LogLineData(
    val ts: String = "",
    val level: String = "",
    val body: String = "",
    val traceId: String = "",
)

/** Tempo spans for the turn's trace — the execution timeline and call purposes. */
@Serializable
data class TempoSource(
    val status: SourceStatus = SourceStatus.SKIPPED_BY_CONFIG,
    val detail: String = "",
    val spans: List<SpanData> = emptyList(),
)

@Serializable
data class SpanData(
    val spanId: String = "",
    val name: String = "",
    val serviceName: String = "",
    val durationMs: Long = 0,
    val attributes: Map<String, String> = emptyMap(),
)

/**
 * The translator's RelPlan. [reconstructed] is S-1's honesty flag: true means the
 * turn did not carry a plan and we asked the translator to explain the SQL
 * afterwards, so what the reader sees is *a* plan for that SQL — not provably the
 * one that ran. The document says so rather than presenting it as the original.
 */
@Serializable
data class ExplainSource(
    val status: SourceStatus = SourceStatus.SKIPPED_BY_CONFIG,
    val detail: String = "",
    val relPlanText: String = "",
    val reconstructed: Boolean = false,
)
