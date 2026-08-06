package org.tatrman.kantheon.golem.resolution

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.tatrman.kantheon.golem.resolution.compose.FallThroughReason
import org.tatrman.kantheon.golem.resolution.compose.RefusalCode
import org.tatrman.kantheon.golem.resolution.ladder.LadderConfig
import org.tatrman.kantheon.golem.resolution.ladder.gap
import org.tatrman.kantheon.golem.resolution.ladder.lattice
import org.tatrman.kantheon.golem.resolution.ladder.mention
import org.tatrman.kantheon.golem.resolution.ladder.openLadderYaml
import org.tatrman.kantheon.golem.resolution.ladder.span
import org.tatrman.kantheon.golem.resolution.skills.LayeredSkillLibrary
import org.tatrman.kantheon.themis.v1.Themis
import org.tatrman.resolver.v1.Binding
import org.tatrman.resolver.v1.Disposition
import org.tatrman.resolver.v1.EvidenceClass
import org.tatrman.resolver.v1.FrameRole
import org.tatrman.resolver.v1.GapKind
import org.tatrman.resolver.v1.Mention
import org.tatrman.resolver.v1.ResolutionState
import org.tatrman.resolver.v1.TargetClass

/**
 * RV-P5.3 T2 — the γ predicate and the residue.
 *
 * Both conjuncts get their own falsification, because either one alone is a plausible-looking
 * predicate that ships a wrong answer: intent-only sends a gapped question to the door,
 * gap-free-only sends *"Proč klesly tržby?"* down a path that can only select rows.
 */
private fun opMention(
    id: String,
    text: String,
    op: String,
    start: Int,
): Mention =
    Mention
        .newBuilder()
        .setId(id)
        .setSpan(span(text, start))
        .setLemma(text)
        .addBindings(
            Binding
                .newBuilder()
                .setRef(op)
                .setTargetClass(TargetClass.TARGET_CLASS_OPERATOR)
                .setEvidenceClass(EvidenceClass.EVIDENCE_CLASS_EXACT),
        ).build()

/** *"Zobraz tržby podle prodejen"* — gap-free, one operator, a measure and a grouping. */
private fun coveredLattice(): ResolutionState =
    lattice(
        mentions =
            listOf(
                opMention("m1", "Zobraz", "op:show", 0),
                Mention
                    .newBuilder()
                    .setId("m2")
                    .setSpan(span("tržby", 7))
                    .addAllFrameRoles(listOf(FrameRole.FRAME_ROLE_SUBJECT, FrameRole.FRAME_ROLE_MEASURE))
                    .addBindings(
                        Binding
                            .newBuilder()
                            .setRef("md.measure.revenue")
                            .setTargetClass(TargetClass.TARGET_CLASS_MODEL_OBJECT)
                            .setEvidenceClass(EvidenceClass.EVIDENCE_CLASS_EXACT),
                    ).build(),
                Mention
                    .newBuilder()
                    .setId("m3")
                    .setSpan(span("prodejen", 19))
                    .addFrameRoles(FrameRole.FRAME_ROLE_GROUPING)
                    .addBindings(
                        Binding
                            .newBuilder()
                            .setRef("md.dimension.Store")
                            .setTargetClass(TargetClass.TARGET_CLASS_MODEL_OBJECT)
                            .setEvidenceClass(EvidenceClass.EVIDENCE_CLASS_EXACT),
                    ).build(),
            ),
    )

