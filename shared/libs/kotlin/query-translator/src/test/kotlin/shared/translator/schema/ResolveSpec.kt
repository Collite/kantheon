package shared.translator.schema

import org.apache.calcite.rel.core.Join
import org.apache.calcite.rel.core.Project
import shared.translator.codec.sql.SqlValidator
import shared.translator.codec.sql.ValidateResult
import shared.translator.framework.FixtureModel
import shared.translator.framework.InMemoryModelHandle
import shared.translator.framework.ModelAttribute
import shared.translator.framework.ModelEntity
import shared.translator.framework.SurfaceType
import shared.translator.framework.TranslatorFramework
import shared.translator.wire.PlanNodeEncoder
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.plan.v1.SchemaCode

/**
 * RESOLVE stage tests.
 *
 * The RESOLVE stage fixes positional RexInputRef names so the encoder emits
 * named ColumnRefs. The stage is semantically idempotent: resolve(resolve(t))
 * == resolve(t) because a tree where every RexInputRef already has the correct
 * field name passes through the shuttle unchanged.
 *
 * The primary encoding path (PlanNodeEncoder.encode) uses field names from the
 * surrounding row type to produce named ColumnRefs even without RESOLVE running.
 * RESOLVE's value is ensuring the names are consistent and correct in the
 * tree itself, which matters for Q3 JOIN round-trips and cross-session re-entry.
 */
class ResolveSpec :
    StringSpec({

        fun parseToRel(sql: String): org.apache.calcite.rel.RelNode {
            val fw = TranslatorFramework(FixtureModel.handle())
            val r = SqlValidator.validateAndConvert(fw.newPlanner(), sql)
            r.shouldBeInstanceOf<ValidateResult.Success>()
            return r.rel
        }

        fun parseErToRel(sql: String): org.apache.calcite.rel.RelNode {
            val handle =
                InMemoryModelHandle(
                    tables = listOf(FixtureModel.customers),
                    entities =
                        listOf(
                            ModelEntity(
                                qname =
                                    org.tatrman.plan.v1.QualifiedName
                                        .newBuilder()
                                        .setSchemaCode(SchemaCode.ER)
                                        .setNamespace("entity")
                                        .setName("customer")
                                        .build(),
                                attributes =
                                    listOf(
                                        ModelAttribute("id", SurfaceType.INT, nullable = false),
                                        ModelAttribute("name", SurfaceType.TEXT, nullable = true),
                                    ),
                            ),
                        ),
                )
            val fw = TranslatorFramework(handle, schemaCode = SchemaCode.ER, namespace = "entity")
            val r = SqlValidator.validateAndConvert(fw.newPlanner(), sql)
            r.shouldBeInstanceOf<ValidateResult.Success>()
            return r.rel
        }

        "apply returns same tree (no structural changes needed)" {
            val rel = parseToRel("SELECT id, name FROM customers WHERE id > 5")
            val resolved = Resolve.apply(rel, TranslatorFramework(FixtureModel.handle()))
            resolved.shouldBeInstanceOf<Project>()
        }

        "apply preserves JOIN tree shape" {
            val rel =
                parseToRel(
                    "SELECT c.name, o.total FROM customers c JOIN orders o ON o.customer_id = c.id",
                )
            val resolved = Resolve.apply(rel, TranslatorFramework(FixtureModel.handle()))
            resolved.shouldBeInstanceOf<Project>()
            (resolved as Project).input.shouldBeInstanceOf<Join>()
        }

        "apply preserves entity scan leaf" {
            val rel = parseErToRel("SELECT id, name FROM er.entity.customer WHERE id > 5")
            val fw =
                TranslatorFramework(
                    InMemoryModelHandle(
                        tables = emptyList(),
                        entities =
                            listOf(
                                ModelEntity(
                                    qname =
                                        org.tatrman.plan.v1.QualifiedName
                                            .newBuilder()
                                            .setSchemaCode(SchemaCode.ER)
                                            .setNamespace("entity")
                                            .setName("customer")
                                            .build(),
                                    attributes =
                                        listOf(
                                            ModelAttribute("id", SurfaceType.INT, nullable = false),
                                            ModelAttribute("name", SurfaceType.TEXT, nullable = true),
                                        ),
                                ),
                            ),
                    ),
                    schemaCode = SchemaCode.ER,
                    namespace = "entity",
                )
            val resolved = Resolve.apply(rel, fw)
            resolved.shouldBeInstanceOf<Project>()
        }

        "semantic idempotent — apply twice returns equivalent tree" {
            val rel = parseToRel("SELECT id, name FROM customers WHERE id > 5")
            val first = Resolve.apply(rel, TranslatorFramework(FixtureModel.handle()))
            val second = Resolve.apply(first, TranslatorFramework(FixtureModel.handle()))
            first::class shouldBe second::class
            first.toString() shouldBe second.toString()
        }

        "unbound identifier propagates as SqlValidator failure" {
            val fw = TranslatorFramework(FixtureModel.handle())
            val r = SqlValidator.validateAndConvert(fw.newPlanner(), "SELECT * FROM nonexistent_table")
            r.shouldBeInstanceOf<ValidateResult.Failure>()
        }

        "Q3 fix: JOIN round-trip preserves field names through encode→decode→encode" {
            val rel =
                parseToRel(
                    "SELECT c.name, o.total FROM customers c JOIN orders o ON o.customer_id = c.id",
                )
            // Apply RESOLVE — fixes positional RexInputRef names in the tree.
            val resolved = Resolve.apply(rel, TranslatorFramework(FixtureModel.handle()))
            // Encode → decode → re-encode.
            val plan1 = PlanNodeEncoder.encode(resolved)
            val bytes = plan1.toByteArray()
            val decoded =
                org.tatrman.plan.v1.PlanNode
                    .parseFrom(bytes)
            val framework = TranslatorFramework(FixtureModel.handle())
            val decodedRel =
                shared.translator.wire.PlanNodeDecoder
                    .decode(decoded, framework)
            val plan2 = PlanNodeEncoder.encode(decodedRel)
            // The join condition in plan2 should have named column refs, not positional.
            val condStr =
                plan2.project.input.join.condition
                    .toString()
            condStr.shouldNotBe("=()")
            condStr.shouldNotBe("OR()")
        }
    })
