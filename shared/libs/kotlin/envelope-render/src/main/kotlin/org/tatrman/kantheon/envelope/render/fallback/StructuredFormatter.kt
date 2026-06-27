package org.tatrman.kantheon.envelope.render.fallback

import org.tatrman.kantheon.envelope.render.catalog.FormatRequest
import org.tatrman.kantheon.envelope.render.catalog.RenderCall

/**
 * The LLM boundary of the format catalog. An implementation asks the model to
 * pick exactly one render tool (`tool_choice="any"` semantics) and returns its
 * validated args as a [RenderCall], or throws
 * [org.tatrman.kantheon.envelope.render.catalog.FormatToolException].
 *
 * envelope-render stays LLM-agnostic: the deterministic core (header inference,
 * directives, retry, fallback) never touches this interface directly — only
 * [FormatCatalog] does. A Koog-backed implementation lives in `catalog/`; Golem
 * and Pythia inject their own gateway-backed implementations.
 */
fun interface StructuredFormatter {
    /**
     * @param request    the block to format.
     * @param priorError the previous attempt's error (reason + message), or `null`
     *                   on the first attempt — fed into the repair prompt.
     */
    suspend fun pick(
        request: FormatRequest,
        priorError: String?,
    ): RenderCall
}
