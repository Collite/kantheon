package org.tatrman.kantheon.themis.koog.nodes

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.tatrman.kantheon.llm.client.LlmGatewayClient
import org.tatrman.kantheon.themis.client.NlpToken
import org.tatrman.kantheon.themis.v1.Themis

/**
 * Phase 3 Stage 3.2 — `classifyIntentKind` rule layer + LLM tie-break.
 * Works on the in-graph [NlpToken] list (lemmas + UD feats), not the proto.
 */
class ClassifyIntentKindSpec :
    StringSpec({

        val rules = IntentKindRules.load()

        "Czech 'proč' triggers RCA via the rules layer; LLM not invoked" {
            val llm = mockk<LlmGatewayClient>()
            val out =
                runBlocking {
                    classifyIntent(tokens("proč", "klesnout", "tržba"), "cs", rules, llm)
                }
            out.intentKind shouldBe Themis.IntentKind.RCA
            out.source shouldBe IntentSource.RULE
            coVerify(exactly = 0) { llm.complete(any(), any(), any(), any(), any()) }
        }

        "English 'why' triggers RCA" {
            val out =
                runBlocking {
                    classifyIntent(tokens("why", "do", "revenue", "drop"), "en", rules, mockk())
                }
            out.intentKind shouldBe Themis.IntentKind.RCA
        }

        "Czech 'predikce' triggers FORECAST" {
            val out = runBlocking { classifyIntent(tokens("predikce", "tržba"), "cs", rules, mockk()) }
            out.intentKind shouldBe Themis.IntentKind.FORECAST
        }

        "English 'forecast' triggers FORECAST" {
            val out = runBlocking { classifyIntent(tokens("forecast", "revenue"), "en", rules, mockk()) }
            out.intentKind shouldBe Themis.IntentKind.FORECAST
        }

        "Czech 'co kdyby' triggers SIMULATION" {
            val out =
                runBlocking { classifyIntent(tokens("co", "kdyby", "cena", "klesnout"), "cs", rules, mockk()) }
            out.intentKind shouldBe Themis.IntentKind.SIMULATION
        }

        "English 'what if' triggers SIMULATION" {
            val out =
                runBlocking { classifyIntent(tokens("what", "if", "price", "drop"), "en", rules, mockk()) }
            out.intentKind shouldBe Themis.IntentKind.SIMULATION
        }

        "no trigger words → PROCEDURAL default" {
            val out =
                runBlocking {
                    classifyIntent(tokens("který", "faktura", "shell", "neuhradit"), "cs", rules, mockk())
                }
            out.intentKind shouldBe Themis.IntentKind.PROCEDURAL
            out.source shouldBe IntentSource.RULE_DEFAULT
        }

        "triggers operate on lemmas, not raw surface form" {
            // 'Predikuji' (1st person sg) lemmatises to 'predikovat' — the lemma in the YAML.
            val out =
                runBlocking {
                    classifyIntent(
                        listOf(
                            token("Predikuji", lemma = "predikovat", upos = "VERB"),
                            token("další", lemma = "další"),
                            token("pokles", lemma = "pokles"),
                        ),
                        "cs",
                        rules,
                        mockk(),
                    )
                }
            out.intentKind shouldBe Themis.IntentKind.FORECAST
        }

        "ambiguous (two triggers tied) → cheap-LLM fallback" {
            val llm = mockk<LlmGatewayClient>()
            coEvery { llm.complete(any(), any(), any(), any(), any()) } returns
                Result.success("""{"intentKind":"RCA","confidence":0.82,"rationale":"why dominates"}""")
            val out =
                runBlocking {
                    // 'proč' (RCA) + 'predikce' (FORECAST) both fire.
                    classifyIntent(tokens("proč", "klesnout", "tržba", "a", "predikce"), "cs", rules, llm)
                }
            out.intentKind shouldBe Themis.IntentKind.RCA
            out.source shouldBe IntentSource.LLM_FALLBACK
            out.confidence shouldBe 0.82
            coVerify(exactly = 1) { llm.complete(any(), any(), any(), any(), any()) }
        }

        "LLM tie-break gateway failure → falls back to a candidate at 0.5" {
            val llm = mockk<LlmGatewayClient>()
            coEvery { llm.complete(any(), any(), any(), any(), any()) } returns
                Result.failure(RuntimeException("gateway down"))
            val out =
                runBlocking {
                    classifyIntent(tokens("proč", "a", "predikce"), "cs", rules, llm)
                }
            out.source shouldBe IntentSource.LLM_FALLBACK
            out.confidence shouldBe 0.5
        }
    })

private fun token(
    text: String,
    lemma: String = text.lowercase(),
    upos: String = "NOUN",
    feats: Map<String, String> = emptyMap(),
): NlpToken =
    NlpToken(
        text = text,
        charStart = 0,
        charEnd = text.length,
        lemma = lemma,
        upos = upos,
        xpos = "",
        feats = feats,
        depHead = 0,
        depRelation = "",
    )

private fun tokens(vararg lemmas: String): List<NlpToken> = lemmas.map { token(it, lemma = it) }
