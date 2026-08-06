package org.tatrman.kantheon.golem.graph

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.tatrman.kantheon.golem.resolution.RecordedResolutionCore
import org.tatrman.kantheon.golem.resolution.ResolutionCoreClient
import org.tatrman.kantheon.golem.v1.Caller
import org.tatrman.kantheon.golem.v1.GolemContext
import org.tatrman.kantheon.golem.v1.GolemRequest

/**
 * RV-P5.1 T3/T5 — the node inside the graph.
 *
 * Under ruling (A) this stage is **additive**, and the load-bearing claim is not "the new
 * node works" but "**everything else is untouched**". Both directions are asserted: an
 * estate with no resolver wired behaves exactly as it did at Stage 2.4, and an estate with
 * one gets the lattice on its turn without any other field moving.
 */
private fun request(correlationId: String = "conv-1"): GolemRequest =
    GolemRequest
        .newBuilder()
        .setId("turn-1")
        .setQuestion("Zobraz náklady účtu 501001 v roce 2025 podle období")
        .setCaller(Caller.newBuilder().setCorrelationId(correlationId))
        .setContext(GolemContext.newBuilder().setLocale("cs"))
        .build()

private fun state() = GolemTurnState(request = request(), userId = "u1", tenantId = "hartland")

/** Deps with only the resolution seam populated — the rest are never reached by this node. */
private fun deps(core: ResolutionCoreClient?) =
    GolemGraphDeps(
        composer = mockk(),
        validator = mockk(),
        miniPlanExecutor = mockk(),
        promptExecutor = mockk(),
        resolutionCore = core,
        referenceDatetime = { "2026-08-06T00:00:00Z" },
    )

class CallResolutionCoreGraphSpec :
    StringSpec({

        "with no core wired the node is a no-op — no lattice, no degrade, nothing else moved" {
            runTest {
                val before = state()

                // The node body the strategy runs, with `deps.resolutionCore` left at its
                // default. This is the shape EVERY estate is on today, so it is the one that
                // has to be provably free.
                val after = callResolutionCoreNode(before, deps(core = null))

                after.lattice.shouldBeNull()
                after.coreDegrade.shouldBeNull()
                after.resolutionProvenance.shouldBeNull()
                after shouldBe before
            }
        }

        "an unwired core makes no call at all — not a call that is discarded" {
            runTest {
                val client = RecordedResolutionCore.client("h1-cs")

                callResolutionCoreNode(state(), deps(core = null))

                // The point of the gate: no gRPC hop, so no timeout where no resolver runs.
                client.requests.size shouldBe 0
            }
        }

        "a wired core runs through the same node body the strategy uses" {
            runTest {
                val client = RecordedResolutionCore.client("h1-cs")

                val after = callResolutionCoreNode(state(), deps(core = client))

                client.requests.size shouldBe 1
                after.lattice.shouldNotBeNull().mentionsCount shouldBe 5
            }
        }

        "with a core wired the lattice lands and NOTHING else on the turn changes" {
            runTest {
                val before = state()

                val after =
                    callResolutionCoreNodeStep(
                        state = before,
                        client = RecordedResolutionCore.client("h1-cs"),
                        referenceDatetime = "2026-08-06T00:00:00Z",
                    )

                after.lattice.shouldNotBeNull().mentionsCount shouldBe 5
                after.resolutionProvenance.shouldNotBeNull().lexiconArtifactHash shouldBe "sha256:h1-lexicon"
                after.coreDegrade.shouldBeNull()

                // The additive claim, mechanically: strip the four new fields and the turn is
                // byte-identical to the one that went in. A later stage that quietly starts
                // mutating the plan or the selection from here fails this.
                after.copy(
                    lattice = null,
                    resolutionProvenance = null,
                    resolutionCapabilities = null,
                    coreDegrade = null,
                ) shouldBe before
            }
        }

        "the core sees the caller's correlation id as the conversation, and the question verbatim" {
            runTest {
                val client = RecordedResolutionCore.client("h1-cs")

                callResolutionCoreNodeStep(state(), client, "2026-08-06T00:00:00Z")

                val sent = client.requests.single()
                sent.conversationId shouldBe "conv-1"
                sent.fresh.text shouldBe request().question
                sent.fresh.locale shouldBe "cs"
                sent.context.tenant shouldBe "hartland"
                sent.callerSubject shouldBe "u1"
                // RS-24 — one channel. A per-request registry override here would be a second.
                sent.hasRegistry() shouldBe false
            }
        }

        "a request with no correlation id falls back to the turn id rather than sending blank" {
            runTest {
                val client = RecordedResolutionCore.client("h1-cs")
                val blank = GolemTurnState(request = request(correlationId = ""), userId = "u1", tenantId = "hartland")

                callResolutionCoreNodeStep(blank, client, "2026-08-06T00:00:00Z")

                client.requests.single().conversationId shouldBe "turn-1"
            }
        }

        "a core that throws anything other than ResolutionCoreException still escapes — deliberately" {
            runTest {
                // The degrade posture covers DOOR failures, which are expected and operational.
                // A programming error (NPE, IllegalState) is not one, and swallowing it here
                // would turn a bug into a silently empty lattice. The Koog strategy's own
                // error boundary is the right place for that, not this node.
                val broken = mockk<ResolutionCoreClient>()
                coEvery { broken.resolve(any()) } throws IllegalStateException("bug")

                try {
                    callResolutionCoreNodeStep(state(), broken, "2026-08-06T00:00:00Z")
                    error("expected the IllegalStateException to escape")
                } catch (e: IllegalStateException) {
                    e.message shouldBe "bug"
                }
            }
        }
    })
