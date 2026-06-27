package shared.translator.schema

import org.tatrman.plan.v1.SchemaCode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.apache.calcite.jdbc.JavaTypeFactoryImpl
import org.apache.calcite.sql.type.SqlTypeName
import shared.translator.framework.FixtureModel
import shared.translator.framework.InMemoryModelHandle
import shared.translator.framework.ModelAttribute
import shared.translator.framework.ModelEntity
import shared.translator.framework.ModelColumn
import shared.translator.framework.PhysicalType
import shared.translator.framework.SurfaceType

class ModelSchemaAdapterSpec :
    StringSpec({

        val factory = JavaTypeFactoryImpl()

        "DbSchemaAdapter: sub-schema map contains dbo namespace" {
            val adapter = DbSchemaAdapter(FixtureModel.handle())
            adapter.getSubSchemaMap().keys.shouldContainAll(listOf("dbo"))
        }

        "TableNamespaceSchema: lists fixture tables under db.dbo" {
            val nsAdapter = TableNamespaceSchema(FixtureModel.handle(), "dbo")
            nsAdapter.getTableMap().keys.shouldContainAll(listOf("customers", "orders"))
        }

        "TableNamespaceSchema: row type derivation: surface types map cleanly" {
            val nsAdapter = TableNamespaceSchema(FixtureModel.handle(), "dbo")
            val customers = nsAdapter.getTableMap()["customers"]!!
            val rowType = customers.getRowType(factory)

            rowType.fieldList.map { it.name } shouldBe listOf("id", "name", "signup")
            rowType.getField("id", false, false)!!.type.sqlTypeName shouldBe SqlTypeName.BIGINT
            rowType.getField("name", false, false)!!.type.sqlTypeName shouldBe SqlTypeName.VARCHAR
            rowType.getField("signup", false, false)!!.type.sqlTypeName shouldBe SqlTypeName.TIMESTAMP
        }

        "TableNamespaceSchema: row type derivation: physical type with precision/scale on orders.total" {
            val nsAdapter = TableNamespaceSchema(FixtureModel.handle(), "dbo")
            val orders = nsAdapter.getTableMap()["orders"]!!
            val rowType = orders.getRowType(factory)

            val total = rowType.getField("total", false, false)!!.type
            total.sqlTypeName shouldBe SqlTypeName.DECIMAL
            total.precision shouldBe 19
            total.scale shouldBe 5
        }

        "TableNamespaceSchema: nullable flag reaches Calcite" {
            val nsAdapter = TableNamespaceSchema(FixtureModel.handle(), "dbo")
            val customers = nsAdapter.getTableMap()["customers"]!!
            val rowType = customers.getRowType(factory)
            rowType.getField("id", false, false)!!.type.isNullable shouldBe false
            rowType.getField("name", false, false)!!.type.isNullable shouldBe true
        }

        "TypeMapping: covers every SurfaceType variant" {
            for (surface in SurfaceType.entries) {
                val type = TypeMapping.fromSurface(surface, factory)
                type.sqlTypeName shouldBe
                    when (surface) {
                        SurfaceType.TEXT -> SqlTypeName.VARCHAR
                        SurfaceType.INT -> SqlTypeName.BIGINT
                        SurfaceType.FLOAT -> SqlTypeName.DOUBLE
                        SurfaceType.BOOL -> SqlTypeName.BOOLEAN
                        SurfaceType.DATETIME -> SqlTypeName.TIMESTAMP
                    }
            }
        }

        "TypeMapping: physical type without precision uses default sql type" {
            val col =
                ModelColumn(
                    "flag",
                    SurfaceType.BOOL,
                    physicalType = PhysicalType(PhysicalType.Kind.BOOLEAN),
                )
            TypeMapping.toRelDataType(col, factory).sqlTypeName shouldBe SqlTypeName.BOOLEAN
        }

        "ErSchemaAdapter: sub-schema map contains entity namespace" {
            val customerQname =
                org.tatrman.plan.v1.QualifiedName
                    .newBuilder()
                    .setSchemaCode(SchemaCode.ER)
                    .setNamespace("entity")
                    .setName("customer")
                    .build()
            val customerEntity =
                ModelEntity(
                    qname = customerQname,
                    attributes =
                        listOf(
                            ModelAttribute("id", SurfaceType.INT, nullable = false),
                            ModelAttribute("name", SurfaceType.TEXT, nullable = true),
                        ),
                )
            val handle = InMemoryModelHandle(tables = emptyList(), entities = listOf(customerEntity))
            val erAdapter = ErSchemaAdapter(handle)
            erAdapter.getSubSchemaMap().keys.shouldContainAll(listOf("entity"))
        }

        "ObjSchemaAdapter: sub-schema map is empty in Stage 1 (no OBJ content)" {
            val adapter = ObjSchemaAdapter(FixtureModel.handle())
            adapter.getSubSchemaMap().size shouldBe 0
        }

        "EntityNamespaceSchema: entity row type matches attribute list" {
            val customerQname =
                org.tatrman.plan.v1.QualifiedName
                    .newBuilder()
                    .setSchemaCode(SchemaCode.ER)
                    .setNamespace("entity")
                    .setName("customer")
                    .build()
            val customerEntity =
                ModelEntity(
                    qname = customerQname,
                    attributes =
                        listOf(
                            ModelAttribute("id", SurfaceType.INT, nullable = false),
                            ModelAttribute("name", SurfaceType.TEXT, nullable = true),
                        ),
                )
            val handle = InMemoryModelHandle(tables = emptyList(), entities = listOf(customerEntity))
            val nsAdapter = EntityNamespaceSchema(handle, "entity")
            val entityTable = nsAdapter.getTableMap()["customer"]!!
            val rowType = entityTable.getRowType(factory)
            rowType.fieldList.map { it.name } shouldBe listOf("id", "name")
            rowType.getField("id", false, false)!!.type.sqlTypeName shouldBe SqlTypeName.BIGINT
            rowType.getField("name", false, false)!!.type.sqlTypeName shouldBe SqlTypeName.VARCHAR
        }

        "SchemaPlusAdapter: only exposes schemas with non-empty namespaces" {
            val adapter = SchemaPlusAdapter(FixtureModel.handle())
            adapter.getSubSchemaMap().keys.shouldContainAll(listOf("db"))
            adapter.getSubSchemaMap().keys.shouldNotContain("er")
            adapter.getSubSchemaMap().keys.shouldNotContain("obj")
        }

        "three-level path: root -> er -> entity -> customer resolves correctly" {
            val customerQname =
                org.tatrman.plan.v1.QualifiedName
                    .newBuilder()
                    .setSchemaCode(SchemaCode.ER)
                    .setNamespace("entity")
                    .setName("customer")
                    .build()
            val customerEntity =
                ModelEntity(
                    qname = customerQname,
                    attributes =
                        listOf(
                            ModelAttribute("id", SurfaceType.INT, nullable = false),
                        ),
                )
            val handle = InMemoryModelHandle(tables = emptyList(), entities = listOf(customerEntity))
            val rootAdapter = SchemaPlusAdapter(handle)
            val erAdapter = rootAdapter.getSubSchemaMap()["er"]!!.shouldBeInstanceOf<ErSchemaAdapter>()
            val entityNsAdapter = erAdapter.getSubSchemaMap()["entity"]!!.shouldBeInstanceOf<EntityNamespaceSchema>()
            val customerTable = entityNsAdapter.getTableMap()["customer"]!!.shouldBeInstanceOf<EntityCalciteTable>()
            customerTable.qname() shouldBe customerQname
        }

        "model.namespaces returns correct sets for fixture" {
            val handle = FixtureModel.handle()
            handle.namespaces(SchemaCode.DB) shouldBe setOf("dbo")
            handle.namespaces(SchemaCode.ER) shouldBe emptySet()
            handle.namespaces(SchemaCode.OBJ) shouldBe emptySet()
        }
    })
