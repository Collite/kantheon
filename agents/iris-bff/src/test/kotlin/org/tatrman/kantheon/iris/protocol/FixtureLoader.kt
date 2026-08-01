package org.tatrman.kantheon.iris.protocol

import com.google.protobuf.util.JsonFormat
import com.typesafe.config.ConfigFactory
import kotlinx.serialization.json.Json
import org.tatrman.kantheon.iris.protocol.config.ProtocolConfig
import org.tatrman.kantheon.iris.protocol.model.DocumentBuilder
import org.tatrman.kantheon.iris.protocol.sections.TurnFacts
import org.tatrman.kantheon.iris.protocol.sources.ExplainSource
import org.tatrman.kantheon.iris.protocol.sources.GatewaySource
import org.tatrman.kantheon.iris.protocol.sources.LokiSource
import org.tatrman.kantheon.iris.protocol.sources.ProtocolSources
import org.tatrman.kantheon.iris.protocol.sources.TempoSource
import org.tatrman.kantheon.protocol.v1.ProtocolDocument
import org.tatrman.kantheon.protocol.v1.ProtocolRecord
import java.io.File

/**
 * Loads the golden-fixture corpus (contracts §9, PT-22).
 *
 * **A ninth file beyond the contract's eight: `turns.json`** (Amendment A-3).
 * The document needs the `iris_turns` row's own facts — question, agent, status,
 * origin, duration — which live in a different table from the protocol record and
 * genuinely are a separate input. Folding them into `record.json` would have made
 * that file stop being "a `ProtocolRecord[]`", which contracts §9 says it is.
 */
object FixtureLoader {
    val ROOT: File = File("src/test/resources/fixtures/protocol")

    val CASES: List<String> =
        listOf(
            "H1-full",
            "H3-operator",
            "degraded-loki",
            "reconstructed-plan",
            "truncation",
            "session-split",
            // Hostile content in every field the renderer fences or tables. The other
            // six cases all carry well-behaved text, so they prove the renderer's
            // shape and nothing about its resistance (review-079 R2/R3).
            "injection",
        )

    /** The files contracts §9 requires, plus `turns.json` (A-3). */
    val REQUIRED_FILES: List<String> =
        listOf(
            "record.json",
            "turns.json",
            "config.conf",
            "sources/gateway.json",
            "sources/loki.json",
            "sources/tempo.json",
            "sources/explain.json",
        )

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    private val protoParser = JsonFormat.parser().ignoringUnknownFields()
    private val protoPrinter = JsonFormat.printer()

    fun dir(case: String): File = File(ROOT, case)

    fun exists(
        case: String,
        relative: String,
    ): Boolean = File(dir(case), relative).isFile

    fun records(case: String): List<ProtocolRecord> =
        readJsonArray(File(dir(case), "record.json")).map { element ->
            ProtocolRecord.newBuilder().also { protoParser.merge(element, it) }.build()
        }

    fun turns(case: String): List<TurnFacts> = json.decodeFromString(File(dir(case), "turns.json").readText())

    fun config(case: String): ProtocolConfig =
        ProtocolConfig.from(ConfigFactory.parseFile(File(dir(case), "config.conf")))

    /**
     * The case's canned sources, stamped with the anchor turn (contracts A-9).
     *
     * The stamp is **derived here the way the assembler derives it** — first in-scope
     * turn that has a record — rather than declared in a fixture file, because the
     * four files under `sources/` describe one turn's context between them and there
     * is nowhere sensible to write it. Without the stamp a multi-turn case renders the
     * anchor's execution, logs and plan under every turn heading, which is exactly what
     * `session-split`'s golden used to assert (review-080 R1).
     */
    fun sources(
        case: String,
        anchorTurnId: String = "",
    ): ProtocolSources {
        val d = File(dir(case), "sources")
        return ProtocolSources(
            anchorTurnId = anchorTurnId,
            gateway = json.decodeFromString<GatewaySource>(File(d, "gateway.json").readText()),
            loki = json.decodeFromString<LokiSource>(File(d, "loki.json").readText()),
            tempo = json.decodeFromString<TempoSource>(File(d, "tempo.json").readText()),
            explain = json.decodeFromString<ExplainSource>(File(d, "explain.json").readText()),
        )
    }

    fun expectedModel(case: String): ProtocolDocument =
        ProtocolDocument
            .newBuilder()
            .also { protoParser.merge(File(dir(case), "expected-model.json").readText(), it) }
            .build()

    fun expectedMarkdown(case: String): String = File(dir(case), "expected.md").readText()

    fun writeExpectedModel(
        case: String,
        doc: ProtocolDocument,
    ) = File(dir(case), "expected-model.json").writeText(protoPrinter.print(doc) + "\n")

    fun writeExpectedMarkdown(
        case: String,
        md: String,
    ) = File(dir(case), "expected.md").writeText(md)

    /**
     * The document a case describes. Ids and the timestamp are fixed per case so
     * a fixture comparison is deterministic — the builder is pure precisely so
     * this is possible.
     */
    fun request(case: String): DocumentBuilder.Request {
        val turns = turns(case)
        val records = records(case).associateBy { it.turnId }
        val cfg = config(case)
        val sessionScope = turns.size > 1
        return DocumentBuilder.Request(
            protocolId = "00000000-0000-4000-8000-0000000000ff",
            sessionId = "11111111-1111-4111-8111-111111111111",
            scope =
                org.tatrman.kantheon.protocol.v1.Scope
                    .newBuilder()
                    .apply { if (sessionScope) wholeSession = true else lastTurn = true }
                    .build(),
            generatedAt = "2026-07-30T09:05:00+02:00",
            turns =
                turns.map { f ->
                    DocumentBuilder.TurnInput(
                        facts = f,
                        record = records[f.turnId] ?: ProtocolRecord.getDefaultInstance(),
                    )
                },
            sources =
                sources(
                    case,
                    anchorTurnId = turns.firstOrNull { records.containsKey(it.turnId) }?.turnId.orEmpty(),
                ),
            config = cfg,
            sessionCreatedAt = "2026-07-30T09:00:00+02:00",
            estate = "hartland",
            assemblerVersion = "1.0",
        )
    }

    /** `record.json` is a JSON array of proto-JSON objects; split it without a proto wrapper type. */
    private fun readJsonArray(file: File): List<String> {
        val root =
            kotlinx.serialization.json.Json
                .parseToJsonElement(file.readText())
        return (root as kotlinx.serialization.json.JsonArray).map { it.toString() }
    }
}
