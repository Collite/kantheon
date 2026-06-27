package org.tatrman.kallimachos.adapters.exposed

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.tatrman.kallimachos.adapters.notebook.NewNotebook
import org.tatrman.kallimachos.adapters.notebook.NotebookPort
import org.tatrman.kallimachos.corpus.NodeKind
import org.tatrman.kallimachos.corpus.NotebookRecord
import java.time.OffsetDateTime

/**
 * The live notebooks (marts) plane on Postgres. Membership writes are idempotent
 * (`insertIgnore` — re-adding a node is a no-op). Integration-verified.
 */
class ExposedNotebookAdapter : NotebookPort {
    override fun create(notebook: NewNotebook): NotebookRecord {
        Notebooks.insert {
            it[id] = notebook.id
            it[displayName] = notebook.displayName
            it[ownerUserId] = notebook.ownerUserId
            it[visibilityRoles] = notebook.visibilityRoles
            it[createdAt] = OffsetDateTime.now()
        }
        return NotebookRecord(
            id = notebook.id,
            displayName = notebook.displayName,
            ownerUserId = notebook.ownerUserId,
            visibilityRoles = notebook.visibilityRoles,
            memberCount = 0,
        )
    }

    override fun get(id: String): NotebookRecord? =
        Notebooks.selectAll().where { Notebooks.id eq id }.singleOrNull()?.let { row ->
            NotebookRecord(
                id = row[Notebooks.id],
                displayName = row[Notebooks.displayName],
                ownerUserId = row[Notebooks.ownerUserId],
                visibilityRoles = row[Notebooks.visibilityRoles],
                memberCount = memberCount(id),
            )
        }

    override fun list(): List<NotebookRecord> =
        Notebooks.selectAll().map { row ->
            NotebookRecord(
                id = row[Notebooks.id],
                displayName = row[Notebooks.displayName],
                ownerUserId = row[Notebooks.ownerUserId],
                visibilityRoles = row[Notebooks.visibilityRoles],
                memberCount = memberCount(row[Notebooks.id]),
            )
        }

    override fun addMember(
        notebookId: String,
        nodeKind: NodeKind,
        nodeId: Long,
    ) {
        NotebookMembers.insertIgnore {
            it[NotebookMembers.notebookId] = notebookId
            it[NotebookMembers.nodeKind] = nodeKind.name.lowercase()
            it[NotebookMembers.nodeId] = nodeId
        }
    }

    override fun memberSourceIds(notebookId: String): Set<Long> =
        NotebookMembers
            .selectAll()
            .where {
                (NotebookMembers.notebookId eq notebookId) and
                    (NotebookMembers.nodeKind eq NodeKind.SOURCE.name.lowercase())
            }.map { it[NotebookMembers.nodeId] }
            .toSet()

    override fun memberCount(notebookId: String): Long =
        NotebookMembers.selectAll().where { NotebookMembers.notebookId eq notebookId }.count()
}
