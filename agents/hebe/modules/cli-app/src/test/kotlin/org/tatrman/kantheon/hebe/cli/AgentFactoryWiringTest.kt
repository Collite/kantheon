package org.tatrman.kantheon.hebe.cli

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.tatrman.kantheon.hebe.config.Axes
import org.tatrman.kantheon.hebe.config.ConfigValidationException
import org.tatrman.kantheon.hebe.config.ProfileResolver
import org.tatrman.kantheon.hebe.config.RawAxisConfig
import org.tatrman.kantheon.hebe.memory.MemoryStoreFactory
import org.tatrman.kantheon.hebe.tools.dispatch.PostureDecision

/**
 * Wiring tests for the axis → subsystem seams that [AgentFactory] (and the
 * MCP-serve path) build from the resolved [Axes]. These assert the *wired*
 * behaviour the per-stage specs only proved in isolation (P2 review follow-up):
 * the posture gate actually carries the axis posture + opt-in lists, and the
 * channel-identity predicate enforces / validates as the profile demands.
 */
class AgentFactoryWiringTest {
    private fun axes(
        profile: String,
        instanceId: String? = null,
        enable: List<String> = emptyList(),
        disable: List<String> = emptyList(),
        boundUser: String? = null,
    ): Axes =
        ProfileResolver.resolve(
            RawAxisConfig(
                profile = profile,
                instanceId = instanceId,
                toolsEnable = enable,
                toolsDisable = disable,
                boundUser = boundUser,
            ),
        )

    @Test
    fun `postureGate carries restricted posture - dangerous family denied`() {
        val gate = AgentFactory.postureGate(axes("k8s", "dev"))
        gate.decide("shell").shouldBeInstanceOf<PostureDecision.Deny>()
    }

    @Test
    fun `postureGate threads the enable opt-in list from axes`() {
        val gate = AgentFactory.postureGate(axes("k8s", "dev", enable = listOf("git")))
        gate.decide("git_status") shouldBe PostureDecision.Allow
        gate.decide("shell").shouldBeInstanceOf<PostureDecision.Deny>()
    }

    @Test
    fun `postureGate threads the disable opt-in list even under full posture`() {
        val gate = AgentFactory.postureGate(axes("local", disable = listOf("http")))
        gate.decide("http_get").shouldBeInstanceOf<PostureDecision.Deny>()
    }

    @Test
    fun `memoryBackend maps storage axis - sqlite profiles to SQLITE`() {
        AgentFactory.memoryBackend(axes("local")) shouldBe MemoryStoreFactory.Backend.SQLITE
        AgentFactory.memoryBackend(axes("personal", "x", boundUser = "bora")) shouldBe MemoryStoreFactory.Backend.SQLITE
    }

    @Test
    fun `memoryBackend maps storage axis - postgres profiles to POSTGRES`() {
        AgentFactory.memoryBackend(axes("server", "ops")) shouldBe MemoryStoreFactory.Backend.POSTGRES
        AgentFactory.memoryBackend(axes("k8s", "dev")) shouldBe MemoryStoreFactory.Backend.POSTGRES
    }

    @Test
    fun `channelIdentityCheck is null on an identity-less profile`() {
        AgentFactory.channelIdentityCheck(axes("local"), emptyMap()).shouldBeNull()
    }

    @Test
    fun `channelIdentityCheck admits a mapped chat and rejects an unmapped one on keycloak`() {
        val check =
            AgentFactory.channelIdentityCheck(
                axes("personal", "x", boundUser = "bora"),
                mapOf("111" to "bora", "222" to "ada"),
            )!!
        check("111") shouldBe true
        check("999") shouldBe false
    }

    @Test
    fun `channelIdentityCheck fails fast when chat_user_map omits the bound user`() {
        shouldThrow<ConfigValidationException> {
            AgentFactory.channelIdentityCheck(axes("personal", "x", boundUser = "bora"), mapOf("111" to "someone-else"))
        }
    }

    @Test
    fun `channelIdentityCheck fails fast when keycloak profile has no bound_user`() {
        shouldThrow<ConfigValidationException> {
            AgentFactory.channelIdentityCheck(axes("personal", "x"), mapOf("111" to "bora"))
        }
    }
}
