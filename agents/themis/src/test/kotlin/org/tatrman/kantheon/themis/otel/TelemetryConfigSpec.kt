package org.tatrman.kantheon.themis.otel

import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * `ResolverOtel.enabled` reads `telemetry.enabled` out of `ConfigFactory.load()`, and
 * HOCON only sees an environment variable if a config line maps it. The block was
 * missing entirely, so the path did not exist, `enabled` was permanently false, and
 * Themis emitted no spans however the deployment was configured — its resolve step,
 * the slowest hop of a turn, was absent from every trace.
 *
 * This pins the wiring itself rather than the value: the point is that the path
 * EXISTS and follows the env var the chart sets.
 */
class TelemetryConfigSpec :
    StringSpec({
        "telemetry.enabled exists and defaults to off" {
            val c = ConfigFactory.load()
            c.hasPath("telemetry.enabled") shouldBe true
            c.getBoolean("telemetry.enabled") shouldBe false
        }

        "telemetry.enabled is mapped from TELEMETRY_ENABLED — the var the chart sets" {
            // Checked as text, deliberately. The defect was a MISSING LINE: HOCON only
            // sees an environment variable if the config file maps it, and no in-process
            // assertion can set a real env var to prove the mapping end to end. What can
            // be pinned is that the shipped resource still carries the substitution — if
            // someone deletes it again, Themis goes silently dark in every deployment.
            val conf =
                requireNotNull(javaClass.classLoader.getResourceAsStream("application.conf"))
                    .bufferedReader()
                    .use { it.readText() }

            conf.contains("telemetry") shouldBe true
            conf.contains("\${?TELEMETRY_ENABLED}") shouldBe true
        }
    })
