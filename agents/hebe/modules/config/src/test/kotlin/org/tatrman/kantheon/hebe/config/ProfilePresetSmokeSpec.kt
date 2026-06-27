package org.tatrman.kantheon.hebe.config

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Preset smoke tests (P2 Stage 2.1 T6) — one cheap assertion per profile that
 * the preset wires the *distinguishing* axis values. This is the "test the
 * axes, not the profiles" mitigation from the plan's risks note: it keeps CI
 * from fanning the suite ×4 across profiles while still catching a preset that
 * silently drifts from the §5.1 matrix.
 */
class ProfilePresetSmokeSpec :
    StringSpec({

        "local = self-contained BYOK, no platform" {
            val a = ProfileResolver.resolve(RawAxisConfig(profile = "local"))
            a.platform.reach shouldBe PlatformReach.NONE
            a.llm.source shouldBe LlmSource.BYOK
            a.security.platformIdentity shouldBe PlatformIdentity.NONE
            a.otel.enabled shouldBe false
        }

        "personal = intermittent platform with byok fallback" {
            val a = ProfileResolver.resolve(RawAxisConfig(profile = "personal", instanceId = "bora"))
            a.platform.availability shouldBe Availability.INTERMITTENT
            a.llm.source shouldBe LlmSource.GATEWAY_WITH_BYOK_FALLBACK
            a.capabilities.enabled shouldBe CapabilitiesEnabled.OPTIONAL
        }

        "server = always-on external PG, OIDC console" {
            val a = ProfileResolver.resolve(RawAxisConfig(profile = "server", instanceId = "ops"))
            a.storage.backend shouldBe StorageBackend.POSTGRES
            a.fs.durability shouldBe Durability.PERSISTENT
            a.platform.availability shouldBe Availability.ALWAYS
            a.security.consoleAuth shouldBe ConsoleAuth.OIDC
        }

        "k8s = ephemeral FS forces PG everywhere, restricted tools" {
            val a = ProfileResolver.resolve(RawAxisConfig(profile = "k8s", instanceId = "dev"))
            a.fs.durability shouldBe Durability.EPHEMERAL
            a.workspace.backend shouldBe WorkspaceBackend.POSTGRES
            a.receipts.backend shouldBe ReceiptsBackend.POSTGRES
            a.tools.posture shouldBe Posture.RESTRICTED
            a.security.secretsBackend shouldBe SecretsBackend.K8S
        }
    })
