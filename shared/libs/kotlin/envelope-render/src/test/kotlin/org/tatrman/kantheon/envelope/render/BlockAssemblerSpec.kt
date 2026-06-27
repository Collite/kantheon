package org.tatrman.kantheon.envelope.render

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.tatrman.kantheon.common.v1.ViewProvenance
import org.tatrman.kantheon.envelope.render.catalog.FormatResult
import org.tatrman.kantheon.envelope.v1.BlockRole
import org.tatrman.kantheon.envelope.v1.FormatKind
import org.tatrman.kantheon.envelope.v1.FormatSpec

private fun plaintextResult(text: String) =
    FormatResult(
        kind = FormatKind.PLAINTEXT,
        text = text,
        contentJson = null,
        format = FormatSpec.newBuilder().setKind(FormatKind.PLAINTEXT).build(),
    )

class BlockAssemblerSpec :
    StringSpec({

        "stamps producing agent id, view and source tables when provenance is supplied" {
            val view =
                ViewProvenance
                    .newBuilder()
                    .setPatternId("acct.balance")
                    .setSql("select 1")
                    .build()
            val block =
                plaintextResult("ahoj").toBlock(
                    blockId = "b-1",
                    provenance =
                        BlockProvenanceInput(
                            producingAgentId = "golem-erp",
                            view = view,
                            sourceTables = listOf("UCETNICTVI.SALDO", "UCETNICTVI.UCET"),
                        ),
                )

            block.blockId shouldBe "b-1"
            block.role shouldBe BlockRole.PRIMARY
            block.text shouldBe "ahoj"
            block.hasProvenance().shouldBeTrue()
            block.provenance.producingAgentId shouldBe "golem-erp"
            block.provenance.view.patternId shouldBe "acct.balance"
            block.provenance.sourceTablesList.shouldContainExactly(
                listOf("UCETNICTVI.SALDO", "UCETNICTVI.UCET"),
            )
        }

        "absence of provenance yields a block with no provenance field (not an error)" {
            val block = plaintextResult("ahoj").toBlock(blockId = "b-2")
            block.hasProvenance().shouldBeFalse()
            block.text shouldBe "ahoj"
        }

        "carries the format spec and caption through" {
            val block =
                plaintextResult("x").toBlock(
                    blockId = "b-3",
                    role = BlockRole.SUMMARY,
                    caption = "Shrnutí",
                )
            block.role shouldBe BlockRole.SUMMARY
            block.caption shouldBe "Shrnutí"
            block.format.kind shouldBe FormatKind.PLAINTEXT
        }
    })
