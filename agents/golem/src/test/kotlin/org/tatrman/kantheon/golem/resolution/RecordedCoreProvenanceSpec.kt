package org.tatrman.kantheon.golem.resolution

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * RV-P5.1 T2 — the vendored fixtures are copies, so their drift is made loud.
 *
 * RV-28 is "one corpus, one core, N shells", and golem-py honours it literally because it
 * lives in the same repo as the goldens. The Kotlin shell does not, so `lattice/` holds
 * copies (see its `PROVENANCE.md` for the three ways out and why vendoring was picked for
 * one stage). The cost of a copy is silent drift; these two tests are the price paid for it.
 *
 * ⚑ `p5-4` T1 owns the structural fix — the recommendation is a published test-fixtures
 * artifact riding the `server-libs` cut, which answers the same question for
 * `conformance/conversations/` at the same time.
 */
private val EXPECTED_SHA256 =
    mapOf(
        "h1-cs.lattice.json" to "841442995821f0bc2827506b2aabebd141d23df0f15c670235e9e244f07f0439",
        "h1-cs.parse.json" to "98b772feb540de8bd20e4842cc72816188c7f5bc4c52be84bcbc94874cef31f5",
        "h1prime-cs.lattice.json" to "229b92a1d3beef78123303c9c7bcfb55a85fba323afa9437f0c7881dcedcd7f2",
        "h1prime-cs.parse.json" to "141352712c6ec282c17add9beda0ab7f8c298ecef115063b28a94497959abb30",
        "h2-cs.lattice.json" to "9b8fba05a876232bac45345fbd89e59c9277a2879c4323a02d8601606f51fd4a",
        "h2-cs.parse.json" to "4164037a76a54e44ea2e1719ff469e05a68cd5e8bee87c235c05f519ee9c1508",
        "h5-cs.lattice.json" to "97348807f0e16aef8def0f33e61bb51d70ef2e625c66fb77a632ec639ad05586",
        "h5-cs.parse.json" to "7034fbd3fa02282f3fb0995139f6a72ad9b8268183266a3eba328d9abcc85c43",
    )

private const val UPSTREAM_DIR = "services/resolver/src/test/resources/lattice"

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

class RecordedCoreProvenanceSpec :
    StringSpec({

        "every vendored fixture still hashes to what PROVENANCE.md records" {
            EXPECTED_SHA256.forEach { (name, expected) ->
                val bytes =
                    requireNotNull(RecordedResolutionCore::class.java.getResourceAsStream("/lattice/$name")) {
                        "missing vendored fixture: lattice/$name"
                    }.use { it.readBytes() }
                withClue(name) { sha256(bytes) shouldBe expected }
            }
        }

        "the vendored fixtures match tatrman-server's originals when a sibling checkout is available" {
            val siblingRoot = System.getenv("TATRMAN_SERVER_DIR")
            if (siblingRoot == null) {
                // A skip that prints nothing is how a vendored fixture rots. Say it out loud.
                println(
                    "SKIPPED cross-repo drift check: set TATRMAN_SERVER_DIR to a tatrman-server " +
                        "checkout to diff agents/golem/src/test/resources/lattice/ against " +
                        "$UPSTREAM_DIR/. The sha256 test above still ran — it proves these files " +
                        "have not changed HERE, not that they still match THERE.",
                )
            } else {
                EXPECTED_SHA256.keys.forEach { name ->
                    val upstream = Path.of(siblingRoot, UPSTREAM_DIR, name)
                    if (Files.exists(upstream)) {
                        withClue("$name drifted from $upstream") {
                            sha256(Files.readAllBytes(upstream)) shouldBe EXPECTED_SHA256.getValue(name)
                        }
                    } else {
                        println("SKIPPED $name: no such file at $upstream (upstream moved it?)")
                    }
                }
            }
        }
    })
