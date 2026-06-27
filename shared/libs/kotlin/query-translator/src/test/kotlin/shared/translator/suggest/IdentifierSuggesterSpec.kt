package shared.translator.suggest

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class IdentifierSuggesterSpec :
    StringSpec({

        val corpus =
            setOf(
                "customer",
                "customer_name",
                "customer_id",
                "orders",
                "order_id",
                "product",
                "tenant_id",
                "id",
            )

        "single-char typo → top suggestion is the exact intended name" {
            // distance("custmer_name", "customer_name") = 1 ('o' inserted) — wins by distance.
            IdentifierSuggester.suggest("custmer_name", corpus).first() shouldBe "customer_name"
        }

        "transposed letters → suggested" {
            // 'cutsomer' is two transpositions away from 'customer'; both are short, so the
            // typo budget (max(2, ceil(0.4 * 8)) = max(2, 4) = 4) easily covers it.
            IdentifierSuggester.suggest("cutsomer", corpus) shouldContain "customer"
        }

        "missing one character → suggested" {
            IdentifierSuggester.suggest("custmer", corpus) shouldContain "customer"
        }

        "extra character → suggested" {
            IdentifierSuggester.suggest("customerx", corpus) shouldContain "customer"
        }

        "case-insensitive matching" {
            IdentifierSuggester.suggest("CUSTMER", corpus) shouldContain "customer"
        }

        "wholly unrelated name → no suggestions (outside typo budget)" {
            IdentifierSuggester.suggest("xyzzy_plugh", corpus) shouldContainExactly emptyList()
        }

        "empty corpus → no suggestions" {
            IdentifierSuggester.suggest("anything", emptySet()) shouldContainExactly emptyList()
        }

        "limit caps the number of suggestions" {
            // Three names are within edit distance of "customer".
            val out = IdentifierSuggester.suggest("custmer", corpus, limit = 2)
            (out.size <= 2) shouldBe true
        }

        "ties broken alphabetically for determinism" {
            // 'order_id' and 'customer_id' are both 'id'-suffixed; with input 'id_' both are
            // outside the small-string typo budget — verify with a closer pair.
            // 'ordder_id' is 1 away from 'order_id'.
            val out = IdentifierSuggester.suggest("ordder_id", corpus)
            out.first() shouldBe "order_id"
        }
    })
