package org.tatrman.kantheon.golem.conformance

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.tatrman.kantheon.golem.graph.GolemGraphDeps
import org.tatrman.kantheon.golem.graph.GolemTurnState
import org.tatrman.kantheon.golem.graph.callResolutionCoreNode
import org.tatrman.kantheon.golem.graph.walkResolutionNodes
import org.tatrman.kantheon.golem.resolution.RecordedResolutionCore
import org.tatrman.kantheon.golem.resolution.ResolutionDeps
import org.tatrman.kantheon.golem.resolution.TurnEnd
import org.tatrman.kantheon.golem.resolution.feedback.FeedbackEvent
import org.tatrman.kantheon.golem.resolution.feedback.Outcome
import org.tatrman.kantheon.golem.resolution.feedback.RecordingFeedbackSink
import org.tatrman.kantheon.golem.resolution.hitl.Pin
import org.tatrman.kantheon.golem.resolution.ladder.HypothesisVerdict
import org.tatrman.kantheon.golem.resolution.ladder.LadderConfig
import org.tatrman.kantheon.golem.resolution.ladder.LlmRung
import org.tatrman.kantheon.golem.resolution.ladder.LookupRung
import org.tatrman.kantheon.golem.resolution.ladder.RungLlm
import org.tatrman.kantheon.golem.resolution.ladder.ScriptedGate
import org.tatrman.kantheon.golem.resolution.ladder.binding
import org.tatrman.kantheon.golem.resolution.ladder.gap
import org.tatrman.kantheon.golem.resolution.ladder.gateResult
import org.tatrman.kantheon.golem.resolution.ladder.hypothesis
import org.tatrman.kantheon.golem.resolution.resumeResolutionTurn
import org.tatrman.kantheon.golem.resolution.testDeps
import org.tatrman.resolver.v1.AwaitingClarification
import org.tatrman.resolver.v1.Binding
import org.tatrman.resolver.v1.Disposition
import org.tatrman.resolver.v1.EvidenceClass
import org.tatrman.resolver.v1.GapKind
import org.tatrman.resolver.v1.Option
import org.tatrman.resolver.v1.RungLogEntry
import org.tatrman.resolver.v1.TargetClass

/**
 * RV-P5.4 T2/T3 — **the two kantheon-local conversations**, run under the ladder kantheon
 * actually ships.
 *
 * The shared corpus (`ConformanceConversationsSpec`) runs the zero-rung file, because that is
 * the premise its numbers were authored against — see `ConversationRun`'s KDoc. What it
 * therefore cannot express is the platform Golem's whole point: **a ladder that climbs.** Both
 * cases here are the "author kantheon-local only what is platform-shaped" carve-out, and
 * neither could be written as a shared fixture without a key golem-py has no way to satisfy.
 *
 *  1. **H1′ from its OWN rung** — the shared fixture's turn 2 supplies the correcting
 *     hypothesis, and its own note says the Kotlin shell "must produce the same turn-2 outcome
 *     from its own local rung". That claim is made here, through the real `LlmRung` prompt and
 *     parser rather than a scripted proposal, because a rung scripted to emit the fixture's
 *     hypothesis and the fixture supplying it are the same experiment.
 *  2. **H2 with the full ladder** — the vocabulary is genuinely absent, so `lookup` no-ops and
 *     `local` proposes NOTHING; only then is a question spent. The rung log has to tell that
 *     story **in order**, because the log is what a plugin and an operator both read, and a log
 *     that merely records the ask would make an estate that never tried look identical to one
 *     that tried everything.
 */
private fun graphDeps(
    resolution: ResolutionDeps,
    core: RecordedResolutionCore.RecordingClient,
) = GolemGraphDeps(
    composer = mockk(relaxed = true),
    validator = mockk(relaxed = true),
    miniPlanExecutor = mockk(relaxed = true),
    promptExecutor = mockk(relaxed = true),
    resolutionCore = core,
    resolution = resolution,
    referenceDatetime = { "2026-08-06T12:00:00Z" },
)

private fun turnState(
    question: String,
    conversationId: String,
) = GolemTurnState(
    request = requestFor(argsOf(question, conversationId)),
    userId = "user-a",
    tenantId = "t1",
)

private fun argsOf(
    text: String,
    conversationId: String,
) = buildJsonObject {
    put("text", text)
    put("conversation_id", conversationId)
    put("turn_id", "t-1")
    put("locale", "cs")
    put("caller_subject", "user-a")
}

