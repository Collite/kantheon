package org.tatrman.kantheon.hebe.memory

import org.tatrman.kantheon.hebe.api.MemoryStore
import org.tatrman.kantheon.hebe.api.Observer
import org.tatrman.kantheon.hebe.memory.db.Db
import org.tatrman.kantheon.hebe.memory.db.PgDb
import org.tatrman.kantheon.hebe.memory.embeddings.EmbeddingProvider
import org.tatrman.kantheon.hebe.memory.hygiene.HygieneScanner
import org.tatrman.kantheon.hebe.memory.workspace.WorkspaceFs

/**
 * Selects the [MemoryStore] implementation by backend (resolved from the
 * `storage.backend` axis by the caller — never the profile name; architecture §2.2).
 * The matching db handle must be supplied; the off-branch handle is ignored. This is
 * the single production swap point (cli-app `AgentFactory`); the selection is
 * unit-tested with a mocked [PgDb] (no live Postgres — real-PG boot is the
 * integration suite, planning-conventions §4).
 */
object MemoryStoreFactory {
    enum class Backend { SQLITE, POSTGRES }

    @Suppress("LongParameterList")
    fun create(
        backend: Backend,
        sqliteDb: Db?,
        pgDb: PgDb?,
        workspaceFs: WorkspaceFs,
        embeddings: EmbeddingProvider,
        hygieneScanner: HygieneScanner,
        observer: Observer?,
    ): MemoryStore =
        when (backend) {
            Backend.SQLITE ->
                SqliteMemoryStore(
                    db = requireNotNull(sqliteDb) { "storage.backend=sqlite requires an open SQLite Db" },
                    workspaceFs = workspaceFs,
                    embeddings = embeddings,
                    hygieneScanner = hygieneScanner,
                    observer = observer,
                )
            Backend.POSTGRES ->
                PostgresMemoryStore(
                    db = requireNotNull(pgDb) { "storage.backend=postgres requires an open PgDb" },
                    workspaceFs = workspaceFs,
                    embeddings = embeddings,
                    hygieneScanner = hygieneScanner,
                    observer = observer,
                )
        }
}
