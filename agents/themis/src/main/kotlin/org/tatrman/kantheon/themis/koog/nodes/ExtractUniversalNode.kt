package org.tatrman.kantheon.themis.koog.nodes

import org.tatrman.kantheon.themis.client.NlpEntity
import org.tatrman.kantheon.themis.koog.ResolverContext
import org.tatrman.kantheon.themis.koog.UniversalEntityNormalized
import org.tatrman.kantheon.themis.v1.Themis

/**
 * Stage 2.3 T3 — pure body of the `extractUniversal` Resolver node.
 *
 * Maps the NLP layer's raw entity labels onto Themis's universal type enum and
 * preserves the supporting metadata (normalizedValue, sourceEngine, char offsets).
 *
 * Lives outside [ResolverContext] so both the legacy `ThemisGraphDispatch` step and
 * the Koog node in [org.tatrman.kantheon.themis.koog.themisGraph] can call the
 * same code — matches the Stage 2.1 spike's recommended pattern (pure delegate
 * + thin wrapper).
 */

fun universalTypeFor(label: String): Themis.UniversalEntityType =
    when (label.uppercase()) {
        "PER", "PERSON" -> Themis.UniversalEntityType.PERSON
        "LOC", "LOCATION" -> Themis.UniversalEntityType.LOCATION
        "ORG", "ORGANIZATION" -> Themis.UniversalEntityType.ORGANIZATION
        "DATE", "TIME" -> Themis.UniversalEntityType.DATE
        "MONEY", "AMOUNT" -> Themis.UniversalEntityType.MONEY
        else -> Themis.UniversalEntityType.MISC
    }

fun normaliseUniversal(entity: NlpEntity): UniversalEntityNormalized =
    UniversalEntityNormalized(
        rawText = entity.text,
        entityType = universalTypeFor(entity.label),
        normalizedValue = entity.normalizedValue,
        sourceEngine = entity.sourceEngine,
        charStart = entity.charStart,
        charEnd = entity.charEnd,
    )

/**
 * State-level transformation used by both ThemisGraphDispatch and ThemisGraph. Does
 * not touch tracing — the spans live in the caller (ThemisGraphDispatch keeps its
 * existing span; the Koog node will get its own via the agent runtime in T6).
 */
fun extractUniversalStep(state: ResolverContext): ResolverContext {
    val nlpResult = state.parseState.nlpResponse
    val entities = nlpResult.entities.map(::normaliseUniversal)
    return state.copy(
        parseState =
            state.parseState.copy(
                universalEntities = entities,
                lastNode = "extractUniversal",
            ),
    )
}
