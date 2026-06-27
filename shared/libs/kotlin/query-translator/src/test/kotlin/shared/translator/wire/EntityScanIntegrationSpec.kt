package shared.translator.wire

import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.apache.calcite.rel.core.TableScan
import shared.translator.schema.EntityCalciteTable
import shared.translator.codec.sql.SqlValidator
import shared.translator.codec.sql.ValidateResult
import shared.translator.framework.InMemoryModelHandle
import shared.translator.framework.ModelAttribute
import shared.translator.framework.ModelEntity
import shared.translator.framework.SurfaceType
import shared.translator.framework.TranslatorFramework

class EntityScanIntegrationSpec :
    StringSpec({

        val customerQname =
            QualifiedName
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

        fun parseToRel(
            sql: String,
            schemaCode: SchemaCode = SchemaCode.ER,
            namespace: String = "entity",
        ): org.apache.calcite.rel.RelNode {
            val handle = InMemoryModelHandle(tables = emptyList(), entities = listOf(customerEntity))
            val fw = TranslatorFramework(handle, schemaCode = schemaCode, namespace = namespace)
            val r = SqlValidator.validateAndConvert(fw.newPlanner(), sql)
            r.shouldBeInstanceOf<ValidateResult.Success>()
            return r.rel
        }

        "SELECT * FROM er.entity.customer encodes to ScanNode with ER schema_code" {
            val rel = parseToRel("SELECT id, name FROM er.entity.customer")
            val plan = PlanNodeEncoder.encode(rel)
            var scanNode = plan
            while (scanNode.nodeCase != PlanNode.NodeCase.TABLE_SCAN && scanNode.nodeCase != PlanNode.NodeCase.SCAN) {
                scanNode =
                    when (scanNode.nodeCase) {
                        PlanNode.NodeCase.PROJECT -> scanNode.project.input
                        else -> error("Unexpected $scanNode")
                    }
            }
            scanNode.nodeCase shouldBe PlanNode.NodeCase.SCAN
            scanNode.scan.getObject().schemaCode shouldBe SchemaCode.ER
            scanNode.scan.getObject().name shouldBe "customer"
            scanNode.scan.getObject().namespace shouldBe "entity"
        }

        "encoded ScanNode round-trips through proto bytes" {
            val rel = parseToRel("SELECT id, name FROM er.entity.customer")
            val plan = PlanNodeEncoder.encode(rel)
            val bytes = plan.toByteArray()
            val decoded = PlanNode.parseFrom(bytes)
            decoded shouldBe plan
        }

        "ScanNode decodes back to Calcite TableScan against EntityCalciteTable" {
            val rel = parseToRel("SELECT id, name FROM er.entity.customer")
            val plan = PlanNodeEncoder.encode(rel)
            val bytes = plan.toByteArray()
            val decodedPlan = PlanNode.parseFrom(bytes)
            val handle = InMemoryModelHandle(tables = emptyList(), entities = listOf(customerEntity))
            val fw = TranslatorFramework(handle, schemaCode = SchemaCode.ER, namespace = "entity")
            val decoded = PlanNodeDecoder.decode(decodedPlan, fw)
            // The decoded shape is `Project(TableScan)` — the encoder writes the SELECT's Project
            // and the decoder preserves it (force=true on RelBuilder.project). Walk to the
            // TableScan leaf to assert on the underlying Calcite table identity.
            val tableScan =
                generateSequence(decoded) { it.inputs.firstOrNull() }
                    .filterIsInstance<TableScan>()
                    .first()
            tableScan.table?.unwrap(EntityCalciteTable::class.java) shouldNotBe null
        }

        "target_schema=ER parse of er.entity.customer produces ScanNode" {
            val sql = "SELECT id, name FROM er.entity.customer"
            val handle = InMemoryModelHandle(tables = emptyList(), entities = listOf(customerEntity))
            val erFw = TranslatorFramework(handle, schemaCode = SchemaCode.ER, namespace = "entity")
            val erResult = SqlValidator.validateAndConvert(erFw.newPlanner(), sql)
            erResult.shouldBeInstanceOf<ValidateResult.Success>()
            val erPlan = PlanNodeEncoder.encode(erResult.rel)
            var erScan = erPlan
            while (erScan.nodeCase != PlanNode.NodeCase.TABLE_SCAN && erScan.nodeCase != PlanNode.NodeCase.SCAN) {
                erScan =
                    when (erScan.nodeCase) {
                        PlanNode.NodeCase.PROJECT -> erScan.project.input
                        else -> error("Unexpected $erScan")
                    }
            }
            erScan.nodeCase shouldBe PlanNode.NodeCase.SCAN
        }
    })
