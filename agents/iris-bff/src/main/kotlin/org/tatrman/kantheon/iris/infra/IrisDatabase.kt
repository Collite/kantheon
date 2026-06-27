package org.tatrman.kantheon.iris.infra

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
 * Postgres bootstrap for iris-bff: builds the Hikari pool + Exposed connection
 * (via the shared `db-common` helper), then runs the Flyway migrations under
 * `classpath:db/migration`. `/ready` gates on this having completed.
 */
class IrisDatabase(
    config: Config,
) {
    private val log = LoggerFactory.getLogger(IrisDatabase::class.java)
    val connection: DatabaseConnection = DatabaseConnection.fromConfig(config, "iris.db")

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
        log.info("iris-bff schema migrated: version={} applied={}", outcome.version, outcome.applied)
        return outcome
    }

    fun close() = connection.close()
}
