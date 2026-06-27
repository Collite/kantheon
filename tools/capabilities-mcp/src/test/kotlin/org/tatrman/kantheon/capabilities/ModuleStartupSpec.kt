package org.tatrman.kantheon.capabilities

import com.typesafe.config.ConfigFactory
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import shared.ktor.KtorEngine
import shared.ktor.KtorServerConfig
import kotlin.time.Duration.Companion.seconds

class ModuleStartupSpec :
    StringSpec({

        "service is not Ready until fixtures load, then list_agents returns the two seed agents" {
            val config =
                ConfigFactory
                    .parseString(
                        """
                        ktor { deployment { port = 0 }, application { serviceName = "capabilities-mcp" } }
                        telemetry { enabled = false }
                        capabilities {
                          manifests-dir = "classpath:manifests"
                          ttl-seconds = 300
                        }
                        """.trimIndent(),
                    ).withFallback(ConfigFactory.load())

            val serverConfig =
                KtorServerConfig(
                    serviceName = "capabilities-mcp",
                    serverPort = 0,
                    engine = KtorEngine.CIO,
                )

            testApplication {
                application { module(serverConfig, config) }
                val client = createClient { install(ClientContentNegotiation) { json() } }

                // Wait until /ready flips to 200 — fixture load happens on ApplicationStarted.
                eventually(5.seconds) { client.get("/ready").status shouldBe HttpStatusCode.OK }

                val agentsBody: JsonObject = client.get("/v1/capabilities/agents").body()
                val agentIds =
                    agentsBody["agents"]!!
                        .jsonArray
                        .map { it.jsonObject["agentId"]!!.jsonPrimitive.content }
                agentIds shouldContainExactlyInAnyOrder listOf("pythia", "golem-erp")
                agentsBody["messages"]!!.jsonArray shouldHaveSize 0
            }
        }

        "health endpoint returns 200 immediately even while fixtures are still loading" {
            val config =
                ConfigFactory
                    .parseString(
                        """
                        ktor { deployment { port = 0 } }
                        telemetry { enabled = false }
                        capabilities {
                          manifests-dir = "classpath:manifests"
                          ttl-seconds = 300
                        }
                        """.trimIndent(),
                    ).withFallback(ConfigFactory.load())

            val serverConfig =
                KtorServerConfig(
                    serviceName = "capabilities-mcp",
                    serverPort = 0,
                    engine = KtorEngine.CIO,
                )

            testApplication {
                application { module(serverConfig, config) }
                val client = createClient { install(ClientContentNegotiation) { json() } }
                val resp = client.get("/health")
                resp.status shouldBe HttpStatusCode.OK
                resp.body<JsonObject>()["status"]!!.jsonPrimitive.content shouldContain "ok"
            }
        }
    })
