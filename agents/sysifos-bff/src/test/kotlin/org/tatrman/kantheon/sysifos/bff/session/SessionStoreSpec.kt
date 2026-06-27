package org.tatrman.kantheon.sysifos.bff.session

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class SessionStoreSpec :
    StringSpec({

        "current() is get-or-create per user" {
            val store = InMemorySessionStore()
            val first = store.current("u1", "acme")
            val again = store.current("u1", "acme")
            again.sessionId shouldBe first.sessionId
        }

        "current() is scoped per (user, tenant) — a user in two tenants gets two sessions" {
            val store = InMemorySessionStore()
            val acme = store.current("u1", "acme")
            val globex = store.current("u1", "globex")
            globex.sessionId shouldNotBe acme.sessionId
            globex.tenantId shouldBe "globex"
            // The first tenant's session is unchanged by the second tenant's read.
            store.current("u1", "acme").sessionId shouldBe acme.sessionId
        }
    })
