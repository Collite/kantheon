package org.tatrman.kantheon.report.engine

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Stage 3.4 T3/T4 — the POI XLSX engine: scalar `{{placeholder}}` substitution and
 * `{{table:NAME}}` row expansion over an in-memory template (the real authored templates
 * are Bora-owned, T7; the engine contract is proven here against a built fixture).
 */
class XlsxRendererSpec :
    StringSpec({

        "scalars are substituted and a table region expands to one sheet row per data row" {
            val template =
                buildTemplate {
                    cell(0, 0, "{{portfolio.name}}")
                    cell(0, 1, "as of {{as_of}}")
                    cell(2, 0, "{{table:tbl_positions}}")
                }
            val data =
                ReportData(
                    scalars = mapOf("portfolio.name" to "Smith", "as_of" to "2026-06-27"),
                    tables = mapOf("tbl_positions" to listOf(listOf("AAPL", "100"), listOf("MSFT", "50"))),
                )

            val outBytes = XlsxRenderer.render(template, data)

            readWorkbook(outBytes) { sheet ->
                sheet.getRow(0).getCell(0).stringCellValue shouldBe "Smith"
                sheet.getRow(0).getCell(1).stringCellValue shouldBe "as of 2026-06-27"
                // table anchor row + the next row carry the two position rows
                sheet.getRow(2).getCell(0).stringCellValue shouldBe "AAPL"
                sheet.getRow(2).getCell(1).stringCellValue shouldBe "100"
                sheet.getRow(3).getCell(0).stringCellValue shouldBe "MSFT"
                sheet.getRow(3).getCell(1).stringCellValue shouldBe "50"
            }
        }

        "an unknown placeholder is blanked, not left literal" {
            val template = buildTemplate { cell(0, 0, "{{missing.key}}") }
            readWorkbook(XlsxRenderer.render(template, ReportData())) { sheet ->
                sheet.getRow(0).getCell(0).stringCellValue shouldBe ""
            }
        }

        "freshly-expanded table rows inherit the anchor cell's authored style" {
            // a template whose {{table:…}} marker carries a non-default (number-format) style
            val template =
                XSSFWorkbook().use { wb ->
                    val sheet = wb.createSheet("Report")
                    val styled =
                        wb.createCellStyle().apply { dataFormat = wb.createDataFormat().getFormat("0.00") }
                    sheet.createRow(0).createCell(0).apply {
                        setCellValue("{{table:tbl}}")
                        cellStyle = styled
                    }
                    val out = ByteArrayOutputStream()
                    wb.write(out)
                    out.toByteArray()
                }
            val data = ReportData(tables = mapOf("tbl" to listOf(listOf("1"), listOf("2"))))

            readWorkbook(XlsxRenderer.render(template, data)) { sheet ->
                val anchorStyle =
                    sheet
                        .getRow(0)
                        .getCell(0)
                        .cellStyle.index
                        .toInt()
                // the row-1 cell was created during expansion → must carry the anchor style, not the default
                sheet
                    .getRow(1)
                    .getCell(0)
                    .cellStyle.index
                    .toInt() shouldBe anchorStyle
            }
        }
    })

private class TemplateBuilder(
    private val sheet: org.apache.poi.ss.usermodel.Sheet,
) {
    fun cell(
        row: Int,
        col: Int,
        text: String,
    ) {
        val r = sheet.getRow(row) ?: sheet.createRow(row)
        r.createCell(col).setCellValue(text)
    }
}

private fun buildTemplate(block: TemplateBuilder.() -> Unit): ByteArray {
    XSSFWorkbook().use { wb ->
        val sheet = wb.createSheet("Report")
        TemplateBuilder(sheet).apply(block)
        val out = ByteArrayOutputStream()
        wb.write(out)
        return out.toByteArray()
    }
}

private fun readWorkbook(
    bytes: ByteArray,
    assert: (org.apache.poi.ss.usermodel.Sheet) -> Unit,
) {
    XSSFWorkbook(ByteArrayInputStream(bytes)).use { assert(it.getSheetAt(0)) }
}
