package org.tatrman.kantheon.iris.protocol.sections

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.kantheon.iris.protocol.FixtureLoader
import org.tatrman.kantheon.iris.protocol.config.ProtocolProfile
import org.tatrman.kantheon.iris.protocol.model.DocumentBuilder
import org.tatrman.kantheon.iris.protocol.sources.ProtocolSources
import org.tatrman.kantheon.protocol.v1.Verbosity

/**
 * Receipts (PT-13/S-6) — the section that makes a degraded protocol honest
 * rather than merely thin. Without it, "the LLM section is missing because the
 * operator switched it off" and "…because the gateway was down" look identical
 * to a reader, and neither can be told from "…because there were no calls".
 */
class ReceiptsSectionBuilderSpec :
    StringSpec({

        fun receiptsFor(case: String) = DocumentBuilder.build(FixtureLoader.request(case)).receipts

        "one SourceReceipt per consulted source with status ok|degraded|skipped-by-config and detail" {
            val r = receiptsFor("H1-full")

            r.sourcesList.map { it.source } shouldContainAll
                listOf("records", "llm-gateway", "loki", "tempo", "translate-explain")
            r.sourcesList.forEach { s ->
                (s.status in setOf("ok", "degraded", "skipped-by-config")) shouldBe true
                // A receipt with no detail is a receipt that explains nothing.
                s.detail.isNotBlank() shouldBe true
            }
        }

        "degraded-loki fixture -> loki receipt degraded with reason; the document is still well-formed (P-4)" {
            val doc = DocumentBuilder.build(FixtureLoader.request("degraded-loki"))
            val loki = doc.receipts.sourcesList.single { it.source == "loki" }

            loki.status shouldBe "degraded"
            loki.detail shouldContain "timed out"

            // Degradation lives INSIDE the document — it is not an error shape (P-4).
            doc.turnsCount shouldBe 1
            doc.hasReceipts() shouldBe true
            // ...and the sources that DID answer still say ok, so the reader can see
            // exactly how much of the picture survived.
            doc.receipts.sourcesList
                .single { it.source == "tempo" }
                .status shouldBe "ok"
        }

        "a capture that was structurally unavailable gets its own receipt line (A-1)" {
            val r = receiptsFor("H1-full")
            val gap = r.sourcesList.single { it.source == "capture:security_applied" }

            gap.status shouldBe "degraded"
            gap.detail shouldContain "the query service does not propagate"
        }

        "zero record rows is degraded, not ok — 'no rows' means capture was not running" {
            val r =
                ReceiptsSectionBuilder.build(
                    records = emptyList(),
                    sources = ProtocolSources(),
                    profile = ProtocolProfile(),
                    estate = "hartland",
                    assemblerVersion = "1.0",
                )

            val records = r.sourcesList.single { it.source == "records" }
            records.status shouldBe "degraded"
            records.detail shouldContain "capture may not have been running"
        }

        "profile_name comes from the resolved config profile" {
            receiptsFor("H1-full").profileName shouldBe "default"
            receiptsFor("H3-operator").profileName shouldBe "operator"
        }

        "generated_by names the service, its version and the estate" {
            receiptsFor("H1-full").generatedBy shouldBe "iris-bff/1.0 hartland"
        }

        "receipts cannot be turned off by any profile (PT-13)" {
            // Belt and braces: the profile resolver refuses, AND the builder takes no
            // verbosity at all, so there is no parameter through which it could be
            // suppressed even by a caller inside the BFF.
            val hostile =
                ProtocolProfile(
                    name = "hostile",
                    sections =
                        ProtocolProfile.DEFAULT_SECTIONS.mapValues { Verbosity.VERBOSITY_OFF } +
                            mapOf(SectionRegistry.RECEIPTS to Verbosity.VERBOSITY_OFF),
                )
            hostile.verbosityFor(SectionRegistry.RECEIPTS) shouldBe Verbosity.VERBOSITY_FULL

            val r =
                ReceiptsSectionBuilder.build(
                    records = FixtureLoader.records("H1-full"),
                    sources = FixtureLoader.sources("H1-full"),
                    profile = hostile,
                    estate = "hartland",
                    assemblerVersion = "1.0",
                )
            r.sourcesCount shouldBe 6
            r.profileName shouldBe "hostile"

            // And it is never a member of the configurable spine, so no section loop
            // can drop it either.
            SectionRegistry.configurableKeys.contains(SectionRegistry.RECEIPTS) shouldBe false
            SectionRegistry.spineFor(sessionScope = true).contains(SectionRegistry.RECEIPTS) shouldBe false
        }
    })
