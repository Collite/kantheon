package org.tatrman.kantheon.midas.loaders.excel.mapper

import com.google.protobuf.Timestamp
import org.tatrman.kantheon.midas.loaders.excel.parser.BrokerTemplate
import org.tatrman.kantheon.midas.loaders.excel.parser.BrokerTemplate.Fields
import org.tatrman.kantheon.midas.loaders.excel.parser.RawRow
import org.tatrman.kantheon.midas.v1.Money
import org.tatrman.kantheon.midas.v1.Transaction
import org.tatrman.kantheon.midas.v1.TransactionKind
import org.tatrman.kantheon.midas.v1.TransactionSource
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * A mapped statement row: the draft [Transaction] plus the broker `symbol` (the
 * draft's `asset_id` is resolved against Midas-core at preview/commit time, since
 * `Transaction` carries no symbol). `error` is non-null when the row could not be
 * mapped (unknown kind, bad date/number) — it becomes a `PV_ERROR` preview row.
 */
data class DraftRow(
    val sourceRowIndex: Int,
    val symbol: String,
    val draft: Transaction,
    val error: String? = null,
)

/**
 * Maps parsed [RawRow]s to midas `Transaction` drafts per a [BrokerTemplate] (Stage
 * 1.5 T4): date parsing (template `date_format`), decimal parsing, broker kind
 * vocabulary -> `TransactionKind`, and a stable `external_id` (`<broker>:<ref>`) so
 * a re-upload deduplicates. `source = TX_SRC_LOADER_EXCEL`. Pure (no DB) so it is
 * unit-tested directly.
 */
class TransactionMapper {
    fun map(
        rows: List<RawRow>,
        template: BrokerTemplate,
        portfolioId: String,
    ): List<DraftRow> = rows.map { mapRow(it, template, portfolioId) }

    fun mapRow(
        row: RawRow,
        template: BrokerTemplate,
        portfolioId: String,
    ): DraftRow {
        val symbol = row[Fields.SYMBOL]
        return try {
            val kind = mapKind(row[Fields.KIND], template)
            val currency = row[Fields.CURRENCY].uppercase()
            val builder =
                Transaction
                    .newBuilder()
                    .setPortfolioId(portfolioId)
                    .setKind(kind)
                    .setTradeDate(parseDate(row[Fields.TRADE_DATE], template, Fields.TRADE_DATE))
                    .setQuantity(parseDecimal(row[Fields.QUANTITY], Fields.QUANTITY).toPlainString())
                    .setCurrency(currency)
                    .setSource(TransactionSource.TX_SRC_LOADER_EXCEL)
            row[Fields.SETTLE_DATE].takeIf { it.isNotBlank() }?.let {
                builder.settleDate = parseDate(it, template, Fields.SETTLE_DATE)
            }
            money(row[Fields.PRICE], currency)?.let { builder.price = it }
            money(row[Fields.FEE], currency)?.let { builder.fee = it }
            money(row[Fields.TOTAL], currency)?.let { builder.total = it }
            externalId(template.brokerId, row[Fields.EXTERNAL_ID])?.let { builder.externalId = it }

            DraftRow(row.sourceRowIndex, symbol, builder.build())
        } catch (e: RowMappingException) {
            DraftRow(row.sourceRowIndex, symbol, Transaction.getDefaultInstance(), error = e.message)
        }
    }

    private fun mapKind(
        raw: String,
        template: BrokerTemplate,
    ): TransactionKind {
        if (raw.isBlank()) throw RowMappingException("missing transaction type")
        val enumName =
            template.kindMap[raw]
                ?: template.kindMap.entries
                    .firstOrNull { it.key.equals(raw, ignoreCase = true) }
                    ?.value
                ?: throw RowMappingException("unknown transaction type '$raw'")
        return runCatching { TransactionKind.valueOf(enumName) }
            .getOrElse { throw RowMappingException("kind_map points '$raw' at unknown enum '$enumName'") }
    }

    private fun parseDate(
        raw: String,
        template: BrokerTemplate,
        field: String,
    ): Timestamp {
        if (raw.isBlank()) throw RowMappingException("missing $field")
        val date =
            runCatching { LocalDate.parse(raw, DateTimeFormatter.ofPattern(template.dateFormat)) }
                .getOrElse {
                    throw RowMappingException(
                        "$field '$raw' does not match date_format '${template.dateFormat}'",
                    )
                }
        val instant = date.atStartOfDay().toInstant(ZoneOffset.UTC)
        return Timestamp
            .newBuilder()
            .setSeconds(instant.epochSecond)
            .setNanos(instant.nano)
            .build()
    }

    private fun parseDecimal(
        raw: String,
        field: String,
    ): BigDecimal {
        if (raw.isBlank()) throw RowMappingException("missing $field")
        return runCatching { BigDecimal(normalizeNumber(raw)) }
            .getOrElse { throw RowMappingException("$field '$raw' is not a number") }
    }

    /** Tolerate thousands separators + a leading currency symbol that some exports include. */
    private fun normalizeNumber(raw: String): String =
        raw.replace(",", "").replace(Regex("[^0-9.\\-]"), "").ifBlank { raw }

    private fun money(
        raw: String,
        currency: String,
    ): Money? {
        if (raw.isBlank()) return null
        val amount = runCatching { BigDecimal(normalizeNumber(raw)) }.getOrNull() ?: return null
        return Money
            .newBuilder()
            .setAmount(amount.toPlainString())
            .setCurrency(currency)
            .build()
    }

    private fun externalId(
        brokerId: String,
        raw: String,
    ): String? = raw.takeIf { it.isNotBlank() }?.let { "$brokerId:$it" }

    private class RowMappingException(
        message: String,
    ) : RuntimeException(message)
}
