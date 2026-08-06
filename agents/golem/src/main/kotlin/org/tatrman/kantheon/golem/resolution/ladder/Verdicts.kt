package org.tatrman.kantheon.golem.resolution.ladder

import org.tatrman.resolver.v1.Disposition
import org.tatrman.resolver.v1.FrameRole
import org.tatrman.resolver.v1.GapRecord

/**
 * RV-P5.2 — `assessGaps`, the loop's objective function (RV-11), as a PURE function.
 *
 * ```
 * gaps ∧ eligible rungs ∧ budget   ⇒  CLIMB
 * load-bearing gap ∧ ask budget    ⇒  ASK
 * covered (or only carryable gaps) ⇒  EMIT
 * otherwise                        ⇒  REFUSE
 * ```
 *
 * Pure — `(state, ladder, budgets) → verdict`, no mutation — for one reason worth stating:
 * the verdict is the only place the Golem decides anything, and a decision that also edits
 * the lattice cannot be tested against a synthetic one.
 *
 * Which gaps BLOCK is the subtle half, and the line is **load-bearing** — the same line
 * RV-15 draws for asking:
 *
 * * a **load-bearing** gap blocks. It is asked about while the HITL budget lasts; once that
 *   is spent it lands on the terminal posture (`strict` ⇒ a refusal carrying the lattice).
 * * every **other** open gap is CARRIED, not blocking — an honest gap note beside the answer
 *   (RV-19). **H5 settles it**: *"porovnej s plánem"* leaves an honest `G1_UNBOUND` on
 *   *plánem* in FILTER position and the hero must ANSWER while saying what it could not do.
 *   Refusing the whole question over one unbindable filter throws away four correct bindings
 *   to protect one.
 * * `degrade-banner` (G5_NLP_DARK) never blocks even when load-bearing: no answer the user
 *   could give fixes a capability the matrix does not have, so the honest banner IS the
 *   answer.
 */
enum class Verdict { CLIMB, ASK, EMIT, REFUSE }

/**
 * RV-15 / RV-21 — a gap is load-bearing when it sits on the question's SUBJECT.
 *
 * ⚑ Carried from the golem-py review, and it is not obvious: a **value** gap with no frame
 * roles is NOT load-bearing while a **mention** gap with none IS. A value's structural
 * position is its anchor's (the roles live on the mention layer, RV-21), so an unroled value
 * gap simply has nothing to say about position — whereas a mention that reached the lattice
 * with no role at all is a mention nothing could place, which is exactly the case a question
 * should be spent on. Without this split, H2 spends its single question on *Praze*.
 */
fun GapRecord.isLoadBearing(): Boolean {
    if (FrameRole.FRAME_ROLE_SUBJECT in frameRolesList) return true
    if (frameRolesList.isNotEmpty()) return false
    // No roles at all: load-bearing iff this is a MENTION gap.
    return mentionId.isNotEmpty()
}

/**
 * Gaps still open — `UNRESOLVED`, the core's own verdict, and **only** that.
 *
 * ⚑ Matched deliberately to golem-py's `open_gaps()` rather than being widened to include
 * `UNSPECIFIED`. Widening looks safer and is not: the two shells would then disagree about
 * what "open" means, which is exactly the divergence RV-28 exists to catch, and the core
 * always sets `UNRESOLVED` (`resolver/pipeline/Gaps.kt:135`) so the widened branch would be
 * dead in production and live only in hand-built fixtures.
 *
 * A gap that arrives `UNSPECIFIED` is therefore invisible to the loop — never worked, never
 * asked about, never carried as a note. That is a silent drop, so it is made loud rather
 * than tolerated: see [warnOnUnspecifiedDisposition].
 */
fun openGaps(gaps: List<GapRecord>): List<GapRecord> =
    gaps.filter { it.disposition == Disposition.DISPOSITION_UNRESOLVED }

/**
 * The core never emits `DISPOSITION_UNSPECIFIED`, so one arriving means either a fixture
 * built by hand or a core that changed under us. Either way the gap would vanish from the
 * loop without trace, which is worth a line in the log.
 */
