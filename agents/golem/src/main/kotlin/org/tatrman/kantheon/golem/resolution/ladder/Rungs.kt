package org.tatrman.kantheon.golem.resolution.ladder

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import org.tatrman.resolver.v1.GapRecord
import org.tatrman.resolver.v1.Hypothesis

private val log = LoggerFactory.getLogger("org.tatrman.kantheon.golem.resolution.ladder.Rungs")

// RV-P5.2 T3/T4 — the four rungs. Every one of them PROPOSES; none of them binds (RV-7).

// ---------------------------------------------------------------------------- lookup

/**
 * T3 — **the `lookup` rung, ruled and recorded.**
 *
 * The task list asked for the semantics to be settled before coding, and recon confirms the
 * shape it expected. The core already ran its anchored lookup rounds **inside**
 * `resolve.bind` (P2.3), so a Golem-side `lookup` that re-ran the broad pass would repeat
 * work the core just did and get the same answer — the vocabulary has not changed between
 * the two calls.
 *
 * So `lookup` is satisfiable only when **the loop has learned something the broad pass did
 * not have**: a gate round that bound a scoping mention gives the planner a new anchor, and
 * re-entering `resolve.gate` with a ref hypothesis against that anchor can close a value gap
 * the first pass could not. That costs no LLM budget, which is exactly why RV-33 keeps
 * `lookup` outside `max_llm_invocations`.
 *
 * When nothing has been learned it is an **honest recorded no-op**: "always eligible first"
 * means always *tried*, cheaply, and the rung log says it proposed nothing. That is better
 * than removing it from the ladder, because the log then shows the deterministic rung was
 * given its chance before any LLM was asked — which is the ordering the whole design claims.
 *
 * ⚑ Consequence worth stating: on the **first** pass of a turn `lookup` always no-ops, since
 * nothing has been gated yet. It earns its place on the second and later passes.
 */
class LookupRung(
    /** Anchors learned this turn — mention ids the gate has bound since the broad pass. */
    private val newlyAnchoredMentionIds: (LadderState) -> Set<String> = ::gatedMentionIds,
) : Rung {
    override suspend fun propose(
        state: LadderState,
        gaps: List<GapRecord>,
        config: LadderConfig,
    ): RungProposal {
        val anchors = newlyAnchoredMentionIds(state)
        if (anchors.isEmpty()) {
            log.debug("lookup: nothing learned since the broad pass — recorded no-op")
            return RungProposal(emptyList())
        }
        // A value gap whose anchor is one of the newly-bound mentions now has a scope the
        // core did not have when it looked. Re-offer the value's own surface text as a ref
        // hypothesis against that scope; the GATE decides whether anything matches.
        val hypotheses =
            gaps
                .filter { it.valueId.isNotEmpty() && it.mentionId.isEmpty() }
                .filter { gap -> anchorOf(state, gap.valueId) in anchors }
                .map { gap ->
                    Hypothesis
                        .newBuilder()
                        .setSpan(gap.span)
                        .setCorrection(gap.span.text)
                        .setProposingRung("lookup")
                        .build()
                }
        return RungProposal(hypotheses)
    }
}

/** Mentions the gate has bound this turn — the only source of a "new" anchor. */
private fun gatedMentionIds(state: LadderState): Set<String> =
    state.gatedBindings
        .mapNotNull { binding ->
            state.lattice.mentionsList
                .firstOrNull { mention -> mention.bindingsList.any { it.ref == binding.ref } }
                ?.id
        }.toSet()

private fun anchorOf(
    state: LadderState,
    valueId: String,
): String =
    state.lattice.valuesList
        .firstOrNull { it.id == valueId }
        ?.anchorMentionId ?: ""

// ------------------------------------------------------------------------ LLM rungs

/** What an LLM rung needs. Kept minimal so the loop does not depend on a gateway type. */
fun interface RungLlm {
    /** Temperature 0 (RV-12); the model class comes from the rung definition. */
    suspend fun complete(
        modelClass: String,
        prompt: String,
        temperature: Double,
    ): String
}

/**
 * T4 — `local` / `capable`. The prompt is the gap, its span context and the lattice's
 * relevant slice; the output is hypotheses and **nothing else**.
 *
 * Parsing is defensive on purpose: an unparseable proposal is a **rung failure**, not a
 * crash and not a verdict about the gap. A rung that returns prose has told us nothing about
 * whether the term exists, and recording that as "no candidate" would teach the next round a
 * lie — the same reasoning the proto gives for `LOOKUP_FAILED`.
 */
