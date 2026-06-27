package shared.translator.suggest

import org.tatrman.plan.v1.SchemaCode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import shared.translator.framework.FixtureModel

class ModelIdentifierCorpusSpec :
    StringSpec({
        val handle = FixtureModel.handleWithEntities()

        "collect(handle, DB, dbo) contains table and column names, excludes ER entity names" {
            val corpus = ModelIdentifierCorpus.collect(handle, SchemaCode.DB, "dbo")
            corpus shouldContain "customers"
            corpus shouldContain "orders"
            corpus shouldContain "id"
            corpus shouldContain "name"
            corpus shouldContain "signup"
            corpus shouldContain "customer_id"
            corpus shouldContain "total"
            corpus shouldNotContain "customer"
        }

        "collect(handle, ER, entity) contains entity and attribute names, excludes DB table names" {
            val corpus = ModelIdentifierCorpus.collect(handle, SchemaCode.ER, "entity")
            corpus shouldContain "customer"
            corpus shouldContain "id"
            corpus shouldContain "name"
            corpus shouldNotContain "customers"
            corpus shouldNotContain "orders"
        }

        "locate(handle, customers) returns DB schema" {
            val schemas = ModelIdentifierCorpus.locate(handle, "customers")
            schemas shouldBe setOf(SchemaCode.DB)
        }

        "locate(handle, customer) returns ER schema" {
            val schemas = ModelIdentifierCorpus.locate(handle, "customer")
            schemas shouldBe setOf(SchemaCode.ER)
        }

        "locate(handle, id) returns both DB and ER (id exists in both)" {
            val schemas = ModelIdentifierCorpus.locate(handle, "id")
            schemas shouldBe setOf(SchemaCode.DB, SchemaCode.ER)
        }

        "collect(handle) model-wide corpus contains both DB and ER identifiers" {
            val corpus = ModelIdentifierCorpus.collect(handle)
            corpus shouldContain "customers"
            corpus shouldContain "customer"
            corpus shouldContain "id"
        }
    })
