package org.tatrman.kantheon.midas.loaders.excel.fixtures

import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Synthetic broker statement fixtures (Stage 1.5 T1) matching the `alpha`/`beta`
 * templates in `src/main/resources/brokers/`. Generated with POI so the data lives
 * in code (reviewable, reproducible) rather than as opaque binaries; the committed
 * `.xlsx` under `src/test/resources/fixtures/` are produced by [main] (the
 * `genFixtures` Gradle task) for the deploy smoke (T7). Replace with real broker
 * exports when in hand.
 */
object BrokerFixtures {
    // alpha — sheet "Transactions", header on row 1, ISO dates.
    private val ALPHA_HEADERS =
        listOf(
            "Trade Date",
            "Settlement Date",
            "Type",
            "Symbol",
            "Quantity",
            "Price",
            "Currency",
            "Fees",
            "Total",
            "Reference",
        )
    private val ALPHA_ROWS: List<List<Any?>> =
        listOf(
            listOf("2026-01-15", "2026-01-17", "BUY", "AAPL", 10, 150.50, "USD", 1.00, 1506.00, "A1001"),
            listOf("2026-01-20", "2026-01-22", "SELL", "AAPL", 4, 160.00, "USD", 1.00, 639.00, "A1002"),
            listOf("2026-02-01", null, "DIV", "AAPL", 0, 0, "USD", 0, 6.00, "A1003"),
            listOf("2026-02-05", null, "FEE", "AAPL", 0, 0, "USD", 2.50, 2.50, "A1004"),
        )

    // beta — sheet "Activity", two preamble rows, header on row 3, day-first dates.
    private val BETA_PREAMBLE = listOf("Beta Securities — Activity Export", "Account: DEMO-001")
    private val BETA_HEADERS =
        listOf("Date", "Action", "Ticker", "Shares", "Unit Price", "Commission", "Net Amount", "Ccy", "OrderID")
    private val BETA_ROWS: List<List<Any?>> =
        listOf(
            listOf("15/03/2026", "Buy", "MSFT", 5, 400.00, 2.00, 2002.00, "EUR", "B5001"),
            listOf("18/03/2026", "Sell", "MSFT", 2, 410.00, 2.00, 818.00, "EUR", "B5002"),
            listOf("01/04/2026", "Dividend", "MSFT", 0, 0, 0, 12.00, "EUR", "B5003"),
            listOf("03/04/2026", "Interest", null, 0, 0, 0, 3.50, "EUR", "B5004"),
        )

    fun alphaBytes(): ByteArray = workbook { build(it, "Transactions", emptyList(), ALPHA_HEADERS, ALPHA_ROWS) }

    fun betaBytes(): ByteArray = workbook { build(it, "Activity", BETA_PREAMBLE, BETA_HEADERS, BETA_ROWS) }

    /** Expected number of data rows per fixture (handy for asserts). */
    const val ALPHA_ROW_COUNT = 4
    const val BETA_ROW_COUNT = 4

    private fun workbook(block: (Workbook) -> Unit): ByteArray =
        XSSFWorkbook().use { wb ->
            block(wb)
            ByteArrayOutputStream().use { out ->
                wb.write(out)
                out.toByteArray()
            }
        }

    private fun build(
        wb: Workbook,
        sheetName: String,
        preamble: List<String>,
        headers: List<String>,
        rows: List<List<Any?>>,
    ) {
        val sheet = wb.createSheet(sheetName)
        var r = 0
        preamble.forEach { text -> sheet.createRow(r++).createCell(0).setCellValue(text) }
        val headerRow = sheet.createRow(r++)
        headers.forEachIndexed { c, h -> headerRow.createCell(c).setCellValue(h) }
        rows.forEach { values ->
            val row = sheet.createRow(r++)
            values.forEachIndexed { c, v ->
                when (v) {
                    null -> {} // leave blank
                    is String -> row.createCell(c).setCellValue(v)
                    is Number -> row.createCell(c).setCellValue(v.toDouble())
                    else -> row.createCell(c).setCellValue(v.toString())
                }
            }
        }
    }
}

/** `genFixtures` entry point — writes the committed sample workbooks. */
fun main() {
    val dir = File("src/test/resources/fixtures").apply { mkdirs() }
    File(dir, "alpha_sample.xlsx").writeBytes(BrokerFixtures.alphaBytes())
    File(dir, "beta_sample.xlsx").writeBytes(BrokerFixtures.betaBytes())
    println("Wrote alpha_sample.xlsx + beta_sample.xlsx to ${dir.absolutePath}")
}
