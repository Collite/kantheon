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
)

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
}
