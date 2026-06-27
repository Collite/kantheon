package org.tatrman.kallimachos.adapters.notebook

import org.tatrman.kallimachos.corpus.NodeKind
import org.tatrman.kallimachos.corpus.NotebookRecord
import org.tatrman.kallimachos.tx.SnapshotStore

/**
 * In-memory notebooks plane — wired adapter for the single-PG-not-yet-live
 * profile and the service-spec fake. Snapshot-able so notebook writes inside the
 * ingestion fan-out roll back with the rest.
 */
class InMemoryNotebookAdapter :
    NotebookPort,
    SnapshotStore {
    private data class Member(
        val kind: NodeKind,
        val id: Long,
    )

    private val notebooks = linkedMapOf<String, NotebookRecord>()
    private val members = linkedMapOf<String, MutableSet<Member>>()

    override fun create(notebook: NewNotebook): NotebookRecord {
        val rec =
            NotebookRecord(
                id = notebook.id,
                displayName = notebook.displayName,
                ownerUserId = notebook.ownerUserId,
                visibilityRoles = notebook.visibilityRoles,
                memberCount = 0,
            )
        notebooks[notebook.id] = rec
        members.putIfAbsent(notebook.id, linkedSetOf())
        return rec
    }

    override fun get(id: String): NotebookRecord? = notebooks[id]?.copy(memberCount = memberCount(id))

    override fun list(): List<NotebookRecord> = notebooks.keys.mapNotNull { get(it) }

    override fun addMember(
        notebookId: String,
        nodeKind: NodeKind,
        nodeId: Long,
    ) {
        members.getOrPut(notebookId) { linkedSetOf() }.add(Member(nodeKind, nodeId))
    }

    override fun memberSourceIds(notebookId: String): Set<Long> =
        members[notebookId]
            .orEmpty()
            .filter { it.kind == NodeKind.SOURCE }
            .map { it.id }
            .toSet()

    override fun memberCount(notebookId: String): Long = members[notebookId].orEmpty().size.toLong()

    override fun snapshot(): () -> Unit {
        val nb = LinkedHashMap(notebooks)
        val mem = LinkedHashMap(members.mapValues { LinkedHashSet(it.value) })
        return {
            notebooks.clear()
            notebooks.putAll(nb)
            members.clear()
            mem.forEach { (k, v) -> members[k] = LinkedHashSet(v) }
        }
    }
}
