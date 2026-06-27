package shared.translator.framework

import org.tatrman.plan.v1.SchemaCode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.apache.calcite.tools.Planner

class TranslatorFrameworkSpec :
    StringSpec({

        "newPlanner returns a fresh Planner each call" {
            val fw = TranslatorFramework(FixtureModel.handle())
            val a: Planner = fw.newPlanner()
            val b: Planner = fw.newPlanner()
            (a === b) shouldBe false
        }

        "Calcite enforces single-use Planner: re-parsing throws" {
            val fw = TranslatorFramework(FixtureModel.handle())
            val planner = fw.newPlanner()
            planner.parse("SELECT id FROM customers")
            shouldThrow<Throwable> {
                planner.parse("SELECT name FROM customers")
            }
        }

        "schemaPlusAdapter.db has dbo sub-schema in three-level tree" {
            val fw = TranslatorFramework(FixtureModel.handle())
            fw.schemaPlusAdapter.db
                .getSubSchemaMap()
                .keys
                .shouldContain("dbo")
        }

        "default schema/namespace land on db.dbo" {
            val fw = TranslatorFramework(FixtureModel.handle())
            fw.schemaCode shouldBe SchemaCode.DB
            fw.namespace shouldBe "dbo"
        }

        "rootSchema registers the adapter under schemaCode" {
            val fw = TranslatorFramework(FixtureModel.handle())
            fw.rootSchema.subSchemas().get("db") shouldNotBe null
        }
    })
