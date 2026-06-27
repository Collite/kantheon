package org.tatrman.kantheon.pythia.api

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.tatrman.kantheon.pythia.v1.Status

/**
 * Stage 1.3 T5/T6 — control-surface component tests (testApplication): PD-8
 * admission (403), submit/get, each AWAITING_* resume endpoint + the 409 on
 * double-resume, the PD-2 list, and the 501 replay/reproduce stubs.
 */
class ControlRoutesSpec :
    StringSpec({

        "POST submit without a bearer is 403" {
            testApplication {
                val h = PythiaTestHarness().also { it.mount(this) }
                val resp = client.post("/v1/investigations") { setBody("""{"question":"q"}""") }
                resp.status shouldBe HttpStatusCode.Forbidden
                resp.bodyAsText() shouldContain "unauthenticated"
            }
        }

        "POST submit with a bearer returns 202 + id, and GET returns the artifact" {
            testApplication {
                val h = PythiaTestHarness().also { it.mount(this) }
                val submit =
                    client.post("/v1/investigations") {
                        bearerAuth("u1")
                        setBody("""{"question":"why did revenue drop?","caller":{"kind":"IRIS"}}""")
                    }
                submit.status shouldBe HttpStatusCode.Accepted
                val id = Regex("\"id\":\"([^\"]+)\"").find(submit.bodyAsText())!!.groupValues[1]

                val get = client.get("/v1/investigations/$id") { bearerAuth("u1") }
                get.status shouldBe HttpStatusCode.OK
                get.bodyAsText() shouldContain "STATUS_DONE" // Unconfined → stub pipeline ran to completion
            }
        }

        "GET another user's investigation is 403; unknown id is 404" {
            testApplication {
                val h = PythiaTestHarness().also { it.mount(this) }
                val id = h.seed(Status.STATUS_DONE, userId = "owner")
                client.get("/v1/investigations/$id") { bearerAuth("intruder") }.status shouldBe HttpStatusCode.Forbidden
                client
                    .get("/v1/investigations/00000000-0000-0000-0000-000000000000") {
                        bearerAuth("u1")
                    }.status shouldBe HttpStatusCode.NotFound
            }
        }

        "approve-plan resumes a parked investigation; second call is 409" {
            testApplication {
                val h = PythiaTestHarness().also { it.mount(this) }
                val id = h.seed(Status.STATUS_AWAITING_PLAN_APPROVAL)
                val first =
                    client.post("/v1/investigations/$id/approve-plan") {
                        bearerAuth("u1")
                        header("Content-Type", "application/json")
                        setBody("""{"verdict":"APPROVE"}""")
                    }
                first.status shouldBe HttpStatusCode.OK
                val second =
                    client.post("/v1/investigations/$id/approve-plan") {
                        bearerAuth("u1")
                        setBody("""{"verdict":"APPROVE"}""")
                    }
                second.status shouldBe HttpStatusCode.Conflict
                second.bodyAsText() shouldContain "already_resumed"
            }
        }

        "answer resumes AWAITING_RESOLUTION_INPUT and AWAITING_USER_INPUT" {
            testApplication {
                val h = PythiaTestHarness().also { it.mount(this) }
                val a = h.seed(Status.STATUS_AWAITING_RESOLUTION_INPUT)
                client
                    .post("/v1/investigations/$a/answer") {
                        bearerAuth("u1")
                        setBody("""{"answers":[]}""")
                    }.status shouldBe HttpStatusCode.OK
                val b = h.seed(Status.STATUS_AWAITING_USER_INPUT)
                client
                    .post("/v1/investigations/$b/answer") {
                        bearerAuth("u1")
                        setBody("""{"answers":[]}""")
                    }.status shouldBe HttpStatusCode.OK
            }
        }

        "budget-decision resumes AWAITING_BUDGET_DECISION (CONTINUE)" {
            testApplication {
                val h = PythiaTestHarness().also { it.mount(this) }
                val id = h.seed(Status.STATUS_AWAITING_BUDGET_DECISION)
                client
                    .post("/v1/investigations/$id/budget-decision") {
                        bearerAuth("u1")
                        setBody("""{"decision":"CONTINUE"}""")
                    }.status shouldBe HttpStatusCode.OK
            }
        }

        "approve-revision resumes AWAITING_PLAN_REVISION_APPROVAL" {
            testApplication {
                val h = PythiaTestHarness().also { it.mount(this) }
                val id = h.seed(Status.STATUS_AWAITING_PLAN_REVISION_APPROVAL)
                client
                    .post("/v1/investigations/$id/approve-revision") {
                        bearerAuth("u1")
                        setBody("""{"verdict":"APPROVE"}""")
                    }.status shouldBe HttpStatusCode.OK
            }
        }

        "halt drains-with-partials to HALTED" {
            testApplication {
                val h = PythiaTestHarness().also { it.mount(this) }
                val id = h.seed(Status.STATUS_EXECUTING)
                client
                    .post("/v1/investigations/$id/halt") {
                        bearerAuth("u1")
                        setBody("{}")
                    }.status shouldBe HttpStatusCode.OK
                Status.valueOf(h.investigations.findById(id)!!.status) shouldBe Status.STATUS_HALTED
            }
        }

        "replay and reproduce create a new investigation (202) linked to the parent" {
            testApplication {
                val h = PythiaTestHarness().also { it.mount(this) }
                val parent = h.seed(Status.STATUS_DONE)
                val replay =
                    client.post("/v1/investigations/$parent/replay") {
                        bearerAuth("u1")
                        setBody("{}")
                    }
                replay.status shouldBe HttpStatusCode.Accepted
                replay.bodyAsText() shouldContain "STATUS_SUBMITTED"
                client
                    .post("/v1/investigations/$parent/reproduce") {
                        bearerAuth("u1")
                        setBody("{}")
                    }.status shouldBe HttpStatusCode.Accepted
            }
        }

        "GET /v1/investigations lists per-user, status-filtered, with paging; excludes other users" {
            testApplication {
                val h = PythiaTestHarness().also { it.mount(this) }
                h.seed(Status.STATUS_DONE, userId = "u1")
                h.seed(Status.STATUS_EXECUTING, userId = "u1")
                h.seed(Status.STATUS_DONE, userId = "u2")

                val mine = client.get("/v1/investigations") { bearerAuth("u1") }
                mine.status shouldBe HttpStatusCode.OK
                val body = mine.bodyAsText()
                // both u1 rows present, u2 excluded (3 seeded, 2 visible)
                Regex("\"id\"").findAll(body).count() shouldBe 2

                val filtered = client.get("/v1/investigations?statuses=DONE") { bearerAuth("u1") }
                Regex("STATUS_DONE").findAll(filtered.bodyAsText()).count() shouldBe 1
            }
        }

        "listing another user's investigations is forbidden" {
            testApplication {
                val h = PythiaTestHarness().also { it.mount(this) }
                client
                    .get("/v1/investigations?user_id=someone-else") { bearerAuth("u1") }
                    .status shouldBe HttpStatusCode.Forbidden
            }
        }
    })