class LlmRung(
    private val name: String,
    private val llm: RungLlm,
    private val promptFor: (List<GapRecord>, LadderState) -> String = ::defaultRungPrompt,
) : Rung {
    override suspend fun propose(
        state: LadderState,
        gaps: List<GapRecord>,
        config: LadderConfig,
    ): RungProposal {
        val def = config.rungs[name] ?: throw RungRefused("rung '$name' is not defined in the config")
        val timeout = config.timeoutMs(name).toLong()
        val raw =
            try {
                withTimeout(timeout) { llm.complete(def.modelClass, promptFor(gaps, state), def.temperature) }
            } catch (e: TimeoutCancellationException) {
                return RungProposal(emptyList(), failure = "rung '$name' timed out after ${timeout}ms")
            } catch (e: Exception) {
                return RungProposal(emptyList(), failure = "rung '$name' failed: ${e.message}")
            }

        val parsed = parseHypotheses(raw, name, gaps)
        return if (parsed == null) {
            RungProposal(emptyList(), failure = "rung '$name' returned output that is not a hypothesis list")
        } else {
            RungProposal(parsed)
        }
    }
}

/**
 * `{span?, ref?, correction?}` per line, or JSON-ish — deliberately forgiving about shape and
 * unforgiving about content. Returns null when the output cannot be read as proposals at all
 * (a rung failure); an empty list means "read fine, proposed nothing", which is a real answer.
 */
internal fun parseHypotheses(
    raw: String,
    rung: String,
    gaps: List<GapRecord>,
): List<Hypothesis>? {
    val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.isEmpty()) return emptyList()

    val spansByText = gaps.associateBy { it.span.text }
    val out = mutableListOf<Hypothesis>()
    var recognised = false
    for (line in lines) {
        if (line.equals("NONE", ignoreCase = true)) {
            recognised = true
            continue
        }
        // `<span text> -> <ref>` is the whole grammar. Anything else is not a proposal.
        val arrow = line.split("->", limit = 2)
        if (arrow.size != 2) continue
        val spanText = arrow[0].trim().trim('"')
        val ref = arrow[1].trim().trim('"')
        val gap = spansByText[spanText] ?: continue
        if (ref.isEmpty()) continue
        recognised = true
        // A rung must not re-propose what the gate already refused on this gap (T1 case (e)).
        val alreadyTried = gap.hypothesesTriedList.any { it.ref == ref }
        if (alreadyTried) {
            log.debug("rung {} re-proposed {} for '{}' — already refused, dropped", rung, ref, spanText)
            continue
        }
        out +=
            Hypothesis
                .newBuilder()
                .setSpan(gap.span)
                .setRef(ref)
                .setProposingRung(rung)
                .build()
    }
    return if (recognised) out else null
}

internal fun defaultRungPrompt(
    gaps: List<GapRecord>,
    state: LadderState,
): String =
    buildString {
        appendLine("The question was parsed into a lattice. These spans could not be bound:")
        gaps.forEach { gap ->
            append("- \"").append(gap.span.text).append("\" (").append(gap.kind.name.removePrefix("GAP_KIND_"))
            if (gap.frameRolesList.isNotEmpty()) {
                append(", roles: ").append(gap.frameRolesList.joinToString(",") { it.name.removePrefix("FRAME_ROLE_") })
            }
            if (gap.hypothesesTriedList.isNotEmpty()) {
                append(", already refused: ").append(gap.hypothesesTriedList.joinToString(",") { it.ref })
            }
            appendLine(")")
        }
        appendLine()
        appendLine("Bound so far:")
        state.lattice.mentionsList
            .filter { it.bindingsCount > 0 }
            .forEach { appendLine("- \"${it.span.text}\" -> ${it.getBindings(0).ref}") }
        appendLine()
        appendLine("Propose a model ref for each unbound span, one per line, as: <span text> -> <ref>")
        appendLine("Answer NONE if you cannot. Propose nothing you are not confident in; a wrong")
        appendLine("proposal is worse than none, because the gate will refuse it and the round is spent.")
    }

// ---------------------------------------------------------------------------- emulated

/**
 * `emulated` is config-legal and **dispatch refuses it by name**. G5 territory belongs to
 * RV-P8; the loader accepting a rung the dispatcher will not run is deliberate, so that
 * enabling it later is a config edit rather than a discovery that the code path is missing.
 */
class EmulatedRung : Rung {
    override suspend fun propose(
        state: LadderState,
        gaps: List<GapRecord>,
        config: LadderConfig,
    ): RungProposal = throw RungRefused("rung 'emulated' is not implemented — G5/LLM_EMULATED is RV-P8")
}
