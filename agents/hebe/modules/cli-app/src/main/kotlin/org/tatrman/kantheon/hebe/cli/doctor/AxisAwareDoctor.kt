package org.tatrman.kantheon.hebe.cli.doctor

import org.tatrman.kantheon.hebe.config.Availability
import org.tatrman.kantheon.hebe.config.Axes
import org.tatrman.kantheon.hebe.config.LlmSource
import org.tatrman.kantheon.hebe.config.PlatformReach

/**
 * The axis-aware `hebe doctor` skeleton (P2 Stage 2.1 T5; contracts §5.3).
 *
 * The check registry is keyed by resolved [Axes]. The structure lands here;
 * Stages 2.2–2.4 register their concrete platform checks (gateway, Keycloak,
 * OTel, capabilities-mcp, iris-bff) into it. The load-bearing idea is the
 * **required-vs-probed** split:
 *
 *  - `platform.availability = always` (server/k8s) → platform dependencies are
 *    **required**: unreachable ⇒ `FAIL`.
 *  - `platform.availability = intermittent` (personal) → the same dependencies
 *    are **probed, not required**: unreachable ⇒ `WARN` (*degraded*), never
 *    `FAIL` — an offline personal host is healthy, just disconnected.
 *  - `platform.reach = none` (local) → there are no platform dependencies at
 *    all; only the self-contained checks (LLM endpoint, keychain, SQLite,
 *    workspace) run.
 */
enum class RequirementLevel {
    /** Unreachable is a hard failure. */
    REQUIRED,

    /** Unreachable is a soft *degraded* state, not a failure. */
    PROBED,
}

/**
 * A registry entry: a named platform check, its probe, and which axes gate it.
 * `gate` decides whether the check applies at all under the resolved axes
 * (e.g. a gateway check only when `llm.source` reaches the gateway).
 */
data class DoctorCheckSpec(
    val name: String,
    val gate: (Axes) -> Boolean,
    val probe: suspend (Axes) -> CheckResult,
)

object AxisAwareDoctor {
    /**
     * The requirement level for a platform-reaching dependency under [axes].
     * Driven solely by `platform.availability` (contracts §5.3): always ⇒
     * required, intermittent ⇒ probed. With `reach = none` there are no platform
     * deps, so the level is moot — treated as [RequirementLevel.PROBED] (the
     * lenient end) defensively.
     */
    fun platformRequirement(axes: Axes): RequirementLevel =
        when {
            axes.platform.reach == PlatformReach.NONE -> RequirementLevel.PROBED
            axes.platform.availability == Availability.ALWAYS -> RequirementLevel.REQUIRED
            axes.platform.availability == Availability.INTERMITTENT -> RequirementLevel.PROBED
            else -> RequirementLevel.PROBED
        }

    /**
     * Applies the requirement level to a raw probe result: a `FAIL` from a
     * `PROBED` dependency is demoted to `WARN` (degraded) with the original
     * cause preserved. `REQUIRED` results pass through unchanged.
     */
    fun applyRequirement(
        level: RequirementLevel,
        raw: CheckResult,
    ): CheckResult =
        if (level == RequirementLevel.PROBED && raw.status is CheckStatus.Fail) {
            raw.copy(
                status = CheckStatus.Warn,
                message = "degraded (probed, not required): ${raw.message}",
            )
        } else {
            raw
        }

    /**
     * Filters the registered platform checks to those whose `gate` admits the
     * resolved axes — the per-profile check set. Concrete specs are appended by
     * later stages; the registry is empty at the 2.1 skeleton.
     */
    fun planChecks(
        axes: Axes,
        registry: List<DoctorCheckSpec>,
    ): List<DoctorCheckSpec> = registry.filter { it.gate(axes) }

    /** The base (always-run) check name that targets the platform LLM gateway. */
    const val LLM_ENDPOINT_CHECK = "LLM Endpoint"

    /**
     * Demotes the platform-reaching results among the base (always-run) checks
     * (those from `runAllChecks`, which are not part of the gated registry). The
     * `LLM Endpoint` probe targets the gateway when `llm.source != byok`, so an
     * intermittent/none profile treats it as *degraded* (WARN), not a failure —
     * an offline `personal` host must not fail `doctor`. Under `byok` (local) the
     * endpoint is not a platform dependency, so failures pass through unchanged.
     */
    fun demoteBaseChecks(
        axes: Axes,
        base: List<CheckResult>,
    ): List<CheckResult> {
        if (axes.llm.source == LlmSource.BYOK) return base
        val level = platformRequirement(axes)
        return base.map { if (it.name == LLM_ENDPOINT_CHECK) applyRequirement(level, it) else it }
    }
}
