package org.tatrman.kantheon.golem.plan

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import org.tatrman.kantheon.golem.context.ModelSnapshot
import org.tatrman.kantheon.golem.prompts.PromptStore
import org.tatrman.kantheon.golem.v1.GolemRequest
import org.tatrman.kantheon.golem.v1.MiniPlan

/** Koog [LLModel] tiers Golem uses. Only `id` matters — the gateway executor maps
 *  it to a tier key ("haiku" CHEAP). */
object GolemModels {
    val Cheap: LLModel = LLModel(provider = LLMProvider.Anthropic, id = "claude-haiku")
}

/** system/user halves of a prompt YAML (the v2 prompt contract). */
internal data class PromptTemplate(
    val system: String,
    val user: String,
) {
    companion object {
        private val mapper = ObjectMapper(YAMLFactory())

        fun parse(yaml: String): PromptTemplate {
            val node = mapper.readTree(yaml)
            return PromptTemplate(node.path("system").asText(""), node.path("user").asText(""))
        }
    }
}

/**
 * Composes a [MiniPlan] from a [GolemRequest] (architecture §4 `classify_and_plan`).
 * CHEAP-tier, single-shot: reads `intent` from the [PromptStore], substitutes
 * the `{{ }}` placeholders from the request + model, runs one LLM completion through
 * the injected Koog [PromptExecutor], and decodes the reply with [MiniPlanCodec]
 * (StructureFixingParser-free). A decode failure raises [PlanDecodeException]; the
 * graph turns that into a clarification (Stage 2.4).
 */
class PlanComposer(
    private val executor: PromptExecutor,
    private val promptStore: PromptStore,
    private val model: LLModel = GolemModels.Cheap,
    private val promptName: String = "intent",
) {
    suspend fun compose(
        request: GolemRequest,
        model: ModelSnapshot?,
        priorViewHint: String? = null,
    ): MiniPlan {
        val template =
            promptStore.prompt(promptName)?.let { PromptTemplate.parse(it) }
                ?: throw PlanDecodeException("plan prompt '$promptName' is not loaded")
        val vars = buildVars(request, model, priorViewHint)
        val p =
            prompt("golem-plan") {
                system(substitute(template.system, vars))
                user(substitute(template.user, vars))
            }
        val content =
            executor
                .execute(p, this.model, emptyList())
                .filterIsInstance<Message.Assistant>()
                .joinToString("\n") { it.content }
                .ifBlank { throw PlanDecodeException("composer returned an empty reply") }
        return MiniPlanCodec.decode(content)
    }

    private fun buildVars(
        request: GolemRequest,
        model: ModelSnapshot?,
        priorViewHint: String?,
    ): Map<String, String> {
        val resolution = request.resolvedIntent
        val bindings =
            """{"function_id":"${resolution.functionId}","args":${resolution.argsJson.ifBlank { "{}" }}}"""
        // Render each pattern WITH its declared parameters (name:type, `?` = optional) so the composer
        // uses the exact declared names in params_json. Without this the placeholder was pattern ids only,
        // so the model had to GUESS parameter names — a strong reasoner (gpt-5) would "helpfully" rename or
        // merge them (e.g. year_from+year_to → date_range), which then fails plan validation.
        val patterns =
            model?.patternQueries?.joinToString("\n") { pq ->
                val params =
                    pq.parametersList.joinToString(", ") { p -> "${p.name}:${p.type}${if (p.optional) "?" else ""}" }
                "- ${pq.objectDescriptor.localName}(${params})"
            }?.ifBlank { "(none)" }
                ?: "(model not loaded)"
        val schema =
            model?.let { snap ->
                snap.entities.joinToString("\n") { "- ${it.objectDescriptor.localName}" }.ifBlank { "(none)" }
            } ?: "(model not loaded)"
        return mapOf(
            "question" to request.question,
            "locale" to request.context.locale.ifBlank { "cs" },
            "bindings" to bindings,
            "prior_view" to (priorViewHint ?: if (request.context.hasPriorView()) "<present>" else ""),
            "patterns" to patterns,
            "schema" to schema,
        )
    }

    companion object {
        private val PLACEHOLDER = Regex("""\{\{\s*(\w+)\s*}}""")

        /** Replace `{{ key }}` / `{{key}}` with `vars[key]` (missing → empty). */
        fun substitute(
            template: String,
            vars: Map<String, String>,
        ): String = PLACEHOLDER.replace(template) { vars[it.groupValues[1]] ?: "" }
    }
}
