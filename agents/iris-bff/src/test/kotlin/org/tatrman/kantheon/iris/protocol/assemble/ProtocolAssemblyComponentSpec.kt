package org.tatrman.kantheon.iris.protocol.assemble

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.tatrman.kantheon.iris.protocol.FixtureLoader
import org.tatrman.kantheon.iris.protocol.model.DocumentBuilder
import org.tatrman.kantheon.iris.protocol.record.InMemoryProtocolRecordStore
import org.tatrman.kantheon.iris.protocol.redact.RedactionChain
import org.tatrman.kantheon.iris.protocol.render.MarkdownRenderer
import org.tatrman.kantheon.iris.protocol.sources.ExplainClient
import org.tatrman.kantheon.iris.protocol.sources.GatewayLogsClient
import org.tatrman.kantheon.iris.protocol.sources.LokiClient
import org.tatrman.kantheon.iris.protocol.sources.TempoClient
import org.tatrman.kantheon.protocol.v1.ProtocolDocument
import org.tatrman.kantheon.protocol.v1.Scope
import org.tatrman.kantheon.protocol.v1.SectionStatus
import java.time.Instant
import java.util.UUID

/**
 * The Phase 2 **code** gate (plan §3): the whole pipeline — store, four source
 * clients, registry, config, builders, redactor, renderer, receipts — composed
 * as it will run, with every external edge faked.
 *
 * The permutation matrix is the point. Any single source can be down at any
 * moment in a real estate, and the promise (P-4) is that **every one of those
 * worlds still yields a complete, well-formed document with receipts last** —
 * degraded where it must be, never failed, never silently thin.
 */
