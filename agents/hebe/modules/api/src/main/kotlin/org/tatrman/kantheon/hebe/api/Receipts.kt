package org.tatrman.kantheon.hebe.api

data class PartialReceipt(
    val sessionId: String,
    val turnId: String,
    val tool: String,
    val argsRedacted: String,
    val risk: String,
    val durationMs: Long,
    val ok: Boolean,
)

interface Receipts {
    suspend fun append(partial: PartialReceipt): Long
}
