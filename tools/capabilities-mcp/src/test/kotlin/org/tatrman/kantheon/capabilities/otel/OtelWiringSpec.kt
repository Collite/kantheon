package org.tatrman.kantheon.capabilities.otel

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * PT Phase 0 · Stage 0.1 T3 — this module's Logback config must declare the OTel
 * appender and wire it to root.
 *
 * Why this is worth a test: Alloy does **no pod-log scraping**. A service's lines
 * reach Loki only if it exports OTLP log records, which only happens if
 * `logback.xml` declares `OpenTelemetryAppender` — `createOpenTelemetrySdk()`
 * installs the SDK *into* an appender that must already exist. Delete the appender
 * and the service goes silent in Loki with nothing failing at boot. That is exactly
 * the state capabilities-mcp was found in.
 */
class OtelWiringSpec :
    StringSpec({

        "logback appender is installed when telemetry enabled" {
            val logback =
                requireNotNull(this::class.java.classLoader.getResourceAsStream("logback.xml")) {
                    "capabilities-mcp has no logback.xml on the classpath — OTLP log export cannot work"
                }.bufferedReader().readText()

            logback shouldContain "io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender"
            // Declaring the appender is not enough; root has to reference it.
            logback.contains(Regex("""<appender-ref\s+ref="OTEL"\s*/>""")) shouldBe true
        }
    })
