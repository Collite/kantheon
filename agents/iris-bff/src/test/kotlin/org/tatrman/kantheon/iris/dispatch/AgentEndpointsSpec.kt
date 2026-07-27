package org.tatrman.kantheon.iris.dispatch

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe

/**
 * `iris.dispatch.agent-endpoints` parsing.
 *
 * The map exists because Themis routes to the agent id registered in capabilities-mcp
 * (`golem-hartland`), while the dispatcher was keyed only on the placeholder `golem-v2` — so a
 * correct routing decision failed with "No dispatch client registered for agent 'golem-hartland'".
 *
 * Empty-in-empty-out is the load-bearing case: it is the default, and it must preserve the
 * single-endpoint behaviour rather than producing a `"" -> ""` entry that would shadow a real id.
 */
class AgentEndpointsSpec :
    StringSpec({

        "parses the multi-golem estate spec" {
            parseAgentEndpoints(
                "golem-hartland=http://golem-hartland:7420,golem-hartland-finance=http://golem-hartland-finance:7420",
            ) shouldContainExactly
                mapOf(
                    "golem-hartland" to "http://golem-hartland:7420",
                    "golem-hartland-finance" to "http://golem-hartland-finance:7420",
                )
        }

        "the empty default yields no entries — not a blank id" {
            parseAgentEndpoints("") shouldBe emptyMap()
        }

        "tolerates surrounding whitespace" {
            parseAgentEndpoints(" a = http://a:1 , b = http://b:2 ") shouldContainExactly
                mapOf("a" to "http://a:1", "b" to "http://b:2")
        }

        "keeps the whole URL when it contains '=' (query strings)" {
            parseAgentEndpoints("a=http://a:1/x?k=v") shouldContainExactly mapOf("a" to "http://a:1/x?k=v")
        }

        "skips malformed entries rather than failing the boot" {
            // A stray comma or a bare token is deployment-config sloppiness, not a reason to
            // refuse to start — the valid neighbours must still register.
            parseAgentEndpoints("a=http://a:1,,garbage,=http://nohost:1,b=,c=http://c:3") shouldContainExactly
                mapOf("a" to "http://a:1", "c" to "http://c:3")
        }
    })
