package org.tatrman.kantheon.golem.eval

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.tatrman.kantheon.envelope.v1.FormatEnvelope
import org.tatrman.kantheon.envelope.v1.FormatSpec
import org.tatrman.kantheon.envelope.v1.TableColumnSpec
import org.tatrman.kantheon.envelope.v1.TableDetails

private fun envWithRows(contentJson: String): FormatEnvelope =
    FormatEnvelope.newBuilder().setContentJson(contentJson).build()

private fun envWithColumns(n: Int): FormatEnvelope {
    val table = TableDetails.newBuilder()
    repeat(n) { table.putColumns("c$it", TableColumnSpec.getDefaultInstance()) }
    return FormatEnvelope.newBuilder().setFormat(FormatSpec.newBuilder().setTable(table)).build()
}

/**
 * Direct coverage of the field-wise envelope diff (the seed corpus only exercises happy paths).
 * Pins the value-level content comparison + the bidirectional column-directive check — the two
 * blind spots a structural-only diff had.
 */
class EnvelopeDiffSpec :
    StringSpec({

        "a per-cell value mismatch is a BUG (structural key-only would have missed it)" {
            val exp = envWithRows("""[{"KOD":"DF01","ZUSTATEK":12500.5}]""")
            val act = envWithRows("""[{"KOD":"DF01","ZUSTATEK":99999.9}]""")
            val d = EnvelopeDiff.diff(exp, act)
            d shouldHaveSize 1
            d.single().cls shouldBe DivergenceClass.BUG
            d.single().field shouldBe "content[0].ZUSTATEK"
        }

        "a real-number difference within float tolerance is NOT flagged (rounding parity)" {
            val exp = envWithRows("""[{"V":0.3333333333}]""")
            val act = envWithRows("""[{"V":0.3333333334}]""")
            EnvelopeDiff.diff(exp, act) shouldBe emptyList()
        }

        "string codes compare exactly — '01' vs '1' is a BUG, not numerically-equal" {
            val exp = envWithRows("""[{"UCET":"01"}]""")
            val act = envWithRows("""[{"UCET":"1"}]""")
            EnvelopeDiff.diff(exp, act).single().cls shouldBe DivergenceClass.BUG
        }

        "FEWER column directives than v2 is a lost-directive BUG; MORE is the Δ5 ACCEPTABLE add" {
            EnvelopeDiff.diff(envWithColumns(2), envWithColumns(1)).single().cls shouldBe DivergenceClass.BUG
            EnvelopeDiff.diff(envWithColumns(1), envWithColumns(2)).single().cls shouldBe DivergenceClass.ACCEPTABLE
        }
    })
