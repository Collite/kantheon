package org.tatrman.kantheon.hebe.memory.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Per-instance Postgres connection for the `storage.backend = postgres` profiles
 * (`server` + `k8s`; contracts §4). Mirrors the kantheon Exposed + HikariCP + Flyway
 * convention (`shared:libs:kotlin:db-common`, midas `MidasDatabase`) but stays
 * hebe-local: it takes explicit connection params (resolved from the `[storage]`
 * axis + the secrets backend) rather than a typesafe `Config`, so the memory module
 * carries no config-loading coupling.
 *
 * Instance isolation is a hard rule (architecture §5.1): the schema `hebe_<id>` is
 * pinned on `search_path` for every pooled connection, and Flyway migrates that one
 * schema (`flyway.schemas=hebe_<id>`). No cross-schema access, ever.
 */
class PgDb internal constructor(
    val dataSource: DataSource,
    private val hikari: HikariDataSource,
) : AutoCloseable {
    /** Exposed transaction helper — the single entry point for app queries. */
    fun <T> query(block: () -> T): T = transaction { block() }

    override fun close() = hikari.close()
}

data class PgConnectionSpec(
    val jdbcUrl: String,
    /** Optional — blank when the JDBC URL carries `user`/`password` params (the `pg` secret). */
    val username: String = "",
    val password: String = "",
    /** `hebe_<instance_id>` — pinned on `search_path`, the migration target schema. */
    val schema: String,
    val maxPoolSize: Int = MAX_POOL_SIZE,
) {
    companion object {
        const val MAX_POOL_SIZE = 4
    }
}

object PgDbFactory {
    /**
     * Opens the pool, pins the instance schema on every connection, migrates the
     * shared `db/migration-pg/` set into that schema, and connects Exposed. Fails
     * fast (the kantheon migrate-on-boot precedent).
     */
    fun open(spec: PgConnectionSpec): PgDb {
        val hikari =
            HikariDataSource(
                HikariConfig().apply {
                    driverClassName = "org.postgresql.Driver"
                    jdbcUrl = spec.jdbcUrl
                    // Only set creds explicitly when supplied; otherwise they ride in the
                    // JDBC URL (`?user=…&password=…` from the `pg` secret).
                    if (spec.username.isNotBlank()) username = spec.username
                    if (spec.password.isNotBlank()) password = spec.password
                    maximumPoolSize = spec.maxPoolSize
                    isAutoCommit = false
                    // Pin the instance schema for every pooled connection — instance
                    // isolation (architecture §5.1). search_path is set per connection.
                    connectionInitSql = "SET search_path TO ${spec.schema}"
                    schema = spec.schema
                    validate()
                },
            )
        migratePg(hikari, spec.schema)
        Database.connect(hikari, databaseConfig = DatabaseConfig { defaultMinRetryDelay = RETRY_DELAY_MS })
        return PgDb(hikari, hikari)
    }

    private const val RETRY_DELAY_MS = 100L
}

/** Runs the shared PG migration set into the instance schema (`flyway.schemas=<schema>`). */
fun migratePg(
    ds: DataSource,
    schema: String,
): MigrationResult {
    val flyway =
        Flyway
            .configure()
            .dataSource(ds)
            .schemas(schema)
            .defaultSchema(schema)
            .locations("classpath:db/migration-pg")
            .baselineOnMigrate(false)
            .load()
    val result = flyway.migrate()

    @Suppress("RedundantCallOfConversionMethod")
    val version: String? = result.targetSchemaVersion?.toString()
    return MigrationResult(version = version, applied = result.migrationsExecuted)
}
