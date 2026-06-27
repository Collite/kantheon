package org.tatrman.kantheon.capabilities.api

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.kantheon.capabilities.ReadinessGate
import org.tatrman.kantheon.capabilities.agentCapability
import org.tatrman.kantheon.capabilities.asCapability
import org.tatrman.kantheon.capabilities.registry.InMemoryRegistry
import org.tatrman.kantheon.capabilities.toolCapability
import org.tatrman.kantheon.capabilities.v1.AgentKind
import org.tatrman.kantheon.capabilities.v1.IntentKind

private fun seededRegistry(): InMemoryRegistry =
    InMemoryRegistry().apply {
        register(toolCapability("model.fit.arima:v1", "ARIMA forecast").asCapability(), fromFixture = true)
        register(
            agentCapability("pythia", AgentKind.INVESTIGATOR) {
                addIntentKindsSupported(IntentKind.RCA)
                addIntentKindsSupported(IntentKind.FORECAST)
            }.asCapability(),
            fromFixture = true,
        )
        register(
            agentCapability("golem-erp", AgentKind.AREA_QA) {
                addIntentKindsSupported(IntentKind.PROCEDURAL)
                areaName = "ERP"
                addAllAreaEntities(listOf("customer", "invoice"))
            }.asCapability(),
            fromFixture = true,
        )
    }

private fun testApp(
    registry: InMemoryRegistry,
    readiness: ReadinessGate = ReadinessGate(),
): io.ktor.server.application.Application.() -> Unit =
    {
        install(ContentNegotiation) { json() }
        val service =
            org.tatrman.kantheon.capabilities.registry
                .RegistryQueryService(registry)
        routing {
            healthRoutes(readiness)
            capabilitiesRestRoutes(service)
        }
    }

