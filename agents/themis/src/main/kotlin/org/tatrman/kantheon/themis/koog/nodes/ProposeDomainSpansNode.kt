package org.tatrman.kantheon.themis.koog.nodes

import org.tatrman.kantheon.themis.client.NlpToken
import org.tatrman.kantheon.themis.koog.DomainSpan
import org.tatrman.kantheon.themis.koog.ResolverContext

/**
 * Stage 2.3 T3 — pure body of the `proposeDomainSpans` Resolver node.
 *
 * Walks the NLP layer's tokens and emits a [DomainSpan] for each noun-head
 * (UPOS `NOUN` or `PROPN`). Multi-word nominal phrases (e.g. "faktury
 * zákazníka Novák") are intentionally single-headed for v1 — see the
 * `// TODO depHead` note that carried over from ai-platform.
 *
 * Lives outside [ResolverContext] so both `ThemisGraphDispatch` and `themisGraph`
 * can call the same code (Stage 2.1 spike pattern).
 */

fun proposeDomainSpansFromTokens(tokens: List<NlpToken>): List<DomainSpan> {
    // TODO: walk depHead to collect modifier chains for multi-word nominal
    //  phrases (e.g. "faktury zákazníka Novák" → single span "zákazníka
    //  Novák"). Deferred from ai-platform — current single-head extraction
    //  is sufficient for Stage 04 scope.
    val nounHeads = tokens.filter { it.upos == "NOUN" || it.upos == "PROPN" }
    return nounHeads.map { token ->
        DomainSpan(
            charStart = token.charStart,
            charEnd = token.charEnd,
            coveredText = token.text,
            pos = token.upos,
            depHead = token.depHead,
            depRelation = token.depRelation,
        )
    }
}

fun proposeDomainSpansStep(state: ResolverContext): ResolverContext {
    val tokens = state.parseState.nlpResponse.tokens
    val domainSpans = proposeDomainSpansFromTokens(tokens)
    return state.copy(
        parseState =
            state.parseState.copy(
                domainSpans = domainSpans,
                lastNode = "proposeDomainSpans",
            ),
    )
}
