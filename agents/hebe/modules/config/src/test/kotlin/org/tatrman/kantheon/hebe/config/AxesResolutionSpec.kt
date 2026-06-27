package org.tatrman.kantheon.hebe.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Axis resolution (P2 Stage 2.1; contracts §5). The §5.1 matrix is the oracle:
 * every profile resolves to its exact row, overrides follow the precedence
 * ladder (env axis > file axis > profile default; HEBE_PROFILE env > file
 * profile), and the cross-axis fail-fasts hold.
 */
class AxesResolutionSpec :
    StringSpec({

        @Suppress("LongParameterList")
        fun raw(
            profile: String? = null,
            envProfile: String? = null,
            overrides: Map<String, String> = emptyMap(),
            envAxes: Map<String, String> = emptyMap(),
            instanceId: String? = null,
            envInstanceId: String? = null,
            toolsEnable: List<String> = emptyList(),
            envToolsEnable: List<String>? = null,
            toolsDisable: List<String> = emptyList(),
            envToolsDisable: List<String>? = null,
            boundUser: String? = null,
            envBoundUser: String? = null,
        ) = RawAxisConfig(
            profile = profile,
            envProfile = envProfile,
            fileAxes = overrides,
            envAxes = envAxes,
            instanceId = instanceId,
            envInstanceId = envInstanceId,
            toolsEnable = toolsEnable,
            envToolsEnable = envToolsEnable,
            toolsDisable = toolsDisable,
            envToolsDisable = envToolsDisable,
            boundUser = boundUser,
            envBoundUser = envBoundUser,
        )

        // ── Per-profile defaults: the full §5.1 row ──────────────────────────────

        "local preset resolves the contracts §5.1 row" {
            val a = ProfileResolver.resolve(raw(profile = "local"))
            a.storage.backend shouldBe StorageBackend.SQLITE
            a.fs.durability shouldBe Durability.PERSISTENT
            a.workspace.backend shouldBe WorkspaceBackend.FILES
            a.receipts.backend shouldBe ReceiptsBackend.FILE
            a.platform.reach shouldBe PlatformReach.NONE
            a.platform.availability shouldBe null
            a.llm.source shouldBe LlmSource.BYOK
            a.security.platformIdentity shouldBe PlatformIdentity.NONE
            a.security.consoleAuth shouldBe ConsoleAuth.PASSWORD
            a.security.secretsBackend shouldBe SecretsBackend.KEYCHAIN
            a.otel.enabled shouldBe false
            a.capabilities.enabled shouldBe CapabilitiesEnabled.DISABLED
            a.tools.posture shouldBe Posture.FULL
            a.instanceId shouldBe "local"
        }

        "personal preset resolves the contracts §5.1 row" {
            val a = ProfileResolver.resolve(raw(profile = "personal", instanceId = "bora"))
            a.storage.backend shouldBe StorageBackend.SQLITE
            a.fs.durability shouldBe Durability.PERSISTENT
            a.platform.reach shouldBe PlatformReach.REMOTE
            a.platform.availability shouldBe Availability.INTERMITTENT
            a.llm.source shouldBe LlmSource.GATEWAY_WITH_BYOK_FALLBACK
            a.security.platformIdentity shouldBe PlatformIdentity.KEYCLOAK
            a.security.consoleAuth shouldBe ConsoleAuth.PASSWORD
            a.capabilities.enabled shouldBe CapabilitiesEnabled.OPTIONAL
            a.tools.posture shouldBe Posture.FULL
        }

        "server preset resolves the contracts §5.1 row" {
            val a = ProfileResolver.resolve(raw(profile = "server", instanceId = "ops"))
            a.storage.backend shouldBe StorageBackend.POSTGRES
            a.fs.durability shouldBe Durability.PERSISTENT
            a.workspace.backend shouldBe WorkspaceBackend.FILES
            a.receipts.backend shouldBe ReceiptsBackend.FILE
            a.platform.reach shouldBe PlatformReach.REMOTE
            a.platform.availability shouldBe Availability.ALWAYS
            a.llm.source shouldBe LlmSource.GATEWAY
            a.security.consoleAuth shouldBe ConsoleAuth.OIDC
            a.security.secretsBackend shouldBe SecretsBackend.FILE
            a.otel.enabled shouldBe true
            a.capabilities.enabled shouldBe CapabilitiesEnabled.ENABLED
            a.tools.posture shouldBe Posture.FULL
        }

        "k8s preset resolves the contracts §5.1 row" {
            val a = ProfileResolver.resolve(raw(profile = "k8s", instanceId = "dev"))
            a.storage.backend shouldBe StorageBackend.POSTGRES
            a.fs.durability shouldBe Durability.EPHEMERAL
            a.workspace.backend shouldBe WorkspaceBackend.POSTGRES
            a.receipts.backend shouldBe ReceiptsBackend.POSTGRES
            a.platform.reach shouldBe PlatformReach.IN_CLUSTER
            a.platform.availability shouldBe Availability.ALWAYS
            a.llm.source shouldBe LlmSource.GATEWAY
            a.security.platformIdentity shouldBe PlatformIdentity.KEYCLOAK
            a.security.consoleAuth shouldBe ConsoleAuth.OIDC
            a.security.secretsBackend shouldBe SecretsBackend.K8S
            a.otel.enabled shouldBe true
            a.capabilities.enabled shouldBe CapabilitiesEnabled.ENABLED
            a.tools.posture shouldBe Posture.RESTRICTED
        }

        // ── Override precedence: env axis > file axis > profile default ───────────

        "explicit file axis key overrides the profile default" {
            val a = ProfileResolver.resolve(raw(profile = "k8s", overrides = mapOf("tools.posture" to "full"), instanceId = "dev"))
            a.tools.posture shouldBe Posture.FULL
        }

        "env axis overrides the file axis" {
            val a =
                ProfileResolver.resolve(
                    raw(
                        profile = "local",
                        overrides = mapOf("tools.posture" to "full"),
                        envAxes = mapOf("tools.posture" to "restricted"),
                    ),
                )
            a.tools.posture shouldBe Posture.RESTRICTED
        }

        "HEBE_PROFILE env beats file profile" {
            val a = ProfileResolver.resolve(raw(profile = "local", envProfile = "server", instanceId = "ops"))
            a.platform.reach shouldBe PlatformReach.REMOTE
            a.storage.backend shouldBe StorageBackend.POSTGRES
        }

        "env instance_id beats file instance_id" {
            val a = ProfileResolver.resolve(raw(profile = "server", instanceId = "file-one", envInstanceId = "env-one"))
            a.instanceId shouldBe "env-one"
        }

        // ── Invalid input → fail fast ────────────────────────────────────────────

        "unknown profile fails fast" {
            shouldThrow<ConfigValidationException> {
                ProfileResolver.resolve(raw(profile = "mainframe"))
            }.message shouldContain "mainframe"
        }

        "unknown axis value fails fast" {
            shouldThrow<ConfigValidationException> {
                ProfileResolver.resolve(raw(profile = "local", overrides = mapOf("storage.backend" to "mysql")))
            }.message shouldContain "storage.backend"
        }

        // ── The load-bearing cross-axis fail-fasts ───────────────────────────────

        "ephemeral FS without postgres workspace fails fast" {
            shouldThrow<ConfigValidationException> {
                ProfileResolver.resolve(raw(profile = "k8s", overrides = mapOf("workspace.backend" to "files"), instanceId = "dev"))
            }.message shouldContain "ephemeral"
        }

        "ephemeral FS without postgres receipts fails fast" {
            shouldThrow<ConfigValidationException> {
                ProfileResolver.resolve(raw(profile = "k8s", overrides = mapOf("receipts.backend" to "file"), instanceId = "dev"))
            }.message shouldContain "ephemeral"
        }

        "postgres storage without instance_id fails fast" {
            shouldThrow<ConfigValidationException> {
                ProfileResolver.resolve(raw(profile = "server"))
            }.message shouldContain "instance_id"
        }

        "postgres storage with explicit instance_id resolves" {
            val a = ProfileResolver.resolve(raw(profile = "server", instanceId = "ops"))
            a.instanceId shouldBe "ops"
        }

        // ── tools.enable / tools.disable opt-in lists (P2 Stage 2.4) ──────────────

        "tools.enable / disable resolve from the file lists" {
            val a =
                ProfileResolver.resolve(
                    raw(
                        profile = "k8s",
                        instanceId = "dev",
                        toolsEnable = listOf("git"),
                        toolsDisable = listOf("shell"),
                    ),
                )
            a.tools.enable shouldBe setOf("git")
            a.tools.disable shouldBe setOf("shell")
        }

        "env tools.enable beats the file list" {
            val a =
                ProfileResolver.resolve(
                    raw(
                        profile = "k8s",
                        instanceId = "dev",
                        toolsEnable = listOf("git"),
                        envToolsEnable = listOf("kubectl", "shell"),
                    ),
                )
            a.tools.enable shouldBe setOf("kubectl", "shell")
        }

        "tools opt-in lists default empty" {
            val a = ProfileResolver.resolve(raw(profile = "local"))
            a.tools.enable shouldBe emptySet()
            a.tools.disable shouldBe emptySet()
        }

        // ── bound_user identity (P2 Stage 2.3) ───────────────────────────────────

        "bound_user resolves and env beats file" {
            ProfileResolver
                .resolve(raw(profile = "personal", instanceId = "x", boundUser = "bora"))
                .boundUser shouldBe "bora"
            ProfileResolver
                .resolve(raw(profile = "personal", instanceId = "x", boundUser = "file", envBoundUser = "env"))
                .boundUser shouldBe "env"
        }

        "bound_user is null on an identity-less profile" {
            ProfileResolver.resolve(raw(profile = "local")).boundUser shouldBe null
        }

        // ── platform-reach invariants (P2 Stage 2.1 validation) ──────────────────

        "platform reach without availability fails fast" {
            shouldThrow<ConfigValidationException> {
                ProfileResolver.resolve(
                    raw(
                        profile = "local",
                        overrides =
                            mapOf(
                                "platform.reach" to "remote",
                                "security.platform_identity" to "keycloak",
                            ),
                    ),
                )
            }.message shouldContain "availability"
        }

        "platform reach without identity fails fast" {
            shouldThrow<ConfigValidationException> {
                ProfileResolver.resolve(
                    raw(
                        profile = "personal",
                        instanceId = "x",
                        overrides = mapOf("security.platform_identity" to "none"),
                    ),
                )
            }.message shouldContain "platform_identity"
        }
    })
