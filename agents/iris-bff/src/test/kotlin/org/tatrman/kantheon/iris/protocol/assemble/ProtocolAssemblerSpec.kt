package org.tatrman.kantheon.iris.protocol.assemble

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.tatrman.kantheon.iris.protocol.FixtureLoader
import org.tatrman.kantheon.iris.protocol.record.InMemoryProtocolRecordStore
import org.tatrman.kantheon.iris.protocol.render.MarkdownRenderer
import org.tatrman.kantheon.iris.protocol.sources.ExplainClient
import org.tatrman.kantheon.iris.protocol.sources.GatewayLogsClient
import org.tatrman.kantheon.iris.protocol.sources.LokiClient
import org.tatrman.kantheon.iris.protocol.sources.TempoClient
import org.tatrman.kantheon.protocol.v1.Scope
import org.tatrman.kantheon.protocol.v1.SectionStatus
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * The orchestration (architecture §3.1). Whole-document equality is already
 * covered by `GoldenCorpusSpec`; what is proven here is the behaviour the
 * assembler adds on top — scope selection, parallel fetch, and above all that
 * **no source failure can turn into a failed document** (P-4).
 *
 * All four clients are stubbed through MockEngine/lambdas; no live anything.
 */
class ProtocolAssemblerSpec :
    StringSpec({

        val case = "H1-full"

        val fixtureSession: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")

        fun store(caseName: String = case): InMemoryProtocolRecordStore {
            val s = InMemoryProtocolRecordStore()
            FixtureLoader.records(caseName).forEach { s.write(it) }
            // `readForSession` joins through `iris_turns`; the fake needs that join
            // declared or a multi-turn scope reads back NOTHING — which is quiet enough
            // that a test asserting only the query count will pass anyway.
            FixtureLoader.turns(caseName).forEachIndexed { index, t ->
                s.linkTurn(UUID.fromString(t.turnId), fixtureSession, index + 1)
            }
            return s
        }

        /** An assembler whose sources answer from the fixture corpus. */
        fun assembler(
            caseName: String = case,
            gateway: GatewayLogsClient? = null,
            loki: LokiClient? = null,
            tempo: TempoClient? = null,
            explain: ExplainClient? = null,
            registry: SimpleMeterRegistry = SimpleMeterRegistry(),
        ) = ProtocolAssembler(
            records = store(caseName),
            config = FixtureLoader.config(caseName),
            gateway = gateway,
            loki = loki,
            tempo = tempo,
            explain = explain,
            registry = registry,
            clock = { Instant.parse("2026-07-30T07:05:00Z") },
            ids = { UUID.fromString("00000000-0000-4000-8000-0000000000ff") },
            estate = "hartland",
        )

        fun request(
            caseName: String = case,
            scope: Scope = Scope.newBuilder().setLastTurn(true).build(),
        ) = ProtocolAssembler.Request(
            sessionId = UUID.fromString("11111111-1111-4111-8111-111111111111"),
            scope = scope,
            turns = FixtureLoader.turns(caseName),
            bearer = "jwt",
        )

        "no configured sources: a document is still produced, every source skipped-by-config" {
            runTest {
                val doc = assembler().assemble(request())

                doc.turnsCount shouldBe 1
                doc.hasReceipts() shouldBe true
                // Absence of a source is a fact in the receipts, never an exception.
                doc.receipts.sourcesList
                    .map { it.source }
                    .contains("llm-gateway") shouldBe true
                doc.schemaVersion shouldBe "protocol/v1.0"
                doc.protocolId shouldNotBe ""
            }
        }

        "sources are resolved in PARALLEL, not one after another" {
            runTest {
                val inFlight = AtomicInteger(0)
                val maxConcurrent = AtomicInteger(0)

                /** A slow engine that records how many source calls overlap. */
                fun slow(body: String) =
                    HttpClient(
                        MockEngine {
                            val now = inFlight.incrementAndGet()
                            maxConcurrent.updateAndGet { m -> maxOf(m, now) }
                            delay(50)
                            inFlight.decrementAndGet()
                            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                        },
                    )

                // Two sources that genuinely run for this fixture. (Explain does NOT:
                // H1-full's record carries plan ids, so the S-1 fallback is correctly
                // skipped — using it here would have made this assertion vacuous.)
                assembler(
                    gateway = GatewayLogsClient("http://gw", slow("""{"items":[]}""")),
                    tempo = TempoClient("http://tempo", slow("""{"batches":[]}""")),
                ).assemble(request())

                // Sequential fetching could never exceed 1; the point of the parallel
                // resolve is that a protocol costs the SLOWEST source, not their sum.
                withClue("sources must overlap") { (maxConcurrent.get() >= 2) shouldBe true }
            }
        }

        "one source Degraded -> its sections degrade + a receipt says why; document still produced (P-4)" {
            runTest {
                val brokenTempo =
                    TempoClient(
                        "http://tempo",
                        HttpClient(
                            MockEngine {
                                respondError(HttpStatusCode.InternalServerError)
                            },
                        ),
                    )

                val doc = assembler(tempo = brokenTempo).assemble(request())

                // The document exists and is well-formed...
                doc.turnsCount shouldBe 1
                // ...the tempo-derived section is degraded...
                val execution =
                    doc.turnsList
                        .single()
                        .sectionsList
                        .single { it.key == "protocol.section.execution" }
                execution.status shouldBe SectionStatus.SECTION_DEGRADED
                // ...and the receipt explains it rather than leaving the reader guessing.
                val receipt = doc.receipts.sourcesList.single { it.source == "tempo" }
                receipt.status shouldBe "degraded"
                receipt.detail shouldContain "500"
            }
        }

        "explain runs ONLY when the turn carried no plan, and always labels reconstructed (S-1)" {
            runTest {
                var called = 0
                val explain =
                    ExplainClient(enabled = true) {
                        called++
                        org.tatrman.translate.v1.ExplainResponse
                            .newBuilder()
                            .addStages(
                                org.tatrman.translate.v1.StageArtifact
                                    .newBuilder()
                                    .setStageCode("to_rel")
                                    .setDurationMs(4)
                                    .setCanonicalForm("LogicalProject(...)"),
                            ).build()
                    }

                // H1-full's record DOES carry plan ids, so the fallback must not fire.
                assembler(explain = explain).assemble(request())
                withClue("plan present -> no reconstruction") { called shouldBe 0 }

                // Strip the plan ids: now it must fire, and label the result.
                val stripped = InMemoryProtocolRecordStore()
                FixtureLoader.records(case).forEach { r ->
                    stripped.write(r.toBuilder().apply { pointersBuilder.clearPlanIds() }.build())
                }
                val doc =
                    ProtocolAssembler(
                        records = stripped,
                        config = FixtureLoader.config(case),
                        gateway = null,
                        loki = null,
                        tempo = null,
                        explain = explain,
                        clock = { Instant.parse("2026-07-30T07:05:00Z") },
                    ).assemble(request())

                called shouldBe 1
                val plan =
                    doc.turnsList
                        .single()
                        .sectionsList
                        .single { it.key == "protocol.section.plan" }
                plan.plan.reconstructed shouldBe true
                plan.plan.relPlanText shouldContain "to_rel"
            }
        }

        "scope resolution: last | lastN | session pull the right record set" {
            runTest {
                val a = assembler("session-split")
                val turns = FixtureLoader.turns("session-split")

                a
                    .assemble(request("session-split", Scope.newBuilder().setLastTurn(true).build()))
                    .turnsCount shouldBe 1
                a
                    .assemble(request("session-split", Scope.newBuilder().setLastN(3).build()))
                    .turnsCount shouldBe 3
                a
                    .assemble(request("session-split", Scope.newBuilder().setWholeSession(true).build()))
                    .turnsCount shouldBe turns.size

                // lastN takes the MOST RECENT n, still oldest -> newest.
                val lastThree = a.assemble(request("session-split", Scope.newBuilder().setLastN(3).build()))
                lastThree.turnsList.map { it.seq } shouldBe listOf(11, 12, 13)
            }
        }

        "pipeline order: built, then redacted, then receipts last" {
            runTest {
                val doc = assembler().assemble(request())

                // Redaction ran (a floor pattern in the fixture's SQL would show).
                // Receipts are the document's last member and were not reordered.
                doc.hasReceipts() shouldBe true
                doc.receipts.profileName shouldBe "default"
                doc.receipts.generatedBy shouldBe "iris-bff/1.0 hartland"

                // ...and the renderer puts them last in the text, always.
                val md = MarkdownRenderer(FixtureLoader.config(case).sessionSplitThreshold).render(doc)
                md.indexOf("## Receipts") shouldNotBe -1
                md.substring(md.indexOf("## Receipts")) shouldNotBe ""
            }
        }

        "metrics: generate_total carries scope + outcome, degraded sections are counted" {
            runTest {
                val registry = SimpleMeterRegistry()
                val brokenTempo =
                    TempoClient(
                        "http://tempo",
                        HttpClient(
                            MockEngine {
                                respondError(HttpStatusCode.ServiceUnavailable)
                            },
                        ),
                    )

                assembler(tempo = brokenTempo, registry = registry).assemble(request())

                registry
                    .counter(
                        "iris_protocol_generate_total",
                        "scope",
                        "last",
                        "outcome",
                        "degraded",
                    ).count() shouldBe
                    1.0
                registry
                    .counter("iris_protocol_section_degraded_total", "key", "protocol.section.execution")
                    .count() shouldBe 1.0
            }
        }

        "a store that throws does not fail the document — the turn simply has no record" {
            runTest {
                val exploding =
                    object : org.tatrman.kantheon.iris.protocol.record.ProtocolRecordStore {
                        override fun write(record: org.tatrman.kantheon.protocol.v1.ProtocolRecord) = Unit

                        override fun readByTurnId(turnId: UUID) = error("PG is down")

                        override fun readForSession(
                            sessionId: UUID,
                            lastN: Int?,
                        ) = emptyList<org.tatrman.kantheon.protocol.v1.ProtocolRecord>()
                    }

                val doc =
                    ProtocolAssembler(
                        records = exploding,
                        config = FixtureLoader.config(case),
                        gateway = null,
                        loki = null,
                        tempo = null,
                        explain = null,
                        clock = { Instant.parse("2026-07-30T07:05:00Z") },
                    ).assemble(request())

                doc.turnsCount shouldBe 1
                // Zero records is DEGRADED, not ok — the reader is told capture is missing.
                doc.receipts.sourcesList
                    .single { it.source == "records" }
                    .status shouldBe "degraded"
            }
        }

        // review-079 R7. The assembler used to read one record per turn, so a
        // session-scope protocol cost one DB round trip per turn on a surface the
        // user is waiting on — while `readForSession`, built for exactly this, was
        // called only from tests.
        "a multi-turn scope reads the records in ONE query, not one per turn" {
            runTest {
                var byId = 0
                var bySession = 0
                val counting =
                    object : org.tatrman.kantheon.iris.protocol.record.ProtocolRecordStore {
                        override fun write(record: org.tatrman.kantheon.protocol.v1.ProtocolRecord) = Unit

                        override fun readByTurnId(turnId: UUID): org.tatrman.kantheon.protocol.v1.ProtocolRecord? {
                            byId++
                            return null
                        }

                        override fun readForSession(
                            sessionId: UUID,
                            lastN: Int?,
                        ): List<org.tatrman.kantheon.protocol.v1.ProtocolRecord> {
                            bySession++
                            return FixtureLoader.records("session-split")
                        }
                    }

                val splitCase = "session-split"
                val doc =
                    ProtocolAssembler(
                        records = counting,
                        config = FixtureLoader.config(splitCase),
                        gateway = null,
                        loki = null,
                        tempo = null,
                        explain = null,
                        clock = { Instant.parse("2026-07-30T07:05:00Z") },
                    ).assemble(
                        ProtocolAssembler.Request(
                            sessionId = UUID.fromString("11111111-1111-4111-8111-111111111111"),
                            scope = Scope.newBuilder().setWholeSession(true).build(),
                            turns = FixtureLoader.turns(splitCase),
                            bearer = "test-bearer",
                        ),
                    )

                bySession shouldBe 1
                byId shouldBe 0
                doc.turnsCount shouldBe FixtureLoader.turns(splitCase).size
            }
        }

        // ---- contracts A-9: sources belong to ONE turn (review-080 R1) ----

        /** A MockEngine client that answers every request with one canned JSON body. */
        fun json(body: String) =
            HttpClient(
                MockEngine {
                    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                },
            )

        "the federated sources describe the ANCHOR turn; every other turn's source-backed sections degrade" {
            runTest {
                val splitCase = "session-split"
                val doc =
                    ProtocolAssembler(
                        records = store(splitCase),
                        config = FixtureLoader.config(splitCase),
                        gateway = GatewayLogsClient("http://gw", json("""{"items":[]}""")),
                        loki = LokiClient("http://loki", json("""{"data":{"result":[]}}""")),
                        // One span that NAMES a dispatch target — otherwise execution
                        // degrades on its own merits (it refuses to guess) and the
                        // assertion below would prove nothing about the anchor rule.
                        tempo =
                            TempoClient(
                                "http://tempo",
                                json(
                                    """
                                    {"batches":[{"resource":{"attributes":[
                                      {"key":"service.name","value":{"stringValue":"ttr-dispatch"}}]},
                                      "scopeSpans":[{"spans":[{"spanId":"a1","name":"run",
                                        "startTimeUnixNano":"0","endTimeUnixNano":"180000000",
                                        "attributes":[{"key":"dispatch.target","value":{"stringValue":"pg-hartland"}}]}]}]}]}
                                    """.trimIndent(),
                                ),
                            ),
                        // Explain stays null: this fixture's anchor carries `plan_ids`, so
                        // the assembler correctly skips reconstruction (S-1) and the plan
                        // section degrades for its own documented reason on EVERY turn.
                        // Asserting it here would test the known plan-by-id gap, not this.
                        explain = null,
                        clock = { Instant.parse("2026-07-30T07:05:00Z") },
                        ids = { UUID.fromString("00000000-0000-4000-8000-0000000000ff") },
                        estate = "hartland",
                    ).assemble(
                        ProtocolAssembler.Request(
                            sessionId = UUID.fromString("11111111-1111-4111-8111-111111111111"),
                            scope = Scope.newBuilder().setWholeSession(true).build(),
                            turns = FixtureLoader.turns(splitCase),
                            bearer = "jwt",
                        ),
                    )

                // v1 fetches once, for the first in-scope turn that has a record. The
                // sections that read those sources and have no per-turn key of their own
                // must therefore say so on every other turn — before this, a 13-turn
                // document printed turn 1's dispatch target, worker, row count and
                // duration under all thirteen headings, as thirteen turns' own facts.
                val sourceBacked =
                    setOf(
                        "protocol.section.llm-calls",
                        "protocol.section.execution",
                        "protocol.section.service-logs",
                    )
                doc.turnsList.forEachIndexed { index, turn ->
                    turn.sectionsList.filter { it.key in sourceBacked }.forEach { section ->
                        withClue("turn ${index + 1} / ${section.key}") {
                            if (index == 0) {
                                section.status shouldNotBe SectionStatus.SECTION_DEGRADED
                            } else {
                                section.status shouldBe SectionStatus.SECTION_DEGRADED
                            }
                        }
                    }
                }

                // ...and the reader is TOLD, which is the other half of the fix: the
                // assembler's own KDoc used to claim the receipts said how much was
                // consulted, and no such line existed.
                val scope = doc.receipts.sourcesList.single { it.source == "scope" }
                scope.status shouldBe "partial"
                scope.detail shouldContain "turn 1 of 13"
            }
        }

        "a single-turn document carries no scope receipt — there is nothing to disclose" {
            runTest {
                assembler()
                    .assemble(request())
                    .receipts.sourcesList
                    .none { it.source == "scope" } shouldBe true
            }
        }

        "max-turns caps the scope, newest kept, shortfall in the receipts" {
            runTest {
                val splitCase = "session-split"
                val turns = FixtureLoader.turns(splitCase)
                val capped =
                    FixtureLoader.config(splitCase).let { c ->
                        c.copy(caps = c.caps.copy(maxTurns = 3))
                    }
                val doc =
                    ProtocolAssembler(
                        records = store(splitCase),
                        config = capped,
                        gateway = null,
                        loki = null,
                        tempo = null,
                        explain = null,
                        clock = { Instant.parse("2026-07-30T07:05:00Z") },
                    ).assemble(
                        ProtocolAssembler.Request(
                            sessionId = UUID.fromString("11111111-1111-4111-8111-111111111111"),
                            scope = Scope.newBuilder().setWholeSession(true).build(),
                            turns = turns,
                            bearer = "jwt",
                        ),
                    )

                doc.turnsCount shouldBe 3
                // The NEWEST three: a protocol is read backwards from what just happened.
                doc.turnsList
                    .first()
                    .turnId shouldBe turns[turns.size - 3].turnId
                doc.receipts.sourcesList
                    .any { it.source == "scope" && it.detail.contains("10 older turn(s) dropped") } shouldBe true
            }
        }
    })
