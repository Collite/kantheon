package shared.translator.framework

import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull

class ModelHandleSpec :
    StringSpec({
        val customerQname =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.ER)
                .setNamespace("entity")
                .setName("customer")
                .build()

        val qsubjektQname =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.DB)
                .setNamespace("dbo")
                .setName("QSUBJEKT")
                .build()

        val customerEntity =
            ModelEntity(
                qname = customerQname,
                attributes =
                    listOf(
                        ModelAttribute("id", SurfaceType.INT, isKey = true, nullable = false),
                        ModelAttribute("name", SurfaceType.TEXT, nullable = true),
                    ),
            )

        val mapping = EntityMapping.ToTable(table = qsubjektQname, whereFilter = null)

        val handle =
            InMemoryModelHandle(
                tables = emptyList(),
                entities = listOf(customerEntity),
                entityMappings = mapOf(customerQname to mapping),
            )

        "entities() returns entities for the given schema and namespace" {
            val entities = handle.entities(SchemaCode.ER, "entity")
            entities.size shouldBe 1
            entities[customerQname] shouldBe customerEntity
        }

        "attributes() returns the attribute list for an entity" {
            val attrs = handle.attributes(customerQname)
            attrs.size shouldBe 2
            attrs.map { it.name } shouldContainExactly listOf("id", "name")
        }

        "entityMapping() returns mapping when present" {
            handle.entityMapping(customerQname) shouldBe mapping
        }

        "entityMapping() returns null when not present" {
            val otherQname =
                QualifiedName
                    .newBuilder()
                    .setSchemaCode(
                        SchemaCode.ER,
                    ).setNamespace("entity")
                    .setName("other")
                    .build()
            handle.entityMapping(otherQname).shouldBeNull()
        }

        "relations() returns relations" {
            handle.relations().size shouldBe 0
        }

        "savedQueries() returns queries" {
            handle.savedQueries(SchemaCode.ER, "entity").size shouldBe 0
        }

        "attributes() returns empty list for unknown entity" {
            val unknownQname =
                QualifiedName
                    .newBuilder()
                    .setSchemaCode(
                        SchemaCode.ER,
                    ).setNamespace("entity")
                    .setName("unknown")
                    .build()
            handle.attributes(unknownQname) shouldBe emptyList()
        }

        "savedQueryBody() returns the body when query is present" {
            val savedQueryQname =
                QualifiedName
                    .newBuilder()
                    .setSchemaCode(
                        SchemaCode.OBJ,
                    ).setNamespace("query")
                    .setName("my_query")
                    .build()
            val body = PlanNode.getDefaultInstance()
            val savedHandle =
                InMemoryModelHandle(
                    tables = emptyList(),
                    savedQueryBodies =
                        mapOf(
                            savedQueryQname to
                                SavedQueryBody(body, listOf(ParamSpec("p1", SurfaceType.TEXT)), emptyList()),
                        ),
                )
            val result = savedHandle.savedQueryBody(savedQueryQname)
            result.planNode shouldBe body
            result.parameters shouldBe listOf(ParamSpec("p1", SurfaceType.TEXT))
        }
    })
