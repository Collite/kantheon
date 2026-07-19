package org.tatrman.kantheon.golem.execution

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.tatrman.meta.v1.Language
import org.tatrman.meta.v1.ModelBundle
import org.tatrman.meta.v1.ModelBundleQuery
import org.tatrman.meta.v1.ObjectDescriptor
import org.tatrman.meta.v1.QueryParameterDef
import org.tatrman.kantheon.golem.context.ModelSnapshot
import org.tatrman.kantheon.golem.v1.GolemRequest
import org.tatrman.kantheon.golem.v1.MiniPlan
import org.tatrman.kantheon.golem.v1.MiniPlanNode
import org.tatrman.kantheon.golem.v1.PlanSource
import org.tatrman.kantheon.golem.v1.QueryNode
import org.tatrman.kantheon.golem.v1.ReasoningNode
import org.tatrman.kantheon.golem.v1.RenderNode
import org.tatrman.kantheon.golem.v1.Status

private data class QueryCall(
    val source: String,
    val language: String,
    val paramsJson: String,
    val rowLimit: Int?,
    val bearer: String?,
)

private class FakeQueryClient : QueryClient {
    val queries = mutableListOf<QueryCall>()
    var compiles = 0
    var failOnSource: String? = null
    var rows: JsonArray =
        buildJsonArray {
            add(
                buildJsonObject {
                    put("invoice", "F1")
                    put("amount", 100)
                },
            )
        }

    override suspend fun query(
        source: String,
        sourceLanguage: String,
        paramsJson: String,
        rowLimit: Int?,
        bearer: String?,
    ): QueryResult {
        queries += QueryCall(source, sourceLanguage, paramsJson, rowLimit, bearer)
        if (source == failOnSource) throw QueryException("boom on $source")
        return QueryResult(rows, emptyList(), rows.size.toLong(), truncated = false)
    }

    override suspend fun compile(
        source: String,
        sourceLanguage: String,
        targetDialect: String,
        bearer: String?,
    ): CompileResult {
        compiles++
        return CompileResult(compiledSql = "COMPILED($source)", appliedSecurity = true)
    }
}

private fun param(
    name: String,
    type: String = "varchar",
    optional: Boolean = false,
): QueryParameterDef =
    QueryParameterDef
        .newBuilder()
        .setName(name)
        .setType(type)
        .setOptional(optional)
        .build()

private fun modelWith(
    patternId: String,
    sourceText: String,
    params: List<QueryParameterDef> = emptyList(),
    sourceLanguage: Language = Language.SQL,
): ModelSnapshot {
    val q =
        ModelBundleQuery
            .newBuilder()
            .setObjectDescriptor(ObjectDescriptor.newBuilder().setLocalName(patternId))
            .setSourceText(sourceText)
            .setSourceLanguage(sourceLanguage)
            .addAllParameters(params)
            .build()
    return ModelSnapshot.from(ModelBundle.newBuilder().addPatternQueries(q).build())
}

private fun queryNode(
    id: String,
    patternId: String? = null,
    source: String = "",
    sourceLanguage: String = "transdsl",
    compileFirst: Boolean = false,
    paramsJson: String = "",
): MiniPlanNode {
    val q =
        QueryNode
            .newBuilder()
            .setSource(source)
            .setSourceLanguage(sourceLanguage)
            .setCompileFirst(compileFirst)
            .setParamsJson(paramsJson)
    if (patternId != null) q.patternId = patternId
    return MiniPlanNode
        .newBuilder()
        .setNodeId(id)
        .setQuery(q)
        .build()
}

private fun renderNode(
    id: String,
    vararg inputs: String,
): MiniPlanNode =
    MiniPlanNode
        .newBuilder()
        .setNodeId(
            id,
        ).setRender(RenderNode.newBuilder().addAllInputNodeIds(inputs.toList()))
        .build()

private fun plan(
    source: PlanSource,
    vararg nodes: MiniPlanNode,
): MiniPlan =
    MiniPlan
        .newBuilder()
        .setSource(source)
        .setConfidence(0.95)
        .addAllNodes(nodes.toList())
        .build()

private fun request(): GolemRequest =
    GolemRequest
        .newBuilder()
        .setId("t1")
        .setGolemId("golem-erp")
        .build()

