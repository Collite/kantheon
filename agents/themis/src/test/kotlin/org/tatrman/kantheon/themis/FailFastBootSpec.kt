package org.tatrman.kantheon.themis

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.runBlocking
import org.tatrman.kantheon.capabilities.client.CapabilitiesReadClient

/**
 * Phase 3 Stage 3.3 — the fail-fast boot gate. Themis must refuse to start when
 * the agent registry is empty or unreachable.
 */
class FailFastBootSpec :
    StringSpec({

        "empty agent list → refuses to start (IllegalArgumentException)" {
            val wm = WireMockServer(WireMockConfiguration.options().dynamicPort()).apply { start() }
            wm.stubFor(
                get(urlPathEqualTo("/v1/capabilities/agents"))
                    .willReturn(okJson("""{"agents":[],"messages":[]}""")),
            )
            val client = CapabilitiesReadClient(endpoint = "http://localhost:${wm.port()}")
            shouldThrow<IllegalArgumentException> {
                runBlocking { assertRoutableAgentsAvailable(client) }
            }
            client.close()
            wm.stop()
        }

        "at least one agent → starts" {
            val wm = WireMockServer(WireMockConfiguration.options().dynamicPort()).apply { start() }
            wm.stubFor(
                get(urlPathEqualTo("/v1/capabilities/agents"))
                    .willReturn(
                        okJson("""{"agents":[{"agentId":"pythia","agentKind":"INVESTIGATOR"}],"messages":[]}"""),
                    ),
            )
            val client = CapabilitiesReadClient(endpoint = "http://localhost:${wm.port()}")
            shouldNotThrowAny { runBlocking { assertRoutableAgentsAvailable(client) } }
            client.close()
            wm.stop()
        }

        "registry unreachable → refuses to start (IllegalStateException)" {
            // Nothing listening on this port.
            val client = CapabilitiesReadClient(endpoint = "http://localhost:1")
            shouldThrow<IllegalStateException> {
                runBlocking { assertRoutableAgentsAvailable(client) }
            }
            client.close()
        }
    })
