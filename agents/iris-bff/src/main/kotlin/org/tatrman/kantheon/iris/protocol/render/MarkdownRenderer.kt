package org.tatrman.kantheon.iris.protocol.render

import org.tatrman.kantheon.iris.protocol.sections.SectionRegistry
import org.tatrman.kantheon.protocol.v1.ProtocolDocument
import org.tatrman.kantheon.protocol.v1.ProtocolTurn
import org.tatrman.kantheon.protocol.v1.Section
import org.tatrman.kantheon.protocol.v1.SectionStatus

/**
 * Renders a [ProtocolDocument] to markdown (S-8). Plain [StringBuilder], no
 * template engine — the output is a contract pinned byte-for-byte by the golden
 * fixtures (PT-22), and a template layer would put an untested indirection
 * between the model and the thing being asserted.
 *
 * **No `<details><summary>` folding (Q-15, answered 2026-07-30).** The Iris FE
 * builds markdown-it with `html: false` and pipes the result straight into
 * `v-html` with no sanitizer, so raw HTML would render as escaped literal text —
 * and enabling it would open an HTML-injection channel into every chat bubble for
 * the sake of a debug surface. Sections therefore fold with plain headings. If
 * folding is ever wanted, the policy-preserving route is a targeted
 * markdown-it-container rule, not `html: true`.
 *
 * **Session split (PT-10 γ):** past [sessionSplitThreshold] turns the document
 * gains a table-of-contents and per-turn chapter headings with in-doc anchors.
 * v1 deliberately stays **one markdown document** — a genuine multi-document
 * split is reserved, not implemented, because it would need a second endpoint and
 * a storage story for the parts.
 */
