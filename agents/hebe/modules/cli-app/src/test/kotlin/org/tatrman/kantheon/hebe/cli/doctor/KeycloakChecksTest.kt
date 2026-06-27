package org.tatrman.kantheon.hebe.cli.doctor

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.tatrman.kantheon.hebe.config.ProfileResolver
import org.tatrman.kantheon.hebe.config.RawAxisConfig

/** Keycloak doctor checks (P2 Stage 2.3 T6) — offline, stubbed probes. */
class KeycloakChecksTest {
    private fun axes(
        profile: String,
        instanceId: String? = null,
    ) = ProfileResolver.resolve(RawAxisConfig(profile = profile, instanceId = instanceId))

    private fun specs(unresolved: List<String> = emptyList()) =
        KeycloakChecks.specs(reachable = { false }, canMint = { false }, unresolvedSecretRefs = { unresolved })

    @Test
    fun `keycloak checks gated out on local, but Secrets always applies`() {
        // Keycloak + OBO gated out; the Secrets check (gate {true}) still applies.
        AxisAwareDoctor.planChecks(axes("local"), specs()).map { it.name } shouldBe listOf("Secrets")
    }

    @Test
    fun `all three checks apply under server`() {
        AxisAwareDoctor.planChecks(axes("server", "ops"), specs()).size shouldBe 3
    }

    @Test
    fun `unreachable keycloak is FAIL on server, degraded WARN on personal`() =
        runBlocking {
            val onServer = AxisAwareDoctor.planChecks(axes("server", "ops"), specs()).first { it.name == "Keycloak" }
            onServer.probe(axes("server", "ops")).status shouldBe CheckStatus.Fail
            val onPersonal = AxisAwareDoctor.planChecks(axes("personal", "bora"), specs()).first { it.name == "Keycloak" }
            onPersonal.probe(axes("personal", "bora")).status shouldBe CheckStatus.Warn
        }

    @Test
    fun `unresolved secret refs fail the Secrets check`() =
        runBlocking {
            val spec = AxisAwareDoctor.planChecks(axes("local"), specs(listOf("keychain:llm"))).first { it.name == "Secrets" }
            spec.probe(axes("local")).status shouldBe CheckStatus.Fail
        }
}
