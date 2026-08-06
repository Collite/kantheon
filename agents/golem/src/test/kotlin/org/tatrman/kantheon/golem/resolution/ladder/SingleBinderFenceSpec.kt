package org.tatrman.kantheon.golem.resolution.ladder

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText

/**
 * RV-P5.2 — **the Golem never binds** (RV-7), enforced by grep rather than by review.
 *
 * golem-py has the same fence (`test_single_binder_fence.py`). The property is structural,
 * not behavioural: a test that merely checks "this path produced no binding" passes right up
 * until someone adds a path. So the fence reads the source.
 *
 * A hypothesis is a PROPOSAL. It becomes a `Binding` only by surviving `resolve.gate:v1`, on
 * the server, under the same evidence-class rules as any other candidate. If Golem code ever
 * constructs one, the whole proposer-not-binder guarantee is a comment.
 */
private val MAIN = Path.of("src/main/kotlin/org/tatrman/kantheon/golem")

/** `Binding.newBuilder()` — the only way to make one, so the only thing to look for. */
private val FORBIDDEN = Regex("""\bBinding\s*\.\s*newBuilder\s*\(""")

class SingleBinderFenceSpec :
    StringSpec({

        "no code under agents/golem constructs a resolver Binding" {
            val offenders =
                Files
                    .walk(MAIN)
                    .filter { Files.isRegularFile(it) && it.extension == "kt" }
                    .filter { FORBIDDEN.containsMatchIn(it.readText()) }
                    .map { it.toString() }
                    .toList()

            withClue(
                "these files build a Binding; only the core may (RV-7) — a rung PROPOSES and " +
                    "`resolve.gate:v1` decides: $offenders",
            ) { offenders shouldBe emptyList() }
        }

        "the fence can actually fail — it matches the construction the test fixtures use" {
            // A fence nobody has seen fire is a fence nobody should trust. `LadderFixtures.kt`
            // legitimately builds bindings (tests may; production may not), so the same regex
            // must find it there. If this stops matching, the pattern has rotted and the test
            // above is passing vacuously.
            val fixtures = Path.of("src/test/kotlin/org/tatrman/kantheon/golem/resolution/ladder/LadderFixtures.kt")
            FORBIDDEN.containsMatchIn(fixtures.readText()) shouldBe true
        }
    })
