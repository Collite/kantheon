package org.tatrman.kantheon.hebe.tools.mcp

import org.tatrman.kantheon.hebe.api.SecretLookup
import org.tatrman.kantheon.hebe.config.McpClientServerConfig
import org.tatrman.kantheon.hebe.tools.dispatch.ToolRegistry
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class McpClientManagerTest :
    StringSpec({

        "SENSITIVE_ENV_KEYS uses substring matching for exclusion" {
            val manager =
                McpClientManager(
                    registry = ToolRegistry(),
                    secretLookup =
                        object : SecretLookup {
                            override fun secret(name: String): String? = "test-secret"
                        },
                )

            val serverConfig =
                McpClientServerConfig(
                    name = "test_server",
                    transport = "stdio",
                    command = listOf("echo", "hello"),
                    envSecrets = mapOf("CUSTOM_KEY" to "my-secret"),
                )

            val env = manager.buildEnvWithSecrets(serverConfig.envSecrets)

            env["CUSTOM_KEY"] shouldBe "test-secret"

            val sensitivePatterns = listOf("API_KEY", "TOKEN", "SECRET", "PASSWORD", "CREDENTIAL", "AUTH")
            val leakingKeys = env.keys.filter { key -> sensitivePatterns.any { key.contains(it) } }
            leakingKeys shouldBe emptyList()
        }

        "missing secret throws IllegalStateException" {
            val manager =
                McpClientManager(
                    registry = ToolRegistry(),
                    secretLookup =
                        object : SecretLookup {
                            override fun secret(name: String): String? = null
                        },
                )

            val serverConfig =
                McpClientServerConfig(
                    name = "test_server",
                    transport = "stdio",
                    command = listOf("echo", "hello"),
                    envSecrets = mapOf("MISSING_SECRET" to "nonexistent-secret"),
                )

            shouldThrow<IllegalStateException> {
                manager.buildEnvWithSecrets(serverConfig.envSecrets)
            }
        }

        "environment with sensitive keys does not leak them" {
            val manager =
                McpClientManager(
                    registry = ToolRegistry(),
                    secretLookup =
                        object : SecretLookup {
                            override fun secret(name: String): String? = "secret-value"
                        },
                )

            val fakeEnv =
                mapOf(
                    "OPENAI_API_KEY" to "sk-secret",
                    "GITHUB_TOKEN" to "ghp_secret",
                    "PATH" to "/usr/bin",
                    "HOME" to "/home/user",
                )

            val result = manager.buildEnvWithSecrets(emptyMap(), systemEnv = fakeEnv)

            result.containsKey("OPENAI_API_KEY") shouldBe false
            result.containsKey("GITHUB_TOKEN") shouldBe false
            result.containsKey("PATH") shouldBe true
            result.containsKey("HOME") shouldBe true
        }
    })
