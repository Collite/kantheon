package org.tatrman.kantheon.golem.resolution.hitl

import org.slf4j.LoggerFactory
import org.tatrman.kantheon.golem.resolution.ladder.LadderState
import org.tatrman.resolver.v1.GapRecord
import org.tatrman.resolver.v1.ResolutionState
import java.time.Duration
import java.time.Instant
import java.util.UUID

private val log = LoggerFactory.getLogger("org.tatrman.kantheon.golem.resolution.hitl.Snapshots")

// RV-P5.3 T4 — the paused turn. Resume rejoins at `assessGaps` (RV-11).

/**
 * The caller does not own this turn. Refused before any work, and before any round trip.
 *
 * ⚑ The message names only the caller who was refused. It used to name the subject the turn was
 * paused FOR, which makes a failed resume a read of another user's identity for anyone who can
 * see the error — and a snapshot id is guessable in a way a subject is not.
 */
class IdentitySubjectMismatch(
    actual: String,
) : RuntimeException("'$actual' did not open this turn and may not resume it")

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
    /** Every option the CORE signed this turn — the set a re-ask scopes against afresh. */
    val signedOptions: List<SignedOption>,
    /**
     * The options this ask actually OFFERED: [signedOptions] scoped to [askedGap]'s span.
     *
     * ⛑ The two are not interchangeable, and conflating them undid half of the P4.2 scoping
     * fix. `buildAsk` scopes what it shows (an option naming a different span is not an answer
     * to this question) — but redemption read the unscoped set, so a pin naming an option the
     * user was never shown was accepted and gated against the asked gap, and the feedback event
     * listed options nobody saw. A pin is redeemable only against what was offered.
     */
    val offeredOptions: List<SignedOption> = emptyList(),
    val resumeToken: String,
    /**
     * The gap this ask is about — the record, not its span text.
     *
     * ⛑ Text is not an identity: a question that says the same word twice has two gaps with one
     * span text, and matching on it attributes the answer (and the feedback event) to whichever
     * came first.
     */
    val askedGap: GapRecord,
    /** What the user was actually shown. contracts §5's `ask_rendered`, which is unrecoverable later. */
    val askRendered: String = "",
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

/**
 * ⛑ **Bounded, because an unanswered ask is the normal case.** A user who ignores a
 * clarification never resumes it, so an unbounded map grows by one full lattice + ladder state
 * per abandoned question for the life of the pod. Both bounds degrade to the same state the
 * KDoc above already names — "the ask expired" — which the user understands and the turn
 * recovers from, so eviction costs nothing that a restart did not already cost.
 *
 * [ttl] bounds how long a pause is resumable; [maxEntries] bounds the store when asks arrive
 * faster than they expire, evicting least-recently-used first.
 */
class InMemorySnapshotStore(
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val maxEntries: Int = 1_000,
    private val ttl: Duration = Duration.ofMinutes(30),
    private val now: () -> Instant = Instant::now,
) : SnapshotStore {
    private data class Held(
        val snapshot: TurnSnapshot,
        val storedAt: Instant,
    )

    // Access-ordered so `removeEldestEntry` evicts least-recently-USED rather than -inserted;
    // synchronized because access order makes even `get` a structural modification.
    private val store =
        java.util.Collections.synchronizedMap(
            object : LinkedHashMap<String, Held>(64, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Held>): Boolean {
                    val evicting = size > maxEntries
                    if (evicting) {
                        log.info(
                            "snapshot store is full ({} entries) — evicting the least recently used paused turn; " +
                                "its resume will read as an expired ask",
                            maxEntries,
                        )
                    }
                    return evicting
                }
            },
        )

    override fun put(snapshot: TurnSnapshot): String {
        val id = snapshot.id.ifBlank { newId() }
        purgeExpired()
        // Stored under its own id so `get` returns a snapshot that knows what it is called.
        store[id] = Held(snapshot.copy(id = id), now())
        return id
    }

    /**
     * Returns the stored value. Proto messages and [LadderState]'s lists are immutable, so the
     * caller cannot mutate what the store holds — which is what makes a second delivery of the
     * same resume read exactly the bytes the first one did.
     */
    override fun get(id: String): TurnSnapshot {
        val held = store[id] ?: throw SnapshotNotFound(id)
        if (expired(held)) {
            store.remove(id)
            throw SnapshotNotFound(id)
        }
        return held.snapshot
    }

    /** Entries currently held. For tests and for anyone wanting the number on a gauge. */
    fun size(): Int {
        purgeExpired()
        return store.size
    }

    private fun expired(held: Held): Boolean = Duration.between(held.storedAt, now()) >= ttl

    private fun purgeExpired() {
        synchronized(store) {
            val it = store.entries.iterator()
            while (it.hasNext()) if (expired(it.next().value)) it.remove()
        }
    }
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
        log.warn(
            "snapshot '{}' was paused for '{}' — '{}' may not resume it",
            id,
            snapshot.callerSubject,
            callerSubject,
        )
        throw IdentitySubjectMismatch(callerSubject)
    }
    return snapshot
}
