package org.tatrman.kantheon.golem.resolution.compose

import org.tatrman.kantheon.golem.resolution.operatorRefs
import org.tatrman.kantheon.golem.resolution.skills.LayeredSkillLibrary
import org.tatrman.kantheon.golem.resolution.skills.SkillBody
import org.tatrman.kantheon.golem.resolution.skills.SkillException
import org.tatrman.resolver.v1.FrameRole
import org.tatrman.resolver.v1.ResolutionState
import org.tatrman.resolver.v1.TargetClass
import org.tatrman.resolver.v1.ValueKind

// RV-P5.3 T2/T3 — `composeStructuredQuestion`. A covered lattice becomes a question.
// Deterministic, ZERO LLM. Everything it needs was decided below the door line: the frame
// roles came from the core's rule pass (Q-15 RULED: rules, no LLM assist), the bindings
// survived the evidence-class gate, the members are attributions on values, the time grain is
// a grounded value. Compose READS; it does not decide.

/** A restriction: an attribute, and the member it is restricted to. */
data class Filter(
    val ref: String,
    val memberRef: String = "",
    val literal: String = "",
    val anchorMentionId: String = "",
)

/** A grounded temporal value — the grain an `op:trend` needs to exist at all. */
data class TimeGrain(
    val ref: String,
    val normalizedValue: String,
    val text: String = "",
)

/**
 * What the Golem asks OF THE DATA — over modeled entities, never over tables. This is the
 * "entity-level query" the server's thesis names: the agent structures a question over
 * entities and the server does the rest deterministically.
 */
data class StructuredQuestion(
    val subjects: List<String> = emptyList(),
    val measures: List<String> = emptyList(),
    val groupings: List<String> = emptyList(),
    val filters: List<Filter> = emptyList(),
    val timeGrain: TimeGrain? = null,
    val operators: List<String> = emptyList(),
    /**
     * Ops the question named, whose bodies loaded, and which could not APPLY to what resolved.
     * Dropped from the composition, carried as notes — "why the comparison is missing" is part
     * of an honest answer.
     */
    val inapplicableOperators: List<String> = emptyList(),
    val retrievalDirectives: List<String> = emptyList(),
    val formattingDirectives: Map<String, String> = emptyMap(),
) {
    /**
     * Something to select. A question with no measure and no subject has no shape the data can
     * answer, whatever else resolved.
     */
    val isAnswerable: Boolean get() = measures.isNotEmpty() || subjects.isNotEmpty()
}

/**
 * Compose could not honour the question. Carries WHY, because the refusal renders it: an
 * operator missing from the library and an unsatisfied `requires:` are different things to be
 * told.
 */
