package org.tatrman.kantheon.golem.graph

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.tatrman.kantheon.golem.resolution.RecordedResolutionCore
import org.tatrman.kantheon.golem.resolution.TurnEnd
import org.tatrman.kantheon.golem.resolution.compose.FallThroughReason
import org.tatrman.kantheon.golem.resolution.inertGate
import org.tatrman.kantheon.golem.resolution.ladder.LadderConfig
import org.tatrman.kantheon.golem.resolution.ladder.openLadderYaml
import org.tatrman.kantheon.golem.resolution.testDeps
import org.tatrman.kantheon.golem.v1.Caller
import org.tatrman.kantheon.golem.v1.GolemContext
import org.tatrman.kantheon.golem.v1.GolemRequest
import org.tatrman.kantheon.themis.v1.Themis
import org.tatrman.resolver.v1.ResolutionState

/**
 * RV-P5.3 T6 — the graph, on recorded-core lattices.
 *
 * These drive the NODES rather than `runResolutionTurn`, because the topology is itself a
 * claim: *"the fast path reaches the door without entering selection"* is only meaningful if
 * there is a selection node to not enter, and only checkable if the edges are the ones that
 * ship. The lattices come from `src/test/resources/lattice/` (P5.1's recorded core).
 */
private fun request(
    question: String,
    intent: Themis.IntentKind? = Themis.IntentKind.PROCEDURAL,
    conversationId: String = "conv-7",
): GolemRequest {
    val ctx =
        GolemContext
            .newBuilder()
            .setLocale("cs")
            .setConversationId(conversationId)
    val b =
        GolemRequest
            .newBuilder()
            .setId("turn-1")
            .setGolemId("golem-erp")
            .setQuestion(question)
            .setContext(ctx)
            .setCaller(
                Caller
                    .newBuilder()
                    .setUserId("user-1")
                    .setTenantId("t1")
                    .setCorrelationId("corr-1"),
            )
    intent?.let {
        b.resolvedIntent =
            Themis.Resolution
                .newBuilder()
                .setIntentKind(it)
                .build()
    }
    return b.build()
}

private fun turn(
    question: String,
    lattice: ResolutionState?,
    intent: Themis.IntentKind? = Themis.IntentKind.PROCEDURAL,
): GolemTurnState =
    GolemTurnState(
        request = request(question, intent),
        userId = "user-1",
        tenantId = "t1",
        lattice = lattice,
    )

private fun deps(resolution: org.tatrman.kantheon.golem.resolution.ResolutionDeps) =
    GolemGraphDeps(
        composer = mockk(relaxed = true),
        validator = mockk(relaxed = true),
        miniPlanExecutor = mockk(relaxed = true),
        promptExecutor = mockk(relaxed = true),
        resolution = resolution,
    )

/**
 * The walk lives in [walkResolutionNodes] — shared with the RV-P5.4 conformance runner, so a
 * change to the strategy's edges breaks both tiers rather than only this one.
 */
private suspend fun walk(
    state: GolemTurnState,
    d: GolemGraphDeps,
): GolemTurnState = walkResolutionNodes(state, d)

