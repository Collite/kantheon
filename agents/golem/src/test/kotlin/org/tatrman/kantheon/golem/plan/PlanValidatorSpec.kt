package org.tatrman.kantheon.golem.plan

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.tatrman.ariadne.v1.ModelBundle
import org.tatrman.ariadne.v1.ModelBundleQuery
import org.tatrman.ariadne.v1.ObjectDescriptor
import org.tatrman.ariadne.v1.QueryParameterDef
import org.tatrman.kantheon.golem.context.ModelSnapshot
import org.tatrman.kantheon.golem.v1.MiniPlan
import org.tatrman.kantheon.golem.v1.MiniPlanNode
import org.tatrman.kantheon.golem.v1.PlanSource
import org.tatrman.kantheon.golem.v1.QueryNode
import org.tatrman.kantheon.golem.v1.RenderNode
import org.tatrman.plan.v1.Value

private fun param(
    name: String,
    required: Boolean,
): QueryParameterDef {
    val b = QueryParameterDef.newBuilder().setName(name)
    if (!required) b.defaultValue = Value.getDefaultInstance()
    return b.build()
}

private fun modelWithPattern(
    id: String,
    vararg params: QueryParameterDef,
): ModelSnapshot {
    val q =
        ModelBundleQuery
            .newBuilder()
            .setObjectDescriptor(ObjectDescriptor.newBuilder().setLocalName(id))
            .addAllParameters(params.toList())
            .build()
    return ModelSnapshot.from(ModelBundle.newBuilder().addPatternQueries(q).build())
}

private fun queryNode(
    id: String,
    patternId: String? = null,
    paramsJson: String = "{}",
    compileFirst: Boolean = false,
    sourceLanguage: String = "transdsl",
): MiniPlanNode {
    val q =
        QueryNode
            .newBuilder()
            .setSourceLanguage(
                sourceLanguage,
            ).setParamsJson(paramsJson)
            .setCompileFirst(compileFirst)
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
    confidence: Double = 0.9,
): MiniPlan =
    MiniPlan
        .newBuilder()
        .setSource(source)
        .setConfidence(confidence)
        .addAllNodes(nodes.toList())
        .build()

class PlanValidatorSpec :
    StringSpec({

        val validator = PlanValidator(maxStepCount = 4)

        "a well-formed PATTERN plan with required params present is valid" {
            val model = modelWithPattern("listUnpaidInvoices", param("customerId", required = true))
            val p =
                plan(
                    PlanSource.PATTERN,
                    queryNode("q1", patternId = "listUnpaidInvoices", paramsJson = """{"customerId":"1"}"""),
                    renderNode("r1", "q1"),
                )
            validator.validate(p, model).isValid shouldBe true
        }

        "an empty plan is rejected" {
            validator.validate(plan(PlanSource.PATTERN), null).isValid shouldBe false
        }

        "exceeding max_step_count is rejected" {
            val nodes = (1..5).map { queryNode("q$it") }.toTypedArray()
            val result = validator.validate(plan(PlanSource.FREE_SQL, *nodes), null)
            result.violations.map { it.message }.any { "max_step_count" in it } shouldBe true
        }

        "a duplicate node_id is rejected" {
            val p = plan(PlanSource.PATTERN, queryNode("q1"), queryNode("q1"))
            validator
                .validate(p, null)
                .violations
                .map { it.message }
                .any { "duplicate" in it } shouldBe true
        }

        "a node referencing a non-earlier node is rejected (linear deps)" {
            val p = plan(PlanSource.PATTERN, renderNode("r1", "q1"), queryNode("q1"))
            val msgs = validator.validate(p, null).violations.map { it.message }
            msgs.any { "not an earlier node" in it } shouldBe true
        }

        "a PATTERN node whose pattern_id is absent from the model is rejected" {
            val model = modelWithPattern("listUnpaidInvoices")
            val p = plan(PlanSource.PATTERN, queryNode("q1", patternId = "ghost"))
            validator
                .validate(p, model)
                .violations
                .map { it.message }
                .any { "not in the model" in it } shouldBe true
        }

        "a missing required pattern param is NOT a validation failure (Δ2: param_fill asks at execute)" {
            val model = modelWithPattern("listUnpaidInvoices", param("customerId", required = true))
            val p = plan(PlanSource.PATTERN, queryNode("q1", patternId = "listUnpaidInvoices", paramsJson = "{}"))
            // The rail surfaces the unbound required param as a param_fill clarification later,
            // so the plan itself validates.
            validator.validate(p, model).isValid shouldBe true
        }

        "an unknown (hallucinated) pattern param is reported" {
            val model = modelWithPattern("listUnpaidInvoices", param("customerId", required = true))
            val p =
                plan(
                    PlanSource.PATTERN,
                    queryNode("q1", patternId = "listUnpaidInvoices", paramsJson = """{"bogus":"x"}"""),
                )
            val v = validator.validate(p, model)
            v.isValid shouldBe false
            v.violations.map { it.message }.any { "no param 'bogus'" in it } shouldBe true
        }

        "a pattern param with a default is not required" {
            val model = modelWithPattern("listUnpaidInvoices", param("limit", required = false))
            val p = plan(PlanSource.PATTERN, queryNode("q1", patternId = "listUnpaidInvoices", paramsJson = "{}"))
            validator.validate(p, model).isValid shouldBe true
        }

        "a FREE_SQL query without compile_first is rejected" {
            val p = plan(PlanSource.FREE_SQL, queryNode("q1", sourceLanguage = "sql", compileFirst = false))
            validator
                .validate(
                    p,
                    null,
                ).violations
                .map { it.message }
                .shouldContain("FREE_SQL query must set compile_first")
        }
    })
