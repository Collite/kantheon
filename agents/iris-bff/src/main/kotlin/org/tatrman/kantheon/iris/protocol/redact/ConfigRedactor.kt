package org.tatrman.kantheon.iris.protocol.redact

import org.tatrman.kantheon.iris.protocol.config.ProtocolProfile
import org.tatrman.kantheon.protocol.v1.ProtocolDocument
import org.tatrman.kantheon.protocol.v1.Section
import org.tatrman.kantheon.protocol.v1.Verbosity

/**
 * Profile-driven redaction (contracts §8, PT-20/21) — the judgement calls the
 * floor deliberately leaves alone: how much of a prompt this estate's readers
 * should see, and whether SQL literals are values or secrets.
 *
 * Runs strictly **after** [FloorRedactor] and can only remove more, never
 * restore. Where it digests content it sets `content_redacted` / `literals_masked`
 * so the document states that it was shortened rather than presenting a digest as
 * the whole truth.
 */
object ConfigRedactor : ProtocolRedactor {
    /** Kept from the head of a digested message — enough to identify it, not to reproduce it. */
    const val DIGEST_CHARS: Int = 120

    private const val ELLIPSIS = "…"

    /**
     * Quoted strings and bare numeric literals in SQL. Identifiers are left alone
     * — a masked column name would make the statement unreadable without
     * protecting anything; it is the *values* that carry the data.
     */
    private val SQL_STRING = Regex("""'(?:[^']|'')*'""")
    private val SQL_NUMBER = Regex("""(?<![A-Za-z_.])\d+(?:\.\d+)?(?![A-Za-z_])""")

    override fun redact(
        doc: ProtocolDocument,
        profile: ProtocolProfile,
    ): ProtocolDocument {
        val b = doc.toBuilder()
        b.turnsBuilderList.forEach { turn ->
            turn.sectionsBuilderList.forEach { section -> apply(section, profile) }
        }
        return b.build()
    }

    private fun apply(
        section: Section.Builder,
        profile: ProtocolProfile,
    ) {
        when (section.payloadCase) {
            Section.PayloadCase.LLM_CALLS ->
                section.llmCallsBuilder.callsBuilderList.forEach { call ->
                    call.messagesBuilderList.forEach { m ->
                        val policy =
                            when (m.role.lowercase()) {
                                "system" -> profile.llmSystemContent
                                else -> profile.llmUserContent
                            }
                        when (policy) {
                            Verbosity.VERBOSITY_OFF -> {
                                m.content = ""
                                m.contentRedacted = true
                            }

                            Verbosity.VERBOSITY_SUMMARY ->
                                if (m.content.length > DIGEST_CHARS) {
                                    m.content = m.content.take(DIGEST_CHARS) + ELLIPSIS
                                    m.contentRedacted = true
                                }

                            else -> Unit
                        }
                    }
                }

            Section.PayloadCase.SQL -> {
                // Literal masking follows the SQL section's own verbosity: a profile
                // that asked for `sql = summary` wants the shape of the statement, not
                // the values it ran against.
                if (section.appliedVerbosity == Verbosity.VERBOSITY_SUMMARY) {
                    val sql = section.sqlBuilder.sql
                    if (sql.isNotBlank()) {
                        section.sqlBuilder.sql = SQL_NUMBER.replace(SQL_STRING.replace(sql, "'?'"), "?")
                        section.sqlBuilder.literalsMasked = true
                    }
                }
            }

            Section.PayloadCase.SECURITY ->
                // An RLS predicate is a statement about who may see what; its literals
                // are tenant ids and user keys. Masked whenever the section is not FULL.
                if (section.appliedVerbosity != Verbosity.VERBOSITY_FULL) {
                    section.securityBuilder.rulesBuilderList.forEach { rule ->
                        if (rule.predicate.isNotBlank()) {
                            rule.predicate = SQL_NUMBER.replace(SQL_STRING.replace(rule.predicate, "'?'"), "?")
                            rule.predicateMasked = true
                        }
                    }
                }

            else -> Unit
        }
    }
}
