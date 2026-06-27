package org.tatrman.kantheon.pythia.persistence

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
 * Postgres bootstrap for pythia (per-pod database `pythia`, kantheon-architecture
 * §7.1): builds the Hikari pool + Exposed connection (via shared `db-common`), then
 * runs the Flyway migrations under `classpath:db/migration`. `/ready` gates on this
 * — the pod never serves with an unmigrated schema (fail-fast at boot).
 */
class PythiaDatabase(
    config: Config,
) {
    private val log = LoggerFactory.getLogger(PythiaDatabase::class.java)
    val connection: DatabaseConnection = DatabaseConnection.fromConfig(config, "pythia.db")

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
        // Report the version actually applied (info().current()), not the target — after a
        // no-op migrate the two differ and /ready should reflect reality.
        val current =
            flyway
                .info()
                .current()
                ?.version
                ?.toString() ?: result.targetSchemaVersion?.toString()
        val outcome = MigrationOutcome(current, result.migrationsExecuted)
        log.info("pythia schema migrated: version={} applied={}", outcome.version, outcome.applied)
        return outcome
    }

    fun close() = connection.close()
}
