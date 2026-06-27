package org.tatrman.kantheon.iris.inbox

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest

/**
 * Stage 5.2 T1 — the live `PythiaClient` over Pythia's REST control surface: submit
 * returns the assigned id (forwarding the OBO bearer); list parses the inbox rows.
 * Live-HTTP fidelity is integration; the unit gate drives a Ktor MockEngine.
 */
class LivePythiaClientSpec :
    StringSpec({

        fun client(
            status: HttpStatusCode,
            body: String,
            captured: MutableList<String> = mutableListOf(),
        ): LivePythiaClient =
            LivePythiaClient(
                baseUrl = "http://pythia",
                httpClient =
                    HttpClient(
                        MockEngine { request ->
                            captured += request.headers[HttpHeaders.Authorization] ?: ""
                            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
                        },
                    ),
            )

        "submit returns the assigned investigation id and forwards the bearer" {
            runTest {
                val bearers = mutableListOf<String>()
                val c = client(HttpStatusCode.Accepted, """{"id":"inv-77"}""", bearers)
                c.submit("""{"question":"why?"}""", "tok-123") shouldBe "inv-77"
                bearers.single() shouldBe "Bearer tok-123"
            }
        }

        "listInvestigations parses the per-user inbox rows" {
            runTest {
                val body =
                    """{"investigations":[
                       {"id":"inv-1","question":"q1","status":"STATUS_EXECUTING","created_at":"t0","updated_at":"t1",
                        "resource_usage":{"total_usd":0.4}}]}"""
                val rows = client(HttpStatusCode.OK, body).listInvestigations("u1", "tok")
                rows.single().id shouldBe "inv-1"
                rows.single().status shouldBe "STATUS_EXECUTING"
                rows.single().costSoFar shouldBe 0.4
            }
        }
    })
