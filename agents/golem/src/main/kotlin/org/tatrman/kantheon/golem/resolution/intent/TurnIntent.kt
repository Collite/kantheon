package org.tatrman.kantheon.golem.resolution.intent

import org.tatrman.kantheon.golem.resolution.operatorRefs
import org.tatrman.kantheon.themis.v1.Themis
import org.tatrman.resolver.v1.ResolutionState

// RV-P5.3 T1 — the intent seam. Operator annotations are EVIDENCE (RV-35 / Q-23):
// they augment a classification, they never replace one.

/**
 * The fast path's half of the intent question. `IntentKind` is Themis's four-value
 * vocabulary and it has **no `DATA_QUERY` member** — the plan's "DATA_QUERY-class" is
 * `PROCEDURAL`, which Themis defines as *"a direct lookup/listing"* in its own tie-break
 * prompt. This enum names the class so the predicate does not read as a magic equality.
 */
enum class IntentClass {
    /** PROCEDURAL — the γ fast path is allowed to consider this turn. */
    DATA_QUERY,

    /** RCA / FORECAST / SIMULATION — over the Golem's paygrade; H4 territory. */
    INVESTIGATION,

    /** Nothing said what this is, and no evidence moved it. */
    UNKNOWN,
}

/** Where the verdict came from — the honesty record, and what the rung log renders. */
enum class IntentSource {
    /** Themis classified it and nothing here changed that. */
    THEMIS,

    /** Themis had no verdict; `op:` evidence in the lattice supplied one. */
    OPERATOR_EVIDENCE,

    /** Themis had no verdict and the lattice offered no evidence either. */
    NONE,
}

data class TurnIntent(
    val kind: Themis.IntentKind,
    val intentClass: IntentClass,
    val source: IntentSource,
    /** `op:` refs the lattice carries, in span order — the order the user said them in. */
    val operators: List<String> = emptyList(),
    val rationale: String = "",
) {
    val isDataQuery: Boolean get() = intentClass == IntentClass.DATA_QUERY
}

/**
 * ## Where the classifier lives, and why nothing moved
 *
 * The entry point the plan asks to be named, not paraphrased:
 * `classifyIntent(tokens, locale, rules, llm)` —
 * `agents/themis/src/main/kotlin/org/tatrman/kantheon/themis/koog/nodes/ClassifyIntentKindNode.kt:38`,
 * wrapped by `classifyIntentKindStep` at :133, wired at `ThemisGraph.kt:71`, with its rule
 * layer in `IntentKindRules.kt` over `prompts/intent_kind_rules.yaml`.
 *
 * It runs over **NLP tokens**, inside Themis, **before the Golem exists**. It therefore
 * cannot "receive the lattice's `op:` annotations": the lattice is produced by
 * `callResolutionCore`, one hop later, in this process. Handing the annotations to that
 * function would mean moving it — as a shared lib, a copy, or a second cross-agent hop —
 * and ruling (A) of 2026-08-06 says Themis is untouched.
 *
 * **So nothing moves.** Themis's verdict already arrives here: `GolemRequestFactory` sets
 * `GolemRequest.resolved_intent` to Themis's `Resolution` verbatim, and `intent_kind` is
 * field 10 on it — `format/InvestigateChip.kt:36` has been reading it since Phase 3. The
 * seam is therefore **prior + evidence**, not a relocated classifier: Themis's kind is the
 * prior, the lattice's operators are the evidence, and this file is the only place they
 * meet. No duplication, no shared lib, no extra hop, Themis untouched by construction.
 *
 * ## ⚑ What the wire does NOT carry, and what it costs
 *
 * `IntentClassifyOutput` has `{intentKind, confidence, source, rationale}` and **only
 * `intentKind` survives onto `Themis.Resolution`**. `Resolution.confidence` is a different
 * number — `DecideHitlOrEmitNode.kt:184` sets it from `result.confidence`, the FUNCTION
 * inference's confidence — so reading it as an intent confidence would be inventing a
 * semantic the producer never promised.
 *
 * The consequence is the shift rule below being narrow, and it is narrow honestly rather
 * than by choice: the only borderline the wire can express is
 * `INTENT_KIND_UNSPECIFIED`. A `RULE_DEFAULT` PROCEDURAL (nothing triggered, so Themis fell
 * back) is indistinguishable from a confident rule match — which happens not to matter,
 * since both are already DATA_QUERY-class — but an `LLM_FALLBACK` RCA at confidence 0.5 is
 * indistinguishable from a confident RCA, and that one does matter.
 *
 * ⚑ For Bora: two additive fields on `themis.v1.Resolution` (7–9 are free) — an
 * `intent_confidence` and an `intent_source` — would make the borderline legible and let
 * this seam shift a *low-confidence* investigation verdict rather than only an absent one.
 * Until then, this file must not pretend to know more than it was told.
 *
 * ## The rule
 *
 * Evidence **augments**, so it may fill a vacuum and may confirm; it may never overturn.
 *
 *  - Themis said PROCEDURAL ⇒ DATA_QUERY. Operators confirm and are recorded; the verdict
 *    was already the one they argue for.
 *  - Themis said RCA / FORECAST / SIMULATION ⇒ INVESTIGATION, **operators notwithstanding**.
 *    A question that says *"Proč"* and also says *"vývoj"* has named a declared action AND
 *    asked why; letting `op:trend` overturn the RCA would be exactly the "replace" this
 *    rule forbids, and it would route a causal question into a fast path that cannot answer
 *    it. It falls to the selection stub, which refuses `NO_CAPABLE_PLUGIN` — the H4 posture.
 *  - Themis said nothing (`UNSPECIFIED`, the real KEEP_TOGETHER-dispatch case where
 *    `GolemRequestFactory` leaves `resolved_intent` unset) ⇒ **operators decide**. An `op:`
 *    binding means the estate declared this action in its own lexicon and the matcher bound
 *    the user's word to it (RV-35). That is a direct statement that the question names
 *    something this Golem was taught to do, which is what DATA_QUERY means here. With no
 *    operators either, the verdict is UNKNOWN and the fast path stays shut.
 *
 * This function is **read-only against the lattice** — `IntentReadOnlyFenceSpec` proves it
 * structurally rather than by inspection.
 */
