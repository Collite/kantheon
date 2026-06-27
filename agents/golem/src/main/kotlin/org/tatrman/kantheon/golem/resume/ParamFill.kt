package org.tatrman.kantheon.golem.resume

import com.google.protobuf.util.JsonFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.tatrman.kantheon.envelope.v1.ClarificationOption
import org.tatrman.kantheon.envelope.v1.FormatEnvelope
import org.tatrman.kantheon.envelope.v1.FormatKind
import org.tatrman.kantheon.envelope.v1.FormatSpec
import org.tatrman.kantheon.envelope.v1.PendingClarification
import org.tatrman.kantheon.envelope.v1.PlanSource
import org.tatrman.kantheon.golem.v1.MiniPlan
import java.util.UUID

/**
 * `param_fill` clarification + resume helpers (Δ2). An unbound required param emits an
 * `awaiting_clarification` envelope (`kind="param_fill"`, `error_code="PARAM_FILL_CLARIFICATION"`,
 * the option `id` IS the param name); resume binds the user's answer back into the partial plan
 * and re-enters at execute (the cascade-skip).
 */
object ParamFill {
    const val KIND = "param_fill"
    const val ERROR_CODE = "PARAM_FILL_CLARIFICATION"

    private val json = Json { ignoreUnknownKeys = true }
    private val protoParser = JsonFormat.parser().ignoringUnknownFields()
    private val protoPrinter = JsonFormat.printer().omittingInsignificantWhitespace()

    /** The clarification envelope asking for [paramName] (label [label]); [resumeToken] resumes it. */
    fun clarificationEnvelope(
        turnId: String,
        agentId: String,
        paramName: String,
        label: String,
        resumeToken: String,
    ): FormatEnvelope {
        val prompt = label.ifBlank { "Zadejte prosím hodnotu parametru: $paramName." }
        return FormatEnvelope
            .newBuilder()
            .setBubbleId(UUID.randomUUID().toString())
            .setTurnId(turnId)
            .setText(prompt)
            .setFormat(FormatSpec.newBuilder().setKind(FormatKind.PLAINTEXT))
            .setPlanSource(PlanSource.CLARIFICATION)
            .setErrorCode(ERROR_CODE)
            .setAgentId(agentId)
            .setPendingClarification(
                PendingClarification
                    .newBuilder()
                    .setKind(KIND)
                    .setResumeToken(resumeToken)
                    .setContextText(prompt)
                    .addOptions(ClarificationOption.newBuilder().setId(paramName).setDisplay(prompt)),
            ).build()
    }

    /** Proto-JSON of [plan] for the resume token's `pickedPlanJson`. */
    fun planToJson(plan: MiniPlan): String = protoPrinter.print(plan)

    /**
     * Bind [answer] into the partial plan's primary pattern args (`bind_param_fill`). Parses the
     * plan from its proto-JSON, sets `args[paramName] = answer` on the primary pattern node's
     * `params_json`, and returns the re-bound plan.
     */
    fun bindParamFill(
        pickedPlanJson: String,
        paramName: String,
        answer: String,
    ): MiniPlan {
        val builder = MiniPlan.newBuilder()
        protoParser.merge(pickedPlanJson, builder)
        val idx = builder.nodesBuilderList.indexOfFirst { it.hasQuery() && it.query.hasPatternId() }
        if (idx < 0) return builder.build()
        val node = builder.getNodesBuilder(idx)
        val args = parseArgs(node.query.paramsJson).toMutableMap()
        args[paramName] = JsonPrimitive(answer)
        node.queryBuilder.paramsJson = JsonObject(args).toString()
        return builder.build()
    }

    private fun parseArgs(raw: String): Map<String, JsonElement> {
        if (raw.isBlank() || raw == "{}") return emptyMap()
        return runCatching { json.parseToJsonElement(raw).jsonObject.toMap() }
            .getOrElse { emptyMap<String, JsonElement>() }
    }
}
