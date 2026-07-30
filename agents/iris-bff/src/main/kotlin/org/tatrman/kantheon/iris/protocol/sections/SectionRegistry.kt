package org.tatrman.kantheon.iris.protocol.sections

/**
 * The section registry (contracts §2) — the one place that decides **which
 * sections exist and in what order they appear**. Keys are stable strings, not
 * an enum, because they are also configuration keys (contracts §7): an operator
 * writes `sections { llm-calls = summary }` in HOCON, and a renamed enum
 * constant would silently orphan their profile.
 *
 * [turnSpine] order IS the render order (S-2). It follows the execution path a
 * turn actually takes — routing, then resolution, then the model calls, then the
 * query as it descends into plan, SQL, security and execution, then the logs and
 * errors that surround it. A reader should be able to follow the list top to
 * bottom and see the machine's reasoning unfold; reordering it is a contracts
 * change, not a preference.
 */
object SectionRegistry {
    private const val PREFIX = "protocol.section."

    val turnSpine: List<String> =
        listOf(
            "${PREFIX}header",
            "${PREFIX}resolution",
            "${PREFIX}llm-calls",
            "${PREFIX}query",
            "${PREFIX}plan",
            "${PREFIX}sql",
            "${PREFIX}security",
            "${PREFIX}execution",
            "${PREFIX}service-logs",
            "${PREFIX}errors",
        )

    /** Session scope only — a single turn has exactly one participant pair. */
    const val PARTICIPANTS: String = "${PREFIX}participants"

    /**
     * Both scopes, ALWAYS last, and **not configurable** (PT-13/S-6). A document
     * whose receipts could be switched off would be a document that cannot be
     * audited — the reader would have no way to tell a complete protocol from a
     * silently degraded one. [configurableKeys] therefore excludes it, and
     * `ProtocolConfig` refuses to honour a profile that tries.
     */
    const val RECEIPTS: String = "${PREFIX}receipts"

    /** Every key a profile may set a verbosity for — receipts deliberately absent. */
    val configurableKeys: List<String> = turnSpine + PARTICIPANTS

    /** The short name used as the HOCON key, e.g. `protocol.section.llm-calls` → `llm-calls`. */
    fun shortName(key: String): String = key.removePrefix(PREFIX)

    /** Full registry key for a HOCON short name; null when the name is not a registry key. */
    fun keyForShortName(name: String): String? = "$PREFIX$name".takeIf { it in configurableKeys || it == RECEIPTS }

    /** Render order for a scope. Receipts is appended by the renderer, never by a builder. */
    fun spineFor(sessionScope: Boolean): List<String> = if (sessionScope) turnSpine + PARTICIPANTS else turnSpine
}
