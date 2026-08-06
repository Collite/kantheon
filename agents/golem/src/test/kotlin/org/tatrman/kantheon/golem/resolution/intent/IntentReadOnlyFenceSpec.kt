package org.tatrman.kantheon.golem.resolution.intent

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText

/**
 * RV-P5.3 T1(c) — **the intent seam is read-only against the lattice**, proven structurally.
 *
 * The P2.2 `SingleBinderTest` idea, applied to intent. Its sibling
 * [org.tatrman.kantheon.golem.resolution.ladder.SingleBinderFenceSpec] forbids one thing
 * (constructing a `Binding`); this forbids two, because the intent seam has a second way to
 * go wrong. A classifier that *writes* to the lattice — even innocently, even to record its
 * own verdict — makes the lattice a thing two producers own, and from that point on
 * "provenance" is a word rather than a guarantee. The core annotates; the Golem reads.
 *
 * Kotlin protobufs are immutable, so mutation can only happen through a builder: either
 * `X.newBuilder()` or `existing.toBuilder()`. Both are banned in this package. A verdict is
 * returned as [TurnIntent] — a Golem-side value — and never smuggled back into the lattice.
 */
private val INTENT_PKG = Path.of("src/main/kotlin/org/tatrman/kantheon/golem/resolution/intent")

/** Constructing any resolver message, or reopening one for edit. */
private val FORBIDDEN =
    mapOf(
        "builds a resolver Binding (only the core binds — RV-7)" to
            Regex("""\bBinding\s*\.\s*newBuilder\s*\("""),
        "builds a lattice message (the core annotates; the Golem reads)" to
            Regex("""\b(ResolutionState|Mention|GapRecord|ValueFinding|Hypothesis)\s*\.\s*newBuilder\s*\("""),
        "reopens a proto message for edit (`.toBuilder()`) — that is a mutation" to
            Regex("""\.toBuilder\s*\("""),
    )

class IntentReadOnlyFenceSpec :
    StringSpec({

        "nothing in the intent package writes a Binding or mutates the lattice" {
            val sources =
                Files
                    .walk(INTENT_PKG)
                    .filter { Files.isRegularFile(it) && it.extension == "kt" }
                    .toList()

            withClue("the fence found no sources to check — the package moved and this test rotted") {
                sources.isNotEmpty() shouldBe true
            }

            FORBIDDEN.forEach { (why, pattern) ->
                val offenders = sources.filter { pattern.containsMatchIn(it.readText()) }.map { it.toString() }
                withClue("$why: $offenders") { offenders shouldBe emptyList() }
            }
        }

        "the fence can actually fail — every pattern matches something real in the test tree" {
            // A fence nobody has seen fire is a fence nobody should trust (the same argument
            // SingleBinderFenceSpec makes). The specs beside this one legitimately build
            // lattices and bindings; if a pattern stops finding them there, it has rotted and
            // the check above is passing vacuously.
            val witnesses =
                Files
                    .walk(Path.of("src/test/kotlin/org/tatrman/kantheon/golem/resolution"))
                    .filter { Files.isRegularFile(it) && it.extension == "kt" }
                    .toList()
                    .joinToString("\n") { it.readText() }

            FORBIDDEN.forEach { (why, pattern) ->
                withClue("no test source matches the pattern for: $why") {
                    pattern.containsMatchIn(witnesses) shouldBe true
                }
            }
        }
    })
