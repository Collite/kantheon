package org.tatrman.kantheon.golem.resolution

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.test.runTest
import org.tatrman.resolver.v1.EvidenceClass
import org.tatrman.resolver.v1.FrameRole
import org.tatrman.resolver.v1.GapKind
import org.tatrman.resolver.v1.ResolutionState

/**
 * RV-P5.1 T2 — component tests for `callResolutionCore`, driven with a recorded
 * `resolve.bind` exchange. Written before the node exists (T3 implements it).
 *
 * Four claims, one per T2 sub-item:
 *  (a) ONE call returns the whole lattice — mentions with repeated frame roles, values,
 *      typed gaps, the core's own round-0 rung log, and the RV-39 layer tuple;
 *  (b) the turn state after the node carries everything the six Themis nodes produced,
 *      field for field against the T1 recon's checklist;
 *  (c) a door error is a degrade posture, not an exception escaping the graph;
 *  (d) S-1/S-4 provenance survives onto the conversation.
 */
private suspend fun call(
    case: String,
    client: ResolutionCoreClient,
) = callResolutionCoreStep(
    question = QUESTIONS.getValue(case),
    conversationId = "conv-$case",
    locale = "cs",
    referenceDatetime = "2026-08-06T00:00:00Z",
    tenant = "hartland",
    callerSubject = "user-1",
    client = client,
)

/** The four recorded questions, from the upstream `PROVENANCE.md` table. */
private val QUESTIONS =
    mapOf(
        "h1-cs" to "Zobraz náklady účtu 501001 v roce 2025 podle období",
        "h1prime-cs" to "Zobraz náklady účtu 5010O1 v roce 2025 podle období",
        "h2-cs" to "Zobraz prvních 10 čerpacích stanic v Praze podle tržby za 12 měsíců.",
        "h5-cs" to "Ukaž vývoj nákladů střediska 220 za posledních 12 měsíců a porovnej s plánem.",
    )

