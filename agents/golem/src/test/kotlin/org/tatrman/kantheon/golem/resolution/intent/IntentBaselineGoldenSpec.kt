package org.tatrman.kantheon.golem.resolution.intent

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.kantheon.golem.resolution.ladder.lattice
import org.tatrman.kantheon.themis.v1.Themis
import java.security.MessageDigest

/**
 * RV-P5.3 T1(b) — **with zero operator annotations the seam is a no-op, over the whole
 * routing-seed corpus.**
 *
 * "Byte-identical to its pre-RV baseline" needs the baseline named, and under the T1 design
 * it is exact rather than approximate: before RV, a Golem's notion of the turn's intent was
 * `request.resolved_intent.intent_kind` and nothing else (`format/InvestigateChip.kt:36` is
 * the surviving reader). So the pin is
 *
 *     ∀ corpus rows: classifyTurnIntent(themis(kind), gap-free lattice with no ops).kind == kind
 *
 * which is a stronger statement than re-running Themis's classifier here would be — that
 * would pin *the classifier*, and the classifier is not what this phase changed.
 *
 * The corpus is a vendored copy; see `PROVENANCE.md` beside it.
 */
private const val CORPUS = "intent/routing-seed.jsonl"

private const val CORPUS_SHA256 = "75ade8bef0e66b17bc4f937000bd59753d203d832382c7ad138bdcab1d525616"

private data class Row(
    val question: String,
    val intentKind: Themis.IntentKind,
)

private fun corpusBytes(): ByteArray =
    checkNotNull(Thread.currentThread().contextClassLoader.getResourceAsStream(CORPUS)) {
        "$CORPUS is not on the test classpath"
    }.use { it.readBytes() }

private fun rows(): List<Row> {
    val json = Json { ignoreUnknownKeys = true }
    return corpusBytes()
        .toString(Charsets.UTF_8)
        .lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { line ->
            val o = json.parseToJsonElement(line).jsonObject
            Row(
                question = o.getValue("question").jsonPrimitive.content,
                intentKind =
                    Themis.IntentKind.valueOf(
                        o
                            .getValue("expected")
                            .jsonObject
                            .getValue("intent_kind")
                            .jsonPrimitive.content,
                    ),
            )
        }
}

class IntentBaselineGoldenSpec :
    StringSpec({

        "the vendored corpus is the one PROVENANCE.md records" {
            // The copy is only honest while it matches what was copied. A silent edit here
            // would turn the golden below into a pin on whatever someone typed.
            val actual =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(corpusBytes())
                    .joinToString("") { "%02x".format(it) }
            actual shouldBe CORPUS_SHA256
        }

        "zero operator annotations ⇒ the verdict is Themis's, unchanged, for every corpus row" {
            val corpus = rows()
            corpus.size shouldBeGreaterThanOrEqual 10

            corpus.forEach { row ->
                val out =
                    classifyTurnIntent(
                        prior =
                            Themis.Resolution
                                .newBuilder()
                                .setIntentKind(row.intentKind)
                                .build(),
                        lattice = lattice(), // no mentions ⇒ no operators ⇒ no evidence
                    )

                withClue("\"${row.question}\" — the seam must not move a verdict it has no evidence about") {
                    out.kind shouldBe row.intentKind
                    out.source shouldBe IntentSource.THEMIS
                    out.operators shouldBe emptyList()
                    // And the class follows the kind by the one rule, with no third answer.
                    out.intentClass shouldBe
                        if (row.intentKind == Themis.IntentKind.PROCEDURAL) {
                            IntentClass.DATA_QUERY
                        } else {
                            IntentClass.INVESTIGATION
                        }
                }
            }
        }

        "the corpus exercises every intent kind the vocabulary has except UNSPECIFIED" {
            // A no-op golden over rows that are all PROCEDURAL would prove nothing about the
            // kinds the seam is forbidden to touch. This is the check that the pin has teeth.
            val covered = rows().map { it.intentKind }.distinct()
            covered shouldContainExactlyInAnyOrder
                listOf(
                    Themis.IntentKind.PROCEDURAL,
                    Themis.IntentKind.RCA,
                    Themis.IntentKind.FORECAST,
                    Themis.IntentKind.SIMULATION,
                )
        }
    })
