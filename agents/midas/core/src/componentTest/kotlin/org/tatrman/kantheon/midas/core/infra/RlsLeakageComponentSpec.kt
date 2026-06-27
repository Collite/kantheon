package org.tatrman.kantheon.midas.core.infra

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.postgresql.ds.PGSimpleDataSource
import org.tatrman.kantheon.testkit.containers.Containers
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

/**
 * Stage 1.3 T4 — the real-Postgres RLS proof (the contract's core security
 * guarantee). Runs the production V0001 migration against a live Postgres as a
 * NON-superuser owner role (`midas_app`) — superusers bypass RLS, so this role is
 * essential — and asserts:
 *   1. cross-tenant reads are impossible (tenant A never sees tenant B's rows),
 *   2. cross-tenant writes are rejected (the FORCE-RLS WITH CHECK),
 *   3. a query with no `app.tenant_id` set fails closed (`app_current_tenant()`).
 *
 * Postgres is native multi-arch, so no `@EnabledIf(CiOnly)` gate is needed.
 * Runs in CI via `just test-component` (testing arc); needs a Docker daemon.
 */
@Tags("component")
class RlsLeakageComponentSpec :
    StringSpec({

        "FORCE RLS isolates tenants on read + write, and fails closed when unset" {
            Containers.postgres().use { pg ->
                pg.start()

                // The container's default user is a superuser (bypasses RLS). Create a
                // plain owner role and migrate as it, so FORCE ROW LEVEL SECURITY binds.
                // Also provision the BYPASSRLS `midas_mv_owner` role exactly as the
                // production init job does — V0002 transfers the materialized views to
                // it, so the migration fails without it (BYPASSRLS needs a superuser to
                // create, which Flyway-as-midas_app is not).
                connect(pg.jdbcUrl, pg.username, pg.password).use { su ->
                    su.createStatement().use { st ->
                        st.execute("CREATE ROLE midas_app LOGIN PASSWORD 'midas_app' NOSUPERUSER")
                        st.execute("GRANT ALL ON SCHEMA public TO midas_app")
                        st.execute("GRANT CREATE ON SCHEMA public TO midas_app")
                        st.execute("CREATE ROLE midas_mv_owner BYPASSRLS NOLOGIN")
                        st.execute("GRANT midas_mv_owner TO midas_app")
                        st.execute("GRANT USAGE, CREATE ON SCHEMA public TO midas_mv_owner")
                        st.execute(
                            "ALTER DEFAULT PRIVILEGES FOR ROLE midas_app IN SCHEMA public " +
                                "GRANT SELECT ON TABLES TO midas_mv_owner",
                        )
                    }
                }

                val appDs =
                    PGSimpleDataSource().apply {
                        setURL(pg.jdbcUrl)
                        user = "midas_app"
                        password = "midas_app"
                    }
                Flyway
                    .configure()
                    .dataSource(appDs)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate()

                val tenantA = UUID.randomUUID()
                val tenantB = UUID.randomUUID()

                connect(pg.jdbcUrl, "midas_app", "midas_app").use { c ->
                    insertClient(c, tenantA, "Alpha Capital")
                    insertClient(c, tenantB, "Beta Partners")

                    // 1. Reads are tenant-scoped.
                    countClients(c, tenantA) shouldBe 1
                    soleClientName(c, tenantA) shouldBe "Alpha Capital"
                    countClients(c, tenantB) shouldBe 1
                    soleClientName(c, tenantB) shouldBe "Beta Partners"

                    // 2. A write whose tenant_id != the session tenant is rejected (WITH CHECK).
                    setTenant(c, tenantA)
                    shouldThrow<SQLException> { rawInsertClient(c, tenantB, "Cross-tenant") }
                }

                // 3. No tenant set → app_current_tenant() raises; the query fails closed.
                connect(pg.jdbcUrl, "midas_app", "midas_app").use { c ->
                    shouldThrow<SQLException> {
                        c.createStatement().use { it.executeQuery("SELECT count(*) FROM clients").close() }
                    }
                }

                // 4. transactions are tenant-scoped, AND the materialized-view REFRESH
                //    (run as midas_app, a member of the BYPASSRLS midas_mv_owner that
                //    owns the MV) reads ACROSS tenants — the core of the Stage 1.4 design.
                connect(pg.jdbcUrl, "midas_app", "midas_app").use { c ->
                    val clientA = insertClientReturningId(c, tenantA, "Alpha Co")
                    val portfolioA = insertPortfolio(c, tenantA, clientA)
                    val assetA = insertAsset(c, tenantA, "AAA")
                    insertBuy(c, tenantA, portfolioA, assetA)

                    val clientB = insertClientReturningId(c, tenantB, "Beta Co")
                    val portfolioB = insertPortfolio(c, tenantB, clientB)
                    val assetB = insertAsset(c, tenantB, "BBB")
                    insertBuy(c, tenantB, portfolioB, assetB)

                    // transactions RLS: each tenant sees only its own row.
                    countTransactions(c, tenantA) shouldBe 1
                    countTransactions(c, tenantB) shouldBe 1

                    // REFRESH succeeds and the MV ends up holding BOTH tenants' positions
                    // — proof the refresh read past FORCE RLS via the BYPASSRLS owner.
                    refreshPositions(c)
                    mvDistinctTenants(c) shouldBe 2
                }
            }
        }
    })

