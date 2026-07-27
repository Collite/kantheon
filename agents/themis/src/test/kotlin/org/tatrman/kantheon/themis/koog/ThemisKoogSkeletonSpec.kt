package org.tatrman.kantheon.themis.koog

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import org.tatrman.kantheon.themis.koog.nodes.IntentKindRules
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.mockk
import org.tatrman.llm.client.LlmGatewayClient
import org.tatrman.llm.client.LlmGatewayPromptExecutor
import org.tatrman.llm.client.LlmGatewayPromptExecutor.Companion.mapModelToGatewayKey

/**
 * Stage 2.3 T2 smoke spec — proves the skeleton compiles and the executor
 * wrapper round-trips a stubbed gateway response. No node bodies exist yet;
 * graph execution is gated on T3–T5 (verified by NotImplementedError on the
 * placeholder).
 */
class ThemisKoogSkeletonSpec :
    StringSpec({

        // The executor emits GENERIC tier keys, not provider-flavoured ones. Provider names
        // (`haiku`/`sonnet`/`opus`) are also catalog aliases, but they pin a specific provider
        // and so only resolve on a cluster holding that provider's credentials; the generic
        // tiers are what a deployment remaps freely. See ThemisTiers.
        "model mapping — Anthropic ids map to generic gateway tier keys" {
            mapModelToGatewayKey(LLModel(LLMProvider.Anthropic, "claude-haiku-3-5")) shouldBe "mini"
            mapModelToGatewayKey(LLModel(LLMProvider.Anthropic, "claude-sonnet-4")) shouldBe "fast"
            mapModelToGatewayKey(LLModel(LLMProvider.Anthropic, "claude-opus-4")) shouldBe "deep"
            // Unknown ids fall through to CHEAP — see the executor's KDoc.
            mapModelToGatewayKey(LLModel(LLMProvider.OpenAI, "gpt-4o-mini")) shouldBe "mini"
        }

        // The graph nodes call LlmGatewayClient.complete directly rather than through the
        // executor; this pins the two paths to the same vocabulary so they cannot drift apart
        // again (they did: the nodes asked for Anthropic aliases the executor never emits).
        "graph-node tiers agree with what the executor emits" {
            ThemisTiers.CHEAP shouldBe mapModelToGatewayKey(LLModel(LLMProvider.Anthropic, "claude-haiku-3-5"))
            ThemisTiers.FAST shouldBe mapModelToGatewayKey(LLModel(LLMProvider.Anthropic, "claude-sonnet-4"))
        }

        "executor — System + User content flow into LlmGatewayClient.complete, response surfaces as Message.Assistant" {
            val gateway = mockk<LlmGatewayClient>()
            coEvery {
                gateway.complete(
                    prompt = "find invoices for ACME",
                    systemPrompt = "You are a Czech ERP question parser.",
                    model = ThemisTiers.CHEAP,
                    temperature = 0.0,
                )
            } returns Result.success("""{"spans":[]}""")

            val executor = LlmGatewayPromptExecutor(gateway)
            val testPrompt =
                prompt("test") {
                    system("You are a Czech ERP question parser.")
                    user("find invoices for ACME")
                }

            val responses =
                executor.execute(
                    prompt = testPrompt,
                    model = ThemisModels.Cheap,
                    tools = emptyList(),
                )

            responses.size shouldBe 1
            val assistant = responses[0] as Message.Assistant
            assistant.content shouldBe """{"spans":[]}"""
        }

        "executor — temperature carries through from prompt.params" {
            val gateway = mockk<LlmGatewayClient>()
            coEvery {
                gateway.complete(prompt = any(), systemPrompt = any(), model = ThemisTiers.FAST, temperature = 0.7)
            } returns Result.success("ok")

            val executor = LlmGatewayPromptExecutor(gateway)
            val warmPrompt =
                prompt(
                    "test",
                    params =
                        ai.koog.prompt.params
                            .LLMParams(temperature = 0.7),
                ) {
                    user("ping")
                }

            executor
                .execute(warmPrompt, ThemisModels.Fast, emptyList())
                .first()
                .let { (it as Message.Assistant).content shouldBe "ok" }
        }

        "executor — gateway failure propagates as exception (Result.failure unwrapped via getOrThrow)" {
            val gateway = mockk<LlmGatewayClient>()
            coEvery {
                gateway.complete(any(), any(), any(), any(), any())
            } returns Result.failure(RuntimeException("gateway down"))

            val executor = LlmGatewayPromptExecutor(gateway)
            val err =
                shouldThrow<RuntimeException> {
                    executor.execute(prompt("test") { user("ping") }, ThemisModels.Cheap, emptyList())
                }
            err.message shouldContain "gateway down"
        }

        "graph skeleton — `buildThemisGraph(...)` exposes its declared name" {
            val graph =
                buildThemisGraph(
                    llm = mockk(),
                    fuzzy = mockk(),
                    tokenManager = mockk(),
                    config = mockk(),
                    intentRules = IntentKindRules.load(),
                    capabilities = mockk(),
                )
            graph.name shouldBe "themis"
        }

        "agent config — themisAgentConfig produces a non-empty config with the requested model" {
            val cfg = themisAgentConfig(model = ThemisModels.Cheap)
            cfg.model shouldBe ThemisModels.Cheap
            cfg.maxAgentIterations shouldBe 50
        }
    })
