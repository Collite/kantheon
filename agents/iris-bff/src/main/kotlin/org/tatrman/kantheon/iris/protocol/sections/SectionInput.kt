package org.tatrman.kantheon.iris.protocol.sections

import kotlinx.serialization.Serializable
import org.tatrman.kantheon.iris.protocol.config.ProtocolCaps
import org.tatrman.kantheon.iris.protocol.config.ProtocolProfile
import org.tatrman.kantheon.iris.protocol.sources.ProtocolSources
import org.tatrman.kantheon.protocol.v1.ProtocolRecord
import org.tatrman.kantheon.protocol.v1.Section
import org.tatrman.kantheon.protocol.v1.SectionStatus
import org.tatrman.kantheon.protocol.v1.Verbosity

/**
 * What every section builder gets. Builders are **pure functions of this** — no
 * I/O, no clock, no store — which is what makes the whole model pipeline
 * golden-fixture testable (PT-22).
 */
data class SectionInput(
    val record: ProtocolRecord,
    val sources: ProtocolSources,
    val turn: TurnFacts,
    val profile: ProtocolProfile,
    val caps: ProtocolCaps = ProtocolCaps(),
)

/**
 * The `iris_turns` row's own facts — what the BFF knows without consulting any
 * federated source. Serializable so fixtures can state it (PT-22).
 */
@Serializable
data class TurnFacts(
    val turnId: String = "",
    val seq: Int = 0,
    val question: String = "",
    val agentId: String = "",
    val status: String = "",
    val origin: String = "user",
    val startedAt: String = "",
    val durationMs: Long = 0,
    val routingOutcome: String = "",
    val userId: String = "",
    val tenantId: String = "",
) {
    /**
     * The turn stopped at routing — Themis declined to pick an agent and asked the
     * user to. Nothing was dispatched, so no plan, SQL or execution exists for it.
     */
    val endedAwaitingPick: Boolean get() = routingOutcome == NEEDS_USER_PICK

    companion object {
        /**
         * `iris_turns`-derived routing outcomes. Constants because the value is written
         * in `ProtocolRoutes` and read in three builders — a magic string in four places
         * is a rename waiting to go quiet.
         */
        const val ROUTED: String = "routed"
        const val NEEDS_USER_PICK: String = "needs_user_pick"
    }
}

/**
 * Shared shape rules for every builder (architecture §3.1).
 *
 * The invariant worth stating out loud: **a builder never throws and never
 * fabricates.** Missing input produces a `SECTION_DEGRADED` section that says so;
 * a section switched off produces `SECTION_OFF` with no payload. A thrown
 * exception here would take down a whole protocol over one absent source, which
 * is precisely the failure mode P-4 exists to prevent.
 */
internal object SectionShape {
    /** An OFF section: present in the document (so the reader sees it was suppressed), payload empty. */
    fun off(key: String): Section =
        Section
            .newBuilder()
            .setKey(key)
            .setStatus(SectionStatus.SECTION_OFF)
            .setAppliedVerbosity(Verbosity.VERBOSITY_OFF)
            .build()

    /** Start a section builder pre-stamped with key + resolved verbosity. */
    fun start(
        key: String,
        verbosity: Verbosity,
        status: SectionStatus = SectionStatus.SECTION_OK,
    ): Section.Builder =
        Section
            .newBuilder()
            .setKey(key)
            .setStatus(status)
            .setAppliedVerbosity(verbosity)

    /**
     * Run [build] unless the profile switched this key off, converting any escape
     * of an exception into a degraded section rather than letting it propagate.
     */
    inline fun guarded(
        key: String,
        input: SectionInput,
        build: (Verbosity) -> Section,
    ): Section {
        val verbosity = input.profile.verbosityFor(key)
        if (verbosity == Verbosity.VERBOSITY_OFF) return off(key)
        return runCatching { build(verbosity) }
            .getOrElse {
                start(key, verbosity, SectionStatus.SECTION_DEGRADED).build()
            }
    }

    /** Truncate to [max] chars, reporting whether it bit. */
    fun cap(
        text: String,
        max: Int,
    ): Pair<String, Boolean> = if (text.length <= max) text to false else text.take(max) to true

    /**
     * A stage the turn never got to.
     *
     * For the three post-dispatch sections only — plan, SQL, execution. When a turn
     * ends at routing there is no query, so those three do not exist; reporting them
     * as `SECTION_DEGRADED` claimed we had tried to fetch them and failed, and sent
     * the reader to receipts that described a failure which had not happened.
     *
     * Checked BEFORE [notConsulted] on purpose. "This turn never got here" is true
     * regardless of which turn the sources were fetched for — it is the stronger
     * statement, so it wins.
     *
     * Deliberately NOT applied to `llm-calls` (Themis's own resolve does call the
     * gateway, so that section's emptiness really is a source question) or to
     * `security` (its A-1 capture-gap marker is a separate and still-true fact).
     */
    fun notReached(
        key: String,
        input: SectionInput,
        verbosity: Verbosity,
    ): Section? =
        if (input.turn.endedAwaitingPick) {
            start(key, verbosity, SectionStatus.SECTION_NOT_REACHED).build()
        } else {
            null
        }

    /**
     * A section whose federated source describes a different turn (contracts A-9).
     *
     * v1 fetches Loki/Tempo/gateway/Explain once, for the anchor turn. Sections that
     * read those sources and have no per-turn key of their own must therefore say
     * **"not consulted for this turn"** rather than render the anchor's facts — which
     * is what they did, thirteen times in a thirteen-turn document (review-080 R1).
     *
     * It is deliberately checked BEFORE the source-status checks. "We did not look
     * here" and "the source was down" are different facts, and the nearer one is the
     * true one: reporting a Loki outage on a turn whose logs were never requested
     * would send a reader to fix the wrong thing.
     */
    fun notConsulted(
        key: String,
        input: SectionInput,
        verbosity: Verbosity,
    ): Section? =
        if (input.sources.describes(input.turn.turnId)) {
            null
        } else {
            start(key, verbosity, SectionStatus.SECTION_DEGRADED).build()
        }
}
