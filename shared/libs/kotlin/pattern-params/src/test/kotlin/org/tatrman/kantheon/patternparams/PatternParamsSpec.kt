package org.tatrman.kantheon.patternparams

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Faithful port of ai-platform's `test_binder.py` (the `aip_pattern_params` suite).
 * Covers `typeTag`, `coerceValue`, `normaliseArgKeys`, and `buildPatternParameters` —
 * the Golem S2.4 parametrization rail.
 */
class PatternParamsSpec :
    StringSpec({

        // ---- typeTag ------------------------------------------------------------
        "typeTag maps varchar variants to text" {
            PatternParams.typeTag("varchar") shouldBe "text"
            PatternParams.typeTag("char") shouldBe "text"
            PatternParams.typeTag("text") shouldBe "text"
            PatternParams.typeTag("VARCHAR") shouldBe "text"
        }
        "typeTag maps int variants" {
            PatternParams.typeTag("int") shouldBe "int"
            PatternParams.typeTag("integer") shouldBe "int"
            PatternParams.typeTag("bigint") shouldBe "int"
        }
        "typeTag maps float variants" {
            PatternParams.typeTag("decimal") shouldBe "float"
            PatternParams.typeTag("numeric") shouldBe "float"
            PatternParams.typeTag("float") shouldBe "float"
            PatternParams.typeTag("double") shouldBe "float"
        }
        "typeTag maps bool variants" {
            PatternParams.typeTag("bool") shouldBe "bool"
            PatternParams.typeTag("boolean") shouldBe "bool"
        }
        "typeTag maps datetime variants" {
            PatternParams.typeTag("date") shouldBe "datetime"
            PatternParams.typeTag("datetime") shouldBe "datetime"
            PatternParams.typeTag("timestamp") shouldBe "datetime"
        }
        "typeTag defaults unknown to text" {
            PatternParams.typeTag("") shouldBe "text"
            PatternParams.typeTag("xml") shouldBe "text"
            PatternParams.typeTag("uuid") shouldBe "text"
        }

        // ---- coerceValue --------------------------------------------------------
        "coerceValue keeps a numeric-looking varchar a string (account code)" {
            PatternParams.coerceValue("518032", "varchar") shouldBe "518032"
            PatternParams.coerceValue("518032", "varchar").shouldBeInstanceOf<String>()
        }
        "coerceValue coerces int" {
            PatternParams.coerceValue("12345", "int") shouldBe 12345L
            PatternParams.coerceValue("12345", "int").shouldBeInstanceOf<Long>()
        }
        "coerceValue keeps a period-bearing varchar a string" {
            PatternParams.coerceValue("2026.04", "varchar") shouldBe "2026.04"
            PatternParams.coerceValue("2026.04", "varchar").shouldBeInstanceOf<String>()
        }
        "coerceValue floats a decimal" {
            val r = PatternParams.coerceValue("3.14", "decimal")
            r.shouldBeInstanceOf<Double>()
            r shouldBe 3.14
        }
        "coerceValue bool true" {
            PatternParams.coerceValue("true", "boolean") shouldBe true
            PatternParams.coerceValue(true, "bool") shouldBe true
        }
        "coerceValue bool false" {
            PatternParams.coerceValue("false", "boolean") shouldBe false
        }
        "coerceValue null stays null (a NULL bind, never the literal 'null')" {
            PatternParams.coerceValue(null, "varchar") shouldBe null
            PatternParams.coerceValue(null, "int") shouldBe null
            PatternParams.coerceValue(null, "datetime") shouldBe null
        }
        "buildPatternParameters carries a null value through as a NULL bind" {
            val params = listOf(ParamSpec(name = "stred", type = "varchar", label = "Středisko"))
            val r = PatternParams.buildPatternParameters(mapOf("stred" to null), params)
            r.parameters shouldBe mapOf("stred" to TypedParam(null, "varchar"))
            r.missingRequired shouldBe emptyList()
        }

        // ---- normaliseArgKeys ---------------------------------------------------
        val params =
            listOf(
                ParamSpec(name = "nazev_strediska", type = "varchar", label = "Název nebo kód střediska"),
                ParamSpec(name = "obdobi", type = "varchar", label = "Účetní období (RRRR.MM)"),
            )

        "normalise exact" {
            val (out, unmapped) = PatternParams.normaliseArgKeys(mapOf("nazev_strediska" to "x"), params)
            out shouldBe mapOf("nazev_strediska" to "x")
            unmapped shouldBe emptyList()
        }
        "normalise case-insensitive" {
            val (out, unmapped) = PatternParams.normaliseArgKeys(mapOf("NAZEV_STREDISKA" to "x"), params)
            out shouldBe mapOf("nazev_strediska" to "x")
            unmapped shouldBe emptyList()
        }
        "normalise fuzzy alias — LLM 'stredisko' → declared 'nazev_strediska' (the reported bug)" {
            val (out, unmapped) = PatternParams.normaliseArgKeys(mapOf("stredisko" to "DF ADNAK"), params)
            out shouldBe mapOf("nazev_strediska" to "DF ADNAK")
            unmapped shouldBe emptyList()
        }
        "normalise drops a truly-unmapped key" {
            val (out, unmapped) = PatternParams.normaliseArgKeys(mapOf("foo" to "y"), params)
            out.containsKey("foo") shouldBe false
            (("foo") in unmapped) shouldBe true
        }

        // ---- buildPatternParameters ---------------------------------------------
        "build full args" {
            val r =
                PatternParams.buildPatternParameters(
                    mapOf("nazev_strediska" to "DF ADNAK", "obdobi" to "2026.04"),
                    params,
                )
            r.shouldBeInstanceOf<ParamBindResult>()
            r.parameters shouldBe
                mapOf(
                    "nazev_strediska" to TypedParam("DF ADNAK", "varchar"),
                    "obdobi" to TypedParam("2026.04", "varchar"),
                )
            r.missingRequired shouldBe emptyList()
        }
        "build flags a missing required param" {
            val r = PatternParams.buildPatternParameters(mapOf("nazev_strediska" to "DF ADNAK"), params)
            r.missingRequired shouldBe listOf("obdobi")
            r.parameters.containsKey("nazev_strediska") shouldBe true
        }
        "build leaves an absent optional param out of missingRequired" {
            val optParams =
                listOf(
                    ParamSpec(name = "nazev_strediska", type = "varchar", label = "Středisko"),
                    ParamSpec(name = "limit", type = "int", label = "Limit", optional = true),
                )
            val r = PatternParams.buildPatternParameters(mapOf("nazev_strediska" to "x"), optParams)
            r.missingRequired shouldBe emptyList()
            r.parameters.containsKey("limit") shouldBe false
        }

        // ---- sequenceRatio (difflib parity sanity) ------------------------------
        "sequenceRatio reproduces the difflib ratio that drives the ≥0.8 fuzzy threshold" {
            // 'stredisko' vs 'strediska' shares the 8-char block 'stredisk'; 2*8/18 = 0.888…
            PatternParams.sequenceRatio("stredisko", "strediska") shouldBeGreaterThanOrEqual 0.8
            PatternParams.sequenceRatio("identical", "identical") shouldBe 1.0
            PatternParams.sequenceRatio("", "") shouldBe 1.0
        }
    })
