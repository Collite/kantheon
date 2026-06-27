package org.tatrman.kantheon.midas.loaders.excel.parser

import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.math.BigDecimal

/**
 * One parsed statement row, keyed by the template's logical field names
 * ([BrokerTemplate.Fields]). Values are the trimmed cell text; absent columns map to
 * empty strings. `sourceRowIndex` is the 0-based sheet row (for PreviewRow + diffs).
 */
data class RawRow(
    val sourceRowIndex: Int,
    val values: Map<String, String>,
) {
    operator fun get(field: String): String = values[field].orEmpty()
}

/** Thrown when a sheet/header named by the template is missing from the workbook. */
class ExcelParseException(
    message: String,
) : RuntimeException(message)

/**
 * Reads an XLSX statement into [RawRow]s using a [BrokerTemplate] (Stage 1.5 T2).
 * Locates the named sheet, reads the template's header row, resolves each logical
 * field to its column index by matching header text (case-insensitive, trimmed),
 * then emits one [RawRow] per non-empty data row below the header. Cell values are
 * normalised to plain strings (numbers without trailing `.0`, dates via the cell's
 * displayed text) so the mapper parses uniformly.
 */
class ExcelParser {
    private val log = LoggerFactory.getLogger(ExcelParser::class.java)
    private val formatter = DataFormatter()

    fun parse(
        input: InputStream,
        template: BrokerTemplate,
    ): List<RawRow> =
        XSSFWorkbook(input).use { workbook ->
            val sheet =
                workbook.getSheet(template.sheet)
                    ?: throw ExcelParseException("sheet '${template.sheet}' not found in workbook")
            val headerRowIdx = template.headerRow - 1
            val headerRow =
                sheet.getRow(headerRowIdx)
                    ?: throw ExcelParseException(
                        "header row ${template.headerRow} is empty in sheet '${template.sheet}'",
                    )

            val headerToCol = headerIndex(headerRow)
            val fieldToCol =
                template.columns.mapValues { (field, header) ->
                    headerToCol[header.trim().lowercase()]
                        ?: throw ExcelParseException(
                            "column '$header' (field '$field') not found in sheet '${template.sheet}' header",
                        )
                }

            val rows = mutableListOf<RawRow>()
            for (r in (headerRowIdx + 1)..sheet.lastRowNum) {
                val row = sheet.getRow(r) ?: continue
                val values = fieldToCol.mapValues { (_, col) -> cellString(row.getCell(col)) }
                if (values.values.all { it.isBlank() }) continue // skip fully blank rows
                rows.add(RawRow(sourceRowIndex = r, values = values))
            }
            log.debug("parsed {} rows from sheet '{}'", rows.size, template.sheet)
            rows
        }

    private fun headerIndex(headerRow: Row): Map<String, Int> {
        val out = mutableMapOf<String, Int>()
        for (c in 0 until headerRow.lastCellNum) {
            val text = cellString(headerRow.getCell(c))
            if (text.isNotBlank()) out.putIfAbsent(text.trim().lowercase(), c)
        }
        return out
    }

    private fun cellString(cell: Cell?): String =
        when (cell?.cellType) {
            null, CellType.BLANK -> ""
            CellType.STRING -> cell.stringCellValue.trim()
            CellType.BOOLEAN -> cell.booleanCellValue.toString()
            CellType.NUMERIC ->
                if (DateUtil.isCellDateFormatted(cell)) {
                    formatter.formatCellValue(cell).trim()
                } else {
                    numericString(cell.numericCellValue)
                }
            else -> formatter.formatCellValue(cell).trim()
        }

    /** Render a numeric cell without a spurious trailing `.0` (10.0 -> "10", 150.5 -> "150.5"). */
    private fun numericString(value: Double): String =
        if (value == Math.floor(value) && !value.isInfinite()) {
            value.toLong().toString()
        } else {
            BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
        }
}
