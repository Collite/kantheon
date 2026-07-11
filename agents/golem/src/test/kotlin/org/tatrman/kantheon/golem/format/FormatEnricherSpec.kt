package org.tatrman.kantheon.golem.format

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import org.tatrman.meta.v1.ModelBundle
import org.tatrman.meta.v1.ModelBundleQuery
import org.tatrman.meta.v1.ObjectDescriptor
import org.tatrman.meta.v1.QueryDescriptor
import org.tatrman.kantheon.envelope.v1.FormatEnvelope
import org.tatrman.kantheon.envelope.v1.FormatKind
import org.tatrman.kantheon.envelope.v1.FormatSpec
import org.tatrman.kantheon.golem.context.ModelSnapshot
import org.tatrman.kantheon.golem.v1.GolemRequest
import org.tatrman.kantheon.golem.v1.MiniPlan
import org.tatrman.kantheon.golem.v1.MiniPlanNode
import org.tatrman.kantheon.golem.v1.PlanSource
import org.tatrman.kantheon.golem.v1.QueryNode

private fun base(): FormatEnvelope.Builder =
    FormatEnvelope
        .newBuilder()
        .setBubbleId("b1")
        .setTurnId("t1")
        .setFormat(FormatSpec.newBuilder().setKind(FormatKind.TABLE))

private fun request(question: String = "kolik?") =
    GolemRequest
        .newBuilder()
        .setId("t1")
        .setGolemId("golem-erp")
        .setQuestion(question)
        .build()

private fun planWith(
    patternId: String?,
    source: PlanSource = PlanSource.PATTERN,
    losing: String? = null,
): MiniPlan {
    val b = MiniPlan.newBuilder().setSource(source).setConfidence(0.9)
    val node = MiniPlanNode.newBuilder().setNodeId("q1")
    val q = QueryNode.newBuilder().setParamsJson("""{"kod":"DF01"}""")
    if (patternId != null) q.patternId = patternId
    b.addNodes(node.setQuery(q))
    losing?.let { b.losingPlanSummary = it }
    return b.build()
}

private fun modelWithHint(
    patternId: String,
    hint: String,
): ModelSnapshot =
    ModelSnapshot.from(
        ModelBundle
            .newBuilder()
            .addPatternQueries(
                ModelBundleQuery
                    .newBuilder()
                    .setObjectDescriptor(ObjectDescriptor.newBuilder().setLocalName(patternId))
                    .setQueryDescriptor(QueryDescriptor.newBuilder().setResultKindHint(hint))
                    .build(),
            ).build(),
    )

private val rows: JsonArray =
    buildJsonArray {
        addJsonObject {
            put("KOD_STR", "DF01")
            put("ZUSTATEK", 12500.5)
        }
    }

class FormatEnricherSpec :
    StringSpec({

        val enricher = FormatEnricher()

        "enriches the table envelope with chips, drilldowns, current_view(total_rows), losing_plan" {
            runTest {
                val env =
                    enricher.enrich(
                        base(),
                        rows,
                        request(),
                        model = null,
                        plan = planWith(patternId = null, losing = "free_sql() @ 0.40"),
                        rowCount = 137,
                    )
                // KOD_STR heuristic chip attaches.
                env.chipsList.any { it.prompt.display == "Detail střediska" } shouldBe true
                // current_view carries the total row count.
                env.currentView.totalRows shouldBe 137
                env.losingPlanSummary shouldBe "free_sql() @ 0.40"
                // The table format (typed column spec) is preserved.
                env.format.kind shouldBe FormatKind.TABLE
            }
        }

        "a result_kind_hint='chart' promotes the envelope to a compiled chart" {
            runTest {
                val env =
                    enricher.enrich(
                        base(),
                        rows,
                        request(),
                        model = modelWithHint("stred", "chart"),
                        plan = planWith(patternId = "stred"),
                        rowCount = 1,
                    )
                env.format.kind shouldBe FormatKind.CHART
                env.format.chart.intent.kind shouldBe "bar"
                // The Vega-Lite spec compiled (x = KOD_STR nominal, y = ZUSTATEK numeric).
                env.format.chart.vegaLiteSpecJson shouldContain "ZUSTATEK"
            }
        }

        "chips are deduplicated across sources — an LLM top-up echoing a heuristic chip collapses" {
            runTest {
                // The LLM top-up returns the same prompt the KOD_STR heuristic emits.
                val dupTopup = LlmTopupChips(FormatConfig()) { "[\"Detail střediska\"]" }
                val env =
                    FormatEnricher(FormatConfig(), dupTopup).enrich(
                        base(),
                        rows,
                        request(),
                        model = null,
                        plan = planWith(patternId = null),
                        rowCount = 1,
                    )
                env.chipsList.count { it.prompt.prompt == "Detail střediska" } shouldBe 1
            }
        }

        "an inferred CHART the rows can't support stays a TABLE and emits a Rule-6 hint" {
            runTest {
                val nonNumeric = buildJsonArray { addJsonObject { put("KOD_STR", "DF01") } }
                val env =
                    enricher.enrich(
                        base(),
                        nonNumeric,
                        request(),
                        model = modelWithHint("stred", "chart"),
                        plan = planWith(patternId = "stred"),
                        rowCount = 1,
                    )
                env.format.kind shouldBe FormatKind.TABLE
                env.messagesList.any { it.code == "CHART_NOT_RENDERABLE" } shouldBe true
            }
        }
    })