fun warnOnUnspecifiedDisposition(
    gaps: List<GapRecord>,
    warn: (String) -> Unit,
) {
    val count = gaps.count { it.disposition == Disposition.DISPOSITION_UNSPECIFIED }
    if (count > 0) {
        warn(
            "$count gap(s) arrived with DISPOSITION_UNSPECIFIED; the core emits UNRESOLVED, so these " +
                "are invisible to the ladder and will be neither worked nor carried",
        )
    }
}

private fun blocks(
    gap: GapRecord,
    policy: GapPolicy,
): Boolean {
    if (policy.ask == AskPolicy.DEGRADE_BANNER) return false
    return gap.isLoadBearing()
}

private fun askable(
    gap: GapRecord,
    policy: GapPolicy,
): Boolean =
    when (policy.ask) {
        AskPolicy.ASK_IMMEDIATELY -> true
        AskPolicy.DEGRADE_BANNER -> false
        else -> gap.isLoadBearing()
    }

fun blockingGaps(
    gaps: List<GapRecord>,
    ladder: LadderConfig,
): List<GapRecord> = openGaps(gaps).filter { blocks(it, ladder.gapPolicy(it.kind)) }

/**
 * In ask order: load-bearing first, then span order. One ask per round, so the ORDER decides
 * which gap the single question is spent on.
 */
fun askableGaps(
    gaps: List<GapRecord>,
    ladder: LadderConfig,
): List<GapRecord> =
    openGaps(gaps)
        .filter { askable(it, ladder.gapPolicy(it.kind)) }
        .sortedWith(compareBy({ !it.isLoadBearing() }, { it.span.start }))

/** Open gaps an answer may carry as notes rather than block on (RV-19). */
fun carryableGaps(
    gaps: List<GapRecord>,
    ladder: LadderConfig,
): List<GapRecord> = openGaps(gaps).filterNot { blocks(it, ladder.gapPolicy(it.kind)) }

/**
 * Policy-eligible ∧ profile-allowed ∧ budget-affordable ∧ **not already run**.
 *
 * ⛑ The last clause is the loop's termination guarantee, and it is also the ladder's own
 * semantics — each rung handles the residue the one below it left. `lookup` spends no LLM
 * budget (RV-33), so without "at most once per turn" a deterministic rung that proposes
 * nothing stays eligible forever and the loop edge spins.
 */
fun eligibleRungs(
    gaps: List<GapRecord>,
    rungsRun: Collection<String>,
    ladder: LadderConfig,
    profileName: String,
    budgets: Budgets,
    llmInvocations: Int,
): List<String> {
    val kinds = openGaps(gaps).map { it.kind }
    val policyEligible = ladder.eligibleRungs(kinds, profileName).filterNot { it in rungsRun }
    return budgets.runnable(policyEligible, llmInvocations)
}

/**
 * The verdict a profile implies once the ladder is done and gaps remain — `strict` refuses,
 * `best-effort-with-gap-notes` answers over the notes.
 *
 * Extracted from [assess]'s tail because [runLadderLoop] needs the same answer when it has to
 * settle a barren pass, and two copies of "what does this profile do at the end" is exactly
 * the kind of divergence that shows up as one shell refusing where the other answers.
 */
fun terminalVerdict(
    ladder: LadderConfig,
    profileName: String,
): Verdict =
    if (ladder.terminalPosture(profileName) == TerminalPosture.BEST_EFFORT_WITH_GAP_NOTES) {
        Verdict.EMIT
    } else {
        Verdict.REFUSE
    }

/** The verdict. See the file header for the four rules. */
fun assess(
    gaps: List<GapRecord>,
    rungsRun: Collection<String>,
    llmInvocations: Int,
    hitlRounds: Int,
    ladder: LadderConfig,
    profileName: String,
    budgets: Budgets,
): Verdict {
    if (openGaps(gaps).isEmpty()) return Verdict.EMIT

    if (eligibleRungs(gaps, rungsRun, ladder, profileName, budgets, llmInvocations).isNotEmpty()) {
        return Verdict.CLIMB
    }

    if (askableGaps(gaps, ladder).isNotEmpty() && budgets.hitlLeft(hitlRounds)) return Verdict.ASK

    if (blockingGaps(gaps, ladder).isEmpty()) return Verdict.EMIT

    return terminalVerdict(ladder, profileName)
}
