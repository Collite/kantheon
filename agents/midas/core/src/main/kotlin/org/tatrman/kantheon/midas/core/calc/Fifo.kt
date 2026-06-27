package org.tatrman.kantheon.midas.core.calc

import java.math.BigDecimal
import java.math.MathContext
import java.time.Instant

/** A lot-affecting event in trade order. A SELL consumes lots FIFO; a reversal of a
 *  prior SELL releases the lots that sale consumed (restoring them to the front). */
data class LotEvent(
    val transactionId: String,
    val tradeDate: Instant,
    val kind: Kind,
    val quantity: BigDecimal,
    /** Per-unit price (BUY: acquisition cost; SELL: disposal proceeds). */
    val pricePerUnit: BigDecimal,
    /** For a reversal, the transaction id of the event being reversed. */
    val reversesTransactionId: String? = null,
) {
    enum class Kind { BUY, SELL }
}

/** A remaining open lot after FIFO consumption. */
data class OpenLot(
    val sourceTransactionId: String,
    val acquiredAt: Instant,
    val remainingQuantity: BigDecimal,
    val costPerUnit: BigDecimal,
) {
    val totalCost: BigDecimal get() = remainingQuantity.multiply(costPerUnit)
}

/** The result of running the FIFO ledger: open lots (FIFO order) + cumulative realised P&L. */
data class FifoResult(
    val openLots: List<OpenLot>,
    val realisedPnl: BigDecimal,
)

/**
 * FIFO cost-basis lot ledger (Stage 3.3 T4) — a **derived** ledger replayed from the
 * transaction log (never denormalised onto positions, per the architecture risk note).
 * Buys push a lot; sells consume lots first-in-first-out, accumulating realised P&L =
 * Σ (disposalPrice − lotCost) · qtyConsumed. A SELL reversal (an edit/delete cascade)
 * **releases** the exact lots that sale consumed — restored to the front in their
 * original order, with the realised P&L of that sale backed out — so an edit round-trip
 * is lot-faithful (the FIFO+reversal interaction the risk register calls out).
 */
object Fifo {
    private val MC = MathContext.DECIMAL64

    fun costBasis(events: List<LotEvent>): FifoResult {
        val ordered = events.sortedWith(compareBy({ it.tradeDate }, { it.transactionId }))
        val lots = ArrayDeque<MutableLot>()
        // Remember, per SELL, exactly which lots (and how much) it consumed — so a reversal can undo it.
        val consumedBySell = HashMap<String, MutableList<Consumed>>()
        var realised = BigDecimal.ZERO

        for (e in ordered) {
            when {
                e.reversesTransactionId != null -> realised = reverse(e, lots, consumedBySell, realised)
                e.kind == LotEvent.Kind.BUY ->
                    lots.addLast(MutableLot(e.transactionId, e.tradeDate, e.quantity, e.pricePerUnit))
                e.kind == LotEvent.Kind.SELL -> realised = sell(e, lots, consumedBySell, realised)
            }
        }
        val open = lots.filter { it.remaining.signum() > 0 }.map { it.toOpenLot() }
        return FifoResult(open, realised)
    }

    private fun sell(
        e: LotEvent,
        lots: ArrayDeque<MutableLot>,
        consumedBySell: MutableMap<String, MutableList<Consumed>>,
        realised: BigDecimal,
    ): BigDecimal {
        var toSell = e.quantity
        var gain = realised
        val consumed = mutableListOf<Consumed>()
        while (toSell.signum() > 0 && lots.isNotEmpty()) {
            val lot = lots.first()
            val take = minOf(toSell, lot.remaining)
            gain = gain.add(e.pricePerUnit.subtract(lot.costPerUnit).multiply(take, MC), MC)
            consumed += Consumed(lot.sourceTransactionId, take, lot.costPerUnit, lot.acquiredAt)
            lot.remaining = lot.remaining.subtract(take)
            toSell = toSell.subtract(take)
            if (lot.remaining.signum() == 0) lots.removeFirst()
        }
        consumedBySell[e.transactionId] = consumed
        return gain
    }

    /**
     * Undo a reversed event. A reversed **SELL** releases the lots it consumed (front, original
     * order) and backs out its realised P&L. A reversed **BUY** (no recorded consumption) drops the
     * open lot(s) that buy created — best-effort: if the buy was already partly sold, only the still-
     * open remainder is removed (the sold part's P&L is owned by that sale's own reversal).
     */
    private fun reverse(
        e: LotEvent,
        lots: ArrayDeque<MutableLot>,
        consumedBySell: MutableMap<String, MutableList<Consumed>>,
        realised: BigDecimal,
    ): BigDecimal {
        val consumed =
            consumedBySell.remove(e.reversesTransactionId) ?: run {
                lots.removeAll { it.sourceTransactionId == e.reversesTransactionId }
                return realised
            }
        var gain = realised
        // Restore in reverse consumption order so the earliest-consumed lot ends up at the front.
        // A lot that was only *partially* consumed is still open at the front — merge the released
        // quantity back into it rather than creating a duplicate lot (keeps the ledger lot-faithful).
        for (c in consumed.asReversed()) {
            gain = gain.subtract(e.pricePerUnit.subtract(c.costPerUnit).multiply(c.quantity, MC), MC)
            val front = lots.firstOrNull()
            if (front != null &&
                front.sourceTransactionId == c.sourceTransactionId &&
                front.costPerUnit.compareTo(c.costPerUnit) == 0
            ) {
                front.remaining = front.remaining.add(c.quantity)
            } else {
                lots.addFirst(MutableLot(c.sourceTransactionId, c.acquiredAt, c.quantity, c.costPerUnit))
            }
        }
        return gain
    }

    private class MutableLot(
        val sourceTransactionId: String,
        val acquiredAt: Instant,
        var remaining: BigDecimal,
        val costPerUnit: BigDecimal,
    ) {
        fun toOpenLot() = OpenLot(sourceTransactionId, acquiredAt, remaining, costPerUnit)
    }

    private data class Consumed(
        val sourceTransactionId: String,
        val quantity: BigDecimal,
        val costPerUnit: BigDecimal,
        val acquiredAt: Instant,
    )
}
