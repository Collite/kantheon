package org.tatrman.kantheon.golem.resolution.hitl

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.tatrman.kantheon.golem.resolution.TurnEnd
import org.tatrman.kantheon.golem.resolution.facts
import org.tatrman.kantheon.golem.resolution.feedback.Outcome
import org.tatrman.kantheon.golem.resolution.feedback.RecordingFeedbackSink
import org.tatrman.kantheon.golem.resolution.inertGate
import org.tatrman.kantheon.golem.resolution.ladder.GateResult
import org.tatrman.kantheon.golem.resolution.ladder.LadderConfig
import org.tatrman.kantheon.golem.resolution.ladder.gap
import org.tatrman.kantheon.golem.resolution.ladder.lattice
import org.tatrman.kantheon.golem.resolution.ladder.openLadderYaml
import org.tatrman.kantheon.golem.resolution.ladder.span
import org.tatrman.kantheon.golem.resolution.resumeResolutionTurn
import org.tatrman.kantheon.golem.resolution.runResolutionTurn
import org.tatrman.kantheon.golem.resolution.testDeps
import org.tatrman.resolver.v1.Binding
import org.tatrman.resolver.v1.Disposition
import org.tatrman.resolver.v1.EvidenceClass
import org.tatrman.resolver.v1.FrameRole
import org.tatrman.resolver.v1.GapKind
import org.tatrman.resolver.v1.ResolutionState
import org.tatrman.resolver.v1.TargetClass

/**
 * RV-P5.3 T4/T5 — the ask, the pin, the resume, and the event each round trip leaves behind.
 */
private fun open(): LadderConfig = LadderConfig.parse(openLadderYaml())

/** *"Zobraz prvních 10 čerpacích stanic v Praze…"* — a load-bearing G1 and a carryable G3. */
private fun h2Gaps(): ResolutionState =
    lattice(
        gaps =
            listOf(
                gap(
                    GapKind.GAP_KIND_G1_UNBOUND,
                    "čerpacích stanic",
                    21,
                    roles = listOf(FrameRole.FRAME_ROLE_SUBJECT),
                    mentionId = "m2",
                ),
                gap(GapKind.GAP_KIND_G3_UNATTRIBUTED, "Praze", 41, valueId = "v1"),
            ),
    )

private fun options(): List<SignedOption> =
    listOf(
        SignedOption(
            id = "o1",
            label = "Čerpací stanice",
            ref = "md.dimension.Station",
            span = span("čerpacích stanic", 21),
        ),
        SignedOption(
            id = "o2",
            label = "Stanice metra",
            ref = "md.dimension.MetroStation",
            span = span("čerpacích stanic", 21),
        ),
    )

