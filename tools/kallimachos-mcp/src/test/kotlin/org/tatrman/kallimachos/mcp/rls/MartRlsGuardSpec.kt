package org.tatrman.kallimachos.mcp.rls

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get as wmGet
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.tatrman.kallimachos.mcp.LibraryForwarder
import java.util.Base64

/**
 * P4 Stage 4.2 T2/T4 — RLS enforcement at the MCP edge BEFORE the store. The
 * guard fetches the notebook ACL and applies the predicate: an authorised caller
 * is allowed (RAG GA — getContext then forwards + cites), an unauthorised caller
 * is denied (PERMISSION_DENIED) without the store being touched.
 */
class MartRlsGuardSpec :
    StringSpec({
        fun jwt(
            sub: String,
            roles: List<String>,
        ): String {
            val rolesJson = roles.joinToString(",") { "\"$it\"" }
            val payload =
                Base64
                    .getUrlEncoder()
                    .withoutPadding()
                    .encodeToString("""{"sub":"$sub","realm_access":{"roles":[$rolesJson]}}""".toByteArray())
            return "Bearer h.$payload.s"
        }

        fun args(json: String) = Json.parseToJsonElement(json) as JsonObject

        fun wiremock() = WireMockServer(WireMockConfiguration.options().dynamicPort()).also { it.start() }

        fun guard(wm: WireMockServer) = MartRlsGuard(LibraryForwarder(HttpClient(CIO), "http://localhost:${wm.port()}"))

        "an authorised caller (role overlap) is allowed to read the mart (RAG GA)" {
            val wm = wiremock()
            try {
                wm.stubFor(
                    wmGet(urlPathEqualTo("/notebooks/finance")).willReturn(
                        aResponse().withStatus(200).withBody(
                            """{"id":"finance","displayName":"F","ownerUserId":"bora","visibilityRoles":["kantheon-area-finance"],"memberCount":1}""",
                        ),
                    ),
                )
                val decision =
                    runBlocking {
                        guard(
                            wm,
                        ).check(
                            "library.getContext",
                            args("""{"notebookId":"finance","query":"q"}"""),
                            jwt("someone", listOf("kantheon-area-finance")),
                        )
                    }
                decision shouldBe RlsDecision.Allow
            } finally {
                wm.stop()
            }
        }

        "an unauthorised caller is DENIED at the edge (store never touched)" {
            val wm = wiremock()
            try {
                wm.stubFor(
                    wmGet(urlPathEqualTo("/notebooks/finance")).willReturn(
                        aResponse().withStatus(200).withBody(
                            """{"id":"finance","displayName":"F","ownerUserId":"bora","visibilityRoles":["kantheon-area-finance"],"memberCount":1}""",
                        ),
                    ),
                )
                val decision =
                    runBlocking {
                        guard(
                            wm,
                        ).check(
                            "library.getContext",
                            args("""{"notebookId":"finance","query":"q"}"""),
                            jwt("intruder", listOf("kantheon-area-hr")),
                        )
                    }
                decision.shouldBeInstanceOf<RlsDecision.Deny>()
            } finally {
                wm.stop()
            }
        }

        "write ops are ops/admin-gated" {
            val wm = wiremock()
            try {
                runBlocking {
                    guard(wm)
                        .check("library.createNotebook", args("""{"displayName":"M"}"""), jwt("user", emptyList()))
                        .shouldBeInstanceOf<RlsDecision.Deny>()
                    guard(
                        wm,
                    ).check(
                        "library.createNotebook",
                        args("""{"displayName":"M"}"""),
                        jwt("ops", listOf(MartRls.ADMIN_ROLE)),
                    ) shouldBe
                        RlsDecision.Allow
                }
            } finally {
                wm.stop()
            }
        }

        "a browse read (getSource) is mart-scoped — authorised caller allowed" {
            val wm = wiremock()
            try {
                wm.stubFor(
                    wmGet(urlPathEqualTo("/notebooks/finance")).willReturn(
                        aResponse().withStatus(200).withBody(
                            """{"id":"finance","displayName":"F","ownerUserId":"bora","visibilityRoles":["kantheon-area-finance"],"memberCount":1}""",
                        ),
                    ),
                )
                runBlocking {
                    guard(wm).check(
                        "library.getSource",
                        args("""{"id":"7","notebookId":"finance"}"""),
                        jwt("someone", listOf("kantheon-area-finance")),
                    ) shouldBe RlsDecision.Allow
                }
            } finally {
                wm.stop()
            }
        }

        "a browse read without a notebook scope is DENIED (no un-scoped node reads)" {
            val wm = wiremock()
            try {
                runBlocking {
                    guard(wm)
                        .check(
                            "library.getSource",
                            args("""{"id":"7"}"""),
                            jwt("someone", listOf("kantheon-area-finance")),
                        ).shouldBeInstanceOf<RlsDecision.Deny>()
                }
            } finally {
                wm.stop()
            }
        }

        "an unmapped tool is DENIED (fail closed)" {
            val wm = wiremock()
            try {
                runBlocking {
                    guard(wm)
                        .check("library.unknownFuture", args("""{"notebookId":"finance"}"""), jwt("u", emptyList()))
                        .shouldBeInstanceOf<RlsDecision.Deny>()
                }
            } finally {
                wm.stop()
            }
        }

        "the admin '*' scope requires the admin role" {
            val wm = wiremock()
            try {
                runBlocking {
                    guard(wm)
                        .check("library.search", args("""{"notebookId":"*"}"""), jwt("u", emptyList()))
                        .shouldBeInstanceOf<RlsDecision.Deny>()
                    guard(
                        wm,
                    ).check(
                        "library.search",
                        args("""{"notebookId":"*"}"""),
                        jwt("admin", listOf(MartRls.ADMIN_ROLE)),
                    ) shouldBe
                        RlsDecision.Allow
                }
            } finally {
                wm.stop()
            }
        }
    })
