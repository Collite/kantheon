package org.tatrman.kantheon.kleio.clients

/**
 * THE GROUNDING CONTRACT, applied per chunk (contracts §5): a retrieved chunk is
 * cited iff the model named its id in the matching id space. Page chunks (which
 * carry `partId = 0`) ground ONLY on `pageId`; part chunks ground ONLY on
 * `partId`. Keeping the two spaces disjoint stops a model that emits part `0`
 * from falsely grounding every page chunk at once.
 */
fun RetrievedChunk.isCitedBy(answer: GroundedAnswer): Boolean =
    if (pageId != null) pageId in answer.citedPageIds else partId in answer.citedPartIds