class ResolutionGraphSpec :
    StringSpec({

        "H1 — the fast path: 0 LLM, 0 asks, and the selection node is never entered" {
            runTest {
                val lattice = RecordedResolutionCore.lattice("h1-cs")
                val resolution = testDeps()
                val out = walk(turn("Zobraz tržby podle prodejen", lattice), deps(resolution))

                val answered = out.turnEnd.shouldBeInstanceOf<TurnEnd.Answered>()
                answered.llmInvocations shouldBe 0
                answered.asks shouldBe 0
                out.outcome shouldBe TurnOutcome.EXECUTED
                resolution.selection.entries shouldBe 0
                // contracts §6's provenance — the RV-39 layer tuple, carried onto the answer.
                answered.envelope.provenance.shouldNotBeNull()
            }
        }

        "H5 — measure-as-subject composes; the mention fills BOTH slots" {
            runTest {
                val lattice = RecordedResolutionCore.lattice("h5-cs")
                val resolution = testDeps()
                val out = walk(turn("Ukaž vývoj tržeb a porovnej s plánem", lattice), deps(resolution))

                val answered = out.turnEnd.shouldBeInstanceOf<TurnEnd.Answered>()
                // Whatever h5 binds, the invariant is the same one: a mention carrying SUBJECT
                // and MEASURE lands in both, and the composition is answerable.
                answered.question.isAnswerable shouldBe true
                resolution.selection.entries shouldBe 0
            }
        }

        "H2 — the ladder exhausts, the turn ASKS, and it pauses on a snapshot" {
            runTest {
                val lattice = RecordedResolutionCore.lattice("h2-cs")
                // Zero-rung config: nothing may climb, so the load-bearing G1 survives.
                val resolution = testDeps(ladder = LadderConfig.parse(openLadderYaml()), gate = inertGate())
                val out =
                    walk(
                        turn("Zobraz prvních 10 čerpacích stanic v Praze podle tržby za 12 měsíců", lattice),
                        deps(resolution),
                    )

                val paused = out.turnEnd.shouldBeInstanceOf<TurnEnd.Paused>()
                out.outcome shouldBe TurnOutcome.CLARIFY
                paused.ask.gap.span.text shouldBe "čerpacích stanic"
                paused.ask.escape shouldBe "none of these"
                paused.ladder.hitlRounds shouldBe 1
                resolution.snapshots.get(paused.ask.snapshotId).conversationId shouldBe "conv-7"
                // Asking is not falling through.
                resolution.selection.entries shouldBe 0
            }
        }

        "an RCA on H1's own lattice enters selection exactly once and refuses by name" {
            runTest {
                val resolution = testDeps()
                val out =
                    walk(
                        turn(
                            "Proč klesly tržby podle prodejen?",
                            RecordedResolutionCore.lattice("h1-cs"),
                            Themis.IntentKind.RCA,
                        ),
                        deps(resolution),
                    )

                val refused = out.turnEnd.shouldBeInstanceOf<TurnEnd.Refused>()
                refused.refusal.fallThrough shouldBe FallThroughReason.NOT_DATA_QUERY
                out.outcome shouldBe TurnOutcome.FAILED
                resolution.selection.entries shouldBe 1
            }
        }

        // --------------------------------------------------- additive, and inert unless wired

        "no lattice ⇒ `assessGaps` is a no-op and the LEGACY chain is what runs" {
            runTest {
                val out = assessGapsNode(turn("Zobraz tržby", lattice = null), deps(testDeps()))
                out.assessed.shouldBeNull()
                out.turnEnd.shouldBeNull()
            }
        }

        "no resolution deps ⇒ the RV nodes are inert even with a lattice" {
            runTest {
                val bare =
                    GolemGraphDeps(
                        composer = mockk(relaxed = true),
                        validator = mockk(relaxed = true),
                        miniPlanExecutor = mockk(relaxed = true),
                        promptExecutor = mockk(relaxed = true),
                    )
                val out = assessGapsNode(turn("Zobraz tržby", RecordedResolutionCore.lattice("h1-cs")), bare)
                out.assessed.shouldBeNull()
            }
        }

        // ------------------------------------------------------------- the conversation id

        "the conversation id comes from the request, and a missing one degrades to the correlation id" {
            val withId = turnFactsFor(turn("q", null), "CHAT_QUICK")
            withId.conversationId shouldBe "conv-7"

            val without =
                GolemTurnState(
                    request =
                        GolemRequest
                            .newBuilder()
                            .setId("turn-9")
                            .setQuestion("q")
                            .setCaller(Caller.newBuilder().setCorrelationId("corr-9"))
                            .build(),
                    userId = "u",
                    tenantId = "t",
                )
            // Degraded, and logged: an ask budget that scopes to the turn never runs out.
            turnFactsFor(without, "CHAT_QUICK").conversationId shouldBe "corr-9"
        }

        "a turn with no Themis verdict carries a null prior, not an empty Resolution" {
            // `hasResolvedIntent()` is the difference between "Themis said UNSPECIFIED" and
            // "Themis was never asked" — the vacuum the operator evidence is allowed to fill.
            turnFactsFor(turn("q", null, intent = null), "CHAT_QUICK").priorIntent.shouldBeNull()
        }

        "the Golem's own profile is CHAT_QUICK — it does not grant itself Pythia's budget" {
            val d = deps(testDeps())
            d.profileName(turn("q", null)) shouldBe "CHAT_QUICK"
        }

        "the strategy builds with the four RV nodes wired" {
            // Cheap, and it is the only thing that checks the EDGES compile into a graph.
            val strategy = buildGolemGraph(deps(testDeps()))
            strategy.name shouldBe "golem"
        }
    })
