package shared.translator.detect

import org.tatrman.proteus.v1.Language
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import shared.translator.framework.InMemoryModelHandle
import shared.translator.framework.ModelEntity
import shared.translator.framework.ModelTable
import shared.translator.framework.SurfaceType

class SchemaDetectorSpec :
    StringSpec({

        val dbNamespace = "dbo"
        val erNamespace = "entity"

        val qskupzboziQname =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.DB)
                .setNamespace(dbNamespace)
                .setName("qskupzbozi_df")
                .build()
        val qzboziQname =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.DB)
                .setNamespace(dbNamespace)
                .setName("qzbozi_df")
                .build()
        val produktQname =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.ER)
                .setNamespace(erNamespace)
                .setName("produkt")
                .build()
        val skupinaQname =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.ER)
                .setNamespace(erNamespace)
                .setName("skupina")
                .build()

        val qskupzboziTable =
            ModelTable(
                qname = qskupzboziQname,
                columns =
                    listOf(
                        shared.translator.framework.ModelColumn("id", SurfaceType.INT),
                        shared.translator.framework.ModelColumn("nazev", SurfaceType.TEXT),
                    ),
            )
        val qzboziTable =
            ModelTable(
                qname = qzboziQname,
                columns =
                    listOf(
                        shared.translator.framework.ModelColumn("id", SurfaceType.INT),
                    ),
            )
        val produktEntity =
            ModelEntity(
                qname = produktQname,
                attributes =
                    listOf(
                        shared.translator.framework.ModelAttribute("id", SurfaceType.INT),
                        shared.translator.framework.ModelAttribute("name", SurfaceType.TEXT),
                    ),
            )
        val skupinaEntity =
            ModelEntity(
                qname = skupinaQname,
                attributes =
                    listOf(
                        shared.translator.framework.ModelAttribute("id", SurfaceType.INT),
                    ),
            )

        fun makeHandle(
            tables: List<ModelTable> = listOf(qskupzboziTable, qzboziTable),
            entities: List<ModelEntity> = listOf(produktEntity, skupinaEntity),
        ): InMemoryModelHandle =
            InMemoryModelHandle(
                tables = tables,
                entities = entities,
            )

        "AUTODETECTED DB when stated=UNSPECIFIED and SQL uses DB table" {
            val sql = "SELECT idskupzbozi, kod_skup_zbozi FROM QSKUPZBOZI_DF WHERE nazev LIKE 'O%'"
            val result = SchemaDetector.detect(sql, Language.SQL, SchemaCode.SCHEMA_CODE_UNSPECIFIED, makeHandle())
            result.decision shouldBe SchemaDecision.AUTODETECTED
            result.effectiveSchema shouldBe SchemaCode.DB
        }

        "AUTODETECTED ER when SQL uses ER entity" {
            val sql = "SELECT id, name FROM produkt"
            val result = SchemaDetector.detect(sql, Language.SQL, SchemaCode.SCHEMA_CODE_UNSPECIFIED, makeHandle())
            result.decision shouldBe SchemaDecision.AUTODETECTED
            result.effectiveSchema shouldBe SchemaCode.ER
        }

        "CONFIRMED when stated matches" {
            val sql = "SELECT id FROM QSKUPZBOZI_DF"
            val result = SchemaDetector.detect(sql, Language.SQL, SchemaCode.DB, makeHandle())
            result.decision shouldBe SchemaDecision.CONFIRMED
            result.effectiveSchema shouldBe SchemaCode.DB
        }

        "CORRECTED when stated=ER but SQL is physical DB" {
            val sql = "SELECT id FROM QSKUPZBOZI_DF"
            val result = SchemaDetector.detect(sql, Language.SQL, SchemaCode.ER, makeHandle())
            result.decision shouldBe SchemaDecision.CORRECTED
            result.effectiveSchema shouldBe SchemaCode.DB
        }

        "UNKNOWN with suggestions for unknown table" {
            val sql = "SELECT id FROM QSKUPZBOZIX"
            val result = SchemaDetector.detect(sql, Language.SQL, SchemaCode.SCHEMA_CODE_UNSPECIFIED, makeHandle())
            result.decision shouldBe SchemaDecision.UNKNOWN
            result.effectiveSchema shouldBe SchemaCode.SCHEMA_CODE_UNSPECIFIED
            result.unknownTables shouldContainExactlyInAnyOrder listOf("qskupzbozix")
            result.suggestions.firstOrNull()?.candidates shouldContainExactlyInAnyOrder listOf("qskupzbozi_df")
        }

        "MIXED when one DB and one ER table in one query" {
            val sql = "SELECT * FROM QSKUPZBOZI_DF, produkt"
            val result = SchemaDetector.detect(sql, Language.SQL, SchemaCode.SCHEMA_CODE_UNSPECIFIED, makeHandle())
            result.decision shouldBe SchemaDecision.MIXED
            result.effectiveSchema shouldBe SchemaCode.SCHEMA_CODE_UNSPECIFIED
        }

        "AMBIGUOUS when table exists in both schemas and no stated" {
            val bothTableQname =
                QualifiedName
                    .newBuilder()
                    .setSchemaCode(SchemaCode.DB)
                    .setNamespace(dbNamespace)
                    .setName("dual_name")
                    .build()
            val bothEntityQname =
                QualifiedName
                    .newBuilder()
                    .setSchemaCode(SchemaCode.ER)
                    .setNamespace(erNamespace)
                    .setName("dual_name")
                    .build()
            val bothTable = ModelTable(qname = bothTableQname, columns = listOf())
            val bothEntity = ModelEntity(qname = bothEntityQname, attributes = listOf())

            val handle =
                makeHandle(
                    tables = listOf(qskupzboziTable, bothTable),
                    entities = listOf(produktEntity, bothEntity),
                )
            val sql = "SELECT id FROM dual_name"
            val result = SchemaDetector.detect(sql, Language.SQL, SchemaCode.SCHEMA_CODE_UNSPECIFIED, handle)
            result.decision shouldBe SchemaDecision.AMBIGUOUS
            result.effectiveSchema shouldBe SchemaCode.SCHEMA_CODE_UNSPECIFIED
        }

        "CONFIRMED when table exists in both schemas but stated=DB" {
            val bothTableQname =
                QualifiedName
                    .newBuilder()
                    .setSchemaCode(SchemaCode.DB)
                    .setNamespace(dbNamespace)
                    .setName("dual_name")
                    .build()
            val bothEntityQname =
                QualifiedName
                    .newBuilder()
                    .setSchemaCode(SchemaCode.ER)
                    .setNamespace(erNamespace)
                    .setName("dual_name")
                    .build()
            val bothTable = ModelTable(qname = bothTableQname, columns = listOf())
            val bothEntity = ModelEntity(qname = bothEntityQname, attributes = listOf())

            val handle =
                makeHandle(
                    tables = listOf(qskupzboziTable, bothTable),
                    entities = listOf(produktEntity, bothEntity),
                )
            val sql = "SELECT id FROM dual_name"
            val result = SchemaDetector.detect(sql, Language.SQL, SchemaCode.DB, handle)
            result.decision shouldBe SchemaDecision.CONFIRMED
            result.effectiveSchema shouldBe SchemaCode.DB
        }

        "NOT_APPLICABLE when sourceLanguage=DATAFRAME_DSL" {
            val sql = "SELECT id FROM QSKUPZBOZI_DF"
            val result =
                SchemaDetector.detect(
                    sql,
                    Language.DATAFRAME_DSL,
                    SchemaCode.SCHEMA_CODE_UNSPECIFIED,
                    makeHandle(),
                )
            result.decision shouldBe SchemaDecision.NOT_APPLICABLE
            result.effectiveSchema shouldBe SchemaCode.SCHEMA_CODE_UNSPECIFIED
        }

        "NOT_APPLICABLE when SQL parse fails" {
            val sql = "SELECT FROM WHERE"
            val result = SchemaDetector.detect(sql, Language.SQL, SchemaCode.SCHEMA_CODE_UNSPECIFIED, makeHandle())
            result.decision shouldBe SchemaDecision.NOT_APPLICABLE
        }

        "NOT_APPLICABLE when zero table refs" {
            val sql = "SELECT 1 + 2"
            val result = SchemaDetector.detect(sql, Language.SQL, SchemaCode.SCHEMA_CODE_UNSPECIFIED, makeHandle())
            result.decision shouldBe SchemaDecision.NOT_APPLICABLE
        }
    })
