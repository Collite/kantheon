package org.tatrman.kantheon.hebe.cli.doctor

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.tatrman.kantheon.hebe.config.ProfileResolver
import org.tatrman.kantheon.hebe.config.RawAxisConfig

/** Connectivity/breaker doctor reflection (P2 Stage 2.5 T4) — offline, stubbed. */
class ConnectivityChecksTest {
    private fun axes(
        profile: String,
        instanceId: String? = null,
    ) = ProfileResolver.resolve(RawAxisConfig(profile = profile, instanceId = instanceId))

    @Test
    fun `gated only on intermittent profiles`() {
        val specs = ConnectivityChecks.specs { true }
        AxisAwareDoctor.planChecks(axes("server", "ops"), specs).size shouldBe 0
        AxisAwareDoctor.planChecks(axes("personal", "bora"), specs).size shouldBe 1
    }

    @Test
    fun `open breaker is degraded (WARN), not a FAIL`() =
        runBlocking {
            val spec = ConnectivityChecks.specs { true }.single()
            spec.probe(axes("personal", "bora")).status shouldBe CheckStatus.Warn
        }

    @Test
    fun `closed breaker passes`() =
        runBlocking {
            val spec = ConnectivityChecks.specs { false }.single()
            spec.probe(axes("personal", "bora")).status shouldBe CheckStatus.Pass
        }
}
