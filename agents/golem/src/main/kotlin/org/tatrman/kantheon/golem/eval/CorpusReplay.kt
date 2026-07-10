package org.tatrman.kantheon.golem.eval

import com.google.protobuf.util.JsonFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.meta.v1.ModelBundle
import org.tatrman.meta.v1.ModelBundleQuery
import org.tatrman.meta.v1.ObjectDescriptor
import org.tatrman.meta.v1.QueryDescriptor
import org.tatrman.meta.v1.QueryParameterDef
import org.tatrman.kantheon.envelope.v1.FormatEnvelope
import org.tatrman.kantheon.golem.context.ModelSnapshot
import org.tatrman.kantheon.golem.execution.CompileResult
import org.tatrman.kantheon.golem.execution.MiniPlanExecutor
import org.tatrman.kantheon.golem.execution.QueryClient
import org.tatrman.kantheon.golem.execution.QueryResult
import org.tatrman.kantheon.golem.v1.GolemRequest
import org.tatrman.kantheon.golem.v1.MiniPlan
import org.tatrman.plan.v1.QualifiedName
import java.io.File

/** One replayed corpus turn + the divergences found against the recorded v2 envelope. */
data class ReplayTurn(
    val name: String,
    val divergences: List<Divergence>,
)

/** A full corpus replay run. */
data class ReplayReport(
    val turns: List<ReplayTurn>,
) {
    val bugs: List<ReplayTurn> get() = turns.filter { t -> t.divergences.any { it.cls == DivergenceClass.BUG } }

    fun markdown(): String =
        buildString {
            appendLine("# Golem parity report (diff-harness, S3.3)")
            appendLine()
            appendLine(
                "Turns: ${turns.size} · bug-class: ${bugs.size} · clean-or-acceptable: ${turns.size - bugs.size}",
            )
            appendLine()
            turns.forEach { t ->
                val tag = if (t.divergences.any { it.cls == DivergenceClass.BUG }) "❌ BUG" else "✅"
                appendLine("## $tag ${t.name}")
                if (t.divergences.isEmpty()) {
                    appendLine("- (identical on the compared surface)")
                } else {
                    t.divergences.forEach { d ->
                        appendLine(
                            "- **${d.cls}** `${d.field}`: expected `${d.expected}` / actual `${d.actual}`" +
                                (if (d.note.isNotBlank()) " — ${d.note}" else ""),
                        )
                    }
                }
                appendLine()
            }
        }
}

/**
 * Replay diff-harness (contracts §8 / S3.3). Reads recorded v2 corpus turns (the JSONL files under
 * `eval/corpus/conversations/`), drives each through the Kotlin Golem executor + format pipeline with the
 * recorded upstream rows, and diffs the produced `envelope/v1` envelope field-wise against the
 * recorded v2 envelope ([EnvelopeDiff]). The CI gate (Phase 4) requires zero `BUG`-class
 * divergences across the curated set; kantheon's intended additions are tolerated as `ACCEPTABLE`.
 *
 * Corpus line shape (one JSON object per line):
 * ```
 * { "name": "...", "request": {question, golemId, intentKind?},
 *   "model": {"patterns":[{id, sourceText?, resultKindHint?, params:[{name,type,optional}]}]},
 *   "plan": <MiniPlan proto-JSON>, "rows": [ ... ], "rowCount": N,
 *   "expectedEnvelope": <FormatEnvelope proto-JSON> }
 * ```
 */
