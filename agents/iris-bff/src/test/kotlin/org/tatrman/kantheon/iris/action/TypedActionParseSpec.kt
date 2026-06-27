package org.tatrman.kantheon.iris.action

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.tatrman.kantheon.iris.audit.Ed25519Signer
import org.tatrman.kantheon.iris.audit.InMemoryAuditStore
import org.tatrman.kantheon.iris.dispatch.golemv2.FakeGolemV2Client
import org.tatrman.kantheon.iris.domain.InMemorySessionStore

/**
 * Input-bounds validation on the typed-action parse edge (Stage 3.2): unknown
 * filter operators and out-of-range pagination must be rejected (→ route 400)
 * rather than persisted as a silently-broken view or overflowing the slice math.
 */
class TypedActionParseSpec :
    StringSpec({
        val dispatcher =
            TypedActionDispatcher(InMemorySessionStore(), FakeGolemV2Client(), InMemoryAuditStore(Ed25519Signer()))

        "filter with a known operator parses" {
            val d = dispatcher.parse("filter", """{"column":"r","operator":"gt","value":110}""")
            (d is ShapeDirective.FilterDir) shouldBe true
        }

        "filter with an unknown operator is rejected" {
            dispatcher.parse("filter", """{"column":"r","operator":"bogus","value":1}""").shouldBeNull()
        }

        "paginate with valid bounds parses" {
            val d = dispatcher.parse("paginate", """{"page":2,"pageSize":50}""")
            d shouldBe ShapeDirective.PaginateDir(2, 50)
        }

        "paginate with page < 1 is rejected" {
            dispatcher.parse("paginate", """{"page":0,"pageSize":50}""").shouldBeNull()
        }

        "paginate with a huge pageSize is rejected" {
            dispatcher.parse("paginate", """{"page":1,"pageSize":2000000000}""").shouldBeNull()
        }

        "paginate with a negative page is rejected" {
            dispatcher.parse("paginate", """{"page":-1,"pageSize":10}""").shouldBeNull()
        }
    })
