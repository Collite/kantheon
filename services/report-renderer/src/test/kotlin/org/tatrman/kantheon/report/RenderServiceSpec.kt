package org.tatrman.kantheon.report

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.tatrman.kantheon.report.engine.DataFetcher
import org.tatrman.kantheon.report.engine.ReportData
import org.tatrman.kantheon.report.store.ArtifactStore
import org.tatrman.kantheon.report.template.TemplateRegistry
import org.tatrman.kantheon.report.template.TemplateResolver
import org.tatrman.kantheon.report.v1.OutputFormat
import org.tatrman.kantheon.report.v1.RenderReportRequest
import org.tatrman.kantheon.report.v1.ReportTemplate
import java.io.ByteArrayOutputStream

/**
 * Stage 3.4 T6 — the synchronous render flow: resolve → validate → fetch → render → store →
 * descriptor. Unknown templates / invalid args / non-XLSX formats fail closed.
 */
class RenderServiceSpec :
    StringSpec({

        fun templateBytes(): ByteArray =
            XSSFWorkbook().use { wb ->
                val s = wb.createSheet("Report")
                s.createRow(0).createCell(0).setCellValue("{{portfolio.name}}")
                s.createRow(2).createCell(0).setCellValue("{{table:tbl_positions}}")
                val out = ByteArrayOutputStream()
                wb.write(out)
                out.toByteArray()
            }

        fun resolver(): TemplateResolver =
            object : TemplateResolver {
                override fun resolve(templateId: String): ReportTemplate? = TemplateRegistry.byId(templateId)

                override fun bytes(template: ReportTemplate): ByteArray = templateBytes()
            }

        fun service(dir: java.nio.file.Path): RenderService =
            RenderService(
                resolver = resolver(),
                data =
                    DataFetcher { _, _ ->
                        ReportData(
                            scalars = mapOf("portfolio.name" to "Smith"),
                            tables = mapOf("tbl_positions" to listOf(listOf("AAPL", "100"))),
                        )
                    },
                artifacts = ArtifactStore(dir),
            )

        fun req(
            id: String = "portfolio-statement:v1",
            args: String = """{"portfolio_id":"p1"}""",
            fmt: OutputFormat = OutputFormat.OUTPUT_XLSX,
        ) = RenderReportRequest
            .newBuilder()
            .setTemplateId(id)
            .setArgsJson(args)
            .setOutputFormat(fmt)
            .build()

        "renders an XLSX artifact with a download descriptor" {
            val dir = tempdir().toPath()
            val resp = service(dir).render(req())
            resp.artifactId.isNotBlank() shouldBe true
            resp.mimeType shouldBe RenderService.XLSX_MIME
            resp.sizeBytes.toInt() shouldBeGreaterThan 0
            resp.artifactUrl shouldBe "/artifacts/${resp.artifactId}"
            // the artifact is retrievable + larger than an empty file
            ArtifactStore(dir).read(resp.artifactId)!!.size shouldBeGreaterThan 0
        }

        "an unknown template fails closed" {
            val e = shouldThrow<RenderException> { service(tempdir().toPath()).render(req(id = "nope:v1")) }
            e.code shouldBe "unknown_template"
        }

        "a missing required parameter fails closed" {
            val e = shouldThrow<RenderException> { service(tempdir().toPath()).render(req(args = "{}")) }
            e.code shouldBe "invalid_args"
        }

        "a non-XLSX output format is unsupported in v1" {
            val e =
                shouldThrow<RenderException> {
                    service(tempdir().toPath()).render(req(fmt = OutputFormat.OUTPUT_PDF))
                }
            e.code shouldBe "unsupported_format"
        }
    })
