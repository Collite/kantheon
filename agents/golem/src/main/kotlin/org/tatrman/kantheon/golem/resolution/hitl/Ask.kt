package org.tatrman.kantheon.golem.resolution.hitl

import org.slf4j.LoggerFactory
import org.tatrman.kantheon.golem.resolution.ladder.LadderConfig
import org.tatrman.kantheon.golem.resolution.ladder.askableGaps
import org.tatrman.kantheon.golem.resolution.ladder.isLoadBearing
import org.tatrman.resolver.v1.AwaitingClarification
import org.tatrman.resolver.v1.GapRecord
import org.tatrman.resolver.v1.Option
import org.tatrman.resolver.v1.ResolutionState
import org.tatrman.resolver.v1.Span

private val log = LoggerFactory.getLogger("org.tatrman.kantheon.golem.resolution.hitl.Ask")

// RV-P5.3 T4 — HITL. ONE pool (RV-17), load-bearing gaps only, one ask per round,
// frame-ranked, always an escape (RV-15). Pins are the CORE's signed tokens (RS-26).

/**
 * One of the options the **core** signed into its resume token.
 *
 * `id` is what travels back — never the label, and never a ref the agent made up. We do not
 * invent options: an ask with an empty option set is honest, and means the core did not
 * clarify this span, so free text or the escape are the only answers available.
 */
data class SignedOption(
    val id: String,
    val label: String = "",
    val ref: String = "",
    val entityTypeRef: String = "",
    val span: Span? = null,
) {
    companion object {
        fun of(o: Option): SignedOption =
            SignedOption(
                id = o.id,
                label = o.label,
                // A VOCABULARY option carries target_ref; a MEMBER option carries resolved_id
                // and no target_ref, which is why entity_type_ref exists (RG-P6 review F).
                ref = o.targetRef.ifBlank { o.resolvedId },
                entityTypeRef = o.entityTypeRef,
                span = if (o.hasSpan()) o.span else null,
            )

        fun from(awaiting: AwaitingClarification): List<SignedOption> = awaiting.optionsList.map(::of)
    }
}

/**
 * The pause. Carries the CORE's signed [resumeToken] and OUR [snapshotId] **side by side and
 * never merged**: signing the option set agent-side would let the agent fabricate "the user
 * chose X".
 */
data class Ask(
    val question: String,
    val gap: GapRecord,
    val options: List<SignedOption> = emptyList(),
    /** The core's, opaque here. Empty when the core issued none. */
    val resumeToken: String = "",
    /** Ours. The state lives in the snapshot store under this id; the id travels, not the state. */
    val snapshotId: String = "",
    /**
     * Always offered (RV-15). A user who cannot answer must be able to say so, and "none of
     * these" is a legitimate answer that closes the gap as `USER_CONFIRMED_UNKNOWN` rather
     * than leaving it open forever.
     */
    val escape: String = "none of these",
)

/** What came back. Exactly one of the three, which is why it is a sealed hierarchy. */
sealed interface Pin {
    /** The user picked a **core-signed** option. Re-enters via `resolve.gate` only. */
    data class Choice(
        val optionId: String,
    ) : Pin

    /** Unsigned. Re-resolves through `callResolutionCore` — refuse-over-guess stays intact. */
    data class FreeText(
        val text: String,
    ) : Pin

    /** RV-15's escape. Closes the gap as USER_CONFIRMED_UNKNOWN. */
    data object NoneOfThese : Pin
}

/**
 * The signed options that belong to **the span being asked about**.
 *
 * ⛑ Carried from golem-py's P4.2 review, and it is a bug that hides well: the ask used to
 * offer every option the core signed, whatever gap it had chosen. [askableGaps] orders
 * load-bearing-first *then* by span, so the gap it picks need not be the span the core
 * clarified — and the user was then asked about A and offered the answers for B. An option
 * carrying no span is unscoped and stays offered; an option naming a *different* span is not
 * an answer to this question.
 */
