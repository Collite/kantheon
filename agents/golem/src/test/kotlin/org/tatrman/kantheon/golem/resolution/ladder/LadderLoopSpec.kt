package org.tatrman.kantheon.golem.resolution.ladder

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import org.tatrman.kantheon.golem.resolution.ResolutionCoreException
import org.tatrman.resolver.v1.Disposition
import org.tatrman.resolver.v1.FrameRole
import org.tatrman.resolver.v1.GapKind

/**
 * RV-P5.2 T1 — loop semantics, table-driven over synthetic lattices. Seven claims, one per
 * sub-item of the task list, plus the two structural properties the P4 review paid for.
 */
private val CONFIG = shippedLadder()

private fun state(
    gaps: List<org.tatrman.resolver.v1.GapRecord>,
    rungsRun: List<String> = emptyList(),
    llm: Int = 0,
) = LadderState(
    lattice = lattice(gaps = gaps),
    gaps = gaps,
    rungsRun = rungsRun,
    llmInvocations = llm,
)

private fun verdictFor(
    gaps: List<org.tatrman.resolver.v1.GapRecord>,
    profile: String = "CHAT_QUICK",
    rungsRun: List<String> = emptyList(),
    llm: Int = 0,
    hitl: Int = 0,
    clock: FakeClock = FakeClock(),
): Verdict =
    assess(
        gaps = gaps,
        rungsRun = rungsRun,
        llmInvocations = llm,
        hitlRounds = hitl,
        ladder = CONFIG,
        profileName = profile,
        budgets = budgetsFor(CONFIG, profile, clock),
    )

