package org.tatrman.kantheon.iris.inbox

/**
 * The Pythia investigation summary the inbox aggregates (contracts §2.7; Pythia
 * `GET /v1/investigations`). Modelled as plain DTOs — Pythia's proto lands in the
 * Pythia arc; at Phase 4 the inbox aggregates a fake/Wiremock Pythia.
 */
data class InvestigationSummary(
    val id: String,
    val question: String,
    /** Raw Pythia status (one of the 12); mapped to [UserFacingStatus] for the inbox. */
    val status: String,
    val createdAt: String,
    val updatedAt: String,
    /** `caller.kind` — IRIS | HEBE | API | SCHEDULED. */
    val callerKind: String = "IRIS",
    /** Originating turn ref (joins `iris_turns`); may be absent for older/orphaned runs. */
    val originTurnId: String? = null,
    val costSoFar: Double = 0.0,
)

/** The five user-facing statuses (contracts §2.7 — Pythia's 12 → 5). */
enum class UserFacingStatus {
    RUNNING,
    NEEDS_INPUT,
    DONE,
    FAILED,
    CANCELLED,
    ;

    /** A bucket that won't transition again — safe to stop tracking (DONE/FAILED/CANCELLED). */
    val isTerminal: Boolean get() = this == DONE || this == FAILED || this == CANCELLED

    companion object {
        /**
         * Map a raw Pythia status to its user-facing bucket (contracts §2.7):
         * `SUBMITTED`/`RESOLVING`/`PLANNING`/`EXECUTING` → Running; every
         * `AWAITING_*` (incl. `AWAITING_BUDGET_DECISION`) → Needs your input;
         * `DONE` → Done; `FAILED` → Failed; `HALTED` → Cancelled (partial). The
         * AWAITING family is matched by prefix so all five (and any future
         * sibling) map without enumerating each name.
         */
        fun of(raw: String): UserFacingStatus {
            // Tolerate Pythia's proto enum names (`STATUS_DONE`, `STATUS_AWAITING_USER_INPUT`)
            // as well as the bare forms — the live Pythia client forwards the enum name verbatim.
            val s = raw.trim().uppercase().removePrefix("STATUS_")
            return when {
                s.startsWith("AWAITING_") -> NEEDS_INPUT
                s in RUNNING_STATES -> RUNNING
                s == "DONE" -> DONE
                s == "FAILED" -> FAILED
                s == "HALTED" -> CANCELLED
                else -> RUNNING // unknown in-flight status defaults to Running
            }
        }

        private val RUNNING_STATES = setOf("SUBMITTED", "RESOLVING", "PLANNING", "EXECUTING")
    }
}

/** One inbox row: a Pythia investigation ⋈ its originating iris session/turn. */
data class InboxItem(
    val investigationId: String,
    val question: String,
    val status: UserFacingStatus,
    val rawStatus: String,
    val origin: String,
    val costSoFar: Double,
    val updatedAt: String,
    val sessionId: String? = null,
    val sessionTitle: String? = null,
    val turnId: String? = null,
    /** HALTED renders a partial conclusion (cancel-with-partials, contracts §2.7). */
    val partial: Boolean = false,
)

data class InboxCounts(
    val running: Int,
    val needsInput: Int,
)

data class InboxView(
    val items: List<InboxItem>,
    val counts: InboxCounts,
)
