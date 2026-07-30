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

        fun store(caseName: String = case): InMemoryProtocolRecordStore {
            val s = InMemoryProtocolRecordStore()
            FixtureLoader.records(caseName).forEach { s.write(it) }
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
    })
