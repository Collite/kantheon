package org.tatrman.kantheon.midas.core.infra

import com.typesafe.config.Config
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import shared.libs.db.common.DatabaseConnection

/**
 * Midas-core operational DB bootstrap (Stage 1.3) — mirrors iris-bff's
 * `IrisDatabase`. Builds the Hikari/Exposed connection via shared `db-common`,
 * then runs Flyway migrations on boot (the kantheon precedent: migrate-on-start,
 * fail fast). The connection is the shared Kantheon Postgres, `midas` database.
 */
class MidasDatabase(
    config: Config,
) {
    private val log = LoggerFactory.getLogger(MidasDatabase::class.java)
    val connection: DatabaseConnection = DatabaseConnection.fromConfig(config, "midas-core.db")

    fun migrateAndConnect(): MigrationOutcome {
        connection.init()
        val flyway =
            Flyway
                .configure()
                .dataSource(connection.getDataSource())
                .locations("classpath:db/migration")
                .baselineOnMigrate(false)
                .load()
        val result = flyway.migrate()
        val outcome = MigrationOutcome(result.targetSchemaVersion?.toString(), result.migrationsExecuted)
        log.info("midas-core schema migrated: version={} applied={}", outcome.version, outcome.applied)
        return outcome
    }

    fun close() = connection.close()
}

data class MigrationOutcome(
    val version: String?,
    val applied: Int,
)
