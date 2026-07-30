package org.tatrman.kantheon.iris.protocol

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * The corpus itself is a contract (contracts §9, PT-22): every case must carry
 * every input file, and every file must still parse against the current protos.
 * A fixture that silently stopped parsing would turn the whole golden suite green
 * for the wrong reason.
 */
class FixtureLoaderSpec :
    StringSpec({

        "every case dir contains the contract files" {
            FixtureLoader.CASES.forEach { case ->
                FixtureLoader.dir(case).isDirectory shouldBe true
                FixtureLoader.REQUIRED_FILES.forEach { f ->
                    withClue(case, f) { FixtureLoader.exists(case, f) shouldBe true }
                }
                // The two expectations are authored by T3/T5 for every case.
                withClue(
                    case,
                    "expected-model.json",
                ) { FixtureLoader.exists(case, "expected-model.json") shouldBe true }
                withClue(case, "expected.md") { FixtureLoader.exists(case, "expected.md") shouldBe true }
            }
        }

        "record.json parses as ProtocolRecord[] and carries the F2 capture" {
            FixtureLoader.CASES.forEach { case ->
                val records = FixtureLoader.records(case)
                records.shouldNotBeEmpty()
                records.forEach { r ->
                    r.turnId shouldNotBe ""
                    r.schemaVersion shouldBe "protocol/v1.0"
                    // The whole point of the corpus is exercising the real capture path,
                    // so an empty F2 would quietly reduce every case to a degraded one.
                    r.captures.resolveResponse.isEmpty shouldBe false
                }
            }
        }

        "expected-model.json parses as ProtocolDocument" {
            FixtureLoader.CASES.forEach { case ->
                val doc = FixtureLoader.expectedModel(case)
                withClue(case, "turns") { doc.turnsCount shouldBe FixtureLoader.turns(case).size }
                // Receipts are mandatory in every document (PT-13), no exceptions.
                withClue(case, "receipts") { doc.hasReceipts() shouldBe true }
                doc.schemaVersion shouldBe "protocol/v1.0"
            }
        }

        "turns.json and record.json agree on turn ids" {
            FixtureLoader.CASES.forEach { case ->
                val turnIds = FixtureLoader.turns(case).map { it.turnId }.toSet()
                val recordIds = FixtureLoader.records(case).map { it.turnId }.toSet()
                withClue(case, "ids") { recordIds shouldBe turnIds }
            }
        }
    })

private fun withClue(
    case: String,
    what: String,
    block: () -> Unit,
) = io.kotest.assertions.withClue("$case / $what") { block() }
