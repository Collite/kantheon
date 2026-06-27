package org.tatrman.kantheon.themis.koog.nodes

import io.github.oshai.kotlinlogging.KotlinLogging
import org.tatrman.kantheon.themis.client.NlpToken
import org.tatrman.kantheon.themis.koog.OutcomeState
import org.tatrman.kantheon.themis.koog.ResolverContext
import org.tatrman.kantheon.themis.v1.Themis

private val logger = KotlinLogging.logger { }

/**
 * Phase 3 Stage 3.2 — body of `detectMultiQuestion` (runs on the fresh path,
 * before `extractUniversal`). Deterministic, UD-dependency-graph based, with a
 * strong conservative bias toward "single question" (a false split is worse
 * than a missed one — Iris can always re-ask).
 *
 * A turn is a multi-question only when ALL hold:
 *  1. ≥2 clause heads — tokens with `dep_relation ∈ {root, conj}` (handles both
 *     verbal and copular/nominal predicates, so "what is X and what is Y" splits).
 *  2. each clause owns at least one **content noun** (NOUN/PROPN). A clause that
 *     is only verbs/aux (e.g. "…a má zaplatit") shares the other's subject — not
 *     independent.
 *  3. the clauses' content-noun lemma sets are **disjoint** — distinct topics
 *     (catches the same entity repeated across clauses).
 *  4. no clause contains an **anaphoric pronoun** (jejich / its / their / …) —
 *     a back-reference ties the clauses together ("…a jaká je jejich částka").
 */
sealed interface DetectMultiQuestionOutput {
    data object SingleQuestion : DetectMultiQuestionOutput

    data class MultiQuestion(
        val subQuestions: List<String>,
    ) : DetectMultiQuestionOutput
}

private val ANAPHORIC_PRONOUN_LEMMAS =
    setOf(
        // cs
        "on",
        "ona",
        "ono",
        "oni",
        "jeho",
        "její",
        "jejich",
        "ten",
        "tento",
        "tato",
        "toto",
        "jenž",
        // en
        "it",
        "its",
        "they",
        "them",
        "their",
        "this",
        "that",
        "these",
        "those",
        "he",
        "she",
    )

private val CLAUSE_HEAD_RELATIONS = setOf("root", "conj")

fun detectMultiQuestion(tokens: List<NlpToken>): DetectMultiQuestionOutput {
    if (tokens.isEmpty()) return DetectMultiQuestionOutput.SingleQuestion

    // 1-based ids mirror UD dep_head indexing.
    val byId: Map<Int, NlpToken> = tokens.mapIndexed { i, t -> (i + 1) to t }.toMap()
    val idOf: Map<NlpToken, Int> = tokens.mapIndexed { i, t -> t to (i + 1) }.toMap()
    val clauseHeadIds =
        tokens.mapIndexedNotNull { i, t -> (i + 1).takeIf { t.depRelation in CLAUSE_HEAD_RELATIONS } }.toSet()

    if (clauseHeadIds.size <= 1) return DetectMultiQuestionOutput.SingleQuestion

    // Assign each token to the clause head it ultimately hangs off (walk dep_head).
    val clauseOf: Map<Int, Int> =
        tokens.indices.associate { i ->
            val id = i + 1
            (id) to ascendToClauseHead(id, byId, clauseHeadIds)
        }

    val tokensByClause: Map<Int, List<NlpToken>> =
        clauseHeadIds.associateWith { head ->
            tokens.filter { clauseOf[idOf.getValue(it)] == head }
        }

    // 2. every clause owns a content noun.
    val contentNounsByClause =
        tokensByClause.mapValues { (_, clauseTokens) ->
            clauseTokens.filter { it.upos == "NOUN" || it.upos == "PROPN" }.map { it.lemma.lowercase() }.toSet()
        }
    if (contentNounsByClause.values.any { it.isEmpty() }) return DetectMultiQuestionOutput.SingleQuestion

    // 3. content-noun sets disjoint across clauses.
    val allNouns = contentNounsByClause.values.flatten()
    if (allNouns.size != allNouns.toSet().size) return DetectMultiQuestionOutput.SingleQuestion

    // 4. no anaphoric pronoun in any clause.
    val hasAnaphora =
        tokensByClause.values.any { clauseTokens ->
            clauseTokens.any { it.upos == "PRON" && it.lemma.lowercase() in ANAPHORIC_PRONOUN_LEMMAS }
        }
    if (hasAnaphora) return DetectMultiQuestionOutput.SingleQuestion

    // Reconstruct each clause's sub-question text, in source order.
    val subQuestions =
        clauseHeadIds
            .sortedBy { byId.getValue(it).charStart }
            .map { head -> reconstructClause(tokensByClause.getValue(head)) }
            .filter { it.isNotBlank() }

    if (subQuestions.size <= 1) return DetectMultiQuestionOutput.SingleQuestion
    return DetectMultiQuestionOutput.MultiQuestion(subQuestions)
}

private fun ascendToClauseHead(
    startId: Int,
    byId: Map<Int, NlpToken>,
    clauseHeadIds: Set<Int>,
): Int {
    var cur = startId
    var guard = 0
    while (guard++ < byId.size + 1) {
        if (cur in clauseHeadIds) return cur
        val head = byId[cur]?.depHead ?: 0
        if (head == 0) return cur // reached a root that isn't a registered clause head
        cur = head
    }
    return cur
}

private fun reconstructClause(clauseTokens: List<NlpToken>): String {
    val ordered =
        clauseTokens
            .filter { it.depRelation != "cc" && it.depRelation != "punct" }
            .sortedBy { it.charStart }
    if (ordered.isEmpty()) return ""
    val text = ordered.joinToString(" ") { it.text }
    val capitalised = text.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    return if (capitalised.endsWith("?")) capitalised else "$capitalised?"
}

suspend fun detectMultiQuestionStep(state: ResolverContext): ResolverContext =
    when (val out = detectMultiQuestion(state.parseState.nlpResponse.tokens)) {
        is DetectMultiQuestionOutput.SingleQuestion ->
            state.copy(parseState = state.parseState.copy(lastNode = "detectMultiQuestion"))

        is DetectMultiQuestionOutput.MultiQuestion -> {
            logger.debug { "detectMultiQuestion fired: ${out.subQuestions}" }
            org.tatrman.kantheon.themis.ResolverOtel
                .recordMultiQuestionDetected() // Phase 3 Stage 3.6 (T4)
            val multi =
                Themis.MultiQuestionDetected
                    .newBuilder()
                    .addAllSubQuestions(out.subQuestions)
                    // Deterministic detection finds disjoint clauses → SPLIT. The
                    // KEEP_TOGETHER (relating-intent) verdict is a later refinement
                    // via joint-inference (PD-13).
                    .setDecomposition(Themis.Decomposition.SPLIT)
                    .setDecompositionRationale("disjoint clauses with distinct topics")
                    .build()
            val awaiting =
                Themis.AwaitingClarification
                    .newBuilder()
                    .setQuestion("Detected ${out.subQuestions.size} independent questions")
                    .setMultiQuestion(multi)
                    .build()
            state.copy(
                parseState =
                    state.parseState.copy(
                        outcome = OutcomeState.AwaitingClarification,
                        terminalAwaiting = awaiting,
                        lastNode = "detectMultiQuestion",
                    ),
            )
        }
    }
