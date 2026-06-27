package org.tatrman.kantheon.hebe.core.compaction

import org.tatrman.kantheon.hebe.api.ConversationMessage

class PreemptivePruner(
    private val compactor: Compactor,
) {
    suspend fun prune(
        history: List<ConversationMessage>,
        turnId: String,
    ): Compactor.CompactionResult = compactor.maybeCompact(history, turnId)
}
