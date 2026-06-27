package org.tatrman.kantheon.golem.shem

import org.tatrman.kantheon.capabilities.v1.AgentCapability
import org.tatrman.kantheon.capabilities.v1.LocaleDefault
import org.tatrman.kantheon.capabilities.v1.TermDef
import java.util.concurrent.atomic.AtomicReference

/**
 * The pod's single loaded Shem — an `AgentCapability` (`agent_kind == AREA_QA`)
 * plus the derived read surface the rest of Golem reads against. One per pod
 * ("each Golem instance is brought to life with a domain manifest"). Assembled at
 * boot by [ShemAssembler] from the four converged-design sources; held in
 * `GolemComponents` and registered into capabilities-mcp (Stage 2.2 T4).
 * `golemId == agentId` — `GolemRequest.golem_id` must match it.
 *
 * The backing capability is swappable: the wiring builds a `ShemContext` from the
 * overlay-only fields (identity + `visibility_roles`, knowable before the model
 * loads) so registration + the admission gate can hold a stable reference, then
 * [update]s it with the model-derived fields once Ariadne's model has loaded (boot
 * `load()` / ops `/v1/refresh`). Admission only reads overlay fields, so it is
 * correct even before the model-derived swap.
 */
class ShemContext(
    manifest: AgentCapability,
) {
    private val ref = AtomicReference(manifest)

    val manifest: AgentCapability get() = ref.get()

    /** Swap in a freshly-assembled capability (model-derived fields) after a model load/refresh. */
    fun update(next: AgentCapability) = ref.set(next)

    val agentId: String get() = manifest.agentId
    val golemId: String get() = manifest.agentId
    val areaName: String get() = manifest.areaName

    /** Empty == visible to every authenticated caller (PD-8, capabilities/v1 §field 17). */
    val visibilityRoles: List<String> get() = manifest.visibilityRolesList
    val areaEntities: List<String> get() = manifest.areaEntitiesList
    val preferredQueries: List<String> get() = manifest.preferredQueriesList
    val preferredCapabilities: List<String> get() = manifest.preferredCapabilitiesList
    val terminology: List<TermDef> get() = manifest.areaTerminologyList
    val localeDefaults: List<LocaleDefault> get() = manifest.localeDefaultsList

    /**
     * PD-8 admission check: a caller may reach this Shem when it declares no
     * `visibility_roles` (visible to all authenticated) or the caller holds at
     * least one of them. The bearer's authenticity is validated separately
     * (Stage 2.2 T5); this is the role-entitlement half only.
     */
    fun isVisibleTo(callerRoles: Set<String>): Boolean =
        visibilityRoles.isEmpty() || visibilityRoles.any { it in callerRoles }
}