class MiniPlanExecutorSpec :
    StringSpec({

        "a PATTERN query + render produces a table envelope from the pattern's source" {
            runTest {
                val client = FakeQueryClient()
                val model = modelWith("listUnpaidInvoices", "SELECT * FROM invoices")
                val p =
                    plan(
                        PlanSource.PATTERN,
                        queryNode("q1", patternId = "listUnpaidInvoices"),
                        renderNode("r1", "q1"),
                    )
                val result = MiniPlanExecutor(client).execute(p, request(), model, bearer = "tok")

                result.status shouldBe Status.STATUS_DONE
                client.queries.single().source shouldBe "SELECT * FROM invoices"
                result.envelopes.single().contentJson shouldContain "invoice"
                val table =
                    result.envelopes
                        .single()
                        .format.table
                table.headersList.map { it.name } shouldBe listOf("invoice", "amount")
                // Typed column-spec emitter (Δ5): the numeric `amount` column is right-aligned;
                // the string `invoice` column carries no directive.
                table.columnsMap["amount"]!!.alignment shouldBe "right"
                table.columnsMap.containsKey("invoice") shouldBe false
                result.stepRecords.map { it.status } shouldBe listOf("COMPLETED", "COMPLETED")
            }
        }

        "a resolved PATTERN dispatches with the model's language, not the LLM's node guess" {
            runTest {
                // The model says the pattern is SQL; the LLM node mislabels it "transdsl".
                // Golem must honour the pattern's own language (regression: sending SQL as
                // transdsl → translator_rejected → 0 rows, Stage 3.3 T7).
                val client = FakeQueryClient()
                val model =
                    modelWith("channelRevenue", "SELECT 1", sourceLanguage = Language.SQL)
                val p =
                    plan(
                        PlanSource.PATTERN,
                        queryNode("q1", patternId = "channelRevenue", sourceLanguage = "transdsl"),
                        renderNode("r1", "q1"),
                    )
                val result = MiniPlanExecutor(client).execute(p, request(), model, bearer = "tok")

                result.status shouldBe Status.STATUS_DONE
                client.queries.single().language shouldBe "sql"
            }
        }

        "a resolved PATTERN with an unspecified model language falls back to the node's value" {
            runTest {
                val client = FakeQueryClient()
                val model =
                    modelWith("q", "SELECT 1", sourceLanguage = Language.LANGUAGE_UNSPECIFIED)
                val p =
                    plan(
                        PlanSource.PATTERN,
                        queryNode("q1", patternId = "q", sourceLanguage = "transdsl"),
                    )
                MiniPlanExecutor(client).execute(p, request(), model, bearer = "tok")
                client.queries.single().language shouldBe "transdsl"
            }
        }

        "FREE_SQL compiles first, then runs the compiled SQL" {
            runTest {
                val client = FakeQueryClient()
                val p =
                    plan(
                        PlanSource.FREE_SQL,
                        queryNode("q1", source = "SELECT 1", sourceLanguage = "sql", compileFirst = true),
                    )
                MiniPlanExecutor(client).execute(p, request(), null, bearer = "tok")

                client.compiles shouldBe 1
                client.queries.single().source shouldBe "COMPILED(SELECT 1)"
                client.queries.single().language shouldBe "sql"
            }
        }

        "the row cap and OBO bearer are forwarded on every query" {
            runTest {
                val client = FakeQueryClient()
                val p = plan(PlanSource.PATTERN, queryNode("q1", source = "x"))
                MiniPlanExecutor(client, rowCap = 250).execute(p, request(), null, bearer = "user-tok")
                client.queries.single().rowLimit shouldBe 250
                client.queries.single().bearer shouldBe "user-tok"
            }
        }

        "partial failure keeps completed envelopes and reports STATUS_FAILED" {
            runTest {
                val client = FakeQueryClient().apply { failOnSource = "BOOM" }
                val p =
                    plan(
                        PlanSource.PATTERN,
                        queryNode("q1", source = "ok"),
                        renderNode("r1", "q1"),
                        queryNode("q2", source = "BOOM"),
                        renderNode("r2", "q2"),
                    )
                val result = MiniPlanExecutor(client).execute(p, request(), null, bearer = "tok")

                result.status shouldBe Status.STATUS_FAILED
                result.envelopes.size shouldBe 1 // r1 kept; r2 never reached
                result.stepRecords.map { it.status } shouldBe listOf("COMPLETED", "COMPLETED", "FAILED")
                result.stepRecords.last().error shouldContain "boom"
            }
        }

        "a reasoning node is recorded SKIPPED (Phase 3)" {
            runTest {
                val client = FakeQueryClient()
                val reasoning =
                    MiniPlanNode
                        .newBuilder()
                        .setNodeId(
                            "z1",
                        ).setReasoning(ReasoningNode.newBuilder().setOutputKind("TEXT"))
                        .build()
                val p = plan(PlanSource.PATTERN, queryNode("q1", source = "x"), reasoning)
                val result = MiniPlanExecutor(client).execute(p, request(), null, bearer = "tok")
                result.stepRecords.last().status shouldBe "SKIPPED"
                result.status shouldBe Status.STATUS_DONE
            }
        }

        // ---- T8 parametrization rail + T9 PATTERN_PARAM_UNFILLED guard ------------------

        "a PATTERN query sends the template verbatim + a typed {name:{value,type}} map" {
            runTest {
                val client = FakeQueryClient()
                val model =
                    modelWith(
                        "stred",
                        "SELECT * FROM ucetnictvi WHERE NAZEV_STR = {nazev_strediska} AND UCET_OBD = {obdobi}",
                        params = listOf(param("nazev_strediska"), param("obdobi")),
                    )
                val p =
                    plan(
                        PlanSource.PATTERN,
                        queryNode(
                            "q1",
                            patternId = "stred",
                            sourceLanguage = "sql",
                            paramsJson = """{"nazev_strediska":"DF ADNAK","obdobi":"2026.04"}""",
                        ),
                        renderNode("r1", "q1"),
                    )
                val result = MiniPlanExecutor(client).execute(p, request(), model, bearer = "tok")

                result.status shouldBe Status.STATUS_DONE
                val call = client.queries.single()
                // The `{name}` template is forwarded UNCHANGED — Translate rewrites it downstream.
                call.source shouldBe
                    "SELECT * FROM ucetnictvi WHERE NAZEV_STR = {nazev_strediska} AND UCET_OBD = {obdobi}"
                // The parameters are the typed envelope, not the raw args.
                call.paramsJson shouldContain "\"value\":\"DF ADNAK\""
                call.paramsJson shouldContain "\"type\":\"varchar\""
                call.paramsJson shouldContain "\"obdobi\""
            }
        }

        "the rail fuzzy-maps a raw arg key onto the declared param name" {
            runTest {
                val client = FakeQueryClient()
                val model =
                    modelWith(
                        "stred",
                        "SELECT * FROM ucetnictvi WHERE NAZEV_STR = {nazev_strediska}",
                        params = listOf(param("nazev_strediska")),
                    )
                // LLM emitted `stredisko`; the rail normalises it onto `nazev_strediska`.
                val p =
                    plan(
                        PlanSource.PATTERN,
                        queryNode(
                            "q1",
                            patternId = "stred",
                            sourceLanguage = "sql",
                            paramsJson = """{"stredisko":"DF ADNAK"}""",
                        ),
                    )
                val result = MiniPlanExecutor(client).execute(p, request(), model, bearer = "tok")
                result.status shouldBe Status.STATUS_DONE
                client.queries.single().paramsJson shouldContain "\"nazev_strediska\""
            }
        }

        "the guard fails closed with PATTERN_PARAM_UNFILLED on a residual placeholder (never forwarded)" {
            runTest {
                val client = FakeQueryClient()
                // Template references {obdobi}, but it is neither declared nor supplied → unfilled.
                val model =
                    modelWith(
                        "stred",
                        "SELECT * FROM ucetnictvi WHERE NAZEV_STR = {nazev_strediska} AND UCET_OBD = {obdobi}",
                        params = listOf(param("nazev_strediska")),
                    )
                val p =
                    plan(
                        PlanSource.PATTERN,
                        queryNode(
                            "q1",
                            patternId = "stred",
                            sourceLanguage = "sql",
                            paramsJson = """{"nazev_strediska":"DF ADNAK"}""",
                        ),
                    )
                val result = MiniPlanExecutor(client).execute(p, request(), model, bearer = "tok")

                result.status shouldBe Status.STATUS_FAILED
                result.errorCode shouldBe "PATTERN_PARAM_UNFILLED"
                // The under-bound query must NOT reach query.
                client.queries.size shouldBe 0
            }
        }

        "a missing required param surfaces a param_fill signal (S3.2), not a hard fail" {
            runTest {
                val client = FakeQueryClient()
                val model =
                    modelWith(
                        "stred",
                        "SELECT * FROM ucetnictvi WHERE NAZEV_STR = {nazev_strediska}",
                        params = listOf(param("nazev_strediska")),
                    )
                // No args at all → nazev_strediska (required) is missing → ask the user (param_fill).
                val p = plan(PlanSource.PATTERN, queryNode("q1", patternId = "stred", sourceLanguage = "sql"))
                val result = MiniPlanExecutor(client).execute(p, request(), model, bearer = "tok")

                result.paramFill?.paramName shouldBe "nazev_strediska"
                // The query is never forwarded under-bound.
                client.queries.size shouldBe 0
            }
        }

        "an absent optional placeholder still trips the guard (it has no binding)" {
            runTest {
                val client = FakeQueryClient()
                // `limit` is optional + absent → not bound; the {limit} placeholder is unfilled.
                val model =
                    modelWith(
                        "stred",
                        "SELECT * FROM ucetnictvi WHERE NAZEV_STR = {nazev_strediska} LIMIT {limit}",
                        params = listOf(param("nazev_strediska"), param("limit", type = "int", optional = true)),
                    )
                val p =
                    plan(
                        PlanSource.PATTERN,
                        queryNode(
                            "q1",
                            patternId = "stred",
                            sourceLanguage = "sql",
                            paramsJson = """{"nazev_strediska":"DF ADNAK"}""",
                        ),
                    )
                val result = MiniPlanExecutor(client).execute(p, request(), model, bearer = "tok")
                result.status shouldBe Status.STATUS_FAILED
                result.errorCode shouldBe "PATTERN_PARAM_UNFILLED"
                client.queries.size shouldBe 0
            }
        }
    })