class CorpusReplay(
    private val corpusDir: File,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val protoParser = JsonFormat.parser().ignoringUnknownFields()

    suspend fun replay(): ReplayReport {
        val files = corpusDir.listFiles { f -> f.extension == "jsonl" }?.sortedBy { it.name } ?: emptyList()
        val turns = mutableListOf<ReplayTurn>()
        for (file in files) {
            for ((lineNo, line) in file.readLines().withIndex()) {
                if (line.isBlank()) continue
                // A malformed corpus line becomes one BUG-class turn, not an uncaught stack trace
                // that nukes the whole gate — one typo shouldn't hide every other turn's result.
                turns +=
                    runCatching { replayTurn(json.parseToJsonElement(line).jsonObject) }
                        .getOrElse { e ->
                            ReplayTurn(
                                "${file.name}:${lineNo + 1}",
                                listOf(
                                    Divergence(
                                        "corpus",
                                        "a well-formed turn",
                                        e.message ?: "parse error",
                                        DivergenceClass.BUG,
                                        "malformed corpus line",
                                    ),
                                ),
                            )
                        }
            }
        }
        return ReplayReport(turns)
    }

    private suspend fun replayTurn(entry: JsonObject): ReplayTurn {
        val name = entry["name"]?.jsonPrimitive?.content ?: "unnamed"
        val requestObj = entry["request"]?.jsonObject ?: error("corpus turn '$name' has no 'request'")
        val request = buildRequest(requestObj, name)
        val model = entry["model"]?.let { buildModel(it.jsonObject) }
        val plan = MiniPlan.newBuilder().also { protoParser.merge(entry["plan"].toString(), it) }.build()
        val rows = entry["rows"]?.jsonArray ?: JsonArray(emptyList())
        val rowCount = entry["rowCount"]?.jsonPrimitive?.content?.toLongOrNull() ?: rows.size.toLong()
        val expected =
            FormatEnvelope.newBuilder().also { protoParser.merge(entry["expectedEnvelope"].toString(), it) }.build()

        val executor = MiniPlanExecutor(FixedRowsQueryClient(rows, rowCount))
        val result = executor.execute(plan, request, model, bearer = "eval-tok")
        val actual = result.envelopes.firstOrNull() ?: FormatEnvelope.getDefaultInstance()
        return ReplayTurn(name, EnvelopeDiff.diff(expected, actual))
    }

    private fun buildRequest(
        o: JsonObject,
        name: String,
    ): GolemRequest {
        // Traceable, deterministic id from the corpus name (not String.hashCode(), which is opaque
        // in step records and can collide across turns).
        val b =
            GolemRequest
                .newBuilder()
                .setId("eval-$name")
                .setGolemId(o["golemId"]?.jsonPrimitive?.content ?: "golem-erp")
                .setQuestion(o["question"]?.jsonPrimitive?.content ?: "")
        return b.build()
    }

    private fun buildModel(o: JsonObject): ModelSnapshot {
        val bundle = ModelBundle.newBuilder()
        (o["patterns"]?.jsonArray ?: JsonArray(emptyList())).forEach { el ->
            val p = el.jsonObject
            val id = p["id"]?.jsonPrimitive?.content ?: return@forEach
            val q =
                ModelBundleQuery
                    .newBuilder()
                    .setObjectDescriptor(
                        ObjectDescriptor
                            .newBuilder()
                            .setLocalName(
                                id,
                            ).setQualifiedName(QualifiedName.newBuilder().setName(id)),
                    ).setSourceText(p["sourceText"]?.jsonPrimitive?.content ?: "SELECT 1")
                    .setQueryDescriptor(
                        QueryDescriptor.newBuilder().setResultKindHint(
                            p["resultKindHint"]?.jsonPrimitive?.content ?: "",
                        ),
                    )
            (p["params"]?.jsonArray ?: JsonArray(emptyList())).forEach { pe ->
                val pp = pe.jsonObject
                q.addParameters(
                    QueryParameterDef
                        .newBuilder()
                        .setName(pp["name"]?.jsonPrimitive?.content ?: "")
                        .setType(pp["type"]?.jsonPrimitive?.content ?: "varchar")
                        .setOptional(pp["optional"]?.jsonPrimitive?.content?.toBoolean() ?: false),
                )
            }
            bundle.addPatternQueries(q)
        }
        return ModelSnapshot.from(bundle.build())
    }
}

/** A [QueryClient] returning a fixed recorded row set — the harness's mocked upstream. */
private class FixedRowsQueryClient(
    private val rows: JsonArray,
    private val rowCount: Long,
) : QueryClient {
    override suspend fun query(
        source: String,
        sourceLanguage: String,
        paramsJson: String,
        rowLimit: Int?,
        bearer: String?,
    ): QueryResult = QueryResult(rows, emptyList(), rowCount, truncated = false)

    override suspend fun compile(
        source: String,
        sourceLanguage: String,
        targetDialect: String,
        bearer: String?,
    ): CompileResult = CompileResult(source, true)
}
