package org.tatrman.kantheon.iris.api

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.tatrman.kantheon.iris.audit.Ed25519Signer
import org.tatrman.kantheon.iris.audit.InMemoryAuditStore
import org.tatrman.kantheon.iris.dispatch.AgentClient
import org.tatrman.kantheon.iris.dispatch.AgentDispatcher
import org.tatrman.kantheon.iris.dispatch.AgentResume
import org.tatrman.kantheon.iris.dispatch.AgentTurn
import org.tatrman.kantheon.iris.dispatch.golem.GolemRequestFactory
import org.tatrman.kantheon.iris.domain.InMemorySessionStore
import org.tatrman.kantheon.iris.domain.TurnStatus
import org.tatrman.kantheon.iris.routing.AgentLabels
import org.tatrman.kantheon.iris.routing.FakeThemisClient
import org.tatrman.kantheon.iris.routing.RoutingEnvelopes
import org.tatrman.kantheon.iris.stream.TurnOutcome
import org.tatrman.kantheon.iris.v1.IrisStreamEvent

/**
 * The turn's answer locale.
 *
 * This is not a cosmetic setting: Golem selects its prompt bundle by it
 * (`prompts/<locale>/intent.yaml`), so it decides the language the *answer* is written in —
 * the table caption and the follow-up chips — independently of the data. A hardcoded `cs`
 * default produced Czech captions over US warehouse data in an English session, because the
 * SPA's language picker was a UI-only concern that never reached the BFF.
 */
class TurnLocaleSpec :
    StringSpec({

        /** Captures the dispatched [AgentTurn] so the locale handed to an agent is assertable. */
        class CapturingAgent : AgentClient {
            var seen: AgentTurn? = null

            override suspend fun runTurn(
                turn: AgentTurn,
                emit: suspend (IrisStreamEvent) -> Unit,
            ): TurnOutcome {
                seen = turn
                return TurnOutcome(null, TurnStatus.DONE, null, null, "done")
            }

            override suspend fun runResume(
                resume: AgentResume,
                emit: suspend (IrisStreamEvent) -> Unit,
            ): TurnOutcome = error("not used")
        }

        suspend fun run(
            requestLocale: String?,
            defaultLocale: String = "cs",
        ): Pair<FakeThemisClient, CapturingAgent> {
            val store = InMemorySessionStore()
            val themis = FakeThemisClient(defaultAgent = "golem-hartland")
            val agent = CapturingAgent()
            val dispatcher =
                ChatDispatcher(
                    store,
                    themis,
                    AgentDispatcher(mapOf("golem-hartland" to agent)),
                    InMemoryAuditStore(Ed25519Signer()),
                    RoutingEnvelopes(AgentLabels.IDENTITY),
                    defaultLocale,
                )
            val session = store.createSession("maya", "hartland")
            dispatcher.runTurn(
                caller = CallerIdentity("maya", "hartland", "jwt-1"),
                sessionId = session.sessionId,
                question = "How did Marketplace revenue develop in 2025?",
                desiredFormat = null,
                correlationId = "corr-1",
                locale = requestLocale,
                emit = {},
            )
            return themis to agent
        }

        "the request's locale reaches both Themis and the dispatched agent" {
            runTest {
                val (themis, agent) = run(requestLocale = "en")

                themis.seenRequests
                    .single()
                    .context.locale shouldBe "en"
                agent.seen?.locale shouldBe "en"
            }
        }

        "no locale on the request falls back to the configured default" {
            runTest {
                val (themis, agent) = run(requestLocale = null, defaultLocale = "cs")

                themis.seenRequests
                    .single()
                    .context.locale shouldBe "cs"
                agent.seen?.locale shouldBe "cs"
            }
        }

        "a blank locale is treated as absent, not as a language" {
            runTest {
                val (_, agent) = run(requestLocale = "   ", defaultLocale = "en")

                agent.seen?.locale shouldBe "en"
            }
        }

        "the deployment default is what an English estate can set — no code change" {
            runTest {
                val (themis, agent) = run(requestLocale = null, defaultLocale = "en")

                themis.seenRequests
                    .single()
                    .context.locale shouldBe "en"
                agent.seen?.locale shouldBe "en"
            }
        }

        "GolemRequest carries the locale; an unset one is left for the Shem to default" {
            val base =
                AgentTurn(
                    turnId = "t-1",
                    sessionId = java.util.UUID.randomUUID(),
                    caller = CallerIdentity("maya", "hartland", "jwt-1"),
                    correlationId = "c-1",
                    question = "q",
                )

            GolemRequestFactory
                .forTurn("golem-hartland", base.copy(locale = "en"))
                .context.locale shouldBe "en"
            // Blank must not be sent as a locale — golem would pick a prompt bundle for "".
            GolemRequestFactory
                .forTurn("golem-hartland", base)
                .context.locale shouldBe ""
        }
    })
