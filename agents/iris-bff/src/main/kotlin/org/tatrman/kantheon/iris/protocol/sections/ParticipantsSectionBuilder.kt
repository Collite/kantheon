package org.tatrman.kantheon.iris.protocol.sections

import org.tatrman.kantheon.protocol.v1.ParticipantsSection
import org.tatrman.kantheon.protocol.v1.Section

/**
 * `protocol.section.participants` — session scope only (contracts §2). Who and
 * what took part across the whole conversation; meaningless for one turn, which
 * has exactly one user and one agent already named in its header.
 */
object ParticipantsSectionBuilder {
    const val KEY: String = SectionRegistry.PARTICIPANTS

    fun build(
        turns: List<TurnFacts>,
        input: SectionInput,
    ): Section =
        SectionShape.guarded(KEY, input) { verbosity ->
            SectionShape
                .start(KEY, verbosity)
                .setParticipants(
                    ParticipantsSection
                        .newBuilder()
                        .addAllUserIds(turns.map { it.userId }.filter { it.isNotBlank() }.distinct())
                        .addAllAgentIds(turns.map { it.agentId }.filter { it.isNotBlank() }.distinct()),
                ).build()
        }
}