class HitlSpec :
    StringSpec({

        // ------------------------------------------------------------------ the ask policy

        "the ask goes to the LOAD-BEARING gap, not to the first askable one" {
            // Both gaps are askable; only the SUBJECT one is load-bearing. Without this rule
            // H2 spends its single question on `Praze`.
            chooseAsk(h2Gaps().gapsList, open())!!.span.text shouldBe "čerpacích stanic"
        }

        "no load-bearing gap ⇒ no ask, whatever the policy says is askable" {
            val onlyCarryable =
                listOf(gap(GapKind.GAP_KIND_G3_UNATTRIBUTED, "Praze", 41, valueId = "v1"))
            chooseAsk(onlyCarryable, open()) shouldBe null
        }

        "one ask per round — `chooseAsk` returns a gap, never a list" {
            val twoLoadBearing =
                listOf(
                    gap(
                        GapKind.GAP_KIND_G1_UNBOUND,
                        "alfa",
                        0,
                        roles = listOf(FrameRole.FRAME_ROLE_SUBJECT),
                        mentionId = "m1",
                    ),
                    gap(
                        GapKind.GAP_KIND_G1_UNBOUND,
                        "beta",
                        9,
                        roles = listOf(FrameRole.FRAME_ROLE_SUBJECT),
                        mentionId = "m2",
                    ),
                )
            // Frame-ranked, then span order: the earlier span wins the single question.
            chooseAsk(twoLoadBearing, open())!!.span.text shouldBe "alfa"
        }

        "options are scoped to the span being asked about" {
            // ⛑ The golem-py review bug: the user was asked about A and offered answers for B.
            val forOther = SignedOption(id = "o9", label = "Praha", ref = "geo.city#1", span = span("Praze", 41))
            val unscoped = SignedOption(id = "o0", label = "něco jiného", ref = "")

            optionsFor(options() + forOther + unscoped, span("čerpacích stanic", 21))
                .map { it.id } shouldContainExactly listOf("o1", "o2", "o0")
        }

        "an ask always carries the escape, even with no options" {
            val ask =
                buildAsk(
                    gap = h2Gaps().getGaps(0),
                    lattice = h2Gaps(),
                    signedOptions = emptyList(),
                    resumeToken = "tok",
                    snapshotId = "s1",
                    locale = "cs",
                )
            ask.escape shouldBe "none of these"
            ask.options shouldBe emptyList()
            // A token with no redeemable options is not carried — a pick could not be honoured
            // and the core would reject the resume anyway.
            ask.resumeToken shouldBe ""
            ask.question shouldContain "čerpacích stanic"
        }

        "an ask with scoped options carries the CORE's token, opaque" {
            buildAsk(h2Gaps().getGaps(0), h2Gaps(), options(), "core-token", "s1", "cs").resumeToken shouldBe
                "core-token"
        }

        // -------------------------------------------------------------------- the one pool

        "the ask is spent as it goes out, and the SNAPSHOT is what carries the spend" {
            runTest {
                val deps = testDeps(ladder = open(), gate = inertGate())
                val end = runResolutionTurn(h2Gaps(), facts(signedOptions = options(), resumeToken = "tok"), deps)

                val paused = end.shouldBeInstanceOf<TurnEnd.Paused>()
                paused.ladder.hitlRounds shouldBe 1
                deps.snapshots
                    .get(paused.ask.snapshotId)
                    .ladder.hitlRounds shouldBe 1
            }
        }

        "a replayed resume cannot double-spend — the stored state has already paid" {
            runTest {
                val deps = testDeps(ladder = open(), gate = inertGate())
                val paused =
                    runResolutionTurn(h2Gaps(), facts(signedOptions = options(), resumeToken = "tok"), deps)
                        .shouldBeInstanceOf<TurnEnd.Paused>()

                // Deliver the SAME resume twice. CHAT_QUICK allows one ask; the stored
                // snapshot already counts it, so neither delivery can buy a second question.
                val first =
                    resumeResolutionTurn(paused.ask.snapshotId, Pin.NoneOfThese, "user-1", facts(), deps)
                val second =
                    resumeResolutionTurn(paused.ask.snapshotId, Pin.NoneOfThese, "user-1", facts(), deps)

                first.shouldBeInstanceOf<TurnEnd.Refused>()
                second.shouldBeInstanceOf<TurnEnd.Refused>()
                // Idempotent by construction: the snapshot is immutable and the copy is fresh.
                deps.snapshots
                    .get(paused.ask.snapshotId)
                    .ladder.hitlRounds shouldBe 1
            }
        }

        "another caller may not resume this turn" {
            runTest {
                val deps = testDeps(ladder = open(), gate = inertGate())
                val paused =
                    runResolutionTurn(h2Gaps(), facts(signedOptions = options()), deps)
                        .shouldBeInstanceOf<TurnEnd.Paused>()

                shouldThrow<IdentitySubjectMismatch> {
                    resumeResolutionTurn(paused.ask.snapshotId, Pin.NoneOfThese, "someone-else", facts(), deps)
                }
            }
        }

        "an unknown snapshot is a typed failure, not a null" {
            shouldThrow<SnapshotNotFound> { InMemorySnapshotStore().get("nope") }
        }

        // ------------------------------------------------------------------ pins and resume

        "a pinned choice re-enters through the GATE — the Golem never binds it" {
            runTest {
                var gatedHypothesisRef: String? = null
                val gate =
                    org.tatrman.kantheon.golem.resolution.ladder.GateCall { _, hypotheses ->
                        gatedHypothesisRef = hypotheses.single().ref
                        GateResult(
                            gatedBindings =
                                listOf(
                                    Binding
                                        .newBuilder()
                                        .setRef("md.dimension.Station")
                                        .setTargetClass(TargetClass.TARGET_CLASS_MODEL_OBJECT)
                                        .setEvidenceClass(EvidenceClass.EVIDENCE_CLASS_EXACT)
                                        .build(),
                                ),
                            updatedGaps = listOf(h2Gaps().getGaps(1)), // only the carryable G3 left
                            rungLogEntry = null,
                            outcomes = emptyList(),
                        )
                    }
                val deps = testDeps(ladder = open(), gate = gate)
                val paused =
                    runResolutionTurn(h2Gaps(), facts(signedOptions = options(), resumeToken = "tok"), deps)
                        .shouldBeInstanceOf<TurnEnd.Paused>()

                resumeResolutionTurn(paused.ask.snapshotId, Pin.Choice("o1"), "user-1", facts(), deps)

                gatedHypothesisRef shouldBe "md.dimension.Station"
            }
        }

        "the user is not a rung — a pin is labelled `user` in the rung log, never `local`" {
            runTest {
                var rung: String? = null
                val gate =
                    org.tatrman.kantheon.golem.resolution.ladder.GateCall { _, h ->
                        rung = h.single().proposingRung
                        GateResult(emptyList(), emptyList(), null, emptyList())
                    }
                val deps = testDeps(ladder = open(), gate = gate)
                val paused =
                    runResolutionTurn(h2Gaps(), facts(signedOptions = options(), resumeToken = "t"), deps)
                        .shouldBeInstanceOf<TurnEnd.Paused>()
                resumeResolutionTurn(paused.ask.snapshotId, Pin.Choice("o1"), "user-1", facts(), deps)

                rung shouldBe "user"
            }
        }

        "a pin naming no signed option is refused rather than fabricated into a hypothesis" {
            runTest {
                var gateCalls = 0
                val gate =
                    org.tatrman.kantheon.golem.resolution.ladder.GateCall { _, _ ->
                        gateCalls++
                        GateResult(emptyList(), emptyList(), null, emptyList())
                    }
                val deps = testDeps(ladder = open(), gate = gate)
                val paused =
                    runResolutionTurn(h2Gaps(), facts(signedOptions = options(), resumeToken = "t"), deps)
                        .shouldBeInstanceOf<TurnEnd.Paused>()

                resumeResolutionTurn(paused.ask.snapshotId, Pin.Choice("o-forged"), "user-1", facts(), deps)
                gateCalls shouldBe 0
            }
        }

        "the escape closes the gap as USER_CONFIRMED_UNKNOWN — not resolved, not still open" {
            runTest {
                val deps = testDeps(ladder = open(), gate = inertGate())
                val paused =
                    runResolutionTurn(h2Gaps(), facts(signedOptions = options()), deps)
                        .shouldBeInstanceOf<TurnEnd.Paused>()

                val resumed = resumeResolutionTurn(paused.ask.snapshotId, Pin.NoneOfThese, "user-1", facts(), deps)
                val ladder = resumed.shouldBeInstanceOf<TurnEnd.Refused>().ladder

                ladder.gaps.single { it.span.text == "čerpacích stanic" }.disposition shouldBe
                    Disposition.DISPOSITION_USER_CONFIRMED_UNKNOWN
                // …and nothing asks about it again: it is no longer OPEN.
                org.tatrman.kantheon.golem.resolution.ladder
                    .openGaps(ladder.gaps)
                    .map { it.span.text } shouldContainExactly listOf("Praze")
            }
        }

        // ------------------------------------------------------------- T5, feedback events

        "an ask→pin round trip emits exactly one event, with the pick" {
            runTest {
                val sink = RecordingFeedbackSink()
                val deps = testDeps(ladder = open(), gate = inertGate(), feedback = sink)
                val paused =
                    runResolutionTurn(
                        h2Gaps(),
                        facts(question = "Zobraz čerpací stanice", signedOptions = options(), resumeToken = "t"),
                        deps,
                    ).shouldBeInstanceOf<TurnEnd.Paused>()

                // The ask alone emits NOTHING — an unanswered question has nothing to teach.
                sink.events shouldHaveSize 0

                resumeResolutionTurn(paused.ask.snapshotId, Pin.Choice("o1"), "user-1", facts(), deps)

                sink.events shouldHaveSize 1
                val e = sink.events.single()
                e.outcome shouldBe Outcome.Pick("o1", "md.dimension.Station")
                e.gapKind shouldBe "G1_UNBOUND"
                e.gapSpanText shouldBe "čerpacích stanic"
                e.conversationId shouldBe "conv-1"
                e.estateId shouldBe "test-estate"
                e.options.map { it.id } shouldContainExactly listOf("o1", "o2")
                // The raw question is NOT in the event.
                e.questionTextHash shouldContain "sha256:"
                e.toJson() shouldContain "\"kind\":\"pick\""
            }
        }

        "an ask→escape emits none_of_these" {
            runTest {
                val sink = RecordingFeedbackSink()
                val deps = testDeps(ladder = open(), gate = inertGate(), feedback = sink)
                val paused =
                    runResolutionTurn(h2Gaps(), facts(signedOptions = options()), deps)
                        .shouldBeInstanceOf<TurnEnd.Paused>()

                resumeResolutionTurn(paused.ask.snapshotId, Pin.NoneOfThese, "user-1", facts(), deps)

                sink.events.single().outcome shouldBe Outcome.NoneOfThese
                sink.events.single().toJson() shouldContain "\"kind\":\"none_of_these\""
            }
        }

        "free text is retained in full — it IS the learning signal" {
            runTest {
                val sink = RecordingFeedbackSink()
                val deps = testDeps(ladder = open(), gate = inertGate(), feedback = sink)
                val paused =
                    runResolutionTurn(h2Gaps(), facts(signedOptions = options()), deps)
                        .shouldBeInstanceOf<TurnEnd.Paused>()

                resumeResolutionTurn(
                    paused.ask.snapshotId,
                    Pin.FreeText("čerpací stanice = benzínky"),
                    "user-1",
                    facts(),
                    deps,
                )

                sink.events.single().outcome shouldBe Outcome.FreeText("čerpací stanice = benzínky")
            }
        }

        "a forged pick carries an EMPTY ref rather than a fabricated one" {
            runTest {
                val sink = RecordingFeedbackSink()
                val deps = testDeps(ladder = open(), gate = inertGate(), feedback = sink)
                val paused =
                    runResolutionTurn(h2Gaps(), facts(signedOptions = options()), deps)
                        .shouldBeInstanceOf<TurnEnd.Paused>()

                resumeResolutionTurn(paused.ask.snapshotId, Pin.Choice("o-forged"), "user-1", facts(), deps)

                // An event claiming a ref nobody offered would poison the overlay at P7.
                sink.events.single().outcome shouldBe Outcome.Pick("o-forged", "")
            }
        }
    })
