package org.tatrman.kantheon.hebe.cli.doctor

import org.tatrman.kantheon.hebe.config.Axes

/**
 * OTel doctor check (P2 Stage 2.4 T6), registered into the [AxisAwareDoctor]
 * matrix. Gated on `otel.enabled`; when on, the OTLP endpoint must be reachable
 * (required-vs-probed per `availability`). Probe injected for the offline test.
 */
object OtelChecks {
    fun otelEnabled(axes: Axes): Boolean = axes.otel.enabled

    fun specs(endpointReachable: suspend () -> Boolean): List<DoctorCheckSpec> =
        listOf(
            DoctorCheckSpec("OTel", ::otelEnabled) { axes ->
                val raw =
                    if (endpointReachable()) {
                        CheckResult("OTel", CheckStatus.Pass, "OTLP endpoint reachable")
                    } else {
                        CheckResult("OTel", CheckStatus.Fail, "OTLP endpoint unreachable", hint = "check [otel].otlp_endpoint")
                    }
                AxisAwareDoctor.applyRequirement(AxisAwareDoctor.platformRequirement(axes), raw)
            },
        )
}
