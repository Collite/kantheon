package org.tatrman.kantheon.golem.resolution.hitl

import org.tatrman.kantheon.golem.resolution.ladder.LadderState
import org.tatrman.resolver.v1.ResolutionState
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// RV-P5.3 T4 — the paused turn. Resume rejoins at `assessGaps` (RV-11).

/** The caller does not own this turn. Refused before any work, and before any round trip. */
class IdentitySubjectMismatch(
    expected: String,
    actual: String,
) : RuntimeException("this turn was paused for '$expected'; '$actual' may not resume it")

class SnapshotNotFound(
    id: String,
) : RuntimeException("no paused turn under snapshot '$id'")

/**
 * Everything a resumed turn needs, frozen at the moment of the ask.
 *
 * ⚑ **The ask budget rides the snapshot, and that is the whole double-spend defence.** The
 * ask was counted when it was emitted (`hitlRounds` on the stored [ladder] state), so a
 * replayed resume reads a state that has already paid — there is no counter anywhere else to
 * get out of step with. At-least-once delivery is the norm, so the same resume WILL arrive
 * twice; making the second delivery harmless is a property of reading immutable bytes, not of
 * a de-duplication table.
 */
data class TurnSnapshot(
    val id: String,
    val conversationId: String,
    val callerSubject: String,
    val tenantId: String,
    val question: String,
    val locale: String,
    val ladder: LadderState,
    val lattice: ResolutionState,
    val signedOptions: List<SignedOption>,
    val resumeToken: String,
    val askedGapSpanText: String,
    /** Hashes the FeedbackEvent needs at resume time, captured when the ask went out. */
    val snapshotHashes: Map<String, String> = emptyMap(),
)

/**
 * Where paused turns live. `assessGaps` is the rejoin point, so a snapshot holds the ladder's
 * state and nothing about the graph — a resumed turn re-enters the loop rather than replaying
 * the nodes before it.
 *
 * ⚑ Recon (T4, recorded): kantheon's conversation persistence is `golem_turns` (Exposed,
 * Postgres) keyed by turn — it stores the *finished* turn, not a paused one, and has no
 * column for a snapshot. This in-memory store is therefore the honest v1: it is correct for
 * a single pod and it is **explicitly not** the durable store, which lands with P7.1's
 * feedback store against the same migration. A snapshot lost to a restart degrades to "the
 * ask expired", which is a state the user already understands.
 */
interface SnapshotStore {
    fun put(snapshot: TurnSnapshot): String

    fun get(id: String): TurnSnapshot
}

class InMemorySnapshotStore(
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : SnapshotStore {
    private val store = ConcurrentHashMap<String, TurnSnapshot>()

    override fun put(snapshot: TurnSnapshot): String {
        val id = snapshot.id.ifBlank { newId() }
        // Stored under its own id so `get` returns a snapshot that knows what it is called.
        store[id] = snapshot.copy(id = id)
        return id
    }

    /**
     * Returns the stored value. Proto messages and [LadderState]'s lists are immutable, so the
     * caller cannot mutate what the store holds — which is what makes a second delivery of the
     * same resume read exactly the bytes the first one did.
     */
    override fun get(id: String): TurnSnapshot = store[id] ?: throw SnapshotNotFound(id)
}

/**
 * Fetch and identity-check.
 *
 * The core signs the subject into its resume token and re-checks it, so a mismatched resume
 * would be refused there anyway; refusing here too is defence in depth, and it means we never
 * ship a round trip whose only possible outcome is a rejection.
 */
fun loadSnapshot(
    store: SnapshotStore,
    id: String,
    callerSubject: String,
): TurnSnapshot {
    val snapshot = store.get(id)
    if (snapshot.callerSubject != callerSubject) {
        throw IdentitySubjectMismatch(snapshot.callerSubject, callerSubject)
    }
    return snapshot
}