class MarkdownRenderer(
    private val sessionSplitThreshold: Int = 12,
) {
    fun render(doc: ProtocolDocument): String {
        val sb = StringBuilder()
        val split = doc.turnsCount > sessionSplitThreshold

        sb.line("# ${doc.header.title}")
        sb.blank()
        renderDocHeader(sb, doc)

        if (split) renderIndex(sb, doc)

        doc.turnsList.forEach { turn ->
            sb.blank()
            renderTurn(sb, turn, split)
        }

        // Receipts last, always (PT-13/S-6) — appended here rather than looked up in
        // the turn spine, so no section list and no profile can reposition them.
        sb.blank()
        renderReceipts(sb, doc)

        return sb.toString()
    }

    private fun renderDocHeader(
        sb: StringBuilder,
        doc: ProtocolDocument,
    ) {
        val h = doc.header
        sb.line("| | |")
        sb.line("|---|---|")
        sb.line("| Session | `${doc.sessionId}` |")
        if (h.userId.isNotBlank()) sb.line("| User | ${h.userId} |")
        if (h.tenantId.isNotBlank()) sb.line("| Tenant | ${h.tenantId} |")
        if (h.agentIdsCount > 0) sb.line("| Agents | ${h.agentIdsList.joinToString(", ")} |")
        sb.line("| Turns | ${h.turnCountInScope} of ${h.turnCountTotal} |")
        sb.line("| Generated | ${doc.generatedAt} |")
        sb.line("| Schema | `${doc.schemaVersion}` |")
    }

    private fun renderIndex(
        sb: StringBuilder,
        doc: ProtocolDocument,
    ) {
        sb.blank()
        sb.line("## Contents")
        sb.blank()
        doc.turnsList.forEach { turn ->
            val question = turn.headerQuestion().ifBlank { "(no question)" }
            sb.line("${turn.seq}. [${question.oneLine()}](#${turn.anchor()})")
        }
    }

    private fun renderTurn(
        sb: StringBuilder,
        turn: ProtocolTurn,
        split: Boolean,
    ) {
        val title = turn.headerQuestion().ifBlank { turn.turnId }
        sb.line("## Turn ${turn.seq} — ${title.oneLine()}")
        if (split) {
            // The anchor GitHub-flavoured markdown would derive from the heading is
            // not stable across renderers, so it is emitted explicitly.
            sb.line("<a id=\"${turn.anchor()}\"></a>")
        }

        turn.sectionsList.forEach { section ->
            if (section.status == SectionStatus.SECTION_OFF) return@forEach
            sb.blank()
            sb.line("### ${section.title()}")
            sb.blank()
            if (section.status == SectionStatus.SECTION_DEGRADED) {
                sb.line("_unavailable — see receipts_")
                // A degraded section with no payload has already said everything it
                // can; falling through would add a second, redundant "_no content_".
                if (section.payloadCase == Section.PayloadCase.PAYLOAD_NOT_SET) {
                    return@forEach
                }
                sb.blank()
            }
            renderPayload(sb, section)
            if (section.truncated) {
                sb.blank()
                sb.line(truncationMarker(section))
            }
        }
    }

    /**
     * The truncation marker (PT-10). contracts/T5 fixes the wording
     * `_…truncated — N more lines; source: <ref>_`, which is written for the
     * log case — logs are the only section truncated by a **line** count.
     *
     * SQL and prompt bodies are truncated by a **character** cap, and the
     * document does not retain the original length, so there is no honest N to
     * print. Emitting "0 more lines" there would state something false about the
     * one thing this marker exists to be truthful about. Those sections
     * therefore get a count-free variant naming the cap that bit
     * (**Amendment A-4**).
     */
    private fun truncationMarker(section: Section): String {
        val ref = SectionRegistry.shortName(section.key)
        return when (section.payloadCase) {
            Section.PayloadCase.SERVICE_LOGS -> {
                val dropped = section.serviceLogs.groupsList.sumOf { it.droppedByCap }
                "_…truncated — $dropped more lines; source: $ref" + "_"
            }

            Section.PayloadCase.SQL -> "_…truncated by the sql-chars cap; source: $ref" + "_"
            Section.PayloadCase.LLM_CALLS -> "_…truncated by the llm-message-chars cap; source: $ref" + "_"
            else -> "_…truncated; source: $ref" + "_"
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun renderPayload(
        sb: StringBuilder,
        section: Section,
    ) {
        when (section.payloadCase) {
            Section.PayloadCase.HEADER -> {
                val h = section.header
                sb.line("- **Question:** ${h.question.oneLine()}")
                sb.line("- **Agent:** ${h.agentId}")
                if (h.routingOutcome.isNotBlank()) sb.line("- **Routing:** ${h.routingOutcome}")
                sb.line("- **Status:** ${h.status}")
                sb.line("- **Origin:** ${h.origin}")
                if (h.startedAt.isNotBlank()) sb.line("- **Started:** ${h.startedAt}")
                sb.line("- **Duration:** ${h.durationMs} ms")
            }

            Section.PayloadCase.RESOLUTION -> {
                val r = section.resolution
                if (r.functionId.isNotBlank()) sb.line("- **Function:** `${r.functionId}`")
                if (r.argsJson.isNotBlank()) sb.line("- **Args:** `${r.argsJson.oneLine()}`")
                sb.line("- **Confidence:** ${r.confidence}")
                sb.line("- **Layer hit:** ${r.layerHit}")
                if (r.needsUserPickShown) sb.line("- **Needed a user pick:** yes")
                if (r.alternatesOfferedCount >
                    0
                ) {
                    sb.line("- **Alternates:** ${r.alternatesOfferedList.joinToString(", ")}")
                }
                if (r.rationale.isNotBlank()) sb.line("- **Rationale:** ${r.rationale.oneLine()}")
                if (r.bindingsCount > 0) {
                    sb.blank()
                    sb.line("| Mention | Bound to | Confidence |")
                    sb.line("|---|---|---|")
                    r.bindingsList.forEach { sb.line("| ${it.mention} | `${it.boundRef}` | ${it.confidence} |") }
                }
            }

            Section.PayloadCase.LLM_CALLS -> {
                val l = section.llmCalls
                if (l.callsCount == 0) {
                    sb.line("_no attributable calls_")
                } else {
                    sb.line("| Purpose | Model | Provider | Prompt | Completion | Duration | Cost |")
                    sb.line("|---|---|---|---|---|---|---|")
                    l.callsList.forEach {
                        sb.line(
                            "| ${it.purpose.ifBlank { "—" }} | ${it.servedModel} | ${it.servedProvider} " +
                                "| ${it.tokensPrompt} | ${it.tokensCompletion} | ${it.durationMs} ms | ${it.costUsd} |",
                        )
                    }
                    l.callsList.filter { it.messagesCount > 0 }.forEach { call ->
                        sb.blank()
                        sb.line("**Call `${call.callRef}`**")
                        call.messagesList.forEach { m ->
                            sb.blank()
                            sb.line("_${m.role}${if (m.contentRedacted) " (redacted)" else ""}:_")
                            sb.blank()
                            sb.fence("", m.content)
                        }
                    }
                }
                if (l.unattributableCount > 0) {
                    sb.blank()
                    sb.line("_${l.unattributableCount} call(s) could not be attributed to this turn._")
                }
            }

            Section.PayloadCase.QUERY -> {
                val q = section.query
                if (q.entityQuery.isBlank()) sb.line("_no query was formed_") else sb.fence("", q.entityQuery)
                if (q.queryKind.isNotBlank()) {
                    sb.blank()
                    sb.line("- **Kind:** ${q.queryKind}")
                }
            }

            Section.PayloadCase.PLAN -> {
                if (section.plan.reconstructed) {
                    sb.line(
                        "_Reconstructed from the SQL after the fact — this is **a** plan for that statement, not provably the one that ran (S-1)._",
                    )
                    sb.blank()
                }
                sb.fence("", section.plan.relPlanText)
            }

            Section.PayloadCase.SQL -> {
                sb.fence("sql", section.sql.sql)
                if (section.sql.literalsMasked) {
                    sb.blank()
                    sb.line("_Literals masked by profile._")
                }
            }

            Section.PayloadCase.SECURITY -> {
                val s = section.security
                if (s.rulesCount == 0) {
                    sb.line(
                        if (section.status == SectionStatus.SECTION_DEGRADED) {
                            "_Not captured: ${s.policySource.ifBlank { "unavailable" }}_"
                        } else {
                            "_No row-level rules were applied to this turn._"
                        },
                    )
                } else {
                    sb.line("| Rule | Description | Predicate |")
                    sb.line("|---|---|---|")
                    s.rulesList.forEach {
                        val predicate = if (it.predicateMasked) "_masked_" else "`${it.predicate}`"
                        sb.line("| `${it.ruleId}` | ${it.description} | $predicate |")
                    }
                    if (s.policySource.isNotBlank()) {
                        sb.blank()
                        sb.line("- **Policy source:** ${s.policySource}")
                    }
                }
            }

            Section.PayloadCase.EXECUTION -> {
                val e = section.execution
                sb.line("- **Target:** ${e.dispatchTarget.ifBlank { "—" }}")
                sb.line("- **Worker:** ${e.worker.ifBlank { "—" }}")
                sb.line("- **Rows:** ${if (e.rowCount < 0) "unknown" else e.rowCount.toString()}")
                sb.line("- **Duration:** ${e.durationMs} ms")
            }

            Section.PayloadCase.SERVICE_LOGS -> {
                // A group can end up empty after the summary-level filter (a service
                // that logged only INFO). Rendering it would emit an empty code fence
                // under a service heading, which reads as "this service said nothing"
                // when in fact it said nothing *matching the filter*. Drop the group;
                // its dropped-count is still reported below if the cap bit.
                val groups = section.serviceLogs.groupsList.filter { it.linesCount > 0 || it.droppedByCap > 0 }
                if (groups.isEmpty()) {
                    sb.line("_no log lines in the turn's window_")
                } else {
                    groups.forEachIndexed { index, g ->
                        if (index > 0) sb.blank()
                        sb.line("**${g.serviceName}**")
                        if (g.linesCount > 0) {
                            sb.blank()
                            sb.fence("", g.linesList.joinToString("\n") { "${it.ts} ${it.level} ${it.body}" })
                        }
                        if (g.droppedByCap > 0) {
                            sb.blank()
                            sb.line("_${g.droppedByCap} more line(s) dropped by cap._")
                        }
                    }
                }
            }

            Section.PayloadCase.ERRORS ->
                if (section.errors.itemsCount == 0) {
                    sb.line("_none_")
                } else {
                    section.errors.itemsList.forEach {
                        sb.line(
                            "- **${it.source}** `${it.code}` — ${it.message.oneLine()}",
                        )
                    }
                }

            Section.PayloadCase.PARTICIPANTS -> {
                val p = section.participants
                sb.line("- **Users:** ${p.userIdsList.joinToString(", ").ifBlank { "—" }}")
                sb.line("- **Agents:** ${p.agentIdsList.joinToString(", ").ifBlank { "—" }}")
            }

            else -> sb.line("_no content_")
        }
    }

    private fun renderReceipts(
        sb: StringBuilder,
        doc: ProtocolDocument,
    ) {
        val r = doc.receipts
        sb.line("## Receipts")
        sb.blank()
        sb.line("| Source | Status | Detail |")
        sb.line("|---|---|---|")
        r.sourcesList.forEach { sb.line("| ${it.source} | ${it.status} | ${it.detail} |") }
        sb.blank()
        sb.line("- **Profile:** ${r.profileName}")
        sb.line("- **Generated by:** ${r.generatedBy}")
    }

    // ---- small helpers; the renderer's whole vocabulary ----

    private fun StringBuilder.line(s: String) = append(s).append('\n')

    private fun StringBuilder.blank() = append('\n')

    private fun StringBuilder.fence(
        lang: String,
        body: String,
    ) {
        line("```$lang")
        line(body.trimEnd('\n'))
        line("```")
    }

    private fun String.oneLine(): String = replace('\n', ' ').replace('\r', ' ').trim()

    private fun ProtocolTurn.anchor(): String = "turn-$seq"

    private fun ProtocolTurn.headerQuestion(): String =
        sectionsList
            .firstOrNull { it.payloadCase == Section.PayloadCase.HEADER }
            ?.header
            ?.question
            .orEmpty()

    private fun Section.title(): String =
        when (SectionRegistry.shortName(key)) {
            "header" -> "Overview"
            "resolution" -> "Resolution"
            "llm-calls" -> "LLM calls"
            "query" -> "Query"
            "plan" -> "Plan"
            "sql" -> "SQL"
            "security" -> "Security"
            "execution" -> "Execution"
            "service-logs" -> "Service logs"
            "errors" -> "Errors"
            "participants" -> "Participants"
            else -> SectionRegistry.shortName(key)
        }
}