class FastPathSpec :
    StringSpec({

        "H1: DATA_QUERY ∧ gap-free ⇒ composed, answered, 0 LLM, 0 asks, selection never entered" {
            runTest {
                val deps = testDeps()
                val end = runResolutionTurn(coveredLattice(), facts(), deps)

                val answered = end.shouldBeInstanceOf<TurnEnd.Answered>()
                answered.llmInvocations shouldBe 0
                answered.asks shouldBe 0
                answered.question.measures shouldContainExactly listOf("md.measure.revenue")
                answered.question.operators shouldContainExactly listOf("op:show")
                answered.envelope.content shouldContain "md.measure.revenue"
                // The load-bearing assertion of the whole task: the fast path reaches the door
                // WITHOUT passing through selection.
                deps.selection.entries shouldBe 0
            }
        }

        "the envelope carries contracts §6's four parts" {
            runTest {
                val end = runResolutionTurn(coveredLattice(), facts(), testDeps())
                val e = end.shouldBeInstanceOf<TurnEnd.Answered>().envelope

                e.content shouldContain "md.measure.revenue"
                e.formattingDirectives.keys shouldContainExactly listOf("op:show")
                // provenance = the RV-39 layer tuple + S-1, which is the P5.1 T2(d) delta.
                e.provenance.shouldNotBeNull()
                e.gapsCarried shouldBe emptyList()
            }
        }

        // ------------------------------------------------------- conjunct 1: the intent

        "an RCA over the SAME gap-free lattice falls through to selection" {
            runTest {
                val deps = testDeps()
                val end = runResolutionTurn(coveredLattice(), facts(intent = Themis.IntentKind.RCA), deps)

                val refused = end.shouldBeInstanceOf<TurnEnd.Refused>()
                refused.refusal.code shouldBe RefusalCode.NO_CAPABLE_PLUGIN
                refused.refusal.fallThrough shouldBe FallThroughReason.NOT_DATA_QUERY
                // What it CAN still do OF THIS QUESTION — a capability statement, not an apology.
                //
                // ⛑ Corrected at RV-P5.4 T2. This used to expect the whole five-operator
                // catalogue, because the stub answered with `library.known()`: a refusal that
                // replied to a question nobody asked. H4 is what settled it — its
                // `composable_residue: []` is empty precisely because the question named no
                // operator we hold, and a catalogue can never be empty.
                refused.refusal.composableResidue shouldContainExactly listOf("op:show")
                deps.selection.entries shouldBe 1
            }
        }

        "no intent and no operator evidence also falls through — UNKNOWN is not DATA_QUERY" {
            runTest {
                val bare =
                    lattice(mentions = listOf(mention("m1", "tržby", refs = listOf("md.measure.revenue"))))
                val deps = testDeps()
                val end = runResolutionTurn(bare, facts(intent = null), deps)

                end.shouldBeInstanceOf<TurnEnd.Refused>().refusal.fallThrough shouldBe FallThroughReason.NOT_DATA_QUERY
            }
        }

        "…but the SAME bare lattice with an operator annotation is DATA_QUERY and composes" {
            runTest {
                // The T1 evidence rule, reaching all the way to an answer.
                val withOp =
                    lattice(
                        mentions =
                            listOf(
                                opMention("m1", "Zobraz", "op:show", 0),
                                Mention
                                    .newBuilder()
                                    .setId("m2")
                                    .setSpan(span("tržby", 7))
                                    .addFrameRoles(FrameRole.FRAME_ROLE_MEASURE)
                                    .addBindings(
                                        Binding
                                            .newBuilder()
                                            .setRef("md.measure.revenue")
                                            .setTargetClass(TargetClass.TARGET_CLASS_MODEL_OBJECT),
                                    ).build(),
                            ),
                    )
                runResolutionTurn(withOp, facts(intent = null), testDeps())
                    .shouldBeInstanceOf<TurnEnd.Answered>()
            }
        }

        // -------------------------------------------------------- conjunct 2: the gaps

        "a DATA_QUERY with an open gap does NOT reach the door" {
            runTest {
                val gapped =
                    lattice(
                        mentions = coveredLattice().mentionsList,
                        gaps = listOf(gap(GapKind.GAP_KIND_G1_UNBOUND, "čerpacích stanic", 21, mentionId = "m4")),
                    )
                // Zero-rung config so the ladder cannot close it; CHAT_QUICK has an ask left,
                // so the turn PAUSES rather than refusing — which is the right answer, and is
                // still "did not reach the door".
                val deps = testDeps(ladder = LadderConfig.parse(openLadderYaml()))
                val end = runResolutionTurn(gapped, facts(), deps)

                end.shouldBeInstanceOf<TurnEnd.Paused>()
                deps.selection.entries shouldBe 0
            }
        }

        "a gap the loop already SETTLED does not keep the fast path shut" {
            runTest {
                // USER_CONFIRMED_UNKNOWN is present in the lattice — it is the record of what
                // happened — but it is not OPEN. Treating presence as openness would make the
                // fast path unreachable on any turn that ever had a gap.
                val settled =
                    lattice(
                        mentions = coveredLattice().mentionsList,
                        gaps =
                            listOf(
                                gap(
                                    GapKind.GAP_KIND_G3_UNATTRIBUTED,
                                    "Praze",
                                    40,
                                    disposition = Disposition.DISPOSITION_USER_CONFIRMED_UNKNOWN,
                                ),
                            ),
                    )
                runResolutionTurn(settled, facts(), testDeps()).shouldBeInstanceOf<TurnEnd.Answered>()
            }
        }

        // ------------------------------------------------------------------- the residue

        "compose refusing after γ fired is its own fall-through reason" {
            runTest {
                // An `op:` the estate has no body for. γ fired (DATA_QUERY, gap-free) and the
                // composition still could not be honoured — a different fact about the turn
                // than "this was never a data query", and typed as one.
                val unknownOp =
                    lattice(
                        mentions =
                            listOf(
                                opMention("m1", "zkoumej", "op:investigate", 0),
                                Mention
                                    .newBuilder()
                                    .setId("m2")
                                    .setSpan(span("tržby", 8))
                                    .addFrameRoles(FrameRole.FRAME_ROLE_MEASURE)
                                    .addBindings(
                                        Binding
                                            .newBuilder()
                                            .setRef("md.measure.revenue")
                                            .setTargetClass(TargetClass.TARGET_CLASS_MODEL_OBJECT),
                                    ).build(),
                            ),
                    )
                val deps = testDeps()
                val refused =
                    runResolutionTurn(unknownOp, facts(), deps).shouldBeInstanceOf<TurnEnd.Refused>()

                refused.refusal.fallThrough shouldBe FallThroughReason.COMPOSE_REFUSED
                refused.refusal.explanation shouldContain "op:investigate"
                deps.selection.entries shouldBe 1
            }
        }

        "the stub is entered exactly ONCE per fall-through, not once per node" {
            runTest {
                val deps = testDeps()
                runResolutionTurn(coveredLattice(), facts(intent = Themis.IntentKind.FORECAST), deps)
                deps.selection.entries shouldBe 1
            }
        }

        "no lattice ⇒ NoResolution, and the legacy chain is what runs" {
            runTest {
                val deps = testDeps()
                runResolutionTurn(null, facts(), deps).shouldBeInstanceOf<TurnEnd.NoResolution>()
                deps.selection.entries shouldBe 0
            }
        }

        "no query door wired ⇒ the composition still happens, and the content is empty rather than invented" {
            runTest {
                val end = runResolutionTurn(coveredLattice(), facts(), testDeps(door = null))
                val answered = end.shouldBeInstanceOf<TurnEnd.Answered>()
                answered.envelope.content shouldBe ""
                answered.question.measures shouldContainExactly listOf("md.measure.revenue")
            }
        }

        "an inapplicable operator rides onto the envelope rather than vanishing" {
            runTest {
                val trendNoGrain =
                    lattice(
                        mentions =
                            listOf(
                                opMention("m1", "Ukaž", "op:show", 0),
                                opMention("m2", "vývoj", "op:trend", 5),
                                Mention
                                    .newBuilder()
                                    .setId("m3")
                                    .setSpan(span("tržeb", 11))
                                    .addFrameRoles(FrameRole.FRAME_ROLE_MEASURE)
                                    .addBindings(
                                        Binding
                                            .newBuilder()
                                            .setRef("md.measure.revenue")
                                            .setTargetClass(TargetClass.TARGET_CLASS_MODEL_OBJECT),
                                    ).build(),
                            ),
                    )
                val answered =
                    runResolutionTurn(trendNoGrain, facts(), testDeps()).shouldBeInstanceOf<TurnEnd.Answered>()

                answered.envelope.inapplicableOperators shouldContainExactly listOf("op:trend requires time-grain")
                answered.question.operators shouldContainExactly listOf("op:show")
            }
        }

        "⛑ a config that admits a rung the deps do not implement is caught at WIRING time" {
            // Found at T6 the hard way: H5's G1 made the shipped config eligible for `lookup`
            // and `local`, and a deps object with no rungs blew up inside the loop — a startup
            // error arriving as a runtime one, on whichever user's question first produced a
            // gap of the right kind.
            testDeps(rungs = emptyMap()).unimplementedRungs("CHAT_QUICK") shouldContainExactly
                listOf("lookup", "local")
            testDeps().unimplementedRungs("CHAT_QUICK") shouldBe emptyList()
            // `emulated` is exempt — config-legal and deliberately unimplemented until RV-P8.
            testDeps().unimplementedRungs("INVESTIGATION_DEEP") shouldBe listOf("capable")
        }

        "an empty operator library refuses every operator, and says which it holds (none)" {
            runTest {
                val deps = testDeps(library = LayeredSkillLibrary.EMPTY)
                val refused =
                    runResolutionTurn(coveredLattice(), facts(), deps).shouldBeInstanceOf<TurnEnd.Refused>()
                refused.refusal.fallThrough shouldBe FallThroughReason.COMPOSE_REFUSED
                refused.refusal.composableResidue shouldBe emptyList()
            }
        }
    })
