package shared.translator.suggest

import org.tatrman.plan.v1.SchemaCode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import shared.translator.framework.FixtureModel

class SuggestingMessageSpec :
    StringSpec({

        val corpus = setOf("customer", "customer_name", "customer_id", "orders", "order_id", "product")

        "appends 'did you mean' suffix when the message contains a quoted identifier with a near match" {
            val msg = "Column 'custmer_name' not found in any table"
            val out = SuggestingMessage.enrich(msg, corpus)
            out shouldContain msg
            out shouldContain "did you mean:"
            out shouldContain "customer_name"
        }

        "preserves the original message when no quoted identifier is present" {
            val msg = "Syntax error at offset 12"
            SuggestingMessage.enrich(msg, corpus) shouldBe msg
        }

        "preserves the original message when the corpus is empty" {
            val msg = "Column 'whatever' not found"
            SuggestingMessage.enrich(msg, emptySet()) shouldBe msg
        }

        "preserves the original message when no suggestions qualify (too distant)" {
            val msg = "Column 'xyzzy_unrelated' not found"
            val out = SuggestingMessage.enrich(msg, corpus)
            out shouldBe msg
            out shouldNotContain "did you mean"
        }

        "matches the FIRST quoted identifier in the message (the offending one)" {
            val msg = "Object 'CUSTMER' not found in schema 'dbo'"
            val out = SuggestingMessage.enrich(msg, corpus)
            out shouldContain "customer"
        }

        val handle = FixtureModel.handleWithEntities()

        "same-schema typo (DB): suggestions drawn only from DB, no ER leakage" {
            val msg = "Column 'custmers' not found"
            val out = SuggestingMessage.enrich(msg, handle, SchemaCode.DB, "dbo")
            out shouldContain "custmers"
            out shouldContain "customers"
            out shouldNotContain "entity"
        }

        "cross-schema (ER path, bad identifier is DB table): cross-schema hint, not 'did you mean'" {
            val msg = "Object 'customers' not found"
            val out = SuggestingMessage.enrich(msg, handle, SchemaCode.ER, "entity")
            out shouldContain "customers"
            out shouldContain "db object"
            out shouldNotContain "did you mean"
            out shouldContain "source_schema=DB"
        }

        "UNSPECIFIED active schema → plain suggestions, no cross-schema hint" {
            val msg = "Object 'customers' not found"
            val out = SuggestingMessage.enrich(msg, handle, SchemaCode.SCHEMA_CODE_UNSPECIFIED, "")
            out shouldContain "did you mean"
            out shouldNotContain "is a db object"
            out shouldNotContain "source_schema"
        }
    })
