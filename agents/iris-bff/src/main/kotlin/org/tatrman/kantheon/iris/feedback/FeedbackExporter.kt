package org.tatrman.kantheon.iris.feedback

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.tatrman.kantheon.iris.domain.FeedbackRecord
import java.util.UUID

/**
 * Offline feedback export (PD-3, contracts §2.9) — `just feedback-export`. Pure
 * transform: turns the `iris_feedback` rows into per-agent JSONL candidates for
 * `eval/candidates/`. **No agent sees feedback at runtime**; this is the offline,
 * human-curated path into the gate corpora. Two corpus kinds:
 *
 *  - **per-agent feedback** (`<agent_id>`): one line per verdict — the answering
 *    agent's 👍/👎 with reason/comment + the originating question.
 *  - **Themis routing labels** (`themis`): every `corrected_agent_id` (PD-14) is a
 *    labelled misroute — `{question, wrong_agent, correct_agent}` — the strongest
 *    routing-correction signal.
 *
 * `questionOf` resolves a turn id to its question (from `iris_turns`); rows whose
 * turn is gone are skipped for the routing corpus (no question to label).
 */
object FeedbackExporter {
    fun export(
        rows: List<FeedbackRecord>,
        questionOf: (UUID) -> String?,
    ): Map<String, List<String>> {
        val byCorpus = mutableMapOf<String, MutableList<String>>()

        fun add(
            corpus: String,
            line: String,
        ) = byCorpus.getOrPut(corpus) { mutableListOf() }.add(line)

        for (row in rows) {
            val question = questionOf(row.turnId)
            add(
                row.agentId,
                buildJsonObject {
                    put("turnId", row.turnId.toString())
                    put("question", question ?: "")
                    put("verdict", row.verdict)
                    row.reason?.let { put("reason", it) }
                    row.comment?.let { put("comment", it) }
                }.toString(),
            )
            // PD-14 misroute → a Themis routing example (needs the question).
            if (row.correctedAgentId != null && question != null) {
                add(
                    "themis",
                    buildJsonObject {
                        put("question", question)
                        put("wrong_agent", row.agentId)
                        put("correct_agent", row.correctedAgentId)
                    }.toString(),
                )
            }
        }
        return byCorpus
    }
}
