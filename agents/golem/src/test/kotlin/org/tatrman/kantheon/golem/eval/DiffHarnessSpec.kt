package org.tatrman.kantheon.golem.eval

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.io.File

/**
 * The Golem parity gate (S3.3). Replays the recorded-v2 corpus through the Kotlin format pipeline
 * and asserts **zero `BUG`-class divergences** (kantheon's intended additions are tolerated). The
 * full ≥30-conversation corpus is Bora-owned (§8); this runs whatever lives under
 * `eval/corpus/conversations/` and always exercises the committed seed turns. `just eval-golem`
 * runs this spec and the regenerated report lands at `build/diff-harness/report.md` (untracked —
 * the committed `eval/diff-harness/report.md` is a checked-in sample, not mutated by local runs).
 *
 * NOTE — this is a **seed smoke gate**, not full parity: it proves zero BUG-class divergences over
 * the committed seeds (value-level on content, plus kind / plan_source / total_rows / chip+drilldown
 * sources / column-directive count). Broad parity waits on the curated ≥30-conversation corpus.
 */
class DiffHarnessSpec :
    StringSpec({

        // The test working dir is the module dir (agents/golem); fall back to a repo-relative path.
        fun corpusDir(): File =
            listOf(
                File("eval/corpus/conversations"),
                File("agents/golem/eval/corpus/conversations"),
            ).first { it.isDirectory }

        "the recorded-v2 corpus replays with zero BUG-class divergences (parity gate)" {
            runTest {
                val report = CorpusReplay(corpusDir()).replay()

                // Always have at least the committed seeds.
                report.turns.size shouldBeGreaterThanOrEqual 3

                // Write the Markdown parity report to an untracked build dir (local runs must not
                // dirty the committed sample report).
                File("build/diff-harness").mkdirs()
                File("build/diff-harness/report.md").writeText(report.markdown())

                // The gate: no Golem-is-wrong divergences. ACCEPTABLE differences are documented, not blocking.
                report.bugs.map { it.name } shouldBe emptyList()
            }
        }

        "the seed turns classify as expected (clean + one ACCEPTABLE typed-columns delta)" {
            runTest {
                val report = CorpusReplay(corpusDir()).replay()
                val byName = report.turns.associateBy { it.name }

                // table-basic: identical on the compared surface.
                byName["table-basic-strediska"]!!.divergences shouldBe emptyList()

                // table-numeric: the only difference is the Δ5 typed column-spec (ACCEPTABLE).
                byName["table-numeric-zustatek"]!!.divergences.map { it.cls } shouldContainExactlyInAnyOrder
                    listOf(DivergenceClass.ACCEPTABLE)

                // chart-on-hint: the result_kind_hint promoted it to a chart, matching the recorded kind.
                byName["chart-on-hint"]!!.divergences shouldBe emptyList()
            }
        }
    })
