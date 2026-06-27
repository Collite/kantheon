package org.tatrman.kallimachos.service

import org.tatrman.kallimachos.adapters.notebook.NewNotebook
import org.tatrman.kallimachos.adapters.notebook.NotebookPort
import org.tatrman.kallimachos.corpus.NotebookRecord
import java.util.UUID

/**
 * Marts (notebooks) — create / list / get + m:n membership (architecture §9).
 * Owner comes from a fixture principal at v1; the real OBO bearer + the
 * `visibility_roles ∩ caller_roles` predicate are Phase 4. `visibility_roles` is
 * stored now (not yet enforced).
 */
class NotebookService(
    private val notebooks: NotebookPort,
) {
    fun create(
        displayName: String,
        ownerUserId: String,
        visibilityRoles: List<String> = emptyList(),
        id: String = UUID.randomUUID().toString(),
    ): NotebookRecord =
        notebooks.create(
            NewNotebook(
                id = id,
                displayName = displayName,
                ownerUserId = ownerUserId,
                visibilityRoles = visibilityRoles,
            ),
        )

    /**
     * Marts visible to the caller. At v1 this is unfiltered (every mart); the
     * `owner == caller OR visibility_roles ∩ roles` predicate lands at P4 — the
     * signature already takes the principal so callers don't change then.
     */
    fun list(
        @Suppress("UNUSED_PARAMETER") callerUserId: String,
        @Suppress("UNUSED_PARAMETER") callerRoles: Set<String> = emptySet(),
    ): List<NotebookRecord> = notebooks.list()

    fun get(id: String): NotebookRecord? = notebooks.get(id)
}
