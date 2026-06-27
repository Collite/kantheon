package org.tatrman.kantheon.pythia.suspicion

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.tatrman.kantheon.pythia.plan.ScriptedPromptExecutor

/**
 * Stage 3.1 T2/T3 — the suspicion rules checklist trips exactly the right reason
 * per fixture; a clean result trips none; the gated CHEAP fuzzy check fires only
 * for a rules-clean, load-bearing result.
 */
class SuspicionClassifierSpec :
    StringSpec({

        fun rows(s: String) = Json.parseToJsonElement(s) as JsonArray
        val classifier = SuspicionClassifier()

        "empty result where rows expected" {
            val v = classifier.classify(rows("[]"), emptyList(), emptyList(), Expectation(expectRows = true))
            v.suspicious shouldBe true
            v.reasons.single().contains("empty result") shouldBe true
        }

        "a 10x row-count anomaly trips" {
            val v =
                classifier.classify(
                    rows(
                        (1..50).joinToString(",", "[", "]") {
                            "{}"
                        },
                    ),
                    emptyList(),
                    emptyList(),
                    Expectation(expectedRowCount = 4),
                )
            v.reasons.single().contains(">10×") shouldBe true
        }

        "a high NULL rate trips" {
            val v =
                classifier.classify(
                    rows("""[{"x":1},{"x":null},{"x":null}]"""),
                    listOf("x"),
                    emptyList(),
                    Expectation(),
                )
            v.reasons.single().contains("NULL rate") shouldBe true
        }

        "a schema mismatch trips" {
            val v =
                classifier.classify(
                    rows("""[{"a":1}]"""),
                    listOf("a"),
                    emptyList(),
                    Expectation(expectedColumns = listOf("b")),
                )
            v.reasons.single().contains("schema mismatch") shouldBe true
        }

        "forwarded security flags trip" {
            val v =
                classifier.classify(
                    rows("""[{"a":1}]"""),
                    listOf("a"),
                    listOf("row-level security denied a table"),
                    Expectation(),
                )
            v.reasons.single().contains("security flags") shouldBe true
        }

        "a clean result trips nothing" {
            classifier
                .classify(
                    rows("""[{"a":1},{"a":2}]"""),
                    listOf("a"),
                    emptyList(),
                    Expectation(expectedRowCount = 2, expectedColumns = listOf("a")),
                ).reasons
                .shouldBeEmpty()
        }

        "the gated fuzzy check fires only for a rules-clean, load-bearing result" {
            runTest {
                val clean = SuspicionVerdict(false, emptyList())
                val exec = ScriptedPromptExecutor(listOf("SUSPICIOUS: implausible spike"))

                // load-bearing + executor → fuzzy runs
                val fuzzy =
                    classifier.fuzzyCheck(
                        rows("""[{"a":1}]"""),
                        "stmt",
                        clean,
                        Expectation(loadBearing = true),
                        exec,
                    )
                fuzzy!!.suspicious shouldBe true
                exec.callCount shouldBe 1

                // not load-bearing → no call
                classifier.fuzzyCheck(
                    rows("""[{"a":1}]"""),
                    "stmt",
                    clean,
                    Expectation(loadBearing = false),
                    exec,
                ) shouldBe
                    null
                exec.callCount shouldBe 1 // unchanged
            }
        }
    })
