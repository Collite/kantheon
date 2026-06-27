package org.tatrman.kantheon.hebe.cli.doctor

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.tatrman.kantheon.hebe.config.ProfileResolver
import org.tatrman.kantheon.hebe.config.RawAxisConfig

/**
 * Gateway doctor checks (P2 Stage 2.2 T5) — offline, with stubbed probes. The
 * gate (gateway vs BYOK) and the required-vs-probed demotion are pure functions
 * of the resolved axes.
 */
class GatewayChecksTest {
    private fun axes(
        profile: String,
        instanceId: String? = null,
    ) = ProfileResolver.resolve(RawAxisConfig(profile = profile, instanceId = instanceId))

    private val specs =
        GatewayChecks.specs(
            defaultModel = "gw-model",
            reach = { false }, // gateway unreachable
            models = { listOf("other-model") },
        )

    @Test
    fun `gateway checks are gated out under local (BYOK)`() {
        AxisAwareDoctor.planChecks(axes("local"), specs).size shouldBe 0
    }

    @Test
    fun `gateway checks apply under server (gateway)`() {
        AxisAwareDoctor.planChecks(axes("server", "ops"), specs).size shouldBe 2
    }

    @Test
    fun `unreachable gateway is a FAIL under always-on (server)`() =
        runBlocking {
            val reachSpec = AxisAwareDoctor.planChecks(axes("server", "ops"), specs).first { it.name == "LLM Gateway" }
            reachSpec.probe(axes("server", "ops")).status shouldBe CheckStatus.Fail
        }

    @Test
    fun `unreachable gateway is a degraded WARN under intermittent (personal)`() =
        runBlocking {
            val reachSpec = AxisAwareDoctor.planChecks(axes("personal", "bora"), specs).first { it.name == "LLM Gateway" }
            val r = reachSpec.probe(axes("personal", "bora"))
            r.status shouldBe CheckStatus.Warn
            (r.message.contains("degraded")) shouldBe true
        }

    @Test
    fun `missing default model fails under server`() =
        runBlocking {
            val modelSpec = AxisAwareDoctor.planChecks(axes("server", "ops"), specs).first { it.name == "Gateway Model" }
            modelSpec.probe(axes("server", "ops")).status shouldBe CheckStatus.Fail
        }
}
