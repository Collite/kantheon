package org.tatrman.kantheon.report.template

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.kantheon.report.v1.ParamDef
import org.tatrman.kantheon.report.v1.ParamKind

/** Stage 3.4 T2 — args_json validation against ParamDefs: required presence, defaults, kind shape. */
class ParamValidatorSpec :
    StringSpec({

        fun param(
            name: String,
            kind: ParamKind,
            required: Boolean,
            default: String = "",
        ) = ParamDef
            .newBuilder()
            .setName(name)
            .setKind(kind)
            .setRequired(required)
            .setDefaultValue(default)
            .build()

        val params =
            listOf(
                param("portfolio_id", ParamKind.PARAM_PORTFOLIO_ID, required = true),
                param("as_of", ParamKind.PARAM_DATE, required = false),
                param("period", ParamKind.PARAM_PERIOD, required = false, default = "ytd"),
            )

        "valid args pass and the declared default is applied for an absent optional" {
            val r = ParamValidator.validate("""{"portfolio_id":"p1","as_of":"2026-06-27"}""", params)
            r.shouldBeInstanceOf<ValidationResult.Ok>()
            r.args["portfolio_id"] shouldBe "p1"
            r.args["period"] shouldBe "ytd" // default applied
        }

        "a missing required parameter is rejected" {
            val r = ParamValidator.validate("""{"as_of":"2026-06-27"}""", params)
            r.shouldBeInstanceOf<ValidationResult.Invalid>()
            r.errors.any { it.contains("portfolio_id") } shouldBe true
        }

        "a malformed date is rejected" {
            val r = ParamValidator.validate("""{"portfolio_id":"p1","as_of":"not-a-date"}""", params)
            r.shouldBeInstanceOf<ValidationResult.Invalid>()
        }

        "an out-of-set period is rejected" {
            val r = ParamValidator.validate("""{"portfolio_id":"p1","period":"weekly"}""", params)
            r.shouldBeInstanceOf<ValidationResult.Invalid>()
        }
    })