class CapabilitiesRestSpec :
    StringSpec({

        "POST /v1/capabilities/search returns mixed tool + agent entries with messages: []" {
            val reg = seededRegistry()
            testApplication {
                application(testApp(reg))
                val client = createClient { install(ClientContentNegotiation) { json() } }
                val resp =
                    client.post("/v1/capabilities/search") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"intentKinds":["RCA"],"filter":{"includeAgents":true,"includeTools":false}}""")
                    }
                resp.status shouldBe HttpStatusCode.OK
                val body: JsonObject = resp.body()
                val entries = body["entries"]!!.jsonArray
                entries shouldHaveSize 1
                entries[0].jsonObject["kind"]!!.jsonPrimitive.content shouldBe "agent"
                entries[0]
                    .jsonObject["agent"]!!
                    .jsonObject["agentId"]!!
                    .jsonPrimitive.content shouldBe "pythia"
                body["messages"]!!.jsonArray shouldHaveSize 0
            }
        }

        "GET /v1/capabilities returns all live entries by default" {
            val reg = seededRegistry()
            testApplication {
                application(testApp(reg))
                val client = createClient { install(ClientContentNegotiation) { json() } }
                val resp = client.get("/v1/capabilities")
                resp.status shouldBe HttpStatusCode.OK
                val body: JsonObject = resp.body()
                body["entries"]!!.jsonArray shouldHaveSize 3
                body["messages"]!!.jsonArray shouldHaveSize 0
            }
        }

        "GET /v1/capabilities/agents returns only agents (ToolCapability filtered out)" {
            val reg = seededRegistry()
            testApplication {
                application(testApp(reg))
                val client = createClient { install(ClientContentNegotiation) { json() } }
                val resp = client.get("/v1/capabilities/agents")
                resp.status shouldBe HttpStatusCode.OK
                val body: JsonObject = resp.body()
                val agents = body["agents"]!!.jsonArray
                agents.map { it.jsonObject["agentId"]!!.jsonPrimitive.content } shouldContainExactlyInAnyOrder
                    listOf("pythia", "golem-erp")
            }
        }

        "GET /v1/capabilities/{id} returns the matching tool" {
            val reg = seededRegistry()
            testApplication {
                application(testApp(reg))
                val client = createClient { install(ClientContentNegotiation) { json() } }
                val resp = client.get("/v1/capabilities/model.fit.arima:v1")
                resp.status shouldBe HttpStatusCode.OK
                val body: JsonObject = resp.body()
                val cap = body["capability"]!!.jsonObject
                cap["kind"]!!.jsonPrimitive.content shouldBe "tool"
                cap["tool"]!!.jsonObject["capabilityId"]!!.jsonPrimitive.content shouldBe "model.fit.arima:v1"
            }
        }

        "GET /v1/capabilities/{unknown} returns 200 with capability:null" {
            val reg = seededRegistry()
            testApplication {
                application(testApp(reg))
                val client = createClient { install(ClientContentNegotiation) { json() } }
                val resp = client.get("/v1/capabilities/unknown")
                resp.status shouldBe HttpStatusCode.OK
                val body: JsonObject = resp.body()
                (body["capability"] == null || body["capability"]!!.jsonPrimitive.toString() == "null") shouldBe true
            }
        }

        "POST /v1/capabilities/register is idempotent on capability_id" {
            val reg = InMemoryRegistry()
            testApplication {
                application(testApp(reg))
                val client = createClient { install(ClientContentNegotiation) { json() } }
                val firstBody =
                    buildString {
                        append("""{"capability":{"kind":"tool","tool":""")
                        append("""{"capabilityId":"theseus.query:v1","category":"theseus.*",""")
                        append(""""version":"v1","description":"first"}}}""")
                    }
                val rid1 =
                    client
                        .post("/v1/capabilities/register") {
                            contentType(ContentType.Application.Json)
                            setBody(firstBody)
                        }.body<JsonObject>()["registrationId"]!!
                        .jsonPrimitive.content
                val secondBody = firstBody.replace("first", "second")
                val rid2 =
                    client
                        .post("/v1/capabilities/register") {
                            contentType(ContentType.Application.Json)
                            setBody(secondBody)
                        }.body<JsonObject>()["registrationId"]!!
                        .jsonPrimitive.content

                rid1.shouldNotBeBlank()
                rid2 shouldBe rid1
                reg
                    .get("theseus.query:v1")
                    ?.capability
                    ?.tool
                    ?.description shouldBe "second"
            }
        }

        "POST /v1/capabilities/{rid}/heartbeat refreshes lastHeartbeatAt" {
            val reg = InMemoryRegistry()
            val rid = reg.register(toolCapability("theseus.query:v1").asCapability())
            testApplication {
                application(testApp(reg))
                val client = createClient { install(ClientContentNegotiation) { json() } }
                val resp = client.post("/v1/capabilities/$rid/heartbeat")
                resp.status shouldBe HttpStatusCode.OK
                val body: JsonObject = resp.body()
                body["acceptedAt"]!!.jsonPrimitive.content.shouldNotBeBlank()
                body["messages"]!!.jsonArray shouldHaveSize 0
            }
        }

        "POST /v1/capabilities/{unknownRid}/heartbeat returns 404 with ERROR message" {
            val reg = InMemoryRegistry()
            testApplication {
                application(testApp(reg))
                val client = createClient { install(ClientContentNegotiation) { json() } }
                val resp = client.post("/v1/capabilities/no-such-rid/heartbeat")
                resp.status shouldBe HttpStatusCode.NotFound
                val body: JsonObject = resp.body()
                val msgs = body["messages"]!!.jsonArray
                msgs shouldHaveSize 1
                val msg = msgs[0].jsonObject
                msg["severity"]!!.jsonPrimitive.content shouldBe "ERROR"
                msg["code"]!!.jsonPrimitive.content shouldBe "unknown_registration_id"
            }
        }

        "every response carries a messages array (Rule 6)" {
            val reg = seededRegistry()
            testApplication {
                application(testApp(reg))
                val client = createClient { install(ClientContentNegotiation) { json() } }
                listOf(
                    client.get("/v1/capabilities").body<JsonObject>(),
                    client.get("/v1/capabilities/agents").body<JsonObject>(),
                    client.get("/v1/capabilities/pythia").body<JsonObject>(),
                ).forEach { it["messages"].shouldNotBeNull() }
            }
        }
    })
