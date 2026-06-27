package org.tatrman.kantheon.sysifos.bff.api

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.tatrman.kantheon.sysifos.bff.module
import org.tatrman.kantheon.sysifos.bff.testDeps

class HealthReadySpec :
    StringSpec({

        "GET /health → 200" {
            testApplication {
                application { module(testDeps()) }
                client.get("/health").status shouldBe HttpStatusCode.OK
            }
        }

        "GET /ready → 200 when ready" {
            testApplication {
                application { module(testDeps(ready = true)) }
                client.get("/ready").status shouldBe HttpStatusCode.OK
            }
        }

        "GET /ready → 503 when Midas-core is unreachable" {
            testApplication {
                application { module(testDeps(ready = false)) }
                client.get("/ready").status shouldBe HttpStatusCode.ServiceUnavailable
            }
        }
    })
