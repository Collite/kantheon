package org.tatrman.kantheon.midas.core.mcp

import com.google.protobuf.Message
import com.google.protobuf.util.JsonFormat
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.tatrman.kantheon.common.v1.ResponseMessage
import org.tatrman.kantheon.common.v1.Severity
import org.tatrman.kantheon.midas.core.calc.DatedCashflow
import org.tatrman.kantheon.midas.core.calc.FeeAllocation as FeeAllocationCalc
import org.tatrman.kantheon.midas.core.calc.FeeBasis
import org.tatrman.kantheon.midas.core.calc.Fifo
import org.tatrman.kantheon.midas.core.calc.LotEvent
import org.tatrman.kantheon.midas.core.calc.Mwr
import org.tatrman.kantheon.midas.core.repository.PositionRepository
import org.tatrman.kantheon.midas.v1.CostBasisLot
import org.tatrman.kantheon.midas.v1.CostBasisToolOutput
import org.tatrman.kantheon.midas.v1.FeeAllocation
import org.tatrman.kantheon.midas.v1.FeeAllocationToolOutput
import org.tatrman.kantheon.midas.v1.Money
import org.tatrman.kantheon.midas.v1.PerformanceMetric
import org.tatrman.kantheon.midas.v1.PortfolioPerformanceToolOutput
import org.tatrman.kantheon.midas.v1.PositionValuationToolOutput
import org.tatrman.kantheon.midas.v1.ReconcileResponse
import org.tatrman.kantheon.midas.v1.ReconcileSummary
import org.tatrman.kantheon.midas.v1.Transaction
import org.tatrman.kantheon.midas.v1.TransactionKind
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The five Midas-core MCP tools (Stage 1.4 + Stage 3.3 — contracts.md §3).
 * `position.valuation` reads `mv_position_current`; the three calc tools now serve real
 * computed data through the [org.tatrman.kantheon.midas.core.calc] engine —
 * `cost_basis` replays the FIFO ledger, `fee_allocation` spreads a fee pro-rata by
 * position value, `portfolio.performance` returns the money-weighted return (IRR) of the
 * portfolio's cashflows. `reconcile.statement` stays empty until statement import (Stage
 * 2.x). Where a result is bounded by missing data (unpriced positions → no market value;
 * TWR needs valuation snapshots) the tool carries an INFO `ResponseMessage` so a caller
 * never mistakes a partial figure for a complete one.
 *
 * Tool I/O is the proto contract: outputs are the `*.ToolOutput` protos rendered to
 * canonical proto-JSON `structuredContent`. Each tool name is the short MCP id; the
 * registry-facing `capability_id` (`midas.*:v1`) lives in the manifest YAMLs.
 */
