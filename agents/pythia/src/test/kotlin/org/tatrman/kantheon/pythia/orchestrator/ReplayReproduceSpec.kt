package org.tatrman.kantheon.pythia.orchestrator

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import org.tatrman.kantheon.pythia.events.EventEmitter
import org.tatrman.kantheon.pythia.persistence.Checkpointer
import org.tatrman.kantheon.pythia.persistence.InMemoryCheckpointRepository
import org.tatrman.kantheon.pythia.persistence.InMemoryEventRepository
import org.tatrman.kantheon.pythia.persistence.InMemoryInvestigationRepository
import org.tatrman.kantheon.pythia.persistence.InvestigationRepository
import org.tatrman.kantheon.pythia.plan.CapabilityChecker
import org.tatrman.kantheon.pythia.plan.PlanComposer
import org.tatrman.kantheon.pythia.plan.PlanValidator
import org.tatrman.kantheon.pythia.plan.Planner
import org.tatrman.kantheon.pythia.plan.ScriptedPromptExecutor
import org.tatrman.kantheon.pythia.resolve.Resolver
import org.tatrman.kantheon.pythia.resolve.ThemisClient
import org.tatrman.kantheon.pythia.v1.Caller
import org.tatrman.kantheon.pythia.v1.Investigation
import org.tatrman.kantheon.pythia.v1.InvestigationContext
import org.tatrman.kantheon.pythia.v1.Status
import org.tatrman.kantheon.themis.v1.Themis
import java.util.UUID

private const val TRIVIAL_PLAN =
    """{ "rationale":"r", "hypotheses":[{"id":"H0","statement":"x","displayPriority":"HIDDEN"}],
        "nodes":[{"nodeId":"N1","testsHypIds":["H0"],"kind":"query","queryRef":"q","paramsJson":"{}"}], "edges":[] }"""

/** Themis whose resolved args reflect a call counter — so replay re-resolution is observable. */
private class CountingThemis : ThemisClient {
    var calls = 0
    var lastBearer: String? = null

    override suspend fun understand(
        request: Themis.ResolveRequest,
        bearer: String,
    ): Themis.ResolveResponse {
        calls++
        lastBearer = bearer
        return Themis.ResolveResponse
            .newBuilder()
            .setResolution(
                Themis.Resolution
                    .newBuilder()
                    .setIntentKind(
                        Themis.IntentKind.PROCEDURAL,
                    ).setArgsJson("""{"run":$calls}"""),
            ).build()
    }
}

/**
 * Stage 3.3 T1/T2 — replay re-resolves (fresh params) while reproduce freezes the
 * parent's resolved params; both set `parent_id` (lineage).
 */
class ReplayReproduceSpec :
    StringSpec({

        "replay re-resolves with fresh params; reproduce freezes the parent's; both link parent_id" {
            runTest {
                val emitter = EventEmitter(InMemoryEventRepository(), RecordingNatsPublisher())
                val investigations: InvestigationRepository = InMemoryInvestigationRepository()
                val themis = CountingThemis()
                val orchestrator =
                    InvestigationOrchestrator(
                        investigations,
                        Checkpointer(InMemoryCheckpointRepository(), investigations),
                        emitter,
                        this,
                        awaitingTtlHours = 24,
                        resolver = Resolver(themis),
                        planner =
                            Planner(
                                PlanComposer(
                                    ScriptedPromptExecutor(listOf(TRIVIAL_PLAN)),
                                ),
                                PlanValidator(
                                    CapabilityChecker {
                                        true
                                    },
                                ),
                            ),
                        // no executionEngine → execute/synth stubs (we only care about resolution lineage here)
                    )

                val parent = orchestrator.submit(request(), "tok")
                orchestrator.awaitTerminal(parent) shouldBe Status.STATUS_DONE
                investigations.findById(parent)!!.resolutionJson!! shouldContain ":1}"

                // replay → re-resolves (run 2), different params, parent_id set
                val replayId = orchestrator.replay(parent, null)!!
                orchestrator.awaitTerminal(replayId) shouldBe Status.STATUS_DONE
                val replayRec = investigations.findById(replayId)!!
                replayRec.parentId shouldBe parent
                replayRec.resolutionJson!! shouldContain ":2}"

                // reproduce → frozen params (run 1, the parent's), resolver NOT called again
                val callsBefore = themis.calls
                val reproId = orchestrator.reproduce(parent)!!
                orchestrator.awaitTerminal(reproId) shouldBe Status.STATUS_DONE
                val reproRec = investigations.findById(reproId)!!
                reproRec.parentId shouldBe parent
                reproRec.resolutionJson!! shouldContain ":1}" // frozen from the parent
                themis.calls shouldBe callsBefore // resolver skipped on reproduce
                reproRec.warningsJson shouldContain "re-materialising from frozen"
            }
        }

        "replay runs under the caller's live bearer, not the parent's (PD-8)" {
            runTest {
                val emitter = EventEmitter(InMemoryEventRepository(), RecordingNatsPublisher())
                val investigations: InvestigationRepository = InMemoryInvestigationRepository()
                val themis = CountingThemis()
                val orchestrator =
                    InvestigationOrchestrator(
                        investigations,
                        Checkpointer(InMemoryCheckpointRepository(), investigations),
                        emitter,
                        this,
                        awaitingTtlHours = 24,
                        resolver = Resolver(themis),
                        planner =
                            Planner(
                                PlanComposer(ScriptedPromptExecutor(listOf(TRIVIAL_PLAN))),
                                PlanValidator(CapabilityChecker { true }),
                            ),
                    )

                val parent = orchestrator.submit(request(), "parent-token")
                orchestrator.awaitTerminal(parent) shouldBe Status.STATUS_DONE

                // Replay carries the *caller's* fresh token through to the re-resolution.
                val replayId = orchestrator.replay(parent, null, "caller-fresh-token")!!
                orchestrator.awaitTerminal(replayId) shouldBe Status.STATUS_DONE
                themis.lastBearer shouldBe "caller-fresh-token"
            }
        }
    })

private fun request(): Investigation =
    Investigation
        .newBuilder()
        .setId(UUID.randomUUID().toString())
        .setQuestion("returns over the last quarter")
        .setCaller(Caller.newBuilder().setKind(Caller.Kind.IRIS).setUserId("u1"))
        .setContext(InvestigationContext.newBuilder().setLocale("en"))
        .build()
