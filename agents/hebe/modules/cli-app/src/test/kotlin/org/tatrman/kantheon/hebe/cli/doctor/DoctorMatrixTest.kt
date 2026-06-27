package org.tatrman.kantheon.hebe.cli.doctor

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.tatrman.kantheon.hebe.config.ProfileResolver
import org.tatrman.kantheon.hebe.config.RawAxisConfig

/**
 * The axis-aware doctor skeleton matrix (P2 Stage 2.1 T5; contracts §5.3). No
 * live calls — the required-vs-probed split and the FAIL→degraded demotion are
 * pure functions of the resolved axes, asserted with a stubbed probe result.
 */
class DoctorMatrixTest {
    private fun axes(
        profile: String,
        instanceId: String? = null,
    ) = ProfileResolver.resolve(RawAxisConfig(profile = profile, instanceId = instanceId))

    @Test
    fun `local has no platform deps - lenient (probed)`() {
        AxisAwareDoctor.platformRequirement(axes("local")) shouldBe RequirementLevel.PROBED
    }

    @Test
    fun `personal is intermittent - platform deps probed`() {
        AxisAwareDoctor.platformRequirement(axes("personal", "bora")) shouldBe RequirementLevel.PROBED
    }

    @Test
    fun `server is always-on - platform deps required`() {
        AxisAwareDoctor.platformRequirement(axes("server", "ops")) shouldBe RequirementLevel.REQUIRED
    }

    @Test
    fun `k8s is always-on - platform deps required`() {
        AxisAwareDoctor.platformRequirement(axes("k8s", "dev")) shouldBe RequirementLevel.REQUIRED
    }

    @Test
    fun `probed failure is demoted to a warn (degraded), not a fail`() {
        val raw = CheckResult("Gateway", CheckStatus.Fail, "connection refused")
        val applied = AxisAwareDoctor.applyRequirement(RequirementLevel.PROBED, raw)
        applied.status shouldBe CheckStatus.Warn
        (applied.message.contains("degraded")) shouldBe true
        (applied.message.contains("connection refused")) shouldBe true
    }

    @Test
    fun `required failure stays a fail`() {
        val raw = CheckResult("Gateway", CheckStatus.Fail, "connection refused")
        val applied = AxisAwareDoctor.applyRequirement(RequirementLevel.REQUIRED, raw)
        applied.status shouldBe CheckStatus.Fail
    }

    @Test
    fun `passing probe is unchanged regardless of level`() {
        val raw = CheckResult("Gateway", CheckStatus.Pass, "reachable")
        AxisAwareDoctor.applyRequirement(RequirementLevel.PROBED, raw) shouldBe raw
        AxisAwareDoctor.applyRequirement(RequirementLevel.REQUIRED, raw) shouldBe raw
    }

    @Test
    fun `offline personal does not fail doctor - gateway-bound LLM endpoint is demoted`() {
        val base =
            listOf(
                CheckResult("LLM Endpoint", CheckStatus.Fail, "Cannot reach LLM endpoint"),
                CheckResult("Database", CheckStatus.Fail, "hebe.db not found"),
            )
        val out = AxisAwareDoctor.demoteBaseChecks(axes("personal", "bora"), base)
        // The platform-reaching endpoint is degraded to WARN…
        out.first { it.name == "LLM Endpoint" }.status shouldBe CheckStatus.Warn
        // …but a genuine local failure (the DB) is untouched.
        out.first { it.name == "Database" }.status shouldBe CheckStatus.Fail
    }

    @Test
    fun `always-on server keeps the LLM endpoint failure a fail`() {
        val base = listOf(CheckResult("LLM Endpoint", CheckStatus.Fail, "Cannot reach LLM endpoint"))
        AxisAwareDoctor.demoteBaseChecks(axes("server", "ops"), base).first().status shouldBe CheckStatus.Fail
    }

    @Test
    fun `local byok LLM endpoint failure is not demoted (not a platform dep)`() {
        val base = listOf(CheckResult("LLM Endpoint", CheckStatus.Fail, "Cannot reach LLM endpoint"))
        AxisAwareDoctor.demoteBaseChecks(axes("local"), base).first().status shouldBe CheckStatus.Fail
    }

    @Test
    fun `planChecks filters the registry by the axis gate`() {
        val onlyServer =
            DoctorCheckSpec(
                name = "gateway",
                gate = { it.platform.reach != org.tatrman.kantheon.hebe.config.PlatformReach.NONE },
                probe = { CheckResult("gateway", CheckStatus.Pass, "ok") },
            )
        AxisAwareDoctor.planChecks(axes("local"), listOf(onlyServer)).size shouldBe 0
        AxisAwareDoctor.planChecks(axes("server", "ops"), listOf(onlyServer)).size shouldBe 1
    }
}
