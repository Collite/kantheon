package shared.translator.codec.transdsl

import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.transdsl.v1.Column
import org.tatrman.transdsl.v1.Query
import org.tatrman.transdsl.v1.Source
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

class TransDslQueryRefSpec :
    StringSpec({

        fun dbQn(name: String) =
            QualifiedName
                .newBuilder()
                .setSchemaCode(org.tatrman.plan.v1.SchemaCode.DB)
                .setNamespace("dbo")
                .setName(name)
                .build()

        // A stand-in "stored query canonical form" — just a TableScan on db.dbo.customers.
        val resolvedPlan: PlanNode =
            PlanNode.newBuilder().setTableScan(TableScanNode.newBuilder().setTable(dbQn("customers"))).build()

        "query_ref with no resolution map → placeholder SubqueryNode (legacy behaviour)" {
            val q =
                Query
                    .newBuilder()
                    .addCore(Source.newBuilder().setQueryRef("obj.q.findCustomers").setAlias("fc"))
                    .build()
            val plan = TransDslCodec.parse(q)
            plan.hasSubquery() shouldBe true
            plan.subquery.alias shouldBe "fc"
            plan.subquery.hasSubquery() shouldBe false // empty placeholder
        }

        "query_ref resolved from the map → SubqueryNode wrapping the stored canonical form" {
            val q =
                Query
                    .newBuilder()
                    .addCore(Source.newBuilder().setQueryRef("obj.q.findCustomers").setAlias("fc"))
                    .build()
            val plan = TransDslCodec.parse(q, queryRefs = mapOf("obj.q.findCustomers" to resolvedPlan))
            plan.hasSubquery() shouldBe true
            plan.subquery.alias shouldBe "fc"
            plan.subquery.subquery shouldBe resolvedPlan
        }

        "query_ref alias defaults to the ref string when no explicit alias is given" {
            val q =
                Query.newBuilder().addCore(Source.newBuilder().setQueryRef("obj.q.orders")).build()
            val plan = TransDslCodec.parse(q, queryRefs = mapOf("obj.q.orders" to resolvedPlan))
            plan.subquery.alias shouldBe "obj.q.orders"
        }

        "query_ref not present in the resolution map → query_ref_unresolved parse error" {
            val q = Query.newBuilder().addCore(Source.newBuilder().setQueryRef("obj.q.missing")).build()
            val ex = shouldThrow<TransDslParseException> { TransDslCodec.parse(q, queryRefs = emptyMap()) }
            ex.code shouldBe "query_ref_unresolved"
        }

        "query_ref inside a nested inline query is resolved too" {
            val nested =
                Query
                    .newBuilder()
                    .addCore(Source.newBuilder().setQueryRef("obj.q.inner"))
                    .build()
            val q =
                Query
                    .newBuilder()
                    .addCore(Source.newBuilder().setQuery(nested).setAlias("n"))
                    .addColumns(
                        Column
                            .newBuilder()
                            .setName("id")
                            .setSource("n")
                            .setAlias("id"),
                    ).build()
            val plan = TransDslCodec.parse(q, queryRefs = mapOf("obj.q.inner" to resolvedPlan))
            // outer: Project → Subquery(alias n) → Subquery(query_ref obj.q.inner → resolvedPlan)
            plan.hasProject() shouldBe true
            val outerSub = plan.project.input
            outerSub.hasSubquery() shouldBe true
            outerSub.subquery.subquery.subquery.subquery shouldBe resolvedPlan
        }

        "queryRefsIn collects refs from the core and from nested inline queries" {
            val nested = Query.newBuilder().addCore(Source.newBuilder().setQueryRef("obj.q.b")).build()
            val q =
                Query
                    .newBuilder()
                    .addCore(Source.newBuilder().setQueryRef("obj.q.a"))
                    .addCore(Source.newBuilder().setQuery(nested))
                    .addCore(Source.newBuilder().setDataObject(dbQn("customers")))
                    .build()
            TransDslCodec.queryRefsIn(q) shouldContainExactlyInAnyOrder listOf("obj.q.a", "obj.q.b")
        }
    })
