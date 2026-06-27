package org.tatrman.kantheon.themis.koog.nodes

import org.tatrman.kantheon.themis.koog.OutcomeState
import org.tatrman.kantheon.themis.koog.ResolverContext

/**
 * Stage 2.3 T4 — pure body of the `detectLangAndParse` node.
 *
 * Despite the name this node performs **no** I/O. The actual `NlpClient.analyze`
 * call lives outside the graph in `Main.kt`'s `buildResolverContext` — by the
 * time the graph runs, `state.parseState.nlpResponse` is already populated.
 * This node only marks the state as having completed the parse step.
 *
 * The name carries over from ai-platform Resolver. Renaming to something
 * less misleading (e.g. `markParseComplete`) is deferred — would conflict
 * with the eval-gate prompt/trace parity Stage 2.4 demands.
 */
fun detectLangAndParseStep(state: ResolverContext): ResolverContext =
    state.copy(
        parseState =
            state.parseState.copy(
                outcome = OutcomeState.Parsing,
                lastNode = "detectLangAndParse",
            ),
    )
