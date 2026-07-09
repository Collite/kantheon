package org.tatrman.kantheon.report.engine

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * WS-C1 T5 — the real-POI XLSX render round-trip. The unit `RenderServiceSpec` only checks that the
 * artifact is non-empty; this drives the production [XlsxRenderer] over a template with both marker
 * kinds and then **re-opens the rendered workbook with POI** to assert the substitution actually
 * happened: the `{{scalar}}` placeholder was filled, the `{{table:…}}` region expanded one sheet row
 * per data row (the anchor marker cleared), the anchor's number-format style propagated to the
 * expanded rows, and the output is a valid OOXML (zip) artifact.
 *
 * No container — POI is the real dependency and the render is the production code path, so it lives
 * in the component tier, out of the mocked `test` gate.
 *
 * **Scope note (deviation, tracked in the C1 session handoff):** the deploy-test C1 T5 wording lists
 * XLSX + PPTX + PDF + HTML, but only the XLSX engine ships today — PPTX (POI slides) and PDF/HTML
 * (Playwright headless Chromium) are engine-deferred in `RenderService`, and no template files are
 * vendored yet (Bora-owned T7 gate). This spec covers the one real render path that exists and grows
 * to the others when their engines land.
 */
@Tags("component")
class ReportRendererComponentSpec :
    StringSpec({

        "XlsxRenderer fills a scalar, expands a table region, preserves the anchor style, and emits valid OOXML" {
            // A template: a scalar in a text cell, and a table anchor carrying a custom number format
            // so we can prove style inheritance survives the expansion.
            val template =
                XSSFWorkbook().use { wb ->
                    val sheet = wb.createSheet("Report")
                    sheet.createRow(0).createCell(0).setCellValue("Portfolio: {{portfolio.name}} — as of {{as_of}}")
                    val moneyStyle =
                        wb.createCellStyle().apply {
                            dataFormat = wb.createDataFormat().getFormat("#,##0.00")
                        }
                    sheet.createRow(2).createCell(0).apply {
                        setCellValue("{{table:positions}}")
                        cellStyle = moneyStyle
                    }
                    ByteArrayOutputStream().also { wb.write(it) }.toByteArray()
                }

            val out =
                XlsxRenderer.render(
                    template,
                    ReportData(
                        scalars = mapOf("portfolio.name" to "Smith Family", "as_of" to "2026-07-07"),
                        tables =
                            mapOf(
                                "positions" to
                                    listOf(
                                        listOf("AAPL", "100"),
                                        listOf("MSFT", "50"),
                                        listOf("NVDA", "25"),
                                    ),
                            ),
                    ),
                )

            // Valid, non-trivial OOXML: a .xlsx is a ZIP — the first two bytes are `PK`.
            out.size shouldBeGreaterThan 0
            (out[0].toInt() == 'P'.code && out[1].toInt() == 'K'.code) shouldBe true

            XSSFWorkbook(ByteArrayInputStream(out)).use { wb ->
                val sheet = wb.getSheetAt(0)

                // Scalar filled — both placeholders replaced, no `{{` residue.
                val header = sheet.getRow(0).getCell(0).stringCellValue
                header shouldBe "Portfolio: Smith Family — as of 2026-07-07"
                header shouldNotContain "{{"

                // Table expanded from the anchor row downward, one row per data row.
                sheet.getRow(2).getCell(0).stringCellValue shouldBe "AAPL"
                sheet.getRow(2).getCell(1).stringCellValue shouldBe "100"
                sheet.getRow(3).getCell(0).stringCellValue shouldBe "MSFT"
                sheet.getRow(4).getCell(0).stringCellValue shouldBe "NVDA"

                // The anchor's number format propagated to the expanded rows (style preservation).
                sheet
                    .getRow(4)
                    .getCell(0)
                    .cellStyle.dataFormatString shouldBe "#,##0.00"

                // The anchor is a plain value cell now — the `{{table:…}}` marker was consumed.
                sheet.getRow(2).getCell(0).cellType shouldBe CellType.STRING
                sheet.getRow(2).getCell(0).stringCellValue shouldNotContain "{{table"
            }
        }
    })
