package org.tatrman.kantheon.iris.protocol.redact

import org.tatrman.kantheon.iris.protocol.config.ProtocolProfile
import org.tatrman.kantheon.protocol.v1.ProtocolDocument
import org.tatrman.kantheon.protocol.v1.Section

/**
 * The non-negotiable floor (contracts §8, PT-19). Runs on **every** document
 * regardless of profile, and no configuration can disable it — a profile is an
 * operator's opinion about verbosity, never a licence to emit credentials.
 *
 * What it removes is deliberately narrow and mechanical: material that is *never*
 * legitimately part of a protocol no matter who is reading. Judgement calls
 * (should this reader see prompt bodies? should SQL literals be masked?) belong
 * to [ConfigRedactor], one layer up.
 *
 * The patterns below are matched on every free-text field the document carries —
 * questions, log bodies, error messages, prompts, entity queries, plans, SQL and
 * RLS predicates. Fields holding only identifiers, labels or counts (execution
 * targets, participant ids) are deliberately left alone; see [scrubSection],
 * where that is a stated case rather than a fall-through. Being aggressive on the
 * text is the correct bias: a
 * false positive costs a reader one obscured string, a false negative leaks a
 * credential into a document designed to be shared.
 */
object FloorRedactor : ProtocolRedactor {
    const val MASK: String = "«redacted»"

    private val PATTERNS: List<Regex> =
        listOf(
            // Authorization headers and bare bearer/JWT tokens.
            Regex("""(?i)\bauthorization\s*[:=]\s*\S+"""),
            Regex("""(?i)\bbearer\s+[A-Za-z0-9._\-+/=]{8,}"""),
            // A JWT is three base64url segments — recognisable without knowing the issuer.
            Regex("""\beyJ[A-Za-z0-9_\-]{5,}\.[A-Za-z0-9_\-]{5,}\.[A-Za-z0-9_\-]{5,}"""),
            // Connection strings, with or without credentials. Matched before the
            // generic key=value rule so the whole URI goes, not just its password.
            Regex("""(?i)\b(?:postgres(?:ql)?|mysql|mongodb(?:\+srv)?|redis|amqp|jdbc:[a-z0-9]+)://\S+"""),
            // key=value / key: value secrets. The key list is what makes this safe to
            // apply broadly — it does not touch ordinary prose.
            Regex(
                """(?i)\b(?:password|passwd|pwd|secret|api[_-]?key|access[_-]?key|secret[_-]?key|token|client[_-]?secret)\b\s*[:=]\s*("[^"]*"|'[^']*'|\S+)""",
            ),
            // PEM key material — everything between the guards, guards included.
            Regex("""-----BEGIN[^-]*PRIVATE KEY-----[\s\S]*?-----END[^-]*PRIVATE KEY-----"""),
            // AWS-style access key ids.
            Regex("""\b(?:AKIA|ASIA)[0-9A-Z]{16}\b"""),
        )

    override fun redact(
        doc: ProtocolDocument,
        profile: ProtocolProfile,
    ): ProtocolDocument {
        val turnRefs = doc.turnsList.map { it.turnId }.toSet()
        val b = doc.toBuilder()

        b.turnsBuilderList.forEach { turn ->
            turn.sectionsBuilderList.forEach { section -> scrubSection(section, turnRefs) }
        }

        // Receipt details are assembler-authored, but they quote source errors
        // verbatim — and an upstream 401 body can carry a token.
        //
        // Guarded on `hasReceipts()`: touching `receiptsBuilder` MATERIALIZES the
        // submessage, so an unguarded scrub would flip `hasReceipts()` from false to
        // true on a document that had none. A redactor may remove content; it must
        // never change which fields are present, or downstream presence checks start
        // answering questions about the redactor instead of about the document.
        if (b.hasReceipts()) {
            b.receiptsBuilder.sourcesBuilderList.forEach { it.detail = scrub(it.detail) }
        }
        return b.build()
    }

    private fun scrubSection(
        section: Section.Builder,
        turnRefs: Set<String>,
    ) {
        when (section.payloadCase) {
            Section.PayloadCase.SERVICE_LOGS ->
                section.serviceLogsBuilder.groupsBuilderList.forEach { g ->
                    g.linesBuilderList.forEach { it.body = scrub(it.body) }
                }

            Section.PayloadCase.ERRORS ->
                section.errorsBuilder.itemsBuilderList.forEach { it.message = scrub(it.message) }

            Section.PayloadCase.LLM_CALLS -> {
                val calls = section.llmCallsBuilder
                // **Cross-user row drop (A-2).** A call naming a turn that is not in
                // this document is another user's. The builder's attribution filter is
                // a correctness measure; this is the security backstop, and it verifies
                // independently rather than trusting it.
                //
                // A call with a BLANK turn_ref is left alone: it is unattributed, not
                // foreign, and the builder already counted it. Dropping it here would
                // hide the fact that unattributed traffic existed at all.
                val kept = calls.callsList.filter { it.turnRef.isBlank() || it.turnRef in turnRefs }
                val dropped = calls.callsCount - kept.size
                if (dropped > 0) {
                    calls.clearCalls()
                    calls.addAllCalls(kept)
                    // Counted, never silently vanished — the reader sees the shortfall.
                    calls.unattributableCount = calls.unattributableCount + dropped
                }
                calls.callsBuilderList.forEach { call ->
                    call.messagesBuilderList.forEach { m -> m.content = scrub(m.content) }
                }
            }

            Section.PayloadCase.SQL -> section.sqlBuilder.sql = scrub(section.sqlBuilder.sql)

            Section.PayloadCase.SECURITY ->
                section.securityBuilder.rulesBuilderList.forEach { it.predicate = scrub(it.predicate) }

            Section.PayloadCase.RESOLUTION -> {
                val r = section.resolutionBuilder
                r.argsJson = scrub(r.argsJson)
                r.rationale = scrub(r.rationale)
            }

            // The user's own prose. A credential pasted into chat is masked in the
            // prompt body two sections down; printing it verbatim here would make the
            // floor look unreliable exactly where a reader can see both.
            Section.PayloadCase.HEADER ->
                section.headerBuilder.question = scrub(section.headerBuilder.question)

            // Model-authored text carrying user-supplied filter values.
            Section.PayloadCase.QUERY ->
                section.queryBuilder.entityQuery = scrub(section.queryBuilder.entityQuery)

            // The single most likely place in the document for a connection string:
            // under S-1 this is translate's per-stage canonical form, and a plan
            // stage names the data source it reads from.
            Section.PayloadCase.PLAN ->
                section.planBuilder.relPlanText = scrub(section.planBuilder.relPlanText)

            // EXECUTION (dispatch target, worker, counts) and PARTICIPANTS (ids) carry
            // labels and numbers, never free text — deliberately not scrubbed rather
            // than accidentally uncovered.
            else -> Unit
        }
    }

    fun scrub(text: String): String {
        if (text.isEmpty()) return text
        var out = text
        PATTERNS.forEach { p -> out = p.replace(out, MASK) }
        return out
    }
}