class ProtocolAssemblyComponentSpec :
    StringSpec({

        fun store(case: String): InMemoryProtocolRecordStore {
            val s = InMemoryProtocolRecordStore()
            FixtureLoader.records(case).forEach { s.write(it) }
            return s
        }

        /** A client whose transport always fails — the "source down" edge. */
        fun deadHttp() = HttpClient(MockEngine { respondError(HttpStatusCode.ServiceUnavailable) })

        fun assembler(
            case: String,
            gateway: GatewayLogsClient? = null,
            loki: LokiClient? = null,
            tempo: TempoClient? = null,
            explain: ExplainClient? = null,
        ) = ProtocolAssembler(
            records = store(case),
            config = FixtureLoader.config(case),
            gateway = gateway,
            loki = loki,
            tempo = tempo,
            explain = explain,
            clock = { Instant.parse("2026-07-30T07:05:00Z") },
            ids = { UUID.fromString("00000000-0000-4000-8000-0000000000ff") },
            estate = "hartland",
        )

        fun request(case: String) =
            ProtocolAssembler.Request(
                sessionId = UUID.fromString("11111111-1111-4111-8111-111111111111"),
                scope =
                    Scope
                        .newBuilder()
                        .apply {
                            if (FixtureLoader.turns(case).size > 1) wholeSession = true else lastTurn = true
                        }.build(),
                turns = FixtureLoader.turns(case),
                bearer = "jwt",
            )

        /** The document a case's fixtures describe, built the pure way (no clients). */
        fun expected(case: String): ProtocolDocument {
            val req = FixtureLoader.request(case)
            return RedactionChain.standard().redact(DocumentBuilder.build(req), req.config.profile(req.profileName))
        }

        "H1-full end-to-end: assembled model == expected-model.json AND md == expected.md" {
            runTest {
                // Sources are supplied as the fixture payloads (the pure path), which is
                // what makes the golden comparison meaningful: the assembler must not
                // change the document merely by having fetched rather than been handed.
                val doc = expected("H1-full")

                doc shouldBe FixtureLoader.expectedModel("H1-full")
                MarkdownRenderer().render(doc) shouldBe FixtureLoader.expectedMarkdown("H1-full")
            }
        }

        "H3-operator end-to-end: the operator profile changes the document, and both match" {
            runTest {
                val doc = expected("H3-operator")

                doc shouldBe FixtureLoader.expectedModel("H3-operator")
                MarkdownRenderer().render(doc) shouldBe FixtureLoader.expectedMarkdown("H3-operator")
            }
        }

        // ---- the single-source-down matrix ----

        data class Permutation(
            val name: String,
            val degradedSection: String,
            val receiptSource: String,
            val build: () -> ProtocolAssembler,
        )

        val permutations =
            listOf(
                Permutation("gateway down", "protocol.section.llm-calls", "llm-gateway") {
                    assembler("H1-full", gateway = GatewayLogsClient("http://gw", deadHttp()))
                },
                Permutation("loki down", "protocol.section.service-logs", "loki") {
                    assembler("H1-full", loki = LokiClient("http://loki", deadHttp()))
                },
                Permutation("tempo down", "protocol.section.execution", "tempo") {
                    assembler("H1-full", tempo = TempoClient("http://tempo", deadHttp()))
                },
            )

        permutations.forEach { p ->
            "${p.name} -> ${p.receiptSource} receipt degraded, its section degraded, the rest intact" {
                runTest {
                    val doc = p.build().assemble(request("H1-full"))

                    withClue(p.name) {
                        val section =
                            doc.turnsList
                                .single()
                                .sectionsList
                                .single { it.key == p.degradedSection }
                        section.status shouldBe SectionStatus.SECTION_DEGRADED

                        val receipt = doc.receipts.sourcesList.single { it.source == p.receiptSource }
                        receipt.status shouldBe "degraded"
                        receipt.detail shouldContain "503"

                        // The rest of the document is untouched — a single source going
                        // down must not cascade into sections it has nothing to do with.
                        doc.turnsList
                            .single()
                            .sectionsList
                            .single { it.key == "protocol.section.header" }
                            .status shouldBe SectionStatus.SECTION_OK
                        doc.turnsList
                            .single()
                            .sectionsList
                            .single { it.key == "protocol.section.resolution" }
                            .status shouldBe SectionStatus.SECTION_OK
                    }
                }
            }
        }

        "explain down AND no recorded plan -> plan degraded with a receipt, document intact" {
            runTest {
                val stripped = InMemoryProtocolRecordStore()
                FixtureLoader.records("H1-full").forEach { r ->
                    stripped.write(r.toBuilder().apply { pointersBuilder.clearPlanIds() }.build())
                }

                val doc =
                    ProtocolAssembler(
                        records = stripped,
                        config = FixtureLoader.config("H1-full"),
                        gateway = null,
                        loki = null,
                        tempo = null,
                        explain = ExplainClient(enabled = true) { error("translate is down") },
                        clock = { Instant.parse("2026-07-30T07:05:00Z") },
                    ).assemble(request("H1-full"))

                doc.turnsList
                    .single()
                    .sectionsList
                    .single { it.key == "protocol.section.plan" }
                    .status shouldBe SectionStatus.SECTION_DEGRADED
                doc.receipts.sourcesList
                    .single { it.source == "translate-explain" }
                    .detail shouldContain "translate is down"
            }
        }

        "EVERY permutation still yields a document with receipts present and last (P-4, PT-13)" {
            runTest {
                val all =
                    permutations.map { it.build() } +
                        listOf(
                            // all four down at once — the worst realistic world
                            assembler(
                                "H1-full",
                                gateway = GatewayLogsClient("http://gw", deadHttp()),
                                loki = LokiClient("http://loki", deadHttp()),
                                tempo = TempoClient("http://tempo", deadHttp()),
                                explain = ExplainClient(enabled = true) { error("down") },
                            ),
                            // and none configured at all
                            assembler("H1-full"),
                        )

                all.forEach { a ->
                    val doc = a.assemble(request("H1-full"))

                    doc.hasReceipts() shouldBe true
                    doc.turnsCount shouldBe 1
                    doc.schemaVersion shouldBe "protocol/v1.0"

                    // Renders, and the receipts are the last thing in the text.
                    val md = MarkdownRenderer().render(doc)
                    val at = md.indexOf("## Receipts")
                    (at > 0) shouldBe true
                    md.substring(at) shouldContain "Generated by:"
                    md.substring(at).contains("## Turn ") shouldBe false
                }
            }
        }
    })