private fun connect(
    url: String,
    user: String,
    pw: String,
): Connection {
    val ds =
        PGSimpleDataSource().apply {
            setURL(url)
            this.user = user
            password = pw
        }
    return ds.connection
}

private fun setTenant(
    c: Connection,
    tenant: UUID,
) {
    c.prepareStatement("SELECT set_config('app.tenant_id', ?, false)").use { ps ->
        ps.setString(1, tenant.toString())
        ps.executeQuery().close()
    }
}

private fun insertClient(
    c: Connection,
    tenant: UUID,
    name: String,
) {
    setTenant(c, tenant)
    rawInsertClient(c, tenant, name)
}

private fun rawInsertClient(
    c: Connection,
    tenant: UUID,
    name: String,
) {
    c
        .prepareStatement(
            "INSERT INTO clients (tenant_id, name, created_by_user_id, updated_by_user_id) " +
                "VALUES (?, ?, 'test', 'test')",
        ).use { ps ->
            ps.setObject(1, tenant)
            ps.setString(2, name)
            ps.executeUpdate()
        }
}

private fun countClients(
    c: Connection,
    tenant: UUID,
): Int {
    setTenant(c, tenant)
    c.createStatement().use { st ->
        st.executeQuery("SELECT count(*) FROM clients").use { rs ->
            rs.next()
            return rs.getInt(1)
        }
    }
}

private fun soleClientName(
    c: Connection,
    tenant: UUID,
): String {
    setTenant(c, tenant)
    c.createStatement().use { st ->
        st.executeQuery("SELECT name FROM clients").use { rs ->
            rs.next()
            return rs.getString(1)
        }
    }
}

private fun insertClientReturningId(
    c: Connection,
    tenant: UUID,
    name: String,
): UUID {
    setTenant(c, tenant)
    c
        .prepareStatement(
            "INSERT INTO clients (tenant_id, name, created_by_user_id, updated_by_user_id) " +
                "VALUES (?, ?, 'test', 'test') RETURNING client_id",
        ).use { ps ->
            ps.setObject(1, tenant)
            ps.setString(2, name)
            ps.executeQuery().use { rs ->
                rs.next()
                return rs.getObject(1, UUID::class.java)
            }
        }
}

private fun insertPortfolio(
    c: Connection,
    tenant: UUID,
    clientId: UUID,
): UUID {
    setTenant(c, tenant)
    c
        .prepareStatement(
            "INSERT INTO portfolios (tenant_id, client_id, name, base_currency, " +
                "created_by_user_id, updated_by_user_id) VALUES (?, ?, 'P', 'USD', 'test', 'test') " +
                "RETURNING portfolio_id",
        ).use { ps ->
            ps.setObject(1, tenant)
            ps.setObject(2, clientId)
            ps.executeQuery().use { rs ->
                rs.next()
                return rs.getObject(1, UUID::class.java)
            }
        }
}

private fun insertAsset(
    c: Connection,
    tenant: UUID,
    symbol: String,
): UUID {
    setTenant(c, tenant)
    c
        .prepareStatement(
            "INSERT INTO assets (tenant_id, symbol, name, kind, currency, " +
                "created_by_user_id, updated_by_user_id) VALUES (?, ?, ?, 'STOCK', 'USD', 'test', 'test') " +
                "RETURNING asset_id",
        ).use { ps ->
            ps.setObject(1, tenant)
            ps.setString(2, symbol)
            ps.setString(3, symbol)
            ps.executeQuery().use { rs ->
                rs.next()
                return rs.getObject(1, UUID::class.java)
            }
        }
}

private fun insertBuy(
    c: Connection,
    tenant: UUID,
    portfolioId: UUID,
    assetId: UUID,
) {
    setTenant(c, tenant)
    c
        .prepareStatement(
            "INSERT INTO transactions (tenant_id, portfolio_id, asset_id, kind, trade_date, " +
                "quantity, total_amount, total_currency, currency, source, recorded_by_user_id) " +
                "VALUES (?, ?, ?, 'BUY', NOW(), 100, 1000, 'USD', 'USD', 'MANUAL', 'test')",
        ).use { ps ->
            ps.setObject(1, tenant)
            ps.setObject(2, portfolioId)
            ps.setObject(3, assetId)
            ps.executeUpdate()
        }
}

private fun countTransactions(
    c: Connection,
    tenant: UUID,
): Int {
    setTenant(c, tenant)
    c.createStatement().use { st ->
        st.executeQuery("SELECT count(*) FROM transactions").use { rs ->
            rs.next()
            return rs.getInt(1)
        }
    }
}

/** Refresh as midas_app — succeeds only because the MV owner (midas_mv_owner) is BYPASSRLS. */
private fun refreshPositions(c: Connection) {
    c.createStatement().use { it.execute("REFRESH MATERIALIZED VIEW mv_position_current") }
}

private fun mvDistinctTenants(c: Connection): Int {
    c.createStatement().use { st ->
        st.executeQuery("SELECT count(DISTINCT tenant_id) FROM mv_position_current").use { rs ->
            rs.next()
            return rs.getInt(1)
        }
    }
}
