package org.tatrman.kantheon.golem.format

import org.tatrman.kantheon.common.v1.EntityBinding as HandoffEntityBinding
import org.tatrman.kantheon.common.v1.HandoffContext
import org.tatrman.kantheon.common.v1.ViewProvenance
import org.tatrman.kantheon.envelope.v1.Chip
import org.tatrman.kantheon.envelope.v1.InvestigateChip
import org.tatrman.kantheon.golem.v1.GolemRequest
import org.tatrman.kantheon.themis.v1.Themis.EntityBinding as ThemisEntityBinding
import org.tatrman.kantheon.themis.v1.Themis.IntentKind

/**
 * `InvestigateChip` emitter (PD-1, iris contracts §1.1) — **kantheon net-new** (no v2
 * source). On a confidence-gate **failure** for an **analytical** intent
 * (RCA / FORECAST / SIMULATION), Golem attaches an escalation affordance carrying a
 * filled [HandoffContext]; the BFF re-issues the turn with `routing_hint = "pythia"`.
 * **Golem never calls Pythia** — this is metadata, not control flow, and may ride
 * alongside a partial answer.
 *
 * Size guards (cohesion review): handoff `entities` capped at [ENTITY_CAP] (most
 * relevant first), `suggested_focus` truncated to [FOCUS_MAX_BYTES].
 */
object InvestigateChips {
    const val ENTITY_CAP: Int = 50
    const val FOCUS_MAX_BYTES: Int = 1024

    private val ANALYTICAL = setOf(IntentKind.RCA, IntentKind.FORECAST, IntentKind.SIMULATION)

    /** Build an `InvestigateChip` when the turn is an analytical intent that failed the gate; else null. */
    fun maybe(
        request: GolemRequest,
        gateFailed: Boolean,
        currentView: ViewProvenance?,
    ): Chip? {
        if (!gateFailed) return null
        val intent = request.resolvedIntent.intentKind
        if (intent !in ANALYTICAL) return null

        val handoff =
            HandoffContext
                .newBuilder()
                .setSourceAgentId(request.golemId)
                .setSourceTurnRef(request.id)
                .setUserQuestion(request.question)
                .setDomain(request.golemId)
                .addAllEntities(
                    request.resolvedIntent.bindingsList
                        .take(ENTITY_CAP)
                        .map { it.toHandoff() },
                ).setSuggestedFocus(truncate(request.question, FOCUS_MAX_BYTES))
        currentView?.let { handoff.view = it }

        return Chip
            .newBuilder()
            .setInvestigate(
                InvestigateChip
                    .newBuilder()
                    .setHandoff(handoff)
                    .setProposedQuestion(request.question)
                    .setLabel(labelFor(intent)),
            ).build()
    }

    private fun labelFor(intent: IntentKind): String =
        when (intent) {
            IntentKind.RCA -> "Prozkoumat příčinu"
            IntentKind.FORECAST -> "Vytvořit prognózu"
            IntentKind.SIMULATION -> "Spustit simulaci"
            else -> "Prozkoumat hlouběji"
        }

    private fun ThemisEntityBinding.toHandoff(): HandoffEntityBinding {
        val b = HandoffEntityBinding.newBuilder()
        when {
            hasDomain() ->
                b
                    .setEntityType(domain.entityTypeRef)
                    .setEntityId(domain.resolvedId)
                    .setDisplayLabel(domain.resolvedLabel.ifBlank { domain.rawText })
                    .setSource("fuzzy")
            hasUniversal() ->
                b
                    .setEntityType(universal.entityType.name.lowercase())
                    .setDisplayLabel(universal.normalizedValue.ifBlank { universal.rawText })
                    .setSource("carryover")
        }
        return b.build()
    }

    private fun truncate(
        s: String,
        maxBytes: Int,
    ): String {
        val bytes = s.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return s
        // Trim to the byte budget without splitting a multi-byte char.
        return String(bytes, 0, maxBytes, Charsets.UTF_8).let {
            if (it.toByteArray(Charsets.UTF_8).size > maxBytes) it.dropLast(1) else it
        }
    }
}
