package org.tatrman.kantheon.report.engine

import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** The data a report binds: scalar placeholders + named table regions of rows. */
data class ReportData(
    val scalars: Map<String, String> = emptyMap(),
    val tables: Map<String, List<List<String>>> = emptyMap(),
)

/** Fetches the data for a template + validated args (Stage 3.4 T4). The live impl calls
 *  Midas-core MCP tools (midas.position.valuation:v1, …); the unit gate runs a fake. */
fun interface DataFetcher {
    fun fetch(
        templateId: String,
        args: Map<String, String>,
    ): ReportData
}

/**
 * The XLSX render engine (Stage 3.4 T4) — POI named-placeholder substitution + table-region
 * row expansion over a `.xlsx` template, style-preserving (cells are mutated in place, so the
 * authored fonts/number-formats survive). Two markers:
 *   - `{{scalar.key}}` anywhere in a string cell → replaced by `scalars["scalar.key"]`,
 *   - a cell whose whole text is `{{table:NAME}}` → the anchor for `tables["NAME"]`; the rows
 *     are written from the anchor downward (one sheet row per data row, cells from the anchor
 *     column), and the marker cell is cleared.
 */
object XlsxRenderer {
    private val SCALAR = Regex("\\{\\{([^{}:]+)}}")
    private val TABLE = Regex("^\\{\\{table:([^{}]+)}}$")

    fun render(
        templateBytes: ByteArray,
        data: ReportData,
    ): ByteArray {
        XSSFWorkbook(ByteArrayInputStream(templateBytes)).use { wb ->
            for (sheet in wb) {
                // Snapshot the rows first — table expansion writes new rows we must not re-scan.
                val rows = sheet.rowIterator().asSequence().toList()
                for (row in rows) {
                    for (cell in row.cellIterator().asSequence().toList()) {
                        if (cell.cellType != CellType.STRING) continue
                        val text = cell.stringCellValue
                        val tableMatch = TABLE.matchEntire(text)
                        if (tableMatch != null) {
                            // Clear the marker first; the first table row reuses this anchor cell. Keep
                            // its authored style so every expanded row inherits the template's format.
                            val col = cell.columnIndex
                            val anchorStyle = cell.cellStyle
                            cell.setBlank()
                            expandTable(sheet, row, col, anchorStyle, data.tables[tableMatch.groupValues[1]].orEmpty())
                        } else if (text.contains("{{")) {
                            cell.setCellValue(SCALAR.replace(text) { m -> data.scalars[m.groupValues[1].trim()] ?: "" })
                        }
                    }
                }
            }
            val out = ByteArrayOutputStream()
            wb.write(out)
            return out.toByteArray()
        }
    }

    private fun expandTable(
        sheet: org.apache.poi.ss.usermodel.Sheet,
        anchorRow: Row,
        anchorCol: Int,
        anchorStyle: org.apache.poi.ss.usermodel.CellStyle,
        rows: List<List<String>>,
    ) {
        rows.forEachIndexed { i, cells ->
            val target =
                if (i == 0) {
                    anchorRow
                } else {
                    sheet.getRow(anchorRow.rowNum + i) ?: sheet.createRow(anchorRow.rowNum + i)
                }
            cells.forEachIndexed { j, value ->
                // A cell freshly created for the table region carries no style — give it the anchor's
                // (workbook-scoped) style so authored fonts / number-formats survive the expansion.
                val existing = target.getCell(anchorCol + j)
                val c = existing ?: target.createCell(anchorCol + j).also { it.cellStyle = anchorStyle }
                c.setCellValue(value)
            }
        }
    }
}