/**
 * A recorded core that also CLARIFIES — the options and the HMAC token the door signs.
 *
 * `RecordedResolutionCore.client` (P5.1) answers with the lattice alone, which is right for
 * every case where the core had nothing to ask about. H2 is the case where it did, and an ask
 * with no options is a different conversation from an ask with two.
 */
private fun clarifyingClient(
    case: String,
    resumeToken: String,
    vararg options: Triple<String, String, String>,
): RecordedResolutionCore.RecordingClient {
    val awaiting = AwaitingClarification.newBuilder().setResumeToken(resumeToken)
    options.forEach { (id, label, ref) ->
        awaiting.addOptions(
            Option
                .newBuilder()
                .setId(id)
                .setLabel(label)
                .setTargetRef(ref),
        )
    }
    val response =
        RecordedResolutionCore
            .response(case)
            .toBuilder()
            .setAwaiting(awaiting)
            .build()
    return RecordedResolutionCore.RecordingClient(answer = { response })
}

/** An LLM rung that says exactly one thing. `NONE` is a real and common answer. */
private fun scriptedLlm(vararg replies: String): RungLlm {
    val queue = ArrayDeque(replies.toList())
    return RungLlm { _, _, _ -> queue.removeFirstOrNull() ?: "NONE" }
}

class LadderConversationSpec :
    StringSpec({

        // ------------------------------------------------ T2 — H1′, corrected by our own rung

        "H1′ — the local rung proposes the correction, the gate binds it, and the gap closes" {
            runTest {
                val core = RecordedResolutionCore.client("h1prime-cs")
                // The shipped INTERNAL-FULL table: G4_METHOD_MISS admits [lookup, local].
                val gated =
                    gateResult(
                        bindings = listOf(binding("md.dimension.Account.code#501001")),
                        updatedGaps = emptyList(),
                        outcomes =
                            listOf(
                                HypothesisVerdict(
                                    hypothesis = hypothesis("5010O1", "md.dimension.Account.code", "local", start = 20),
                                    accepted = true,
                                    reason = "",
                                    binding = binding("md.dimension.Account.code#501001"),
                                ),
                            ),
                        rungLogEntry =
                            RungLogEntry
                                .newBuilder()
                                .setRound(1)
                                .setRung("local")
                                .setAction("regate")
                                .setBindingsAdded(1)
                                .build(),
                    )
                val resolution =
                    testDeps(
                        ladder = LadderConfig.loadDefault(),
                        rungs =
                            mapOf(
                                "lookup" to LookupRung(),
                                // The REAL rung: its own prompt, its own parser. A ScriptedRung
                                // here would prove only that the loop calls what it is given.
                                "local" to LlmRung("local", scriptedLlm("5010O1 -> md.dimension.Account.code")),
                            ),
                        gate = ScriptedGate(gated),
                    )

                val out =
                    walkResolutionNodes(
                        callResolutionCoreNode(
                            turnState("Zobraz náklady účtu 5010O1 v roce 2025 podle období", "c-h1prime"),
                            graphDeps(resolution, core),
                        ),
                        graphDeps(resolution, core),
                    )

                val answered = out.turnEnd.shouldBeInstanceOf<TurnEnd.Answered>()
                withClue("the correction must reach the LATTICE — `gatedBindings` alone changes no answer") {
                    // On the VALUE layer: `5010O1` is a ValueFinding, not a mention, so the
                    // gated member binding comes back as an attribution (RV-33). A mention-only
                    // fold drops it — see `foldGateResult`.
                    answered.ladder.lattice.valuesList
                        .flatMap { it.attributionsList }
                        .map { it.binding.ref } shouldContain "md.dimension.Account.code#501001"
                }
                withClue("and it reaches the composed QUESTION, which is the point of folding it") {
                    answered.question.filters.map { it.memberRef } shouldContain "md.dimension.Account.code#501001"
                }
                // Provenance survives the correction: who proposed it is on the rung log.
                answered.ladder.rungLog
                    .map { it.rung } shouldContain "local"
                withClue("the G4 closed, so nothing is carried") { answered.envelope.gapsCarried shouldBe emptyList() }
                // One LLM invocation, and it is the one that did the work.
                answered.llmInvocations shouldBe 1
                answered.asks shouldBe 0
            }
        }

        "H1′ — a local rung that proposes NOTHING still answers, carrying the miss honestly" {
            runTest {
                // The same turn under the zero-rung PREMISE the shared fixture asserts. Both
                // shapes are correct behaviour; what differs is only what the estate enabled.
                val core = RecordedResolutionCore.client("h1prime-cs")
                val resolution =
                    testDeps(
                        ladder = LadderConfig.loadDefault(),
                        rungs = mapOf("lookup" to LookupRung(), "local" to LlmRung("local", scriptedLlm("NONE"))),
                        gate = ScriptedGate(gateResult()),
                    )
                val deps = graphDeps(resolution, core)
                val out =
                    walkResolutionNodes(
                        callResolutionCoreNode(turnState("Zobraz náklady účtu 5010O1", "c-h1prime-b"), deps),
                        deps,
                    )

                val answered = out.turnEnd.shouldBeInstanceOf<TurnEnd.Answered>()
                answered.envelope.gapsCarried.map { it.kind } shouldContainExactly
                    listOf(GapKind.GAP_KIND_G4_METHOD_MISS)
                answered.envelope.gapsCarried.map { it.span.text } shouldContainExactly listOf("5010O1")
                withClue("a FILTER-position value miss is never worth the conversation's one question") {
                    answered.asks shouldBe 0
                }
            }
        }

        // ------------------------------------------------- T3 — H2, the full ladder+ask+pin

        "H2 — lookup no-ops, local proposes nothing, ONE ask, pin, resume, answer" {
            runTest {
                val core =
                    clarifyingClient(
                        "h2-cs",
                        resumeToken = "core-signed-h2",
                        Triple("opt-1", "Zákazník / kategorie: čerpací stanice", "md.dimension.Customer.category"),
                        Triple("opt-2", "Distribuční centrum", "md.dimension.DistributionCentre"),
                    )
                // The pin's gate: the user's hypothesis survives, and `Praze` stays open.
                val pinGate =
                    gateResult(
                        bindings = listOf(binding("md.dimension.Customer.category")),
                        updatedGaps =
                            listOf(
                                gap(
                                    GapKind.GAP_KIND_G3_UNATTRIBUTED,
                                    "Praze",
                                    start = 37,
                                    valueId = "v2",
                                    disposition = Disposition.DISPOSITION_UNRESOLVED,
                                ),
                            ),
                        outcomes =
                            listOf(
                                HypothesisVerdict(
                                    hypothesis =
                                        hypothesis(
                                            "čerpacích stanic",
                                            "md.dimension.Customer.category",
                                            "user",
                                            start = 18,
                                        ),
                                    accepted = true,
                                    reason = "",
                                    binding =
                                        Binding
                                            .newBuilder()
                                            .setRef("md.dimension.Customer.category")
                                            .setTargetClass(TargetClass.TARGET_CLASS_MODEL_OBJECT)
                                            .setEvidenceClass(EvidenceClass.EVIDENCE_CLASS_DECLARED_ALIAS)
                                            .setInClassScore(0.95)
                                            .build(),
                                ),
                            ),
                        rungLogEntry =
                            RungLogEntry
                                .newBuilder()
                                .setRound(3)
                                .setRung("user")
                                .setAction("regate")
                                .setBindingsAdded(1)
                                .build(),
                    )
                val resolution =
                    testDeps(
                        ladder = LadderConfig.loadDefault(),
                        rungs =
                            mapOf(
                                "lookup" to LookupRung(),
                                // The vocabulary genuinely does not have "čerpacích stanic".
                                "local" to LlmRung("local", scriptedLlm("NONE")),
                            ),
                        gate = ScriptedGate(pinGate),
                    )
                val deps = graphDeps(resolution, core)

                val question = "Zobraz prvních 10 čerpacích stanic v Praze podle tržby za 12 měsíců."
                val afterCore = callResolutionCoreNode(turnState(question, "c-h2"), deps)
                val walked = walkResolutionNodes(afterCore, deps)
                val paused = walked.turnEnd.shouldBeInstanceOf<TurnEnd.Paused>()

                // --- the ask is spent on the SUBJECT, and it offers the CORE's options -----
                paused.ask.gap.span.text shouldBe "čerpacích stanic"
                paused.ask.gap.kind shouldBe GapKind.GAP_KIND_G1_UNBOUND
                paused.ask.escape shouldBe "none of these"
                withClue("the ask offers the CORE's signed options, never ones we invented") {
                    paused.ask.options.map { it.id } shouldContainExactly listOf("opt-1", "opt-2")
                    paused.ask.resumeToken shouldBe "core-signed-h2"
                }
                paused.ladder.hitlRounds shouldBe 1
                withClue("the ladder tried before it asked — one LLM rung ran and found nothing") {
                    paused.ladder.llmInvocations shouldBe 1
                }

                // --- ⛑ THE RUNG LOG TELLS THE WHOLE STORY, IN ORDER ------------------------
                // Not a set: the ORDER is the claim. `lookup` before `local` is the ladder's
                // central promise — the deterministic rung is given its chance before any LLM
                // is asked — and an unordered assertion would pass on a ladder that climbed
                // backwards, which is the one arrangement that costs real money.
                paused.ladder.rungLog.map { it.rung to it.action } shouldContainExactly
                    listOf("lookup" to "no-proposal", "local" to "no-proposal")
                paused.ladder.rungLog.map { it.round } shouldContainExactly listOf(1, 2)
                withClue("every entry records how many gaps were still open when it ran") {
                    paused.ladder.rungLog.map { it.gapsOpen } shouldContainExactly listOf(2, 2)
                }

                // --- resume: the pin re-enters as a hypothesis and the gate decides --------
                val end =
                    resumeResolutionTurn(
                        snapshotId = paused.ask.snapshotId,
                        pin = Pin.Choice("opt-1"),
                        callerSubject = "user-a",
                        // Off the WALKED state: `turnFacts` is assembled by `assessGaps`, so the
                        // pre-walk state has none.
                        facts = requireNotNull(walked.turnFacts),
                        deps = resolution,
                    )
                val answered = end.shouldBeInstanceOf<TurnEnd.Answered>()

                withClue("the pinned entity must be in the lattice the answer was composed from") {
                    answered.ladder.lattice.mentionsList
                        .flatMap { it.bindingsList }
                        .map { it.ref } shouldContain "md.dimension.Customer.category"
                }
                withClue("the ask is counted once, on the snapshot — a resume cannot buy a second") {
                    answered.asks shouldBe 1
                }
                withClue("the unanchored LOCATION hint is CARRIED, never asked about") {
                    answered.envelope.gapsCarried.map { it.span.text } shouldContainExactly listOf("Praze")
                }
                // The user is not a rung: `user` is deliberately outside the closed four-rung
                // vocabulary so the ladder's health numbers cannot be made to lie by a pin.
                answered.ladder.rungLog
                    .map { it.rung } shouldContainExactly listOf("lookup", "local", "user")

                // --- the feedback event, shaped for P7.1 (emit-only here) ------------------
                val events = resolution.feedbackEvents()
                events.size shouldBe 1
                val event = events.single()
                // Bare, not `GAP_KIND_`-prefixed: golem-py's enum values are the bare names, and
                // a learning corpus the two shells write differently is two corpora.
                event.gapKind shouldBe "G1_UNBOUND"
                event.gapSpanText shouldBe "čerpacích stanic"
                event.conversationId shouldBe "c-h2"
                event.userId shouldBe "user-a"
                withClue("the raw question never enters the learning log — only its hash") {
                    event.questionTextHash.startsWith("sha256:") shouldBe true
                    (question in event.toJson()) shouldBe false
                }
                val pick = event.outcome.shouldBeInstanceOf<Outcome.Pick>()
                pick.optionId shouldBe "opt-1"
                pick.ref shouldBe "md.dimension.Customer.category"
            }
        }

        // ------------------------------------------- the config THIS Golem actually ships

        "the shipped kantheon ladder is the internal-full table, and every rung it admits exists" {
            val shipped = LadderConfig.loadDefault()
            withClue("kantheon does NOT ship the zero-rung file — that is the OS Golem's") {
                shipped.policy.getValue(GapKind.GAP_KIND_G1_UNBOUND).rungs shouldContainExactly
                    listOf("lookup", "local", "capable")
            }
            // The wiring-time check P5.3 T6 added, run against the deps this tier wires: a
            // config admitting a rung nobody implements fails MID-TURN otherwise, on whichever
            // user's question first produces a gap of the right kind.
            testDeps(
                ladder = shipped,
                rungs = mapOf("lookup" to LookupRung(), "local" to LlmRung("local", scriptedLlm())),
            ).unimplementedRungs("CHAT_QUICK") shouldBe emptyList()
        }
    })

/** `testDeps` always wires a recording sink; the cast names that rather than hiding it. */
private fun ResolutionDeps.feedbackEvents(): List<FeedbackEvent> = (feedback as RecordingFeedbackSink).events
