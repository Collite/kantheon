package org.tatrman.kantheon.golem.plan

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.tatrman.meta.v1.ModelBundle
import org.tatrman.meta.v1.ModelBundleQuery
import org.tatrman.meta.v1.ObjectDescriptor
import org.tatrman.meta.v1.QueryParameterDef
import org.tatrman.kantheon.golem.context.ModelSnapshot
import org.tatrman.kantheon.golem.v1.MiniPlan
import org.tatrman.kantheon.golem.v1.MiniPlanNode
import org.tatrman.kantheon.golem.v1.PlanSource
import org.tatrman.kantheon.golem.v1.QueryNode

private fun modelWith(
    patternId: String,
    vararg params: String,
): ModelSnapshot {
    val q =
        ModelBundleQuery
            .newBuilder()
            .setObjectDescriptor(ObjectDescriptor.newBuilder().setLocalName(patternId))
            .setSourceText("SELECT * FROM t")
            .addAllParameters(
                params.map {
                    QueryParameterDef
                        .newBuilder()
                        .setName(it)
                        .setType("varchar")
                        .build()
                },
            ).build()
    return ModelSnapshot.from(ModelBundle.newBuilder().addPatternQueries(q).build())
}

private fun patternPlan(
    patternId: String,
    paramsJson: String,
): MiniPlan =
    MiniPlan
        .newBuilder()
        .setSource(PlanSource.DRILL)
        .addNodes(
            MiniPlanNode
                .newBuilder()
                .setNodeId("q1")
                .setQuery(QueryNode.newBuilder().setPatternId(patternId).setParamsJson(paramsJson)),
        ).build()

private fun selection(vararg cols: Pair<String, String>): JsonObject =
    buildJsonObject { cols.forEach { (k, v) -> put(k, v) } }

private fun primaryArgs(plan: MiniPlan): Map<String, JsonPrimitive> =
    kotlinx.serialization.json.Json
        .parseToJsonElement(
            plan.nodesList
                .first()
                .query.paramsJson,
        ).jsonObject
        .mapValues { it.value as JsonPrimitive }

class SelectionBindingSpec :
    StringSpec({

        "fills an unfilled pattern param from the selected row (case-insensitive column match)" {
            val plan = patternPlan("detailStrediska", paramsJson = "{}")
            // Selected row column KOD_STR matches the (lower-case) param kod_str.
            val bound = bindSelectionArgs(plan, selection("KOD_STR" to "DF02"), modelWith("detailStrediska", "kod_str"))
            primaryArgs(bound)["kod_str"] shouldBe JsonPrimitive("DF02")
        }

        "an explicitly-bound arg wins over the selection (selection is context, not an override)" {
            val plan = patternPlan("detailStrediska", paramsJson = """{"kod_str":"EXPLICIT"}""")
            val bound = bindSelectionArgs(plan, selection("KOD_STR" to "DF02"), modelWith("detailStrediska", "kod_str"))
            primaryArgs(bound)["kod_str"] shouldBe JsonPrimitive("EXPLICIT")
        }

        "leaves the plan unchanged when the selection has no matching column" {
            val plan = patternPlan("detailStrediska", paramsJson = "{}")
            val bound = bindSelectionArgs(plan, selection("OTHER" to "x"), modelWith("detailStrediska", "kod_str"))
            bound shouldBe plan
        }

        "no selection context → no-op" {
            val plan = patternPlan("detailStrediska", paramsJson = "{}")
            bindSelectionArgs(plan, JsonObject(emptyMap()), modelWith("detailStrediska", "kod_str")) shouldBe plan
        }

        "no model → no-op" {
            val plan = patternPlan("detailStrediska", paramsJson = "{}")
            bindSelectionArgs(plan, selection("KOD_STR" to "DF02"), null) shouldBe plan
        }
    })
