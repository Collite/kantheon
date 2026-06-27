package org.tatrman.kantheon.smoke

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication

class HealthSpec :
    StringSpec({
        "health endpoint returns ok" {
            testApplication {
                application { smokeModule() }
                val response = client.get("/health")
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "\"status\":\"ok\""
            }
        }
    })