class ComposeRefused(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Refs of mentions carrying [role] — **a mention contributes to EVERY role it has.**
 *
 * 32 of 137 corpus mentions carry two (P0.2's blocking finding): *"Zobraz tržby podle
 * prodejen"* is ABOUT revenue *and* aggregates it. Treating roles as a partition would drop
 * either the subject or the measure, and which one it dropped would depend on emission order.
 */
private fun refs(
    state: ResolutionState,
    role: FrameRole,
): List<String> =
    state.mentionsList
        .filter { role in it.frameRolesList }
        .flatMap { it.bindingsList }
        .filter { it.targetClass == TargetClass.TARGET_CLASS_MODEL_OBJECT }
        .map { it.ref }
        .distinct()

/**
 * **Members belong to filters, not to mentions.** `501001` is an attribution on a VALUE
 * (`er.CostCenter.code#220`), anchored to the mention whose categories scoped the lookup
 * (RV-33). The filter is therefore `(attribute_ref, member_ref)`, and the anchoring is what
 * makes it "the account 501001" rather than "some 501001 somewhere".
 */
private fun filters(state: ResolutionState): List<Filter> {
    // Members first: a value attributed to an attribute IS a filter, and it is the most
    // specific thing the question said.
    val fromValues =
        state.valuesList.flatMap { value ->
            value.attributionsList.map { attribution ->
                Filter(
                    ref = attribution.attributeRef,
                    memberRef = if (attribution.hasBinding()) attribution.binding.ref else "",
                    literal = value.span.text,
                    anchorMentionId = value.anchorMentionId,
                )
            }
        }
    // Then FILTER-role mentions that no value pinned — "costs of the cost centre", with the
    // centre itself unrestricted, is still a restriction on scope.
    val anchored = fromValues.map { it.anchorMentionId }.toSet()
    val fromMentions =
        state.mentionsList
            .filter { FrameRole.FRAME_ROLE_FILTER in it.frameRolesList && it.id !in anchored }
            .flatMap { mention ->
                mention.bindingsList
                    .filter {
                        it.targetClass == TargetClass.TARGET_CLASS_MODEL_OBJECT ||
                            it.targetClass == TargetClass.TARGET_CLASS_MEMBER
                    }.map { Filter(ref = it.ref, anchorMentionId = mention.id) }
            }
    return fromValues + fromMentions
}

private fun timeGrain(state: ResolutionState): TimeGrain? =
    state.valuesList
        .firstOrNull { it.kind == ValueKind.VALUE_KIND_GROUNDED && it.hasGrounding() && it.grounding.kind == "DATE" }
        ?.let { value ->
            TimeGrain(
                ref = value.grounding.ref.ifBlank { value.grounding.kernel },
                normalizedValue = value.grounding.normalizedValue,
                text = value.span.text,
            )
        }

/**
 * Resolve every `op:` to a body and compose the directives (contracts §6).
 *
 * Retrieval **MERGES** — two operators both narrowing the retrieval are both honoured.
 *
 * ⚠ Formatting **ACCUMULATES in op order**, and §6's *"last-op-wins per directive key"* is not
 * literally implementable yet. A body's `Formatting:` section is undifferentiated prose
 * (contracts §2: prose for the Golem), so there is no directive KEY for a later op to win on.
 * This returns a map keyed by op id in question order, which is the honest shape: nothing is
 * dropped, and the LAST entry is the one a renderer should prefer when two conflict. H5 is the
 * live case — `op:show` says "table by default" and `op:trend` says "line chart by default",
 * and both reach the envelope.
 *
 * ⚑ Making §6 literal needs a KEYED formatting line in the body grammar (e.g.
 * `Formatting: chart=line`), which is a change to the artifact `tatrman` emits — the same
 * `OperatorEntry` change `requires:` wants. Both Golems have now independently reached this
 * conclusion and written it down, which is the argument for doing it.
 */
fun composeOperators(
    ops: List<String>,
    library: LayeredSkillLibrary,
): Triple<List<SkillBody>, List<String>, Map<String, String>> {
    val bodies = ops.map { library.get(it) } // throws SkillException on an unknown op
    val retrieval = bodies.mapNotNull { it.retrieval.takeIf(String::isNotBlank) }
    // LinkedHashMap: insertion order IS question order, and the order is load-bearing.
    val formatting = LinkedHashMap<String, String>()
    bodies.forEach { if (it.formatting.isNotBlank()) formatting[it.op] = it.formatting }
    return Triple(bodies, retrieval, formatting)
}

/**
 * The requirement vocabulary the stdlib declares, each a predicate over what RESOLVED.
 *
 * ⛑ Carried from golem-py's P4.3 review: two of these were missing there and **the miss was
 * silent** — an unrecognised name fell through the if/elif chain and counted as SATISFIED, so
 * `op:top-n` and `op:drilldown` had their applicability waved through. An unknown requirement
 * REFUSES here, which is the same direction every other unhonourable operator takes: "this
 * operator declares a condition we cannot evaluate" is the same class of ignorance as an
 * operator with no body.
 */
internal val REQUIREMENT_CHECKS: Map<String, (StructuredQuestion) -> Boolean> =
    mapOf(
        // "without a grain … nothing to group by" (trend.md)
        "time-grain" to { q -> q.timeGrain != null },
        // "exactly two comparanda" (compare.md)
        "two-series" to { q -> q.measures.size >= 2 },
        // "a ranking needs something to rank by … when it names several, ask rather than
        // choose" (top-n.md) — so exactly one measure satisfies it, and both 0 and ≥2 do not
        "order-measure" to { q -> q.measures.size == 1 },
        // "with no parent, there is no 'finer'" (drilldown.md)
        "parent-context" to { q -> q.groupings.isNotEmpty() },
    )

/**
 * `requires:` is validated at **COMPOSE, not at match** (contracts §2). A trigger word matching
 * is a fact about the question's surface; whether the operator can DO anything is a fact about
 * what resolved. `op:trend` with no time grain has nothing to group by, and surfacing that
 * beats inventing a grain.
 */
fun checkApplicability(
    bodies: List<SkillBody>,
    question: StructuredQuestion,
): List<String> =
    bodies.flatMap { body ->
        body.requires.mapNotNull { requirement ->
            val check =
                REQUIREMENT_CHECKS[requirement]
                    ?: throw ComposeRefused(
                        "${body.op} declares requirement '$requirement', which this Golem cannot " +
                            "evaluate (known: ${REQUIREMENT_CHECKS.keys.sorted().joinToString(", ")})",
                    )
            if (check(question)) null else "${body.op} requires $requirement"
        }
    }

/**
 * The whole composition. Raises [ComposeRefused] rather than guessing.
 *
 * ⚑ **Divergence from golem-py, and it is the point of this phase.** golem-py's compose is the
 * *only* gate on "can this Golem do this", because the OS Golem has no intent classifier — so
 * its P4.3 T1 ruling made the operator requirement do that job: no `op:` ⇒ refuse. The Kotlin
 * Golem has Themis's verdict (see `resolution/intent/TurnIntent.kt`), so **the intent
 * predicate is the gate and this function is not**: an operator-less DATA_QUERY is a perfectly
 * ordinary *"Zobraz tržby podle prodejen"* — a question with a subject, a measure and a
 * grouping and no action word at all — and refusing it here would fail H1's own shape.
 *
 * The two shells therefore disagree on one predicate **by design**, each using the strongest
 * signal it has. What they must NOT disagree on is the composed question, which is what
 * P5.4's conformance tier checks.
 */
fun composeStructuredQuestion(
    state: ResolutionState,
    library: LayeredSkillLibrary,
): StructuredQuestion {
    val base =
        StructuredQuestion(
            subjects = refs(state, FrameRole.FRAME_ROLE_SUBJECT),
            measures = refs(state, FrameRole.FRAME_ROLE_MEASURE),
            groupings = refs(state, FrameRole.FRAME_ROLE_GROUPING),
            filters = filters(state),
            timeGrain = timeGrain(state),
            operators = operatorRefs(state),
        )

    val (bodies, retrieval, formatting) =
        try {
            composeOperators(base.operators, library)
        } catch (e: SkillException) {
            throw ComposeRefused(e.message ?: "operator library refused", e)
        }

    // Applicability is checked against the question as composed so far — a `requires:` is a
    // claim about what RESOLVED, which is exactly what this object holds.
    val composed = base.copy(retrievalDirectives = retrieval, formattingDirectives = formatting)
    val unsatisfied = checkApplicability(bodies, composed)

    val question =
        if (unsatisfied.isEmpty()) {
            composed
        } else {
            val inapplicable = unsatisfied.map { it.substringBefore(' ') }.toSet()
            val applicable = bodies.filter { it.op !in inapplicable }
            composed.copy(
                operators = applicable.map { it.op },
                inapplicableOperators = unsatisfied.sorted(),
                retrievalDirectives = applicable.mapNotNull { it.retrieval.takeIf(String::isNotBlank) },
                formattingDirectives =
                    LinkedHashMap<String, String>().apply {
                        applicable.forEach { if (it.formatting.isNotBlank()) put(it.op, it.formatting) }
                    },
            )
        }

    if (!question.isAnswerable) {
        throw ComposeRefused(
            "the question has no measure and no subject — nothing to select, whatever else resolved",
        )
    }
    return question
}
