package org.tatrman.kantheon.hebe.tools.builtin.kantheon

import org.tatrman.kantheon.envelope.v1.FormatEnvelope
import org.tatrman.kantheon.envelope.v1.FormatKind
import org.tatrman.kantheon.envelope.v1.FormatSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Envelope→channel rendering (T3) + the never-silent delivery mapping (T4/T5).
 * Telegram gets conclusion text + artifact counts + a deep link — no chart rendering.
 */
class KantheonDeliveryTest {
    private val link = "https://iris.kantheon.example/sessions/sess-1"

    private fun envelope(
        text: String? = null,
        kind: FormatKind = FormatKind.PLAINTEXT,
    ): FormatEnvelope =
        FormatEnvelope
            .newBuilder()
            .apply { if (text != null) setText(text) }
            .setFormat(FormatSpec.newBuilder().setKind(kind))
            .build()

    @Test
    fun `render concatenates text, counts tables and charts, appends the deep link`() {
        val rendered =
            ConclusionRenderer.render(
                listOf(
                    envelope("Revenue rose 12% QoQ.", FormatKind.MARKDOWN),
                    envelope(kind = FormatKind.TABLE),
                    envelope(kind = FormatKind.CHART),
                    envelope(kind = FormatKind.CHART),
                ),
                link,
            )
        assertTrue(rendered.contains("Revenue rose 12% QoQ."), rendered)
        assertTrue(rendered.contains("1 table"), rendered)
        assertTrue(rendered.contains("2 charts"), rendered)
        assertTrue(rendered.contains(link), rendered)
    }

    @Test
    fun `render omits the counts line when there are no tables or charts`() {
        val rendered = ConclusionRenderer.render(listOf(envelope("Just a text answer.")), link)
        assertTrue(rendered.contains("Just a text answer."))
        assertTrue(!rendered.contains("open in Iris"), rendered)
        assertTrue(rendered.contains(link))
    }

    @Test
    fun `succeeded turn delivers the rendered conclusion`() {
        val msg =
            KantheonDelivery.message(
                routineName = "Weekly revenue",
                result = IrisTurnResult.Succeeded("turn-1", envelope("done")),
                envelopes = listOf(envelope("Conclusion text", FormatKind.TABLE)),
                deepLink = link,
            )
        assertEquals("⏰ Weekly revenue", msg.title)
        assertTrue(msg.body.contains("Conclusion text"))
        assertTrue(msg.body.contains("1 table"))
    }

    @Test
    fun `awaiting agent delivers a deep link, not an answer`() {
        val msg =
            KantheonDelivery.message(
                "Weekly revenue",
                IrisTurnResult.AwaitingAgent("turn-1"),
                emptyList(),
                link,
            )
        assertTrue(msg.title.contains("needs your input"))
        assertTrue(msg.body.contains(link))
    }

    @Test
    fun `failure is never silent - it delivers a failure notification`() {
        val msg =
            KantheonDelivery.message(
                "Weekly revenue",
                IrisTurnResult.Failed("turn-1", "BOOM: dispatch failed"),
                emptyList(),
                link,
            )
        assertTrue(msg.title.contains("failed"))
        assertTrue(msg.body.contains("BOOM"))
        assertTrue(msg.body.contains("retried"))
    }
}