class MidasTools(
    private val positions: PositionRepository,
    private val transactions: TransactionLog,
) {
    // ---- position.valuation (real) -----------------------------------------

    val positionValuationTool =
        Tool(
            name = TOOL_POSITION_VALUATION,
            description = "Net positions (quantity per holding) for a portfolio from mv_position_current.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putStringProp("portfolio_id", "Portfolio UUID")
                            putStringProp("as_of", "RFC3339 instant (optional; valuation date)")
                        },
                    required = listOf("portfolio_id"),
                ),
        )

    fun positionValuationCallback(request: CallToolRequest): CallToolResult {
        val portfolioId = request.str("portfolio_id") ?: return argError("portfolio_id is required")
        val rows = positions.positionsForPortfolio(portfolioId)
        val out =
            PositionValuationToolOutput
                .newBuilder()
                .addAllPositions(rows)
                .setTotalValue(zeroMoney())
                .addMessages(
                    info(
                        "position_valuation_stub_value",
                        "quantities are live; market value/total_value are computed in Stage 3.3 (returned as 0)",
                    ),
                ).build()
        return result(out)
    }

    // ---- portfolio.performance ---------------------------------------------

    val portfolioPerformanceTool =
        Tool(
            name = TOOL_PORTFOLIO_PERFORMANCE,
            description =
                "Portfolio MWR (IRR of the dated cashflows) over an optional [period_start, period_end] " +
                    "window. TWR is reported as 0 until valuation snapshots land (Stage 3.6).",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putStringProp("portfolio_id", "Portfolio UUID")
                            putStringProp("period_start", "RFC3339 instant")
                            putStringProp("period_end", "RFC3339 instant")
                            putBoolProp("include_breakdown_by_asset", "Per-asset breakdown")
                        },
                    required = listOf("portfolio_id"),
                ),
        )

    fun portfolioPerformanceCallback(request: CallToolRequest): CallToolResult {
        val portfolioId = request.str("portfolio_id") ?: return argError("portfolio_id is required")
        val messages = mutableListOf<ResponseMessage>()

        // The reporting window: [period_start, period_end] (RFC3339; both optional). A malformed
        // bound is reported, not silently dropped, so a caller never mistakes an unscoped figure
        // for a scoped one.
        val periodStart = request.instantOrNull("period_start") { messages += badArg("period_start", it) }
        val periodEnd = request.instantOrNull("period_end") { messages += badArg("period_end", it) }

        // MWR = IRR of the portfolio's dated external cashflows + the terminal market value.
        // Contributions (buys/fees/taxes/transfers-in) are money in → negative; distributions
        // (sells/dividends/interest/transfers-out) are money out → positive. The terminal
        // position value is the closing positive flow. (Security-leg model; derived cash legs
        // are skipped to avoid double-counting.)
        val txns =
            transactions
                .forPortfolio(portfolioId, null)
                .filter { periodStart == null || !it.tradeDate.toInstant().isBefore(periodStart) }
                .filter { periodEnd == null || !it.tradeDate.toInstant().isAfter(periodEnd) }
        val cashflows = txns.mapNotNull { it.toCashflow() }.toMutableList()
        // The terminal NAV closes an *open-ended* window ("…to now"). A window with an explicit
        // period_end needs the end-of-period NAV, which the valuation snapshots (Stage 3.6) supply;
        // until then a bounded window omits the terminal leg with a note rather than splicing in a
        // current-value leg that does not belong to the period.
        if (periodEnd == null) {
            val terminalValue =
                positions.positionsForPortfolio(portfolioId).fold(BigDecimal.ZERO) { acc, p ->
                    acc.add(p.currentValue.amount.toDecimal())
                }
            val asOf = txns.maxOfOrNull { it.tradeDate.toLocalDate() } ?: LocalDate.now(ZoneOffset.UTC)
            if (terminalValue.signum() != 0) cashflows += DatedCashflow(asOf, terminalValue)
        } else {
            messages +=
                info("performance_period_end_nav_pending", "period-bounded MWR omits end-of-period NAV (Stage 3.6)")
        }

        val mwr =
            if (cashflows.size >= 2) {
                runCatching { Mwr.irr(cashflows).toPlainString() }
                    .getOrElse {
                        messages += info("performance_mwr_unavailable", "MWR: ${it.message}")
                        "0"
                    }
            } else {
                messages += info("performance_mwr_insufficient", "not enough cashflows to compute MWR")
                "0"
            }
        // TWR needs a per-sub-period NAV history (valuation snapshots between flows), which the
        // valuation/pricing pipeline (Stage 3.6) supplies — reported as 0 with a note until then.
        messages += info("performance_twr_pending", "TWR needs valuation snapshots (Stage 3.6); reported as 0")

        val out =
            PortfolioPerformanceToolOutput
                .newBuilder()
                .setPortfolio(
                    PerformanceMetric
                        .newBuilder()
                        .setPortfolioId(portfolioId)
                        .setTwr("0")
                        .setMwr(mwr)
                        .setTotalReturnAmount("0")
                        .build(),
                ).addAllMessages(messages)
                .build()
        return result(out)
    }

    // ---- cost_basis --------------------------------------------------------

    val costBasisTool =
        Tool(
            name = TOOL_COST_BASIS,
            description = "FIFO cost-basis open lots per holding, replayed from the transaction log.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putStringProp("portfolio_id", "Portfolio UUID")
                            putStringProp("asset_id", "Asset UUID (optional; all assets if absent)")
                            putStringProp("as_of", "RFC3339 instant")
                        },
                    required = listOf("portfolio_id"),
                ),
        )

    fun costBasisCallback(request: CallToolRequest): CallToolResult {
        val portfolioId = request.str("portfolio_id") ?: return argError("portfolio_id is required")
        val assetId = request.str("asset_id")
        // FIFO is per-asset (one lot queue per holding); group the log by asset, replay each,
        // tag the open lots with their asset. Reversals are replayed inline (release on undo).
        val byAsset = transactions.forPortfolio(portfolioId, assetId).groupBy { it.assetId }
        val builder = CostBasisToolOutput.newBuilder()
        for ((asset, txns) in byAsset) {
            val events = txns.mapNotNull { it.toLotEvent() }
            if (events.isEmpty()) continue
            for (lot in Fifo.costBasis(events).openLots) {
                builder.addLots(
                    CostBasisLot
                        .newBuilder()
                        .setAssetId(asset)
                        .setAcquiredAt(lot.acquiredAt.toProtoTimestamp())
                        .setRemainingQuantity(lot.remainingQuantity.toPlainString())
                        .setCostPerUnit(Money.newBuilder().setAmount(lot.costPerUnit.toPlainString()))
                        .setTotalCost(Money.newBuilder().setAmount(lot.totalCost.toPlainString()))
                        .setSourceTransactionId(lot.sourceTransactionId),
                )
            }
        }
        return result(builder.build())
    }

    // ---- fee_allocation ----------------------------------------------------

    val feeAllocationTool =
        Tool(
            name = TOOL_FEE_ALLOCATION,
            description = "Pro-rata (by position value) allocation of a transaction's fee across the portfolio.",
            inputSchema =
                ToolSchema(
                    properties = buildJsonObject { putStringProp("transaction_id", "Transaction UUID") },
                    required = listOf("transaction_id"),
                ),
        )

    fun feeAllocationCallback(request: CallToolRequest): CallToolResult {
        val txId = request.str("transaction_id") ?: return argError("transaction_id is required")
        val txn =
            transactions.byId(txId)
                ?: return result(
                    FeeAllocationToolOutput
                        .newBuilder()
                        .setTotalFee(zeroMoney())
                        .addMessages(info("fee_allocation_tx_not_found", "transaction '$txId' was not found"))
                        .build(),
                )
        val fee = txn.fee.amount.toDecimal()
        val basis =
            positions
                .positionsForPortfolio(txn.portfolioId)
                .map { FeeBasis(it.assetId, it.currentValue.amount.toDecimal()) }
        val shares = FeeAllocationCalc.allocate(fee, basis)
        val builder =
            FeeAllocationToolOutput
                .newBuilder()
                .setTotalFee(Money.newBuilder().setAmount(fee.toPlainString()).setCurrency(txn.fee.currency))
        shares.forEach { s ->
            builder.addAllocations(
                FeeAllocation
                    .newBuilder()
                    .setAssetId(s.assetId)
                    .setAllocatedFee(Money.newBuilder().setAmount(s.allocatedFee.toPlainString()))
                    .setAllocationBasis(s.basis),
            )
        }
        // Positions carry market value only once the pricing poller (Stage 3.6) populates it;
        // until then the value-weighted basis collapses and the split falls back to equal.
        if (basis.all { it.marketValue.signum() == 0 }) {
            builder.addMessages(
                info("fee_allocation_no_values", "position market values are not yet priced; allocated equally"),
            )
        }
        return result(builder.build())
    }

    // ---- reconcile.statement (empty until statement import) ----------------

    val reconcileStatementTool =
        Tool(
            name = TOOL_RECONCILE_STATEMENT,
            description = "Reconcile an imported statement against system transactions (empty until Stage 2.x).",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putStringProp("loader_run_id", "Loader run UUID")
                            putStringProp("portfolio_id", "Portfolio UUID")
                        },
                    required = listOf("portfolio_id"),
                ),
        )

    fun reconcileStatementCallback(request: CallToolRequest): CallToolResult {
        request.str("portfolio_id") ?: return argError("portfolio_id is required")
        val out =
            ReconcileResponse
                .newBuilder()
                .setSummary(ReconcileSummary.newBuilder().build())
                .addMessages(
                    info(
                        "reconcile_no_statement",
                        "statement import is not available yet (Stage 2.x); returning an empty reconciliation",
                    ),
                ).build()
        return result(out)
    }

    /** All five tool names, in registration order. */
    fun toolNames(): List<String> =
        listOf(
            positionValuationTool.name,
            portfolioPerformanceTool.name,
            costBasisTool.name,
            feeAllocationTool.name,
            reconcileStatementTool.name,
        )

    // ---- helpers -----------------------------------------------------------

    private fun result(msg: Message): CallToolResult {
        val text = printer.print(msg)
        return CallToolResult(
            content = listOf(TextContent(text = text)),
            structuredContent = Json.parseToJsonElement(text).jsonObject,
        )
    }

    private fun argError(message: String): CallToolResult =
        CallToolResult(
            content = listOf(TextContent(text = message)),
            isError = true,
            structuredContent =
                buildJsonObject {
                    put("errorCode", "INVALID_ARGUMENT")
                    put("error", message)
                    put("message", message)
                },
        )

    private fun info(
        code: String,
        message: String,
    ): ResponseMessage =
        ResponseMessage
            .newBuilder()
            .setSeverity(Severity.INFO)
            .setCode(code)
            .setHumanMessage(message)
            .build()

    private fun badArg(
        field: String,
        raw: String,
    ): ResponseMessage =
        ResponseMessage
            .newBuilder()
            .setSeverity(Severity.WARNING)
            .setCode("invalid_$field")
            .setHumanMessage("ignored unparseable $field '$raw' (expected an RFC3339 instant)")
            .build()

    /** An optional RFC3339-instant argument; a present-but-unparseable value invokes [onBad] and yields null. */
    private inline fun CallToolRequest.instantOrNull(
        key: String,
        onBad: (String) -> Unit,
    ): Instant? {
        val raw = str(key) ?: return null
        return runCatching { Instant.parse(raw) }.getOrElse {
            onBad(raw)
            null
        }
    }

    private fun zeroMoney(): Money = Money.newBuilder().setAmount("0").build()

    // ---- calc adapters -----------------------------------------------------

    /** A BUY/SELL transaction → a FIFO lot event; income/cash/other kinds → null (skipped). */
    private fun Transaction.toLotEvent(): LotEvent? {
        val lotKind =
            when (kind) {
                TransactionKind.TX_BUY -> LotEvent.Kind.BUY
                TransactionKind.TX_SELL -> LotEvent.Kind.SELL
                else -> return null
            }
        return LotEvent(
            transactionId = transactionId,
            tradeDate = tradeDate.toInstant(),
            kind = lotKind,
            quantity = quantity.toDecimal().abs(),
            pricePerUnit = price.amount.toDecimal(),
            reversesTransactionId = reversesTransactionId.ifBlank { null },
        )
    }

    /** A transaction → a signed external cashflow for MWR (security/income legs only). */
    private fun Transaction.toCashflow(): DatedCashflow? {
        val gross = total.amount.toDecimal().abs()
        if (gross.signum() == 0) return null
        val signed =
            when (kind) {
                // money in (contributions) → negative for IRR
                TransactionKind.TX_BUY, TransactionKind.TX_FEE, TransactionKind.TX_TAX,
                TransactionKind.TX_TRANSFER_IN,
                -> gross.negate()
                // money out (distributions / disposals) → positive for IRR
                TransactionKind.TX_SELL, TransactionKind.TX_DIVIDEND, TransactionKind.TX_INTEREST,
                TransactionKind.TX_TRANSFER_OUT,
                -> gross
                else -> return null // adjustments + derived cash legs are not external flows
            }
        return DatedCashflow(tradeDate.toLocalDate(), signed)
    }

    private fun String.toDecimal(): BigDecimal = if (isBlank()) BigDecimal.ZERO else BigDecimal(this)

    private fun com.google.protobuf.Timestamp.toInstant(): Instant = Instant.ofEpochSecond(seconds, nanos.toLong())

    private fun com.google.protobuf.Timestamp.toLocalDate(): LocalDate =
        toInstant().atZone(ZoneOffset.UTC).toLocalDate()

    private fun Instant.toProtoTimestamp(): com.google.protobuf.Timestamp =
        com.google.protobuf.Timestamp
            .newBuilder()
            .setSeconds(epochSecond)
            .setNanos(nano)
            .build()

    private fun CallToolRequest.str(key: String): String? =
        (params.arguments?.get(key) as? JsonPrimitive)?.contentOrNull?.ifBlank { null }

    companion object {
        const val TOOL_PORTFOLIO_PERFORMANCE = "portfolio_performance"
        const val TOOL_POSITION_VALUATION = "position_valuation"
        const val TOOL_COST_BASIS = "cost_basis"
        const val TOOL_FEE_ALLOCATION = "fee_allocation"
        const val TOOL_RECONCILE_STATEMENT = "reconcile_statement"

        private val printer: JsonFormat.Printer = JsonFormat.printer().omittingInsignificantWhitespace()

        private fun kotlinx.serialization.json.JsonObjectBuilder.putStringProp(
            name: String,
            description: String,
        ) = put(
            name,
            buildJsonObject {
                put("type", "string")
                put("description", description)
            },
        )

        private fun kotlinx.serialization.json.JsonObjectBuilder.putBoolProp(
            name: String,
            description: String,
        ) = put(
            name,
            buildJsonObject {
                put("type", "boolean")
                put("description", description)
            },
        )
    }
}