class LadderLoopSpec :
    StringSpec({

        // ---- (a) zero gaps ⇒ the loop body never runs -------------------------------

        "zero gaps emits without entering the loop — the fast-path precondition" {
            verdictFor(emptyList()) shouldBe Verdict.EMIT
        }

        "a gap the core already dispositioned is not open, so it does not hold the turn" {
            val closed =
                listOf(gap(GapKind.GAP_KIND_G1_UNBOUND, "x", disposition = Disposition.DISPOSITION_DEGRADED))
            verdictFor(closed) shouldBe Verdict.EMIT
        }

        // ---- (b) profile bounds the climb -------------------------------------------

        "a G1 under CHAT_QUICK climbs lookup then local and STOPS — capable is not in the profile" {
            val g1 = listOf(gap(GapKind.GAP_KIND_G1_UNBOUND, "stanic", mentionId = "m1"))

            eligibleRungs(g1, emptyList(), CONFIG, "CHAT_QUICK", budgetsFor(CONFIG, "CHAT_QUICK"), 0) shouldBe
                listOf("lookup", "local")
            // The same gap under DEEP gets the third rung; the policy row is identical, the
            // profile is what differs.
            eligibleRungs(g1, emptyList(), CONFIG, "INVESTIGATION_DEEP", budgetsFor(CONFIG, "INVESTIGATION_DEEP"), 0)
                .shouldContainExactly(listOf("lookup", "local", "capable"))
        }

        "the climb order is the policy TABLE's, not the order the gaps arrived in" {
            // ⛑ golem-py's P4 review: iterating the caller's set of gap kinds made the order
            // depend on hash iteration. Same trap in Kotlin, different mechanism.
            val g1 = gap(GapKind.GAP_KIND_G1_UNBOUND, "a", mentionId = "m1")
            val g4 = gap(GapKind.GAP_KIND_G4_METHOD_MISS, "b", start = 10, valueId = "v1")
            val budgets = budgetsFor(CONFIG, "INVESTIGATION_DEEP")

            val forwards = eligibleRungs(listOf(g1, g4), emptyList(), CONFIG, "INVESTIGATION_DEEP", budgets, 0)
            val backwards = eligibleRungs(listOf(g4, g1), emptyList(), CONFIG, "INVESTIGATION_DEEP", budgets, 0)

            forwards shouldBe listOf("lookup", "local", "capable")
            backwards shouldBe forwards
            eligibleRungs(setOf(g4, g1).toList(), emptyList(), CONFIG, "INVESTIGATION_DEEP", budgets, 0) shouldBe
                forwards
        }

        // ---- (c) budget exhaustion exits to the ask policy ---------------------------

        "max_llm_invocations exhausted mid-ladder exits to ask, and the rung log says so" {
            runTest {
                // CHAT_QUICK allows 2 LLM invocations; start with 2 already spent.
                val gaps = listOf(gap(GapKind.GAP_KIND_G1_UNBOUND, "stanic", mentionId = "m1"))
                val budgets = budgetsFor(CONFIG, "CHAT_QUICK")

                // `lookup` is deterministic so it stays eligible; `local` does not.
                eligibleRungs(gaps, emptyList(), CONFIG, "CHAT_QUICK", budgets, llmInvocations = 2) shouldBe
                    listOf("lookup")

                // With lookup already run too, nothing is eligible and the verdict is ASK.
                verdictFor(gaps, rungsRun = listOf("lookup", "local"), llm = 2) shouldBe Verdict.ASK
            }
        }

        "the ladder wall clock expires against the TURN, not against each node" {
            // ⛑ The P4 review's inert-budget bug: every node rebuilt its budget object, so
            // `elapsed` measured the node and `ladder_budget_ms` could never be exceeded.
            val clock = FakeClock()
            val budgets = budgetsFor(CONFIG, "CHAT_QUICK", clock)
            val gaps = listOf(gap(GapKind.GAP_KIND_G1_UNBOUND, "stanic", mentionId = "m1"))

            budgets.wallClockLeft() shouldBe true
            clock.advanceMs(5_001) // CHAT_QUICK ladder_budget_ms = 5000
            budgets.wallClockLeft() shouldBe false
            // Even `lookup`, which spends no LLM budget, is bounded by the wall clock.
            budgets.canRun("lookup", 0) shouldBe false
            eligibleRungs(gaps, emptyList(), CONFIG, "CHAT_QUICK", budgets, 0) shouldBe emptyList()
        }

        "a budget of one buys ONE invocation, not one per rung in the eligible list" {
            runTest {
                // ⛑ Re-check before each climb. Filtering once at entry let a budget of 1 pay
                // for three rungs, `emulated` among them.
                val gaps = listOf(gap(GapKind.GAP_KIND_G1_UNBOUND, "stanic", mentionId = "m1"))
                val local = ScriptedRung(RungProposal(emptyList()))
                val capable = ScriptedRung(RungProposal(emptyList()))
                val lookup = ScriptedRung(RungProposal(emptyList()))
                val budgets = budgetsFor(CONFIG, "INVESTIGATION_DEEP")

                val after =
                    climbLadder(
                        state = state(gaps).copy(llmInvocations = 5), // DEEP allows 6
                        config = CONFIG,
                        profileName = "INVESTIGATION_DEEP",
                        budgets = budgets,
                        rungs = mapOf("lookup" to lookup, "local" to local, "capable" to capable),
                        gate = ScriptedGate(gateResult()),
                    )

                lookup.calls shouldHaveSize 1 // deterministic, always affordable
                local.calls shouldHaveSize 1 // spends the last invocation
                capable.calls shouldHaveSize 0 // must not run
                after.llmInvocations shouldBe 6
                after.rungLog.map { it.action } shouldContainExactly
                    listOf("no-proposal", "no-proposal", "halt")
            }
        }

        // ---- (d) a surviving hypothesis closes its gap and the loop RECOMPUTES --------

        "a hypothesis that survives the gate closes its gap, and the next round sees updated_gaps" {
            runTest {
                val g1 = gap(GapKind.GAP_KIND_G1_UNBOUND, "stanic", mentionId = "m1")
                val g2 = gap(GapKind.GAP_KIND_G2_AMBIGUOUS, "tržby", start = 10, mentionId = "m2")
                val proposal = RungProposal(listOf(hypothesis("stanic", "md.dimension.Station")))
                // The gate closes g1 and hands back ONLY g2 — "the caller's next input, not a
                // delta to apply".
                val gate =
                    ScriptedGate(
                        gateResult(bindings = listOf(binding("md.dimension.Station")), updatedGaps = listOf(g2)),
                    )

                val after =
                    climbLadder(
                        state = state(listOf(g1, g2)),
                        config = CONFIG,
                        profileName = "CHAT_QUICK",
                        budgets = budgetsFor(CONFIG, "CHAT_QUICK"),
                        rungs = mapOf("lookup" to ScriptedRung(), "local" to ScriptedRung(proposal)),
                        gate = gate,
                    )

                after.gaps.map { it.span.text } shouldContainExactly listOf("tržby")
                after.gatedBindings.map { it.ref } shouldContainExactly listOf("md.dimension.Station")
            }
        }

        // ---- (e) refused hypotheses are consumed, not discarded ----------------------

        "a refused hypothesis lands on its gap and the rung does not re-propose it verbatim" {
            runTest {
                val g1 = gap(GapKind.GAP_KIND_G1_UNBOUND, "stanic", mentionId = "m1")
                val refused = hypothesis("stanic", "md.dimension.Store")
                val gate =
                    ScriptedGate(
                        gateResult(
                            updatedGaps = listOf(g1),
                            outcomes = listOf(HypothesisVerdict(refused, accepted = false, reason = "NO_CANDIDATE")),
                        ),
                    )

                val after =
                    climbLadder(
                        state = state(listOf(g1)),
                        config = CONFIG,
                        profileName = "CHAT_QUICK",
                        budgets = budgetsFor(CONFIG, "CHAT_QUICK"),
                        rungs =
                            mapOf(
                                "lookup" to ScriptedRung(),
                                "local" to ScriptedRung(RungProposal(listOf(refused))),
                            ),
                        gate = gate,
                    )

                after.gaps
                    .single()
                    .hypothesesTriedList
                    .map { it.ref } shouldContainExactly
                    listOf("md.dimension.Store")

                // …and the parser drops a verbatim re-proposal on the next round.
                val reproposed = parseHypotheses("stanic -> md.dimension.Store", "local", after.gaps)
                reproposed.shouldNotBeNull() shouldHaveSize 0
            }
        }

        // ---- (f) LOOKUP_FAILED retries once, then degrades ---------------------------

        "LOOKUP_FAILED is retried once and then degrades — it is never a verdict about a gap" {
            runTest {
                val g1 = gap(GapKind.GAP_KIND_G1_UNBOUND, "stanic", mentionId = "m1")
                val h = hypothesis("stanic", "md.dimension.Station")
                val failing =
                    gateResult(
                        updatedGaps = listOf(g1),
                        outcomes = listOf(HypothesisVerdict(h, accepted = false, reason = REASON_LOOKUP_FAILED)),
                    )
                val gate = ScriptedGate(failing)

                val after =
                    climbLadder(
                        state = state(listOf(g1)),
                        config = CONFIG,
                        profileName = "CHAT_QUICK",
                        budgets = budgetsFor(CONFIG, "CHAT_QUICK"),
                        rungs = mapOf("lookup" to ScriptedRung(), "local" to ScriptedRung(RungProposal(listOf(h)))),
                        gate = gate,
                    )

                gate.calls shouldBe 2 // exactly once more, because Gate is idempotent
                after.degraded shouldBe "GATE_$REASON_LOOKUP_FAILED"
                after.rungLog.map { it.action } shouldContainExactly listOf("no-proposal", "gate-failed")
                // The crucial half: the gap did NOT collect a NO_CANDIDATE-shaped refusal.
                after.gaps.single().hypothesesTriedList shouldHaveSize 0
            }
        }

        "a LOOKUP_FAILED that clears on the retry proceeds normally" {
            runTest {
                val g1 = gap(GapKind.GAP_KIND_G1_UNBOUND, "stanic", mentionId = "m1")
                val h = hypothesis("stanic", "md.dimension.Station")
                val gate =
                    ScriptedGate(
                        gateResult(
                            updatedGaps = listOf(g1),
                            outcomes = listOf(HypothesisVerdict(h, false, REASON_LOOKUP_FAILED)),
                        ),
                        gateResult(bindings = listOf(binding("md.dimension.Station")), updatedGaps = emptyList()),
                    )

                val after =
                    climbLadder(
                        state = state(listOf(g1)),
                        config = CONFIG,
                        profileName = "CHAT_QUICK",
                        budgets = budgetsFor(CONFIG, "CHAT_QUICK"),
                        rungs = mapOf("lookup" to ScriptedRung(), "local" to ScriptedRung(RungProposal(listOf(h)))),
                        gate = gate,
                    )

                gate.calls shouldBe 2
                after.degraded.shouldBeNull()
                after.gaps shouldHaveSize 0
            }
        }

        // ---- (g) monotonicity --------------------------------------------------------

        "bindings are only added across rounds, never dropped" {
            runTest {
                val g1 = gap(GapKind.GAP_KIND_G1_UNBOUND, "a", mentionId = "m1")
                val g2 = gap(GapKind.GAP_KIND_G2_AMBIGUOUS, "b", start = 5, mentionId = "m2")
                val first = binding("md.measure.one")
                val second = binding("md.measure.two")

                var current = state(listOf(g1, g2))
                current =
                    climbLadder(
                        current,
                        CONFIG,
                        "INVESTIGATION_DEEP",
                        budgetsFor(CONFIG, "INVESTIGATION_DEEP"),
                        mapOf(
                            "lookup" to ScriptedRung(),
                            "local" to ScriptedRung(RungProposal(listOf(hypothesis("a", "md.measure.one")))),
                            "capable" to ScriptedRung(),
                        ),
                        ScriptedGate(gateResult(bindings = listOf(first), updatedGaps = listOf(g2))),
                    )
                val afterFirst = current.gatedBindings.map { it.ref }

                // A second pass with the rungs reset (as a real second round would be).
                current =
                    climbLadder(
                        current.copy(rungsRun = emptyList()),
                        CONFIG,
                        "INVESTIGATION_DEEP",
                        budgetsFor(CONFIG, "INVESTIGATION_DEEP"),
                        mapOf(
                            "lookup" to ScriptedRung(),
                            "local" to ScriptedRung(RungProposal(listOf(hypothesis("b", "md.measure.two", start = 5)))),
                            "capable" to ScriptedRung(),
                        ),
                        ScriptedGate(gateResult(bindings = listOf(second), updatedGaps = emptyList())),
                    )

                current.gatedBindings.map { it.ref } shouldContainExactly (afterFirst + "md.measure.two")
                current.gaps shouldHaveSize 0
            }
        }

        // ---- termination -------------------------------------------------------------

        "a rung runs at most once per turn — otherwise a no-op lookup spins the loop forever" {
            val gaps = listOf(gap(GapKind.GAP_KIND_G1_UNBOUND, "stanic", mentionId = "m1"))
            val budgets = budgetsFor(CONFIG, "CHAT_QUICK")

            eligibleRungs(gaps, listOf("lookup"), CONFIG, "CHAT_QUICK", budgets, 0) shouldBe listOf("local")
            eligibleRungs(gaps, listOf("lookup", "local"), CONFIG, "CHAT_QUICK", budgets, 0) shouldBe emptyList()
        }

        "the zero-rung config never climbs — the SHAPE is present and nothing is on" {
            runTest {
                val open = LadderConfig.parse(openLadderYaml())
                val gaps = listOf(gap(GapKind.GAP_KIND_G1_UNBOUND, "stanic", mentionId = "m1"))
                val budgets = Budgets(open.profile("CHAT_QUICK"), 0) { 0 }

                eligibleRungs(gaps, emptyList(), open, "CHAT_QUICK", budgets, 0) shouldBe emptyList()

                val after =
                    climbLadder(
                        state(gaps),
                        open,
                        "CHAT_QUICK",
                        budgets,
                        rungs = emptyMap(),
                        gate = ScriptedGate(gateResult()),
                    )
                after.rungLog.map { it.action } shouldContainExactly listOf("noop")
                after.gaps shouldContainExactly gaps
            }
        }

        "a config that admits a rung dispatch cannot run DEGRADES the turn rather than killing it" {
            runTest {
                // ⛑ This used to assert a thrown `LadderConfigException`, and that was the bug:
                // the loop runs inside a Koog node, and an exception out of a node aborts the
                // whole strategy — so one bad config row took the user's turn down instead of
                // degrading it. The boot-time check (`ResolutionDeps.unimplementedRungs`) is
                // still the real answer; this is what happens when someone skipped it.
                val after =
                    climbLadder(
                        state(listOf(gap(GapKind.GAP_KIND_G1_UNBOUND, "x", mentionId = "m1"))),
                        CONFIG,
                        "CHAT_QUICK",
                        budgetsFor(CONFIG, "CHAT_QUICK"),
                        rungs = emptyMap(), // the config admits lookup + local
                        gate = ScriptedGate(gateResult()),
                    )
                after.degraded shouldBe "RUNG_MISSING_lookup"
                after.rungLog.map { it.action } shouldContainExactly listOf("rung-missing")
                withClue("a rung that could not run has not run — it must not count as tried") {
                    after.rungsRun shouldBe emptyList()
                }
            }
        }

        "a degraded pass reaches the caller as ASK, not as a barren-pass CLIMB" {
            runTest {
                // The degrade check has to come BEFORE the barren-pass check in `runLadderLoop`:
                // a pass that died on its first rung also ran no rung, and reporting that as
                // "nothing left to climb" would lose the reason.
                val outcome =
                    runLadderLoop(
                        initial = state(listOf(gap(GapKind.GAP_KIND_G1_UNBOUND, "x", mentionId = "m1"))),
                        config = CONFIG,
                        profileName = "CHAT_QUICK",
                        budgets = budgetsFor(CONFIG, "CHAT_QUICK"),
                        rungs = emptyMap(),
                        gate = ScriptedGate(gateResult()),
                    )
                outcome.verdict shouldBe Verdict.ASK
                outcome.state.degraded shouldBe "RUNG_MISSING_lookup"
            }
        }

        "the loop NEVER hands CLIMB back — a wall clock expiring mid-pass settles the verdict" {
            runTest {
                // ⛑ The regression this exists for. `assess` and `climbLadder` read the same
                // `eligibleRungs` off the same state, so they can only disagree through the
                // WALL CLOCK — and a turn that takes longer than `ladder_budget_ms: 5000` makes
                // them disagree by construction. The pass then runs no rung while the verdict
                // still says CLIMB, and CLIMB used to escape to `error()` in
                // `runResolutionTurn` and to a graph with no CLIMB edge.
                //
                // The clock advances on every observation, which is what a slow turn does. It
                // is in budget while `assess` looks and out of budget by the time the climb
                // does — no ordering trickery, just time passing.
                var reads = 0L
                val steppingClock = { (reads++ * 2_500L) * 1_000_000 }
                val outcome =
                    runLadderLoop(
                        initial = state(listOf(gap(GapKind.GAP_KIND_G1_UNBOUND, "x", mentionId = "m1"))),
                        config = CONFIG,
                        profileName = "CHAT_QUICK",
                        budgets =
                            Budgets(
                                CONFIG.profile("CHAT_QUICK"),
                                turnStartedAtNanos = 0,
                                clockNanos = steppingClock,
                            ),
                        rungs = mapOf("lookup" to LookupRung(), "local" to ScriptedRung()),
                        gate = ScriptedGate(gateResult()),
                    )
                withClue("a verdict the caller has no branch for is the bug, whatever else is true") {
                    outcome.verdict shouldNotBe Verdict.CLIMB
                }
                withClue("the re-assess settles it: the gap is load-bearing and the ask budget is unspent") {
                    outcome.verdict shouldBe Verdict.ASK
                }
                withClue("the barren pass is recorded, not silent") {
                    outcome.state.rungLog.map { it.action } shouldContainExactly listOf("noop")
                }
            }
        }

        // ------------------------------------------------------------------- the rungs

        "cancelling the turn cancels the rung — it is not recorded as a rung failure" {
            runTest {
                // ⛑ `CancellationException` IS an `Exception`, so the catch-all used to swallow
                // the turn being cancelled and hand back "rung 'local' failed" — after which
                // the ladder kept climbing inside a dead scope. Cancellation is the caller's
                // decision, not a proposal about a gap.
                val rung =
                    LlmRung("local", RungLlm { _, _, _ -> throw kotlinx.coroutines.CancellationException("turn gone") })
                shouldThrow<kotlinx.coroutines.CancellationException> {
                    rung.propose(
                        state(listOf(gap(GapKind.GAP_KIND_G1_UNBOUND, "x", mentionId = "m1"))),
                        listOf(gap(GapKind.GAP_KIND_G1_UNBOUND, "x", mentionId = "m1")),
                        CONFIG,
                    )
                }
            }
        }

        "an ordinary rung failure is still a proposal-less rung, not a thrown turn" {
            runTest {
                val rung = LlmRung("local", RungLlm { _, _, _ -> error("gateway said no") })
                val proposal =
                    rung.propose(
                        state(listOf(gap(GapKind.GAP_KIND_G1_UNBOUND, "x", mentionId = "m1"))),
                        listOf(gap(GapKind.GAP_KIND_G1_UNBOUND, "x", mentionId = "m1")),
                        CONFIG,
                    )
                proposal.hypotheses.shouldHaveSize(0)
                proposal.failure!! shouldContain "gateway said no"
            }
        }

        "`lookup` finds an anchor even when two mentions carry the same ref" {
            runTest {
                // ⛑ The anchor set was built with `firstOrNull { it.ref == binding.ref }`, so an
                // entity the question named TWICE reported the first mention as newly anchored
                // whether or not that was the one the gate bound — and a value gap hanging off
                // the second was never re-offered. Matched on the whole binding, across every
                // mention, both are anchors.
                val ref = "md.dimension.Station"
                val latticeWithTwins =
                    lattice(
                        mentions =
                            listOf(
                                mention("m1", "stanic", 5, refs = listOf(ref)),
                                mention("m2", "stanic", 40, refs = listOf(ref)),
                            ),
                        values = listOf(value("v1", "501001", 50, anchorMentionId = "m2")),
                    )
                val gaps = listOf(gap(GapKind.GAP_KIND_G3_UNATTRIBUTED, "501001", 50, valueId = "v1"))
                val withGate =
                    LadderState(
                        lattice = latticeWithTwins,
                        gaps = gaps,
                        gatedBindings = listOf(binding(ref)),
                    )

                val proposal = LookupRung().propose(withGate, gaps, CONFIG)

                withClue("the value's anchor is the SECOND mention — the one a first-match misses") {
                    proposal.hypotheses.shouldHaveSize(1)
                }
                proposal.hypotheses
                    .single()
                    .span.text shouldBe "501001"
            }
        }

        "the terminal fallback is the profile's own posture, not a hardcoded refusal" {
            // The last resort inside `runLadderLoop` when even a re-assess says CLIMB. Reached
            // only by a clock that flaps, so it is asserted directly rather than staged.
            terminalVerdict(CONFIG, "CHAT_QUICK") shouldBe Verdict.REFUSE
            val bestEffort =
                LadderConfig.parse(
                    openLadderYaml().replace(
                        "    terminal: strict\n  INVESTIGATION_DEEP:",
                        "    terminal: human_profiles\n  INVESTIGATION_DEEP:",
                    ),
                )
            terminalVerdict(bestEffort, "CHAT_QUICK") shouldBe Verdict.EMIT
        }

        // ---- the ask/emit/refuse boundary --------------------------------------------

        "a load-bearing gap blocks; a non-load-bearing one is CARRIED — H5's whole shape" {
            // H5: `plánem` is an honest G1 in FILTER position. Refusing the question over it
            // would throw away four correct bindings to protect one.
            val filterGap =
                listOf(
                    gap(
                        GapKind.GAP_KIND_G1_UNBOUND,
                        "plánem",
                        roles = listOf(FrameRole.FRAME_ROLE_FILTER),
                        mentionId = "m6",
                    ),
                )
            verdictFor(filterGap, rungsRun = listOf("lookup", "local"), llm = 2) shouldBe Verdict.EMIT
            carryableGaps(filterGap, CONFIG) shouldHaveSize 1
            blockingGaps(filterGap, CONFIG) shouldHaveSize 0

            val subjectGap =
                listOf(
                    gap(
                        GapKind.GAP_KIND_G1_UNBOUND,
                        "stanic",
                        roles = listOf(FrameRole.FRAME_ROLE_SUBJECT),
                        mentionId = "m3",
                    ),
                )
            verdictFor(subjectGap, rungsRun = listOf("lookup", "local"), llm = 2) shouldBe Verdict.ASK
            verdictFor(subjectGap, rungsRun = listOf("lookup", "local"), llm = 2, hitl = 1) shouldBe Verdict.REFUSE
        }

        "an unroled VALUE gap is not load-bearing, an unroled MENTION gap is" {
            // Without this split H2 spends its single question on `Praze`.
            val praze = listOf(gap(GapKind.GAP_KIND_G3_UNATTRIBUTED, "Praze", valueId = "v2"))
            askableGaps(praze, CONFIG) shouldHaveSize 0

            val unroledMention = listOf(gap(GapKind.GAP_KIND_G1_UNBOUND, "cosi", mentionId = "m9"))
            askableGaps(unroledMention, CONFIG) shouldHaveSize 1
        }

        "G5_NLP_DARK never blocks, even load-bearing — the banner IS the answer" {
            val dark =
                listOf(
                    gap(
                        GapKind.GAP_KIND_G5_NLP_DARK,
                        "cokoliv",
                        roles = listOf(FrameRole.FRAME_ROLE_SUBJECT),
                        mentionId = "m1",
                    ),
                )
            blockingGaps(dark, CONFIG) shouldHaveSize 0
            askableGaps(dark, CONFIG) shouldHaveSize 0
            // `emulated` is the only rung G5 admits, and CHAT_QUICK does not allow it.
            verdictFor(dark, profile = "CHAT_QUICK") shouldBe Verdict.EMIT
        }

        "the single ask is spent on the load-bearing gap, whatever span order says" {
            val gaps =
                listOf(
                    gap(GapKind.GAP_KIND_G6_INCOHERENT, "early", start = 0),
                    gap(
                        GapKind.GAP_KIND_G1_UNBOUND,
                        "later",
                        start = 50,
                        roles = listOf(FrameRole.FRAME_ROLE_SUBJECT),
                        mentionId = "m2",
                    ),
                )
            askableGaps(gaps, CONFIG).first().span.text shouldBe "later"
        }

        "a gate outage degrades the ladder without pretending the hypothesis was refused" {
            runTest {
                val g1 = gap(GapKind.GAP_KIND_G1_UNBOUND, "stanic", mentionId = "m1")
                val h = hypothesis("stanic", "md.dimension.Station")
                val gate =
                    GateCall { _, _ -> throw ResolutionCoreException("UNAVAILABLE", "resolver down") }

                val after =
                    climbLadder(
                        state(listOf(g1)),
                        CONFIG,
                        "CHAT_QUICK",
                        budgetsFor(CONFIG, "CHAT_QUICK"),
                        mapOf("lookup" to ScriptedRung(), "local" to ScriptedRung(RungProposal(listOf(h)))),
                        gate,
                    )

                after.degraded shouldBe "GATE_UNAVAILABLE"
                after.gaps.single().hypothesesTriedList shouldHaveSize 0
            }
        }
    })