class CallResolutionCoreSpec :
    StringSpec({

        // ---- (a) one call, the whole lattice -------------------------------------------

        "one call returns a lattice with mentions, values, gaps, round-0 rung log and the layer tuple" {
            runTest {
                val client = RecordedResolutionCore.client("h1-cs")
                val result = call("h1-cs", client)

                // ONE rpc. The six nodes were six round trips' worth of orchestration; this is one.
                client.requests shouldHaveSize 1
                client.requests
                    .single()
                    .fresh.text shouldBe QUESTIONS.getValue("h1-cs")
                client.requests
                    .single()
                    .fresh.locale shouldBe "cs"
                client.requests.single().conversationId shouldBe "conv-h1-cs"

                val lattice = result.lattice.shouldNotBeNull()
                lattice.mentionsList.shouldNotBeNull().size shouldBe 5
                lattice.valuesList shouldHaveSize 2
                // H1 is the 0-LLM hero: gap-free is the whole point of it.
                lattice.gapsList shouldHaveSize 0
                // The CORE writes round 0 itself — we never synthesise it (the golem-py drill
                // found exactly this: our fallback set a `note` the real core does not).
                lattice.rungLogList
                    .shouldNotBeNull()
                    .first()
                    .rung shouldBe "core"
                lattice.rungLogList.first().round shouldBe 0
                lattice.lexiconVersions.lexiconArtifactHash shouldStartWith "sha256:"
            }
        }

        "frame roles are REPEATED — the measure-as-subject mention carries both (P0.2's blocking finding)" {
            runTest {
                val lattice = call("h1-cs", RecordedResolutionCore.client("h1-cs")).lattice.shouldNotBeNull()

                val nakladyMention = lattice.mentionsList.single { it.span.text == "náklady" }
                nakladyMention.frameRolesList shouldContainExactly
                    listOf(FrameRole.FRAME_ROLE_SUBJECT, FrameRole.FRAME_ROLE_MEASURE)
            }
        }

        "typed gaps arrive typed — H2 carries a G1 on the subject and a G3 on the location hint" {
            runTest {
                val lattice = call("h2-cs", RecordedResolutionCore.client("h2-cs")).lattice.shouldNotBeNull()

                val kinds = lattice.gapsList.map { it.kind }
                kinds shouldContainExactly listOf(GapKind.GAP_KIND_G1_UNBOUND, GapKind.GAP_KIND_G3_UNATTRIBUTED)
                // RV-15: whether an ask is load-bearing is decided by SUBJECT being among the
                // gap's roles, so a gap that lost its roles is a gap that cannot be triaged.
                lattice.gapsList
                    .single { it.kind == GapKind.GAP_KIND_G1_UNBOUND }
                    .frameRolesList
                    .shouldContainExactly(listOf(FrameRole.FRAME_ROLE_SUBJECT))
            }
        }

        "H1' differs from H1 by one method-miss gap and nothing else" {
            runTest {
                val h1 = call("h1-cs", RecordedResolutionCore.client("h1-cs")).lattice.shouldNotBeNull()
                val h1p = call("h1prime-cs", RecordedResolutionCore.client("h1prime-cs")).lattice.shouldNotBeNull()

                h1p.mentionsList.map { it.span.text } shouldContainExactly h1.mentionsList.map { it.span.text }
                h1p.mentionsList.map { it.frameRolesList } shouldContainExactly
                    h1.mentionsList.map { it.frameRolesList }
                h1p.gapsList.map { it.kind } shouldContainExactly listOf(GapKind.GAP_KIND_G4_METHOD_MISS)
            }
        }

        "H5 binds three operators in one question — the operator layer rides the ordinary path" {
            runTest {
                val lattice = call("h5-cs", RecordedResolutionCore.client("h5-cs")).lattice.shouldNotBeNull()

                val ops =
                    lattice.mentionsList
                        .flatMap { it.bindingsList }
                        .map { it.ref }
                        .filter { it.startsWith("op:") }
                ops shouldHaveSize 3
            }
        }

        // ---- (b) the migration checklist, field for field --------------------------------

        // The T1 recon's table: what each of the six themis nodes wrote on `ParseState`, and
        // where the same information lives in the lattice. Five of six map; the sixth does
        // not, deliberately, and that is asserted rather than left as an absence.
        "the lattice carries what detectLangAndParse's nlpResponse carried" {
            runTest {
                val lattice = call("h1-cs", RecordedResolutionCore.client("h1-cs")).lattice.shouldNotBeNull()

                lattice.parse.tokensList
                    .shouldNotBeNull()
                    .isEmpty() shouldBe false
                lattice.parse.language shouldBe "cs"
                lattice.parse.tokensList
                    .first()
                    .lemma
                    .isEmpty() shouldBe false
            }
        }

        "the lattice carries what extractUniversal's universalEntities carried" {
            runTest {
                // universalEntities = NER-typed spans with a normalised value and a source
                // engine. In the lattice those are GROUNDED values with a `grounding` kernel.
                val lattice = call("h1-cs", RecordedResolutionCore.client("h1-cs")).lattice.shouldNotBeNull()

                val grounded = lattice.valuesList.filter { it.hasGrounding() }
                grounded.isEmpty() shouldBe false
                grounded.forEach { it.grounding.kernel.isEmpty() shouldBe false }
            }
        }

        "the lattice carries what proposeDomainSpans + filterRelevantSpans carried" {
            runTest {
                // domainSpans were noun-head candidates; filteredSpans were the LLM-kept
                // subset. The lattice's `mentions` ARE the kept set — the core filters
                // deterministically, which is why filterRelevantSpans' LLM call disappears.
                val lattice = call("h1-cs", RecordedResolutionCore.client("h1-cs")).lattice.shouldNotBeNull()

                lattice.mentionsList.forEach { mention ->
                    mention.id.isEmpty() shouldBe false
                    mention.span.text.isEmpty() shouldBe false
                    mention.lemma.isEmpty() shouldBe false
                }
                // in span order (the proto says so, and downstream ranking depends on it)
                lattice.mentionsList.map { it.span.start } shouldBe lattice.mentionsList.map { it.span.start }.sorted()
            }
        }

        "the lattice carries what fuzzyMatchSpans' candidate map carried, but classed" {
            runTest {
                val lattice = call("h1-cs", RecordedResolutionCore.client("h1-cs")).lattice.shouldNotBeNull()

                val bindings = lattice.mentionsList.flatMap { it.bindingsList }
                bindings.isEmpty() shouldBe false
                bindings.forEach { binding ->
                    binding.ref.isEmpty() shouldBe false
                    // RV-14: evidence WITH a class, never a bare score. This is the structural
                    // difference from `FuzzyCandidate(fuzzyId, label, score, entityTypeRef)`, and
                    // UNSPECIFIED would put us back there.
                    (binding.evidenceClass != EvidenceClass.EVIDENCE_CLASS_UNSPECIFIED) shouldBe true
                    // WEAK never binds, whatever its score.
                    (binding.evidenceClass != EvidenceClass.EVIDENCE_CLASS_WEAK) shouldBe true
                }
            }
        }

        "jointInference's functionId/argsJson has NO counterpart, and that is the design" {
            runTest {
                // The sixth node produced `InferenceResult(functionId, argsJson, bindings,
                // confidence, alternatives, rationale)`. Four of those six fields DO map —
                // bindings → mention bindings, confidence+alternatives → the evidence class
                // and the 0..n binding list, rationale → the rung log. `functionId`/`argsJson`
                // map to NOTHING, because RV replaces function-id guessing with
                // `composeStructuredQuestion` over a covered lattice (P5.3 T2). Pinning it so
                // that a future reader does not "fix" the gap by inventing a function field.
                val fields = ResolutionState.getDescriptor().fields.map { it.name }
                fields shouldContainExactly
                    listOf("parse", "mentions", "values", "gaps", "rung_log", "lexicon_versions")
                fields.none { it.contains("function") || it.contains("args") } shouldBe true
            }
        }

        // ---- (c) a door error degrades, it does not throw ---------------------------------

        "a door error becomes a degrade posture rather than an exception out of the node" {
            runTest {
                val result = call("h1-cs", RecordedResolutionCore.failing(code = "UNAVAILABLE"))

                result.degrade.shouldNotBeNull().code shouldBe "UNAVAILABLE"
                // A degraded turn has NO lattice, and must not pretend to: an empty lattice
                // reads downstream as "understood, nothing found", which is a different and
                // dishonest answer.
                result.lattice.shouldBeNull()
                result.provenance.shouldBeNull()
            }
        }

        // ---- (d) S-1 / S-4 provenance survives --------------------------------------------

        "S-1 engine identity and the RV-39 layer tuple survive onto the conversation" {
            runTest {
                val result = call("h1-cs", RecordedResolutionCore.client("h1-cs"))

                val provenance = result.provenance.shouldNotBeNull()
                provenance.lexiconArtifactHash shouldStartWith "sha256:"
                provenance.memberIndexVersions.isEmpty() shouldBe false
                // ABSENT until RV-P6 — the absence is the contract, so null and not "".
                provenance.overlayVersion.shouldBeNull()
                // S-1: never a blank model.
                provenance.engineVersions.forEach { it.model.isEmpty() shouldBe false }
            }
        }

        "S-4 per-binding provenance is left in the lattice, not copied up" {
            runTest {
                val result = call("h1-cs", RecordedResolutionCore.client("h1-cs"))
                val lattice = result.lattice.shouldNotBeNull()

                lattice.mentionsList.flatMap { it.bindingsList }.forEach { binding ->
                    binding.producer.algorithm.isEmpty() shouldBe false
                    binding.producer.snapshotHash.isEmpty() shouldBe false
                    // The core proposes to nobody — `proposing_rung` is empty on round 0, and
                    // P5.2's rungs are what fill it.
                    binding.producer.proposingRung shouldBe ""
                }
            }
        }
    })