fun classifyTurnIntent(
    prior: Themis.Resolution?,
    lattice: ResolutionState?,
): TurnIntent {
    val ops = operatorRefs(lattice)
    val kind = prior?.intentKind ?: Themis.IntentKind.INTENT_KIND_UNSPECIFIED

    return when (kind) {
        Themis.IntentKind.PROCEDURAL ->
            TurnIntent(
                kind = kind,
                intentClass = IntentClass.DATA_QUERY,
                source = IntentSource.THEMIS,
                operators = ops,
                rationale =
                    if (ops.isEmpty()) {
                        "themis: PROCEDURAL"
                    } else {
                        "themis: PROCEDURAL, confirmed by ${ops.joinToString(",")}"
                    },
            )

        Themis.IntentKind.RCA,
        Themis.IntentKind.FORECAST,
        Themis.IntentKind.SIMULATION,
        ->
            TurnIntent(
                kind = kind,
                intentClass = IntentClass.INVESTIGATION,
                source = IntentSource.THEMIS,
                operators = ops,
                rationale =
                    if (ops.isEmpty()) {
                        "themis: $kind"
                    } else {
                        // Recorded, deliberately, and deliberately not acted on.
                        "themis: $kind — ${ops.joinToString(",")} present but evidence does not overturn"
                    },
            )

        // UNSPECIFIED, and whatever a future enum member turns out to be: no prior.
        else ->
            if (ops.isEmpty()) {
                TurnIntent(
                    kind = Themis.IntentKind.INTENT_KIND_UNSPECIFIED,
                    intentClass = IntentClass.UNKNOWN,
                    source = IntentSource.NONE,
                    operators = emptyList(),
                    rationale = "no themis verdict and no operator evidence",
                )
            } else {
                TurnIntent(
                    kind = Themis.IntentKind.PROCEDURAL,
                    intentClass = IntentClass.DATA_QUERY,
                    source = IntentSource.OPERATOR_EVIDENCE,
                    operators = ops,
                    rationale = "no themis verdict; operator evidence ${ops.joinToString(",")}",
                )
            }
    }
}
