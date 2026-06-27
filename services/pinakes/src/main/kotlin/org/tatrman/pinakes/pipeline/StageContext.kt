package org.tatrman.pinakes.pipeline

/**
 * The in-flight pipeline state threaded through the stage DAG. Immutable —
 * each stage returns a copy with its output filled. Carries the asset identity,
 * the extracted text + chunked parts, and the corpus ids the LOAD stage minted
 * (so EMBED/COMPILE/LINK/RESOLVE can act on the loaded source).
 */
@Suppress("ArrayInDataClass") // bytes are never used in equals/hashCode comparisons
data class StageContext(
    val assetId: String,
    val assetRef: String,
    val sourceFeed: String,
    val mimeType: String,
    val originalName: String,
    val notebookId: String,
    val bytes: ByteArray,
    val text: String? = null,
    val parts: List<String> = emptyList(),
    val sourceId: Long? = null,
    val partIds: List<Long> = emptyList(),
    val pageIds: List<Long> = emptyList(),
    val embedded: Boolean = false,
    // Compile tail (S3.2) — drafts produced by COMPILE, annotated by RESOLVE,
    // written by LINK.
    val pageDrafts: List<org.tatrman.pinakes.compile.PageDraft> = emptyList(),
    val resolvedPages: List<org.tatrman.pinakes.resolve.ResolvedPage> = emptyList(),
    // The compile degraded to mechanical (budget/LLM failure) — the run is PARTIAL
    // but the corpus stays queryable on source parts (architecture §14).
    val compileDegraded: Boolean = false,
)
