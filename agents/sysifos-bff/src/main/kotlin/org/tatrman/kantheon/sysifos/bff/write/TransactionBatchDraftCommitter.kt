package org.tatrman.kantheon.sysifos.bff.write

import com.google.protobuf.util.JsonFormat
import io.ktor.http.HttpMethod
import org.tatrman.kantheon.bffbase.auth.CallerIdentity
import org.tatrman.kantheon.midas.v1.BatchInsertTransactionsRequest
import org.tatrman.kantheon.midas.v1.BatchInsertTransactionsResponse
import org.tatrman.kantheon.midas.v1.Transaction
import org.tatrman.kantheon.sysifos.bff.midas.MidasCoreClient
import org.tatrman.kantheon.sysifos.v1.BatchRowOutcome
import org.tatrman.kantheon.sysifos.v1.BatchRowResult
import org.tatrman.kantheon.sysifos.v1.Draft
import org.tatrman.kantheon.sysifos.v1.SysifosStreamEvent
import org.tatrman.kantheon.sysifos.v1.TransactionBatchForm
import org.tatrman.kantheon.sysifos.v1.TransactionForm

private val batchParser: JsonFormat.Parser = JsonFormat.parser().ignoringUnknownFields()
private val batchPrinter: JsonFormat.Printer = JsonFormat.printer().omittingInsignificantWhitespace()

/**
 * `DRAFT_TRANSACTION_BATCH` committer (S3 bulk grid; contracts §1, §3.2). Maps
 * `TransactionBatchForm` → Midas-core `BatchInsertTransactionsRequest` and
 * `POST /api/v1/transactions:batch` (security legs only — Midas derives the cash
 * legs). The batch response is aggregate (inserted/skipped/failed + the offending
 * `Transaction`s), so per-row outcomes are reconstructed by matching each error
 * back to its input row by a content key; matched rows stream `BR_FAILED`, the
 * rest `BR_COMMITTED`. (Midas reports `skipped_count` only in aggregate — a
 * skipped row shows a committed pill in v1; the summary carries the skip count.)
 */
class TransactionBatchDraftCommitter(
    private val midas: MidasCoreClient,
) : DraftCommitter {
    override suspend fun commit(
        draft: Draft,
        caller: CallerIdentity,
        sink: DraftEventSink,
    ): CommitOutcome {
        val form = TransactionBatchForm.newBuilder().also { batchParser.merge(draft.payloadJson, it) }.build()
        val rows = form.rowsList

        val request =
            BatchInsertTransactionsRequest
                .newBuilder()
                .addAllTransactions(rows.map { it.toTransaction() })
                .setSkipExisting(form.skipExisting)
                .build()

        val resp = midas.forward(HttpMethod.Post, "/api/v1/transactions:batch", caller, batchPrinter.print(request))
        if (!resp.isSuccess) {
            return midasRejection(resp)
        }
        val parsed =
            BatchInsertTransactionsResponse
                .newBuilder()
                .also { batchParser.merge(resp.body, it) }
                .build()

        // Match each error back to a row by content key (first unmatched wins).
        val failedRowIndices = mutableMapOf<Int, String>()
        for (err in parsed.errorsList) {
            val target = rows.indexOfFirstMatching(rowKey(err.transaction), failedRowIndices.keys)
            if (target >= 0) failedRowIndices[target] = err.reason.ifEmpty { err.code }
        }

        rows.forEachIndexed { i, _ ->
            val failure = failedRowIndices[i]
            sink.emit(rowResult(draft.draftId, i, failure))
        }

        return CommitOutcome.Committed(
            artifactRef = draft.draftId,
            committedCount = parsed.insertedCount,
            skippedCount = parsed.skippedCount,
        )
    }
}

/** Map a grid row (security leg) to a Midas `Transaction`; cash legs are derived. */
private fun TransactionForm.toTransaction(): Transaction {
    val b =
        Transaction
            .newBuilder()
            .setPortfolioId(portfolioId)
            .setAssetId(assetId)
            .setKind(kind)
            .setQuantity(quantity)
            .setCurrency(currency)
    if (hasTradeDate()) b.tradeDate = tradeDate
    if (hasSettleDate()) b.settleDate = settleDate
    if (hasPrice()) b.price = price
    if (hasFee()) b.fee = fee
    if (hasTax()) b.tax = tax
    if (note.isNotEmpty()) b.note = note
    return b.build()
}

private fun rowResult(
    draftId: String,
    index: Int,
    failureReason: String?,
): SysifosStreamEvent {
    val result =
        BatchRowResult
            .newBuilder()
            .setDraftId(draftId)
            .setRowIndex(index)
    if (failureReason != null) {
        result.outcome = BatchRowOutcome.BR_FAILED
        result.message = failureReason
    } else {
        result.outcome = BatchRowOutcome.BR_COMMITTED
    }
    return SysifosStreamEvent.newBuilder().setBatchRowResult(result).build()
}

/** Content key tying a returned error `Transaction` back to its grid row. */
private fun rowKey(tx: Transaction): String =
    "${tx.assetId}|${tx.quantity}|${if (tx.hasTradeDate()) tx.tradeDate.seconds else 0}|${tx.kind.number}"

private fun TransactionForm.rowKey(): String =
    "$assetId|$quantity|${if (hasTradeDate()) tradeDate.seconds else 0L}|${kind.number}"

private fun List<TransactionForm>.indexOfFirstMatching(
    key: String,
    taken: Set<Int>,
): Int {
    for (i in indices) if (i !in taken && this[i].rowKey() == key) return i
    return -1
}
