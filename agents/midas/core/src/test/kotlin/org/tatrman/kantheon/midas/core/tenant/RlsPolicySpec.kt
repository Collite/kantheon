package org.tatrman.kantheon.midas.core.tenant

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.util.UUID

/**
 * Stage 1.3 T1 — unit-level guard on the RLS session-var discipline (contracts
 * §6.4). Asserts the pure pieces: a tenant is always required (no silent
 * unset-tenant query), and the transaction-local statement pins the validated
 * UUID verbatim. Real RLS enforcement (cross-tenant isolation) is proven against
 * a live Postgres by `RlsLeakageComponentSpec`.
 */
class RlsPolicySpec :
    StringSpec({

        "requireTenant returns the UUID for a valid id" {
            val id = UUID.randomUUID()
            TenantContext.requireTenant(id.toString()) shouldBe id
        }

        "requireTenant throws when the tenant is null" {
            shouldThrow<MissingTenantException> { TenantContext.requireTenant(null) }
        }

        "requireTenant throws when the tenant is blank" {
            shouldThrow<MissingTenantException> { TenantContext.requireTenant("   ") }
        }

        "requireTenant throws when the tenant is not a UUID" {
            shouldThrow<MissingTenantException> { TenantContext.requireTenant("not-a-uuid") }
        }

        "setTenantSql pins app.tenant_id transaction-locally for the validated uuid" {
            val id = UUID.fromString("11111111-1111-1111-1111-111111111111")
            TenantContext.setTenantSql(id) shouldBe
                "SET LOCAL app.tenant_id = '11111111-1111-1111-1111-111111111111'"
        }

        "setTenantSql carries only the canonical uuid — no injection vector" {
            // Even if a hostile string reaches requireTenant, it is rejected before
            // ever reaching setTenantSql; the statement only ever sees a parsed UUID.
            val id = UUID.randomUUID()
            TenantContext.setTenantSql(id) shouldContain id.toString()
            shouldThrow<MissingTenantException> { TenantContext.requireTenant("'; DROP TABLE clients;--") }
        }
    })
