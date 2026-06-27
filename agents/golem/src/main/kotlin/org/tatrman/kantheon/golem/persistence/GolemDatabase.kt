package org.tatrman.kantheon.golem.persistence

import com.typesafe.config.Config
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import shared.libs.db.common.DatabaseConnection

/** Outcome of the boot-time migration, surfaced on `/ready`. */
data class MigrationOutcome(
    val version: String?,
    val applied: Int,
)

/**
 * Postgres bootstrap for golem (per-pod database, kantheon-architecture §7.1):
 * builds the Hikari pool + Exposed connection (via shared `db-common`), then runs
 * the Flyway migrations under `classpath:db/migration`. `/ready` gates on this.
 */
class GolemDatabase(
    config: Config,
) {
    private val log = LoggerFactory.getLogger(GolemDatabase::class.java)
    val connection: DatabaseConnection = DatabaseConnection.fromConfig(config, "golem.db")

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
        log.info("golem schema migrated: version={} applied={}", outcome.version, outcome.applied)
        return outcome
    }

    fun close() = connection.close()
}
