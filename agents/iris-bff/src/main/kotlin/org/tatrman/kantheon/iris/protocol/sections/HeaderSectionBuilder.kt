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
                        .setDurationMs(t.durationMs),
                ).build()
        }
}
