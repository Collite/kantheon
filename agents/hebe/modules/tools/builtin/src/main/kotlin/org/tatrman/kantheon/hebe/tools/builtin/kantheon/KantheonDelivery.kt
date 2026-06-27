package org.tatrman.kantheon.hebe.tools.builtin.kantheon

import org.tatrman.kantheon.envelope.v1.FormatEnvelope

/** A rendered channel message for a scheduled-question delivery. */
data class DeliveryMessage(
    val title: String,
    val body: String,
)

/**
 * Maps a resolved iris-bff turn to the channel message Hebe delivers (P4 S4.2 T4/T5).
 * **Never silent** (architecture §7.1): every outcome produces a message — a success
 * delivers the rendered conclusion; an agent pause delivers a deep link for the human
 * to resume in Iris (Hebe does not answer clarifications in v1, contracts §3.4); a
 * failure delivers a failure notification (the retry/notify policy is the scheduler's).
 */
object KantheonDelivery {
    fun message(
        routineName: String,
        result: IrisTurnResult,
        envelopes: List<FormatEnvelope>,
        deepLink: String,
    ): DeliveryMessage =
        when (result) {
            is IrisTurnResult.Succeeded ->
                DeliveryMessage(
                    title = "⏰ $routineName",
                    body = ConclusionRenderer.render(envelopes, deepLink),
                )
            is IrisTurnResult.AwaitingAgent ->
                DeliveryMessage(
                    title = "⏰ $routineName — needs your input",
                    body = "The scheduled investigation paused for a clarification. Continue in Iris:\n🔗 $deepLink",
                )
            is IrisTurnResult.Failed ->
                DeliveryMessage(
                    title = "⏰ $routineName — failed",
                    body =
                        "The scheduled run failed (${result.error}). " +
                            "It will be retried per the routine policy.\n🔗 $deepLink",
                )
        }
}
