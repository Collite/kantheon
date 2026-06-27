package org.tatrman.kantheon.sysifos.bff.session

import com.google.protobuf.util.Timestamps
import org.tatrman.kantheon.sysifos.v1.Draft
import org.tatrman.kantheon.sysifos.v1.DraftStatus
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory draft scratch (contracts §3.2; plan §5 — durability across refresh is
 * v1.x). Tracks each draft's lifecycle status so `GET /drafts/{id}` can answer
 * while the async commit runs. Visible only within the owning session.
 */
class DraftScratch {
    private val byId = ConcurrentHashMap<String, Draft>()

    fun put(draft: Draft): Draft {
        byId[draft.draftId] = draft
        return draft
    }

    fun get(draftId: String): Draft? = byId[draftId]

    fun updateStatus(
        draftId: String,
        status: DraftStatus,
        artifactRef: String? = null,
    ): Draft? {
        val current = byId[draftId] ?: return null
        val builder = current.toBuilder().setStatus(status)
        if (status == DraftStatus.DRAFT_COMMITTED) {
            builder.committedAt = Timestamps.fromMillis(System.currentTimeMillis())
        }
        if (artifactRef != null) builder.commitArtifactRef = artifactRef
        return byId.compute(draftId) { _, _ -> builder.build() }
    }
}
