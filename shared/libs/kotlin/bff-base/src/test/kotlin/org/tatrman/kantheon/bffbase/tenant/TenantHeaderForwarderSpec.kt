package org.tatrman.kantheon.bffbase.tenant

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.kantheon.bffbase.auth.CallerIdentity

class TenantHeaderForwarderSpec :
    StringSpec({

        val caller = CallerIdentity(userId = "u1", tenantId = "acme", bearer = "t")

        "produces X-Tenant-Id from the caller's JWT tenant" {
            TenantHeaderForwarder.header(caller) shouldBe ("X-Tenant-Id" to "acme")
        }

        "matches when the request names no tenant or the same tenant" {
            TenantHeaderForwarder.matches(caller, null) shouldBe true
            TenantHeaderForwarder.matches(caller, "acme") shouldBe true
        }

        "rejects a request that names a different tenant" {
            TenantHeaderForwarder.matches(caller, "other") shouldBe false
        }
    })
