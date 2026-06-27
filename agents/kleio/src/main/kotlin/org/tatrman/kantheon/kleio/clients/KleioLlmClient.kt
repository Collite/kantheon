package org.tatrman.kantheon.kleio.clients

/**
 * The grounded synthesis result — the answer text + the chunk ids the model
 * claims to have cited. `RenderNode` validates these against the retrieved set
 * (the grounding contract); any id not retrieved is dropped.
 */
data class GroundedAnswer(
    val text: String,
    val citedPartIds: List<Long>,
    val citedPageIds: List<Long>,
    val tokensIn: Int = 0,
    val tokensOut: Int = 0,
)

/**
 * Kleio → Prometheus for grounded synthesis. The prompt (in `prompts/`)
 * constrains the answer to the retrieved chunks and asks the model to cite the
 * chunk ids it used. The synthesis is grounded — never free-form generation over
 * the model's prior knowledge.
 */
interface KleioLlmClient {
    suspend fun answer(
        question: String,
        chunks: List<RetrievedChunk>,
    ): GroundedAnswer
}
