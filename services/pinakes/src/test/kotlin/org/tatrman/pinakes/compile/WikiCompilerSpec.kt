package org.tatrman.pinakes.compile

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post as wmPost
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import org.tatrman.kallimachos.v1.PageKind
import org.tatrman.pinakes.clients.HttpLlmGatewayClient

/**
 * P3 Stage 3.2 T1 — the WikiCompiler against a Wiremock'd LLM gateway. Source
 * parts → ENTITY/CONCEPT/SUMMARY page drafts with `derivedFromParts` provenance;
 * a malformed/failed LLM response degrades to a mechanical SUMMARY (never crashes
 * the run — architecture §14).
 */
class WikiCompilerSpec :
    StringSpec({
        val parts = listOf(PartInput(8, "Kaufland is a retail chain."), PartInput(9, "It operates many stores."))

        fun compilerFor(wm: WireMockServer): WikiCompiler {
            val http = HttpClient(CIO) { install(ContentNegotiation) { json() } }
            return WikiCompiler(HttpLlmGatewayClient(http, "http://localhost:${wm.port()}", "sonnet"))
        }

        fun wiremock() = WireMockServer(WireMockConfiguration.options().dynamicPort()).also { it.start() }

        "source parts compile into ENTITY/SUMMARY pages with provenance" {
            val wm = wiremock()
            try {
                wm.stubFor(
                    wmPost(urlPathEqualTo("/v1/chat/completions")).willReturn(
                        aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(
                            """{"choices":[{"message":{"content":"[{\"kind\":\"ENTITY\",\"title\":\"Kaufland\",\"contentMd\":\"# Kaufland\",\"derivedFromParts\":[8,9],\"entityType\":\"customer\",\"entityLabel\":\"Kaufland\"},{\"kind\":\"SUMMARY\",\"title\":\"Overview\",\"contentMd\":\"...\",\"derivedFromParts\":[8,9]}]"}}]}""",
                        ),
                    ),
                )
                val drafts = runBlocking { compilerFor(wm).compile(parts).pages }
                drafts.map { it.kind } shouldContainExactly listOf(PageKind.ENTITY, PageKind.SUMMARY)
                val entity = drafts.first { it.kind == PageKind.ENTITY }
                entity.derivedFromParts shouldContainExactly listOf(8L, 9L)
                entity.conceptRef!!.displayLabel shouldBe "Kaufland"
                entity.conceptRef!!.entityId shouldBe "wiki:kaufland"
                entity.conceptRef!!.velesQname shouldBe "" // §6 seam — empty at v1
            } finally {
                wm.stop()
            }
        }

        "a malformed LLM response degrades to a mechanical SUMMARY" {
            val wm = wiremock()
            try {
                wm.stubFor(
                    wmPost(urlPathEqualTo("/v1/chat/completions")).willReturn(
                        aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(
                            """{"choices":[{"message":{"content":"not valid json at all"}}]}""",
                        ),
                    ),
                )
                val drafts = runBlocking { compilerFor(wm).compile(parts).pages }
                drafts.size shouldBe 1
                drafts.first().kind shouldBe PageKind.SUMMARY
                drafts.first().derivedFromParts shouldContainExactly listOf(8L, 9L)
            } finally {
                wm.stop()
            }
        }

        "an LLM error degrades to a mechanical SUMMARY (the corpus stays queryable)" {
            val wm = wiremock()
            try {
                wm.stubFor(wmPost(urlPathEqualTo("/v1/chat/completions")).willReturn(aResponse().withStatus(503)))
                val drafts = runBlocking { compilerFor(wm).compile(parts).pages }
                drafts.size shouldBe 1
                drafts.first().kind shouldBe PageKind.SUMMARY
            } finally {
                wm.stop()
            }
        }
    })
