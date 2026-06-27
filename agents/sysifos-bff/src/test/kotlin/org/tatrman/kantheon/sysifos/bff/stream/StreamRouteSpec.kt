package org.tatrman.kantheon.sysifos.bff.stream

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.withTimeout
import org.tatrman.kantheon.sysifos.bff.bearer
import org.tatrman.kantheon.sysifos.bff.module
import org.tatrman.kantheon.sysifos.bff.testDeps

class StreamRouteSpec :
    StringSpec({

        "GET /stream without a JWT → 401" {
            testApplication {
                application { module(testDeps()) }
                client.get("/stream").status shouldBe HttpStatusCode.Unauthorized
            }
        }

        "GET /stream emits a SessionHeartbeat frame" {
            testApplication {
                application { module(testDeps(heartbeatMs = 20)) }
                client
                    .prepareGet("/stream") {
                        header(HttpHeaders.Authorization, bearer("""{"sub":"u1","tenant":"acme"}"""))
                    }.execute { resp ->
                        val channel = resp.bodyAsChannel()
                        val sb = StringBuilder()
                        withTimeout(3000) {
                            while (true) {
                                val line = channel.readUTF8Line() ?: break
                                sb.append(line).append('\n')
                                if (sb.contains("heartbeat")) break
                            }
                        }
                        sb.toString() shouldContain "heartbeat"
                    }
            }
        }
    })
