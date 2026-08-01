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

        // `oneLine()` on the title for the same reason every question rendering gets
        // it: the title is `question.take(60)`, so a multi-line question would
        // otherwise continue past the `# ` and inject markdown at line 1.
        sb.line("# ${doc.header.title.oneLine()}")
        sb.blank()
        renderDocHeader(sb, doc)

        if (split) renderIndex(sb, doc)

        doc.turnsList.forEach { turn ->
            sb.blank()
            renderTurn(sb, turn, split)
        }

        // Participants once, after the turns (A-5) — it is a fact about the whole
        // conversation, not one repeated under every turn heading.
        if (doc.hasParticipants() && doc.participants.status != SectionStatus.SECTION_OFF) {
            sb.blank()
            sb.line("## ${doc.participants.title()}")
            sb.blank()
            renderPayload(sb, doc.participants)
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
        sb.line("| Session | ${cell(code(doc.sessionId))} |")
        if (h.userId.isNotBlank()) sb.line("| User | ${cell(h.userId)} |")
        if (h.tenantId.isNotBlank()) sb.line("| Tenant | ${cell(h.tenantId)} |")
        if (h.agentIdsCount > 0) sb.line("| Agents | ${cell(h.agentIdsList.joinToString(", "))} |")
        sb.line("| Turns | ${h.turnCountInScope} of ${h.turnCountTotal} |")
        sb.line("| Generated | ${cell(doc.generatedAt)} |")
        sb.line("| Schema | ${cell(code(doc.schemaVersion))} |")
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
            // The link TEXT is untrusted (it is the question); `]` would close it early
            // and turn the rest into a live link target. The anchor is ours.
            sb.line("${turn.seq}. [${text(question).replace("[", "\\[").replace("]", "\\]")}](#${turn.anchor()})")
        }
    }

    private fun renderTurn(
        sb: StringBuilder,
        turn: ProtocolTurn,
        split: Boolean,
    ) {
        val title = turn.headerQuestion().ifBlank { turn.turnId }
        sb.line("## Turn ${turn.seq} — ${text(title)}")
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
                // Zero dropped with the section still truncated means LOKI cut the result
                // set, server-side, before this process saw a line — so there is no honest
                // N and A-4's count-free variant applies. "0 more lines" would state
                // something false about the one thing this marker exists to be true about.
                if (dropped > 0) {
                    "_…truncated — $dropped more lines; source: $ref" + "_"
                } else {
                    "_…truncated by the loki page limit; source: $ref" + "_"
                }
            }

            Section.PayloadCase.ERRORS -> "_…truncated by the errors-items cap; source: $ref" + "_"

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
                sb.line("- **Question:** ${text(h.question)}")
                sb.line("- **Agent:** ${text(h.agentId)}")
                if (h.routingOutcome.isNotBlank()) sb.line("- **Routing:** ${text(h.routingOutcome)}")
                sb.line("- **Status:** ${text(h.status)}")
                sb.line("- **Origin:** ${text(h.origin)}")
                if (h.startedAt.isNotBlank()) sb.line("- **Started:** ${text(h.startedAt)}")
                // Omitted rather than printed as zero. `iris_turns` stores no per-turn
                // duration, so this is 0 on every live document — and next to the
                // execution section's real number it read as a broken field rather than
                // an absent one. A line that is not there asks no questions; "0 ms" does.
                if (h.durationMs > 0) sb.line("- **Duration:** ${h.durationMs} ms")
            }

            Section.PayloadCase.RESOLUTION -> {
                val r = section.resolution
                if (r.functionId.isNotBlank()) sb.line("- **Function:** ${code(r.functionId)}")
                if (r.argsJson.isNotBlank()) sb.line("- **Args:** ${code(r.argsJson)}")
                sb.line("- **Confidence:** ${r.confidence}")
                sb.line("- **Layer hit:** ${r.layerHit}")
                if (r.needsUserPickShown) sb.line("- **Needed a user pick:** yes")
                if (r.alternatesOfferedCount >
                    0
                ) {
                    sb.line("- **Alternates:** ${text(r.alternatesOfferedList.joinToString(", "))}")
                }
                if (r.rationale.isNotBlank()) sb.line("- **Rationale:** ${text(r.rationale)}")
                if (r.bindingsCount > 0) {
                    sb.blank()
                    sb.line("| Mention | Bound to | Confidence |")
                    sb.line("|---|---|---|")
                    r.bindingsList.forEach {
                        // `code()` first, then escape pipes: the span delimiter is chosen
                        // from the raw body, and a backslash inserted before it would be
                        // counted as content and widen nothing.
                        sb.line("| ${cell(it.mention)} | ${cell(code(it.boundRef))} | ${it.confidence} |")
                    }
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
                            "| ${cell(it.purpose.ifBlank { "—" })} | ${cell(it.servedModel)} " +
                                "| ${cell(it.servedProvider)} | ${it.tokensPrompt} | ${it.tokensCompletion} " +
                                "| ${it.durationMs} ms | ${it.costUsd} |",
                        )
                    }
                    l.callsList.filter { it.messagesCount > 0 }.forEach { call ->
                        sb.blank()
                        sb.line("**Call ${code(call.callRef)}**")
                        call.messagesList.forEach { m ->
                            sb.blank()
                            sb.line("_${text(m.role)}${if (m.contentRedacted) " (redacted)" else ""}:_")
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
                    sb.line("- **Kind:** ${text(q.queryKind)}")
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
                val s = section.sql
                if (s.sql.isBlank()) {
                    // No empty fences. A ref-only turn used to render `_unavailable_`
                    // followed by an empty ```sql block, with the ref itself nowhere in
                    // the document — it had been stashed in `engine_label`, which nothing
                    // prints (review-080 R6). An empty block says the statement was empty;
                    // the truth is that it was not inlined, and the ref is how a reader
                    // goes and gets it.
                    if (s.sqlRef.isNotBlank()) sb.line("_statement not inlined; ref: ${code(s.sqlRef)}_")
                } else {
                    sb.fence("sql", s.sql)
                    if (s.literalsMasked) {
                        sb.blank()
                        sb.line("_Literals masked by profile._")
                    }
                }
            }

            Section.PayloadCase.SECURITY -> {
                val s = section.security
                if (s.rulesCount == 0) {
                    sb.line(
                        if (section.status == SectionStatus.SECTION_DEGRADED) {
                            "_Not captured: ${text(s.policySource.ifBlank { "unavailable" })}_"
                        } else {
                            "_No row-level rules were applied to this turn._"
                        },
                    )
                } else {
                    sb.line("| Rule | Description | Predicate |")
                    sb.line("|---|---|---|")
                    s.rulesList.forEach {
                        val predicate = if (it.predicateMasked) "_masked_" else cell(code(it.predicate))
                        sb.line("| ${cell(code(it.ruleId))} | ${cell(it.description)} | $predicate |")
                    }
                    if (s.policySource.isNotBlank()) {
                        sb.blank()
                        sb.line("- **Policy source:** ${text(s.policySource)}")
                    }
                }
            }

            Section.PayloadCase.EXECUTION -> {
                val e = section.execution
                sb.line("- **Target:** ${text(e.dispatchTarget).ifBlank { "—" }}")
                sb.line("- **Worker:** ${text(e.worker).ifBlank { "—" }}")
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
                        sb.line("**${text(g.serviceName)}**")
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
                            "- **${text(it.source)}** ${code(it.code)} — ${text(it.message)}",
                        )
                    }
                }

            Section.PayloadCase.PARTICIPANTS -> {
                val p = section.participants
                sb.line("- **Users:** ${text(p.userIdsList.joinToString(", ")).ifBlank { "—" }}")
                sb.line("- **Agents:** ${text(p.agentIdsList.joinToString(", ")).ifBlank { "—" }}")
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
        r.sourcesList.forEach { sb.line("| ${cell(it.source)} | ${cell(it.status)} | ${cell(it.detail)} |") }
        sb.blank()
        sb.line("- **Profile:** ${text(r.profileName)}")
        sb.line("- **Generated by:** ${text(r.generatedBy)}")
    }

    // ---- small helpers; the renderer's whole vocabulary ----

    private fun StringBuilder.line(s: String) = append(s).append('\n')

    private fun StringBuilder.blank() = append('\n')

    /**
     * A fenced block whose fence is **longer than anything inside it**.
     *
     * Almost every body this renderer fences is content the document does not
     * control — the entity query and SQL literals carry user-supplied values, log
     * lines carry whatever a service logged, prompt bodies carry what the user
     * typed. A fixed three-backtick fence lets any of them close the block early
     * and continue in markdown, which forges document structure: a body ending
     * with a fence and a `## Receipts` table renders a **second, fake receipts
     * table above the real one**. For a document whose whole value is being
     * trustworthy about what happened, that is the one injection that matters.
     *
     * CommonMark permits any opening run of three or more backticks and closes on
     * the first run of *at least* that length, so a fence one longer than the
     * body's longest run is unbreakable from inside. (review-079 R2.)
     */
    private fun StringBuilder.fence(
        lang: String,
        body: String,
    ) {
        val trimmed = body.trimEnd('\n')
        val fence = "`".repeat(maxOf(3, longestBacktickRun(trimmed) + 1))
        line("$fence$lang")
        line(trimmed)
        line(fence)
    }

    /**
     * An inline code span wider than anything inside it — [fence]'s inline twin.
     *
     * The fence learned this in review-079 R2; the inline spans did not, and stayed
     * breakable for exactly the same reason: a fixed `` ` `` closes on the first
     * backtick in the body, and everything after it becomes live markdown. That is
     * reachable from the user's own keyboard — `ResolutionSection.args_json` carries
     * the filter values lifted from their question and `EntityBindingView.mention`
     * carries their literal words — so a mention could render a working hyperlink, or
     * an image, inside what reads as a quoted value (review-080 R3).
     *
     * CommonMark closes a span on the first backtick run of equal length, so one
     * longer is unbreakable; content that starts or ends with a backtick needs one
     * space of padding, which the reader never sees.
     */
    private fun code(s: String): String {
        val body = s.oneLine()
        if (body.isEmpty()) return "``"
        val ticks = "`".repeat(longestBacktickRun(body) + 1)
        val pad = if (body.startsWith("`") || body.endsWith("`")) " " else ""
        return "$ticks$pad$body$pad$ticks"
    }

    private fun longestBacktickRun(s: String): Int {
        var best = 0
        var run = 0
        s.forEach { c ->
            if (c == '`') {
                run++
                if (run > best) best = run
            } else {
                run = 0
            }
        }
        return best
    }

    /**
     * One table cell. A `|` splits the row and a newline ends the table, and cell
     * content is not ours either — receipt details are built from upstream
     * exception messages, rule descriptions come from the policy source, mentions
     * come from the user's question. Escaping here keeps a hostile or merely
     * unlucky string from reshaping the table it lands in. (review-079 R3.)
     */
    private fun cell(s: String): String = s.replace("|", "\\|").oneLine()

    /**
     * Any string the document did not author, on its way into prose.
     *
     * The rule this enforces is one line long and was previously enforced per call
     * site: **nothing untrusted may start a new markdown line.** review-079 R2 fixed
     * the fenced fields and the table cells; eleven bare `${…}` interpolations were
     * neither, and a newline in any of them opened a line at column 0 — enough to
     * render a complete forged `## Receipts` table above the genuine one, in the one
     * document whose entire value is being trustworthy about what happened
     * (review-080 R2).
     *
     * It exists as a named helper rather than a habit so the next section builder
     * inherits it, and so `MarkdownRendererSpec`'s reflective guard has something to
     * point at.
     */
    private fun text(s: String): String = s.oneLine()

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
