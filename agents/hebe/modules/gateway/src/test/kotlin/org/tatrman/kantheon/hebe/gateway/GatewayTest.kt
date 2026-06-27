package org.tatrman.kantheon.hebe.gateway

import org.tatrman.kantheon.hebe.api.ChannelHealth
import org.tatrman.kantheon.hebe.config.SecretStoreProvider
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.mockk
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class GatewayTest {
    private val testPassword = "testpass"
    private val testPasswordHash: ByteArray =
        MessageDigest.getInstance("SHA-256").digest(testPassword.toByteArray())

    private fun makeSecretStore(returns: ByteArray? = testPasswordHash): SecretStoreProvider {
        val mock = mockk<SecretStoreProvider>()
        coEvery { mock.get(any()) } returns returns
        return mock
    }

    private fun basicAuthHeader(password: String = testPassword): String =
        "Basic " + Base64.getEncoder().encodeToString("admin:$password".toByteArray())

    @Test
    fun `health endpoint returns 200 without authentication`() =
        testApplication {
            application {
                makeGateway().run {
                    configureApplication(makeSecretStore()) {}
                }
            }

            val response = client.get("/health")
            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `api status requires authentication — no credentials returns 401`() =
        testApplication {
            application {
                makeGateway().run {
                    configureApplication(makeSecretStore()) {}
                }
            }

            val response = client.get("/api/status")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `api status returns 200 with correct credentials`() =
        testApplication {
            application {
                makeGateway().run {
                    configureApplication(makeSecretStore()) {}
                }
            }

            val response =
                client.get("/api/status") {
                    header("Authorization", basicAuthHeader())
                }
            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `api status returns 401 with wrong password`() =
        testApplication {
            application {
                makeGateway().run {
                    configureApplication(makeSecretStore()) {}
                }
            }

            val response =
                client.get("/api/status") {
                    header("Authorization", basicAuthHeader("wrongpassword"))
                }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `api status returns 401 when no secret is configured`() =
        testApplication {
            application {
                makeGateway().run {
                    configureApplication(makeSecretStore(returns = null)) {}
                }
            }

            val response =
                client.get("/api/status") {
                    header("Authorization", basicAuthHeader())
                }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `api status response body contains channel health data`() =
        testApplication {
            val gateway = makeGateway()
            gateway.setChannelHealthProvider {
                listOf("web" to ChannelHealth.Up, "telegram" to ChannelHealth.Down)
            }
            gateway.setLlmHealthProvider { "https://api.example.com" to true }

            application {
                gateway.run {
                    configureApplication(makeSecretStore()) {}
                }
            }

            val response =
                client.get("/api/status") {
                    header("Authorization", basicAuthHeader())
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue("uptimeMs" in body, "Missing uptimeMs: $body")
            assertTrue("\"name\":\"web\"" in body, "Missing web channel: $body")
            assertTrue("\"health\":\"Up\"" in body, "Missing Up health: $body")
            assertTrue("\"name\":\"telegram\"" in body, "Missing telegram channel: $body")
            assertTrue("\"health\":\"Down\"" in body, "Missing Down health: $body")
            assertTrue("\"reachable\":true" in body, "Missing LLM reachable: $body")
            assertTrue("api.example.com" in body, "Missing LLM endpoint: $body")
        }

    private fun makeGateway() = Gateway()
}
