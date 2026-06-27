package org.tatrman.kantheon.pythia.evaluate

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.tatrman.kantheon.pythia.v1.Predicate

/**
 * Stage 2.3 T3 — one case per predicate kind (pass / fail / non-applicable). A
 * predicate that can't apply to the result shape returns NON_APPLICABLE, never an
 * error (the LLM fallback for that gap is Phase 3 Stage 3.1).
 */
class PredicateEvaluatorSpec :
    StringSpec({

        fun rows(s: String): JsonArray = Json.parseToJsonElement(s) as JsonArray

        fun predicate(
            kind: Predicate.Kind,
            threshold: Double,
            params: String = "{}",
        ) = Predicate
            .newBuilder()
            .setKind(kind)
            .setThreshold(threshold)
            .setParametersJson(params)
            .build()

        "ROW_COUNT_GT passes above and fails at/below the threshold" {
            val p = predicate(Predicate.Kind.ROW_COUNT_GT, 2.0)
            PredicateEvaluator.evaluate(p, rows("""[{},{},{}]""")) shouldBe PredicateVerdict.PASS
            PredicateEvaluator.evaluate(p, rows("""[{},{}]""")) shouldBe PredicateVerdict.FAIL
        }

        "ROW_COUNT_LT and ROW_COUNT_EQ" {
            PredicateEvaluator.evaluate(predicate(Predicate.Kind.ROW_COUNT_LT, 2.0), rows("""[{}]""")) shouldBe
                PredicateVerdict.PASS
            PredicateEvaluator.evaluate(predicate(Predicate.Kind.ROW_COUNT_EQ, 2.0), rows("""[{},{}]""")) shouldBe
                PredicateVerdict.PASS
        }

        "METRIC_DELTA_RATIO: a >=5% drop passes; a flat series fails; missing column is non-applicable" {
            val p = predicate(Predicate.Kind.METRIC_DELTA_RATIO, -0.05, """{"column":"revenue"}""")
            PredicateEvaluator.evaluate(p, rows("""[{"revenue":100},{"revenue":86}]""")) shouldBe PredicateVerdict.PASS
            PredicateEvaluator.evaluate(p, rows("""[{"revenue":100},{"revenue":99}]""")) shouldBe PredicateVerdict.FAIL
            PredicateEvaluator.evaluate(p, rows("""[{"other":1},{"other":2}]""")) shouldBe
                PredicateVerdict.NON_APPLICABLE
            PredicateEvaluator.evaluate(p, rows("""[{"revenue":100}]""")) shouldBe PredicateVerdict.NON_APPLICABLE
        }

        "NULL_RATE_LT: low null rate passes; high fails; missing column is non-applicable" {
            val p = predicate(Predicate.Kind.NULL_RATE_LT, 0.5, """{"column":"x"}""")
            PredicateEvaluator.evaluate(p, rows("""[{"x":1},{"x":2}]""")) shouldBe PredicateVerdict.PASS
            PredicateEvaluator.evaluate(p, rows("""[{"x":1},{"x":null},{"x":null}]""")) shouldBe PredicateVerdict.FAIL
            PredicateEvaluator.evaluate(p, rows("""[{"y":1}]""")) shouldBe PredicateVerdict.NON_APPLICABLE
        }

        "CORRELATION_STRENGTH: strong correlation passes; weak fails; too few rows is non-applicable" {
            val p = predicate(Predicate.Kind.CORRELATION_STRENGTH, 0.8, """{"x":"a","y":"b"}""")
            PredicateEvaluator.evaluate(p, rows("""[{"a":1,"b":2},{"a":2,"b":4},{"a":3,"b":6}]""")) shouldBe
                PredicateVerdict.PASS
            PredicateEvaluator.evaluate(p, rows("""[{"a":1,"b":9},{"a":2,"b":1},{"a":3,"b":5}]""")) shouldBe
                PredicateVerdict.FAIL
            PredicateEvaluator.evaluate(p, rows("""[{"a":1,"b":2}]""")) shouldBe PredicateVerdict.NON_APPLICABLE
        }
    })
