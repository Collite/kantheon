package org.tatrman.kantheon.hebe.cli.doctor

import org.tatrman.kantheon.hebe.config.Axes
import org.tatrman.kantheon.hebe.config.LlmSource

/**
 * llm-gateway doctor checks (P2 Stage 2.2 T5), registered into the
 * [AxisAwareDoctor] matrix. Both are gated on `llm.source` reaching the gateway
 * (i.e. not pure BYOK) and carry the required-vs-probed semantics of the
 * resolved profile: on `availability = always` an unreachable gateway is a
 * `FAIL`; on `intermittent` it is demoted to a degraded `WARN`.
 *
 * The probes are injected (`reach`/`modelList`) so the unit test stays offline;
 * production wires them to real HTTP calls.
 */
object GatewayChecks {
    /** True when the LLM target is the gateway (server/k8s default; personal fallback). */
    fun usesGateway(axes: Axes): Boolean = axes.llm.source != LlmSource.BYOK

    /**
     * Builds the two gateway specs. [reach] returns whether the gateway endpoint
     * answered; [models] returns the gateway's advertised model list. The
     * requirement demotion is applied by [AxisAwareDoctor.applyRequirement] using
     * [AxisAwareDoctor.platformRequirement].
     */
    fun specs(
        defaultModel: String,
        reach: suspend () -> Boolean,
        models: suspend () -> List<String>,
    ): List<DoctorCheckSpec> =
        listOf(
            DoctorCheckSpec(
                name = "LLM Gateway",
                gate = ::usesGateway,
                probe = { axes ->
                    val raw =
                        if (reach()) {
                            CheckResult("LLM Gateway", CheckStatus.Pass, "gateway reachable")
                        } else {
                            CheckResult("LLM Gateway", CheckStatus.Fail, "gateway unreachable", hint = "check [llm].base_url")
                        }
                    AxisAwareDoctor.applyRequirement(AxisAwareDoctor.platformRequirement(axes), raw)
                },
            ),
            DoctorCheckSpec(
                name = "Gateway Model",
                gate = ::usesGateway,
                probe = { axes ->
                    val available = models()
                    val raw =
                        if (defaultModel.isBlank() || defaultModel in available) {
                            CheckResult("Gateway Model", CheckStatus.Pass, "model '$defaultModel' available")
                        } else {
                            CheckResult(
                                "Gateway Model",
                                CheckStatus.Fail,
                                "model '$defaultModel' not in gateway model list",
                                hint = "set [llm].default_model to one of: ${available.joinToString(", ")}",
                            )
                        }
                    AxisAwareDoctor.applyRequirement(AxisAwareDoctor.platformRequirement(axes), raw)
                },
            ),
        )
}
