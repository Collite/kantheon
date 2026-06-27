package org.tatrman.kallimachos.ingestion

/**
 * Ingestion knobs for the ported parsers. Replaces doc-store's Spring-bound
 * `AppConfig` (the only thing the handlers reached into it for) with a plain
 * Kotlin value — wired by constructor, no DI (architecture §4: "strip Spring,
 * wire as plain Kotlin constructors").
 *
 * Defaults mirror doc-store's (`DocStoreConfig`: maxPdfPages=10) and the
 * paragraph-splitter constraints the `ParagraphSplitterSpec` corpus exercises.
 */
data class IngestionConfig(
    val splitterParagraphMinLen: Int = 30,
    val splitterParagraphMaxLen: Int = 200,
    val maxPdfPages: Int = 10,
) {
    companion object {
        val DEFAULT = IngestionConfig()
    }
}
