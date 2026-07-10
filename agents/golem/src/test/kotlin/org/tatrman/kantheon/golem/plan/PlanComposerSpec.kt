package org.tatrman.kantheon.golem.plan

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.tatrman.kantheon.golem.prompts.PromptStore
import org.tatrman.kantheon.golem.v1.GolemContext
import org.tatrman.kantheon.golem.v1.GolemRequest
import org.tatrman.kantheon.golem.v1.PlanSource
import org.tatrman.llm.client.LlmGatewayClient
import org.tatrman.llm.client.LlmGatewayPromptExecutor
import org.tatrman.kantheon.themis.v1.Themis.Resolution
import java.nio.file.Path

private const val PLAN_PROMPT = "system: \"Plánuj.\"\nuser: |\n  Otázka: {{ question }}\n  Vzory: {{ patterns }}"

private fun request(question: String): GolemRequest =
    GolemRequest
        .newBuilder()
        .setId("t1")
        .setGolemId("golem-erp")
        .setQuestion(question)
        .setResolvedIntent(Resolution.newBuilder().setFunctionId("acct.balance").setArgsJson("""{"x":1}"""))
        .setContext(GolemContext.newBuilder().setLocale("cs"))
        .build()

/** PromptStore loaded with the inline plan prompt (no mount → bundled fallback). */
private fun loadedStore(): PromptStore {
    val store =
        PromptStore(
            shemDir = Path.of("/nonexistent-golem-shem"),
            locale = "cs",
            fallback = { mapOf("intent" to PLAN_PROMPT) },
        )
    store.refresh()
    return store
}

private suspend fun composeWith(replyJson: String): Pair<PlanSource, org.tatrman.kantheon.golem.v1.MiniPlan> {
    val gateway = mockk<LlmGatewayClient>()
    coEvery { gateway.complete(any(), any(), any(), any(), any()) } returns Result.success(replyJson)
    val composer = PlanComposer(LlmGatewayPromptExecutor(gateway), loadedStore())
    val plan = composer.compose(request("kolik mám tržeb?"), model = null)
    return plan.source to plan
}

class PlanComposerSpec :
    StringSpec({

        "PATTERN source composes a pattern plan" {
            runTest {
                val json =
                    """{"source":"PATTERN","confidence":0.95,"nodes":[
                       {"node_id":"q1","query":{"source":"p","source_language":"transdsl","params_json":"{}","pattern_id":"p"}}]}"""
                composeWith(json).first shouldBe PlanSource.PATTERN
            }
        }

        "FREE_SQL source composes a free-sql plan" {
            runTest {
                val json =
                    """{"source":"FREE_SQL","confidence":0.7,"nodes":[
                       {"node_id":"q1","query":{"source":"SELECT 1","source_language":"sql","params_json":"{}","compile_first":true}}]}"""
                val (source, plan) = composeWith(json)
                source shouldBe PlanSource.FREE_SQL
                plan.getNodes(0).query.compileFirst shouldBe true
            }
        }

        "AMEND source composes" {
            runTest {
                composeWith(
                    """{"source":"AMEND","confidence":0.9,"nodes":[]}""",
                ).first shouldBe PlanSource.AMEND
            }
        }

        "DRILL source composes" {
            runTest {
                composeWith(
                    """{"source":"DRILL","confidence":0.9,"nodes":[]}""",
                ).first shouldBe PlanSource.DRILL
            }
        }

        "CLARIFICATION source composes" {
            runTest {
                composeWith("""{"source":"CLARIFICATION","confidence":0.4,"nodes":[]}""").first shouldBe
                    PlanSource.CLARIFICATION
            }
        }

        "the user prompt is substituted with the question before the LLM call" {
            runTest {
                val gateway = mockk<LlmGatewayClient>()
                val userSlot = slot<String>()
                coEvery {
                    gateway.complete(capture(userSlot), any(), any(), any(), any())
                } returns Result.success("""{"source":"PATTERN","confidence":0.9,"nodes":[]}""")
                val composer = PlanComposer(LlmGatewayPromptExecutor(gateway), loadedStore())

                composer.compose(request("kolik mám tržeb?"), model = null)
                userSlot.captured shouldContain "kolik mám tržeb?"
            }
        }

        "an undecodable reply raises PlanDecodeException" {
            runTest {
                shouldThrow<PlanDecodeException> { composeWith("nope, not json") }
            }
        }
    })
