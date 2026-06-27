package org.tatrman.kantheon.pythia.plan

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import org.tatrman.kantheon.pythia.v1.PlanDag

/** Tier models — the id substring selects the gateway tier (CHEAP/FAST/STRONG). */
object PythiaModels {
    val Strong: LLModel = LLModel(provider = LLMProvider.Anthropic, id = "claude-opus")
    val Cheap: LLModel = LLModel(provider = LLMProvider.Anthropic, id = "claude-haiku")
}

/** Inputs the planner prompt is rendered against. */
data class PlanContext(
    val locale: String,
    val question: String,
    val intent: String,
    val capabilities: List<String>,
    val anchor: String,
    val feedback: String? = null,
    /** Master-of-Golems (Stage 5.1): relevant Golems' terminology + preferred queries, pre-rendered. */
    val shems: String = "",
)

/**
 * Composes a typed [PlanDag] from a STRONG-tier LLM call (task_kind PLANNING).
 * Koog `PromptExecutor` + the externalised planner prompt + [PlanDagCodec] — the
 * repo's codec-over-`StructureFixingParser` convention (golem `PlanComposer`).
 */
class PlanComposer(
    private val executor: PromptExecutor,
    private val prompts: Prompts = Prompts(),
    private val model: LLModel = PythiaModels.Strong,
) {
    suspend fun compose(context: PlanContext): PlanDag {
        val template = prompts.load(context.locale, "planner")
        val user =
            Prompts.substitute(
                template,
                mapOf(
                    "question" to context.question,
                    "intent" to context.intent,
                    "capabilities" to context.capabilities.joinToString(", "),
                    "anchor" to context.anchor,
                    "shems" to context.shems,
                    "feedback" to (context.feedback ?: ""),
                ),
            )
        val p =
            prompt("pythia-plan") {
                system(SYSTEM)
                user(user)
            }
        val content =
            executor
                .execute(p, model, emptyList())
                .filterIsInstance<Message.Assistant>()
                .joinToString("\n") { it.content }
                .ifBlank { throw PlanDecodeException("planner returned an empty reply") }
        return PlanDagCodec.decode(content)
    }

    private companion object {
        const val SYSTEM =
            "You are Pythia's planner. Produce a typed PlanDag as a single JSON object with " +
                "keys: rationale, hypotheses[], nodes[], edges[]. Node kinds: 'query', 'reasoning', " +
                "'render', and (when the data plane is available) 'dataframe' and 'model'. Return ONLY the JSON object."
    }
}
