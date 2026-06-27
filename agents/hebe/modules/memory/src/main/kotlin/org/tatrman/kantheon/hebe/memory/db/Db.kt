package org.tatrman.kantheon.hebe.memory.db

import java.nio.file.Path
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource
import org.flywaydb.core.Flyway

class Db(
    val dataSource: DataSource,
    val isInMemory: Boolean,
    // Keeps the named in-memory SQLite DB alive; SQLite deletes it when all connections close.
    private val keepAliveConn: java.sql.Connection? = null,
) : AutoCloseable {
    override fun close() {
        keepAliveConn?.close()
    }
}

data class MigrationResult(
    val version: String?,
    val applied: Int,
)

/**
 * Wraps a DataSource so every new connection gets the sqlite-vec extension loaded and the
 * per-connection PRAGMAs applied. This is required because SQLite extensions are per-connection —
 * Flyway and the application each open their own connections from the pool.
 *
 * The underlying [org.sqlite.SQLiteDataSource] must already have `setLoadExtension(true)` called
 * so that new connections accept `SELECT load_extension(...)`.
 */
private class VecLoadingDataSource(
    private val delegate: DataSource,
    private val perConnectionPragmas: List<String> = emptyList(),
) : DataSource by delegate {
    override fun getConnection(): Connection = setup(delegate.connection)

    override fun getConnection(
        username: String,
        password: String,
    ): Connection = setup(delegate.getConnection(username, password))

    @Suppress("detekt:TooGenericExceptionCaught")
    private fun setup(conn: Connection): Connection {
        try {
            SqliteVecExtension.loadOnConnection(conn)
        } catch (e: Exception) {
            System.err.println("Warning: failed to load sqlite-vec on connection: ${e.message}. Vector search disabled.")
        }
        if (perConnectionPragmas.isNotEmpty()) {
            conn.createStatement().use { st ->
                perConnectionPragmas.forEach { pragma -> st.execute(pragma) }
            }
        }
        return conn
    }
}

object DbFactory {
    fun open(
        path: Path,
        observer: org.tatrman.kantheon.hebe.api.Observer? = null,
    ): Db {
        val raw = org.sqlite.SQLiteDataSource()
        raw.url = "jdbc:sqlite:$path?journal_mode=WAL&busy_timeout=5000&foreign_keys=on"
        raw.setLoadExtension(true)

        // One-time file-level PRAGMAs (WAL, mmap_size) applied once; per-connection PRAGMAs
        // (foreign_keys, synchronous, temp_store) applied by VecLoadingDataSource on every connection.
        raw.connection.use { conn ->
            conn.createStatement().use { st ->
                st.execute("PRAGMA journal_mode=WAL")
                st.execute("PRAGMA mmap_size=268435456")
            }
        }

        val ds =
            VecLoadingDataSource(
                raw,
                listOf(
                    "PRAGMA synchronous=NORMAL",
                    "PRAGMA foreign_keys=ON",
                    "PRAGMA temp_store=MEMORY",
                ),
            )

        val result = migrate(ds)
        observer?.event(
            org.tatrman.kantheon.hebe.api.ObserverEvent
                .MemoryDbReady(result.version, result.applied),
        )

        return Db(ds, isInMemory = false)
    }

    fun openInMemory(): Db {
        // Use a named shared-cache in-memory database so multiple connections within the same
        // process share the same schema. A UUID ensures test-level isolation.
        val dbName = "hebe-mem-${UUID.randomUUID()}"
        val raw = org.sqlite.SQLiteDataSource()
        raw.url = "jdbc:sqlite:file:$dbName?mode=memory&cache=shared"
        raw.setLoadExtension(true)

        val ds = VecLoadingDataSource(raw, listOf("PRAGMA foreign_keys=ON"))

        // Open keepalive before migrating so the DB stays alive after Flyway closes its connections.
        val keepAlive = ds.connection
        migrate(ds)
        return Db(ds, isInMemory = true, keepAliveConn = keepAlive)
    }
}

fun migrate(ds: DataSource): MigrationResult {
    val flyway =
        Flyway
            .configure()
            .dataSource(ds)
            .locations("classpath:db/migration")
            .baselineOnMigrate(false)
            .load()
    val result = flyway.migrate()
    val flywayVersion = result.targetSchemaVersion

    @Suppress("RedundantCallOfConversionMethod")
    val version: String? = flywayVersion?.toString()
    return MigrationResult(
        version = version,
        applied = result.migrationsExecuted,
    )
}
