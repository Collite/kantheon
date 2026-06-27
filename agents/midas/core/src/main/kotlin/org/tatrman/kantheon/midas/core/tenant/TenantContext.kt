package org.tatrman.kantheon.midas.core.tenant

import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.slf4j.LoggerFactory
import shared.libs.db.common.DatabaseConnection
import java.util.UUID

/** Thrown when an operation is attempted without a resolvable tenant — RLS would
 *  run with an unset `app.tenant_id` and `app_current_tenant()` would error. */
class MissingTenantException(
    message: String,
) : IllegalStateException(message)

/**
 * The RLS session-var discipline (contracts §6.4): every DB transaction borrowed
 * by Midas-core sets `app.tenant_id` (transaction-local) before any application
 * SQL, so the Postgres row-level-security policies scope every read/write to the
 * caller's tenant.
 *
 * Injection-safety: the tenant id is parsed to a canonical [UUID] first, so its
 * string form is strictly `[0-9a-f-]` — there is no interpolation vector — and the
 * statement matches the contract's `SET LOCAL app.tenant_id = '<uuid>'` verbatim.
 * The validation + statement builder are pure so the unit test (`RlsPolicySpec`)
 * can assert the discipline without a database; real enforcement is proven by
 * `RlsLeakageComponentSpec` against a live Postgres.
 */
object TenantContext {
    private val log = LoggerFactory.getLogger(TenantContext::class.java)

    /** Parse + require a tenant id; throws [MissingTenantException] when absent/invalid. */
    fun requireTenant(tenantId: String?): UUID {
        if (tenantId.isNullOrBlank()) {
            throw MissingTenantException("tenant id is required; RLS session var would be unset")
        }
        return try {
            UUID.fromString(tenantId.trim())
        } catch (_: IllegalArgumentException) {
            throw MissingTenantException("tenant id is not a valid UUID: '$tenantId'")
        }
    }

    /** The transaction-local RLS statement for a (already-validated) tenant UUID. */
    fun setTenantSql(tenant: UUID): String = "SET LOCAL app.tenant_id = '$tenant'"

    /**
     * Run [block] inside an Exposed transaction that first pins `app.tenant_id` for
     * RLS. All repository DML for a request goes through here.
     */
    fun <T> withTenant(
        db: DatabaseConnection,
        tenantId: String?,
        block: () -> T,
    ): T {
        val tenant = requireTenant(tenantId)
        return db.query {
            TransactionManager.current().exec(setTenantSql(tenant))
            block()
        }
    }
}
