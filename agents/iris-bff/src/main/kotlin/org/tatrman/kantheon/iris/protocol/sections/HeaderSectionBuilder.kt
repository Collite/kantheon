package org.tatrman.kantheon.iris.protocol.sections

import org.tatrman.kantheon.protocol.v1.HeaderSection
import org.tatrman.kantheon.protocol.v1.Section

/**
 * `protocol.section.header` — the turn's own facts, sourced entirely from the
 * `iris_turns` row. The only section that needs no federated source at all, which
 * is why it can never degrade: if the BFF can answer `/protocol` it knows this.
 */
object HeaderSectionBuilder {
    const val KEY: String = "protocol.section.header"

    fun build(input: SectionInput): Section =
        SectionShape.guarded(KEY, input) { verbosity ->
            val t = input.turn
            SectionShape
                .start(KEY, verbosity)
                .setHeader(
                    HeaderSection
                        .newBuilder()
                        .setQuestion(t.question)
                        .setAgentId(t.agentId)
                        .setRoutingOutcome(t.routingOutcome)
                        .setStatus(t.status)
                        .setOrigin(t.origin)
                        .setStartedAt(t.startedAt)
                        .setDurationMs(t.durationMs)
                        .setOutcome(outcomeOf(t)),
                ).build()
        }

    /**
     * What the turn produced, in a sentence — blank when it answered normally.
     *
     * `status` measures dispatch and reads `done` for a turn that produced no answer,
     * which is correct and useless to a reader asking why their bubble was empty. Seen
     * live on hartland 2026-08-01: a turn ended awaiting an agent pick and the document
     * said `Status: done` with four sections reporting "unavailable"; the two facts that
     * explained it were an attribute here and another two sections down.
     */
    private fun outcomeOf(t: TurnFacts): String =
        when {
            t.endedAwaitingPick ->
                "No answer — the router did not choose an agent and asked the user to pick. " +
                    "Nothing was dispatched, so the query, plan, SQL and execution stages never ran."

            t.status.equals("failed", ignoreCase = true) -> "The turn failed — see Errors."
            else -> ""
        }
}
