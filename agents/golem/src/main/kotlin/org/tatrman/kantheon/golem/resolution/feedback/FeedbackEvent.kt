package org.tatrman.kantheon.golem.resolution.feedback

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import org.tatrman.kantheon.golem.resolution.hitl.Ask
import org.tatrman.kantheon.golem.resolution.hitl.Pin
import org.tatrman.kantheon.golem.resolution.hitl.SignedOption
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

private val log = LoggerFactory.getLogger("org.tatrman.kantheon.golem.resolution.feedback")

// RV-P5.3 T5 — the learning signal. contracts §5, PLATFORM ONLY (RV-4/18/20/23): golem-py
// emits none of these, deliberately, and that asymmetry is a feature of the two-shell design
// rather than a gap in the open one.
//
// EMIT-ONLY here. P7.1 builds the store, the overlay, and the promotion queue; this list's
// whole job is that the signal exists and is shaped right at the moment it can still be
// captured — after the fact, `question_text_hash` and the snapshot hashes are unrecoverable.

/** What the user did with the ask. */
sealed interface Outcome {
    /** They picked a core-signed option. `ref` is what an overlay entry would learn. */
    data class Pick(
        val optionId: String,
        val ref: String,
    ) : Outcome

    data class FreeText(
        val text: String,
    ) : Outcome

    /** RV-15's escape — and a **negative** learning signal, which is why it is an outcome
     *  rather than an absence. "None of these" says the offered refs are all wrong. */
    data object NoneOfThese : Outcome
}

/**
 * contracts §5's shape: `{event_id, estate_id, user_id, conversation_id, question_text_hash,
 * gap_record, ask_rendered, options[], outcome{pick(ref) | free_text | none_of_these},
 * snapshot_hashes, ts}`.
 *
 * ⚑ **`question_text_hash`, not the raw text**, and the contract is emphatic about it. A
 * feedback log grows the eval corpus and outlives the conversation, so it must not become a
 * shadow transcript of everything anyone ever asked. The hash still supports the two things
 * learning needs — "how often is this same question asked" and "did this question recur after
 * we learned the term" — without retaining the question.
 *
 * ⚑ **`free_text` is retained in full**, which looks inconsistent beside that and is not: a
 * free-text answer is the user's *proposed vocabulary* ("tržby means net revenue"), and it is
 * the entire content of the learning signal. Hashing it would leave an event that records
 * that something was learned and not what. The asymmetry is deliberate; it is also the reason
 * an estate's feedback log is estate-scoped and never crosses (contracts §5).
 */
