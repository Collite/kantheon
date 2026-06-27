package org.tatrman.kallimachos.mcp.rls

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * P4 Stage 4.2 T1 — the mart RLS predicate (contracts §7): owner OR
 * visibility_roles ∩ caller_roles; the `"*"` admin scope; the no-overlap denial.
 */
class MartRlsSpec :
    StringSpec({
        val mart = NotebookAcl("finance", ownerUserId = "bora", visibilityRoles = listOf("kantheon-area-finance"))

        "the owner may read their mart" {
            MartRls.canRead(Identity("bora", emptySet()), mart) shouldBe true
        }

        "a role overlap grants access" {
            MartRls.canRead(Identity("someone", setOf("kantheon-area-finance", "x")), mart) shouldBe true
        }

        "no owner match and no role overlap is denied" {
            MartRls.canRead(Identity("someone", setOf("kantheon-area-hr")), mart) shouldBe false
            MartRls.canRead(Identity("someone", emptySet()), mart) shouldBe false
        }

        "the admin scope requires the admin role" {
            MartRls.canReadAdminScope(Identity("ops", setOf(MartRls.ADMIN_ROLE))) shouldBe true
            MartRls.canReadAdminScope(Identity("ops", setOf("kantheon-area-finance"))) shouldBe false
        }

        "IdentityResolver reads sub + roles from a JWT payload (realm_access + flat)" {
            // header.payload.sig — payload = {"sub":"bora","realm_access":{"roles":["kantheon-area-finance"]}}
            val payload =
                java.util.Base64
                    .getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(
                        """{"sub":"bora","realm_access":{"roles":["kantheon-area-finance"]}}""".toByteArray(),
                    )
            val id = IdentityResolver.fromBearer("Bearer h.$payload.s")
            id.userId shouldBe "bora"
            id.roles shouldBe setOf("kantheon-area-finance")
        }

        "a blank bearer is anonymous (no roles) → denied for any non-public mart" {
            val anon = IdentityResolver.fromBearer(null)
            anon.userId shouldBe ""
            MartRls.canRead(anon, mart) shouldBe false
        }

        "an owner-less mart is NOT readable by an anonymous (blank-sub) caller" {
            // Regression: "" owner == "" userId must not grant access.
            val ownerless = NotebookAcl("orphan", ownerUserId = "", visibilityRoles = emptyList())
            MartRls.canRead(Identity("", emptySet()), ownerless) shouldBe false
            MartRls.canRead(IdentityResolver.fromBearer(null), ownerless) shouldBe false
        }
    })