fun optionsFor(
    options: List<SignedOption>,
    span: Span,
): List<SignedOption> = options.filter { it.span == null || (it.span.start == span.start && it.span.end == span.end) }

/**
 * **The ask policy** — WHEN and WHAT. GC owns rendering; this owns the choice.
 *
 * Four rules, all from RV-15/17, and the order matters:
 *
 *  1. **Load-bearing only.** A non-load-bearing gap is carried, not asked about — spending a
 *     conversation's single question on *Praze* rather than on the subject is the failure
 *     mode [isLoadBearing] exists to prevent.
 *  2. **One ask per round.** Returns at most one gap, ever.
 *  3. **Frame-ranked.** [askableGaps] already sorts load-bearing-first then span order, so the
 *     first askable gap IS the ranked choice; this does not re-sort it.
 *  4. **Always an escape** — [Ask.escape], unconditionally.
 *
 * Returns null when nothing should be asked, which is a decision and not a failure.
 */
fun chooseAsk(
    gaps: List<GapRecord>,
    ladder: LadderConfig,
): GapRecord? {
    val askable = askableGaps(gaps, ladder)
    // Rule 1 applied on top of the policy's own askability: a gap whose policy says
    // `ask-if-load-bearing` has already been filtered, but `escalate-then-ask` has not, and
    // RV-15 is a global floor rather than a per-policy one.
    val loadBearing = askable.filter { it.isLoadBearing() }
    if (loadBearing.isEmpty()) {
        if (askable.isNotEmpty()) {
            log.debug("{} askable gap(s), none load-bearing — carried rather than asked", askable.size)
        }
        return null
    }
    return loadBearing.first()
}

/**
 * Render the question. Deliberately plain: GC owns the user-facing rendering (recon — the
 * Golem's existing `emitClarification` builds a `FormatEnvelope` and the BFF shows it), so
 * what this produces is the *content* of the ask, not its presentation.
 */
fun renderAsk(
    gap: GapRecord,
    options: List<SignedOption>,
    locale: String,
): String {
    val term = gap.span.text
    val cs = locale.startsWith("cs")
    return if (options.isEmpty()) {
        if (cs) "Nerozumím výrazu „$term“. Co jím myslíte?" else "I don't recognise \"$term\". What does it mean here?"
    } else {
        if (cs) "Co znamená „$term“?" else "What does \"$term\" mean here?"
    }
}

/**
 * Build the ask for the chosen gap.
 *
 * The resume token and the options both come from the **core**, never from here. When the core
 * issued no clarification for this span the ask still goes out — with no options and an empty
 * token — because "I don't recognise this word, what does it mean?" is a better turn than a
 * refusal, and free text re-resolves through `callResolutionCore`.
 */
fun buildAsk(
    gap: GapRecord,
    lattice: ResolutionState,
    signedOptions: List<SignedOption>,
    resumeToken: String,
    snapshotId: String,
    locale: String,
): Ask {
    val scoped = optionsFor(signedOptions, gap.span)
    if (signedOptions.isNotEmpty() && scoped.isEmpty()) {
        log.info(
            "the core signed {} option(s) but none for the span being asked about ('{}') — asking without options",
            signedOptions.size,
            gap.span.text,
        )
    }
    return Ask(
        question = renderAsk(gap, scoped, locale),
        gap = gap,
        options = scoped,
        // ⚑ The token is handed back OPAQUE and is only meaningful with options attached: a
        // token with no scoped options cannot be redeemed by a pick, so carrying it would
        // invite a resume that the core will reject. Free text does not need it.
        resumeToken = if (scoped.isEmpty()) "" else resumeToken,
        snapshotId = snapshotId,
    ).also {
        require(lattice.gapsList.isEmpty() || gap.span.text.isNotEmpty()) {
            "an ask must name the span it is about"
        }
    }
}