data class FeedbackEvent(
    val eventId: String,
    val estateId: String,
    val userId: String,
    val conversationId: String,
    val questionTextHash: String,
    val gapKind: String,
    val gapSpanText: String,
    val askRendered: String,
    val options: List<SignedOption>,
    val outcome: Outcome,
    val snapshotHashes: Map<String, String>,
    val ts: Instant,
) {
    /**
     * The wire shape. `payloadJson` + an `eventKind` mirrors `iris-bff`'s `AuditRecord`
     * (`agents/iris-bff/.../audit/Audit.kt`), which is the nearest thing kantheon has to an
     * event convention — so P7.1 can back this with the same table type without reshaping the
     * event.
     */
    fun toJson(): String =
        buildJsonObject {
            put("event_id", eventId)
            put("estate_id", estateId)
            put("user_id", userId)
            put("conversation_id", conversationId)
            put("question_text_hash", questionTextHash)
            put("gap_kind", gapKind)
            put("gap_span_text", gapSpanText)
            put("ask_rendered", askRendered)
            put(
                "options",
                buildJsonArray {
                    options.forEach { o ->
                        add(
                            buildJsonObject {
                                put("id", o.id)
                                put("label", o.label)
                                put("ref", o.ref)
                            },
                        )
                    }
                },
            )
            put(
                "outcome",
                buildJsonObject {
                    when (val o = outcome) {
                        is Outcome.Pick -> {
                            put("kind", "pick")
                            put("option_id", o.optionId)
                            put("ref", o.ref)
                        }

                        is Outcome.FreeText -> {
                            put("kind", "free_text")
                            put("free_text", o.text)
                        }

                        Outcome.NoneOfThese -> put("kind", "none_of_these")
                    }
                },
            )
            put(
                "snapshot_hashes",
                buildJsonObject { snapshotHashes.forEach { (k, v) -> put(k, v) } },
            )
            put("ts", ts.toString())
        }.toString()

    companion object {
        const val EVENT_KIND: String = "rv.feedback"

        fun hashQuestion(text: String): String =
            "sha256:" +
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(text.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
    }
}

/**
 * Where events go. **Emit-only** — there is no `read`, because this list does not build a
 * store and an interface that promised one would invite a caller to depend on it.
 *
 * Recon (T5, recorded): kantheon has **no event bus**. Grepping every Kotlin module for
 * `EventBus` / `publishEvent` / outbox finds nothing; the nearest convention is iris-bff's
 * hash-chained audit log, which is BFF-local. Since contracts §5 puts the store at P7.1, the
 * absence is not a blocker — the deliverable is the seam, and [LoggingFeedbackSink] is a
 * default that makes the signal real today without pretending to durability.
 */
fun interface FeedbackSink {
    fun emit(event: FeedbackEvent)
}

/**
 * The default. One structured line per event, on a dedicated logger so an estate can route
 * `rv.feedback` to its own appender and start collecting before P7.1 exists.
 */
class LoggingFeedbackSink : FeedbackSink {
    private val feed = LoggerFactory.getLogger("rv.feedback")

    override fun emit(event: FeedbackEvent) {
        feed.info(event.toJson())
    }
}

/** Records what it was given. Used by the tests, and by nothing else. */
class RecordingFeedbackSink : FeedbackSink {
    val events = mutableListOf<FeedbackEvent>()

    override fun emit(event: FeedbackEvent) {
        events += event
    }
}

/** A sink that must never throw into the turn — a learning signal is not worth an answer. */
class SafeFeedbackSink(
    private val delegate: FeedbackSink,
) : FeedbackSink {
    override fun emit(event: FeedbackEvent) {
        try {
            delegate.emit(event)
        } catch (e: Exception) {
            log.warn("feedback sink threw ({}) — the turn continues; the signal is lost", e.message)
        }
    }
}

/**
 * Build the event for one completed ask round-trip. Called at RESUME — the outcome is the
 * point of the event, so an ask that was never answered emits nothing, which is why the
 * conversation-level "exactly one event per answered ask" property holds.
 */
fun feedbackFor(
    ask: Ask,
    pin: Pin,
    estateId: String,
    userId: String,
    conversationId: String,
    questionText: String,
    snapshotHashes: Map<String, String>,
    now: Instant = Instant.now(),
    newId: () -> String = { UUID.randomUUID().toString() },
): FeedbackEvent =
    FeedbackEvent(
        eventId = newId(),
        estateId = estateId,
        userId = userId,
        conversationId = conversationId,
        questionTextHash = FeedbackEvent.hashQuestion(questionText),
        gapKind =
            ask.gap.kind.name
                .removePrefix("GAP_KIND_"),
        gapSpanText = ask.gap.span.text,
        askRendered = ask.question,
        options = ask.options,
        outcome =
            when (pin) {
                is Pin.Choice ->
                    Outcome.Pick(
                        optionId = pin.optionId,
                        // The ref the option carried. An unknown option id yields an empty ref
                        // rather than a fabricated one — the core will reject the pin anyway,
                        // and an event claiming a ref nobody offered would poison the overlay.
                        ref =
                            ask.options
                                .firstOrNull { it.id == pin.optionId }
                                ?.ref
                                .orEmpty(),
                    )

                is Pin.FreeText -> Outcome.FreeText(pin.text)
                Pin.NoneOfThese -> Outcome.NoneOfThese
            },
        snapshotHashes = snapshotHashes,
        ts = now,
    )

/** For tests and for anyone reading a log line back. */
internal val FEEDBACK_JSON = Json { ignoreUnknownKeys = true }
