package org.tatrman.kantheon.capabilities.registry

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.tatrman.kantheon.capabilities.agentCapability
import org.tatrman.kantheon.capabilities.asCapability
import org.tatrman.kantheon.capabilities.v1.AgentKind

/**
 * The routing-view contract (Hebe P3 S3.4 T4/T5): a `non_routable` agent (Hebe) is
 * absent from the `listAgents()` routing view served to Themis, but present in the
 * discovery surfaces (`list`/`search`/`get`); `visibility_roles` are transported
 * end-to-end (the registry carries the declaration — Themis filters per caller).
 */
class RoutingViewSpec :
    StringSpec({

        fun service(): RegistryQueryService {
            val reg = InMemoryRegistry()
            reg.register(agentCapability("golem-erp", AgentKind.AREA_QA).asCapability())
            reg.register(
                agentCapability("hebe-bora", AgentKind.PERSONAL_ASSISTANT) {
                    nonRoutable = true
                }.asCapability(),
            )
            reg.register(
                agentCapability("golem-hr", AgentKind.AREA_QA) {
                    addVisibilityRoles("kantheon-area-hr")
                }.asCapability(),
            )
            return RegistryQueryService(reg)
        }

        "non_routable agent is excluded from the routing view" {
            val routable = service().listAgents().map { it.agentId }
            routable shouldContain "golem-erp"
            routable shouldNotContain "hebe-bora"
        }

        "non_routable agent is still present for discovery (get + search)" {
            val svc = service()
            svc.get("hebe-bora")?.agent?.agentId shouldBe "hebe-bora"
            val searched =
                svc
                    .search(
                        RegistryQueryService.SearchParams(),
                    ).filter { it.hasAgent() }
                    .map { it.agent.agentId }
            searched shouldContain "hebe-bora"
        }

        "visibility_roles are transported through the routing view" {
            val hr = service().listAgents().single { it.agentId == "golem-hr" }
            hr.visibilityRolesList shouldContain "kantheon-area-hr"
        }
    })
