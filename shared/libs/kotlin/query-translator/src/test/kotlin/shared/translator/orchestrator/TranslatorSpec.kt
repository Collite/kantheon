package shared.translator.orchestrator

import org.tatrman.proteus.v1.Language
import org.tatrman.proteus.v1.SqlDialect as SqlDialectProto
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContainIgnoringCase
import io.kotest.matchers.string.shouldNotContainIgnoringCase
import io.kotest.matchers.types.shouldBeInstanceOf
import shared.translator.framework.FixtureModel
import shared.translator.params.SqlParam

class TranslatorSpec :
    StringSpec({

        val translator = Translator(FixtureModel.handle())

        "translate SQL to SQL via the orchestrator round-trips" {
            val r =
                translator.translate(
                    source = "SELECT id, name FROM customers",
                    sourceLanguage = Language.SQL,
                    targetLanguage = Language.SQL,
                    targetDialect = SqlDialectProto.MSSQL,
                )
            r.shouldBeInstanceOf<TranslateResult.Success>()
            r.output.shouldContainIgnoringCase("select")
            r.output.shouldContainIgnoringCase("customers")
        }

        "parseToRelNode produces a PlanNode for valid SQL" {
            val r = translator.parseToRelNode("SELECT id FROM customers", Language.SQL)
            r.shouldBeInstanceOf<ParseResult.Success>()
            // PlanNode's top is a Project (over a TableScan).
            r.plan.hasProject() shouldBe true
        }

        "parseToRelNode surfaces validation_failed on unknown column" {
            val r = translator.parseToRelNode("SELECT does_not_exist FROM customers", Language.SQL)
            r.shouldBeInstanceOf<ParseResult.Failure>()
            r.code shouldBe "validation_failed"
        }

        "unparseFromRelNode emits SQL for a PlanNode" {
            val parsed =
                translator.parseToRelNode("SELECT id FROM customers", Language.SQL)
                    as ParseResult.Success
            val r =
                translator.unparseFromRelNode(
                    parsed.plan,
                    Language.SQL,
                    SqlDialectProto.MSSQL,
                )
            r.shouldBeInstanceOf<UnparseResult.Success>()
            r.output.shouldContainIgnoringCase("customers")
        }

        "unparseFromRelNode emits TransDSL JSON for TRANSFORMATION_DSL target" {
            val parsed =
                translator.parseToRelNode("SELECT id FROM customers", Language.SQL)
                    as ParseResult.Success
            val r =
                translator.unparseFromRelNode(
                    parsed.plan,
                    Language.TRANSFORMATION_DSL,
                    SqlDialectProto.MSSQL,
                )
            r.shouldBeInstanceOf<UnparseResult.Success>()
            r.output.shouldContainIgnoringCase("customers")
        }

        "explain captures stage artefacts" {
            val r = translator.explain("SELECT id FROM customers", Language.SQL)
            r.stages.size shouldBe 2
            r.finalOutput!!.shouldContainIgnoringCase("customers")
        }

        "translate from TransDSL JSON to SQL works end-to-end" {
            // The fixture model registers `customers` flat under schema "db"
            // (no namespace subschema), so the QualifiedName the parser
            // emits must use an empty `namespace`. The PlanNode wire codec
            // encodes Calcite TableScans the same way (see
            // PlanNodeEncoder.encodeTableScan).
            val transDslJson =
                """
                {
                  "core": [{"dataObject": {"schemaCode": "db", "name": "customers"}}],
                  "columns": [{"name": "id"}]
                }
                """.trimIndent()
            val r =
                translator.translate(
                    source = transDslJson,
                    sourceLanguage = Language.TRANSFORMATION_DSL,
                    targetLanguage = Language.SQL,
                    targetDialect = SqlDialectProto.MSSQL,
                )
            r.shouldBeInstanceOf<TranslateResult.Success>()
            r.output.shouldContainIgnoringCase("customers")
        }

        // ---- Parameter rail (ParameterBridge / ParameterTyper wiring) -------------------
        // Regression guard for the fork-dropped wiring (master-plan §7, Golem S2.4 T7):
        // pattern queries send SQL with `{name}` placeholders + typed `parameters`; the
        // orchestrator must rewrite `{name}` → Calcite `?` before parsing. Before this wiring
        // the `{name}` token reached Calcite and produced an opaque "Incorrect syntax near …"
        // parse error (the reported golem/translator failure).

        "parseToRelNode rewrites {name} placeholders to ? when typed parameters are supplied" {
            val r =
                translator.parseToRelNode(
                    source = "SELECT id FROM customers WHERE name LIKE {q}",
                    sourceLanguage = Language.SQL,
                    parameters = listOf(SqlParam(name = "q", type = "text", value = "DF%")),
                )
            r.shouldBeInstanceOf<ParseResult.Success>()
        }

        "parseToRelNode validates a placeholder as the first || operand via the typed-CAST fallback" {
            // A bare `?` is untypeable as an operand of `||`/CONCAT (its operand-type inference is
            // null, unlike LIKE/BETWEEN). Bare validation fails; the orchestrator retries with
            // CAST(? AS …). This is the prefix-LIKE form `LIKE {q} || '%'` — param FIRST.
            val r =
                translator.parseToRelNode(
                    source = "SELECT id FROM customers WHERE name LIKE {q} || '%'",
                    sourceLanguage = Language.SQL,
                    parameters = listOf(SqlParam(name = "q", type = "text", value = "DF")),
                )
            r.shouldBeInstanceOf<ParseResult.Success>()
        }

        "validates a many-param query with mixed first/middle || operands" {
            // A prefix-LIKE (param FIRST: `{a} || '%'`) OR an infix-LIKE (param MIDDLE:
            // `'%' || {a} || '%'`), AND a second infix-LIKE on another param — every `?` an
            // untypeable `||` operand. All must validate via the typed fallback.
            val r =
                translator.parseToRelNode(
                    source =
                        "SELECT id FROM customers " +
                            "WHERE (name LIKE {a} || '%' OR name LIKE '%' || {a} || '%') " +
                            "AND name LIKE '%' || {b} || '%'",
                    sourceLanguage = Language.SQL,
                    parameters =
                        listOf(
                            SqlParam(name = "a", type = "text", value = "DF ADNAK"),
                            SqlParam(name = "b", type = "text", value = "2026.04"),
                        ),
                )
            r.shouldBeInstanceOf<ParseResult.Success>()
        }

        "a `||` placeholder query unparses without a synthetic CAST (crutch is unwrapped)" {
            // Infix-LIKE form `LIKE '%' || {q} || '%'` — param in a middle operand.
            val parsed =
                translator.parseToRelNode(
                    source = "SELECT id FROM customers WHERE name LIKE '%' || {q} || '%'",
                    sourceLanguage = Language.SQL,
                    parameters = listOf(SqlParam(name = "q", type = "text", value = "DF")),
                ) as ParseResult.Success
            // The plan carries one `?`; supply its binding (the realistic worker path) so the
            // positional expansion runs — an unparse with placeholders but no bindings is a contract
            // violation guarded by PositionalParameters (H1).
            val qBinding =
                org.tatrman.plan.v1.ParameterBinding
                    .newBuilder()
                    .setName("q")
                    .setType("text")
                    .setValue(
                        org.tatrman.plan.v1.Value
                            .newBuilder()
                            .setStringValue("DF"),
                    ).build()
            val r =
                translator.unparseFromRelNode(
                    parsed.plan,
                    Language.SQL,
                    SqlDialectProto.MSSQL,
                    parameters = listOf(qBinding),
                )
            r.shouldBeInstanceOf<UnparseResult.Success>()
            // The validation-only CAST(? AS VARCHAR) must not leak into the executed SQL — otherwise
            // a short default VARCHAR length would truncate the search term on some dialects.
            r.output.shouldNotContainIgnoringCase("cast")
        }

        "named {name} SQL round-trips to ordered ? + a positional parametersList" {
            // The T7 DONE shape: `{name}` SQL + typed parameters → unparsed SQL whose `?` are
            // bound 1:1 by a positional ParameterBinding list (the form Brontes JDBC-binds).
            // A name used twice yields two `?` and two positional entries from one named binding.
            val binding =
                org.tatrman.plan.v1.ParameterBinding
                    .newBuilder()
                    .setName("q")
                    .setType("text")
                    .setValue(
                        org.tatrman.plan.v1.Value
                            .newBuilder()
                            .setStringValue("DF")
                            .build(),
                    ).build()
            val parsed =
                translator.parseToRelNode(
                    source = "SELECT id FROM customers WHERE name = {q} OR name = {q}",
                    sourceLanguage = Language.SQL,
                    parameters = listOf(SqlParam(name = "q", type = "text", value = "DF")),
                ) as ParseResult.Success
            val r =
                translator.unparseFromRelNode(
                    parsed.plan,
                    Language.SQL,
                    SqlDialectProto.MSSQL,
                    parameters = listOf(binding),
                )
            r.shouldBeInstanceOf<UnparseResult.Success>()
            r.output.shouldContainIgnoringCase("?")
            // Two `?` positions, both bound to the single named value.
            r.parameters.size shouldBe 2
            r.parameters.all { it.name == "q" && it.value.stringValue == "DF" } shouldBe true
        }

        "parseToRelNode dedups a repeated {name} to one binding identity" {
            val r =
                translator.parseToRelNode(
                    source = "SELECT id FROM customers WHERE name = {q} OR name = {q}",
                    sourceLanguage = Language.SQL,
                    parameters = listOf(SqlParam(name = "q", type = "text", value = "x")),
                )
            r.shouldBeInstanceOf<ParseResult.Success>()
        }

        "parseToRelNode fails with parameter_unknown when a {name} has no binding" {
            val r =
                translator.parseToRelNode(
                    source = "SELECT id FROM customers WHERE name = {missing}",
                    sourceLanguage = Language.SQL,
                    parameters = listOf(SqlParam(name = "q", type = "text", value = "x")),
                )
            r.shouldBeInstanceOf<ParseResult.Failure>()
            r.code shouldBe "parameter_unknown"
        }

        "parseToRelNode leaves {…} verbatim when no parameters are supplied (free-SQL)" {
            // No bindings → the bridge is bypassed so JDBC escapes / literal `{` survive; here a
            // bare `{q}` is therefore handed to Calcite as-is and rejected (i.e. we did NOT
            // silently rewrite it).
            val r =
                translator.parseToRelNode(
                    source = "SELECT id FROM customers WHERE name LIKE {q}",
                    sourceLanguage = Language.SQL,
                )
            r.shouldBeInstanceOf<ParseResult.Failure>()
        }
    })
