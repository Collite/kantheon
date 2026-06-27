package org.tatrman.kantheon.midas.core.mcp

import com.google.protobuf.Timestamp
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.tatrman.kantheon.midas.core.repository.PositionRepository
import org.tatrman.kantheon.midas.v1.Money
import org.tatrman.kantheon.midas.v1.Position
import org.tatrman.kantheon.midas.v1.Transaction
import org.tatrman.kantheon.midas.v1.TransactionKind

/**
 * Stage 1.4 + Stage 3.3 — unit coverage of the five MCP tools over mocked data seams
 * (no DB). `position.valuation` reads positions; the three calc tools (`cost_basis`,
 * `fee_allocation`, `portfolio.performance`/MWR) now serve real computed data through
 * the calc engine; `reconcile.statement` stays empty until statement import. T6 asserts
 * proto-faithful `structuredContent`.
 */
class MidasToolsSpec :
    StringSpec({

        fun request(
            name: String,
            args: JsonObject,
        ) = CallToolRequest(CallToolRequestParams(name = name, arguments = args))

        fun tools(
            positions: PositionRepository = mockk(relaxed = true),
            log: TransactionLog = FakeTransactionLog(),
        ) = MidasTools(positions, log)

        val sc: (CallToolResult) -> JsonObject = { it.structuredContent as JsonObject }

        val messageCodes: (CallToolResult) -> List<String> = { r ->
            (sc(r)["messages"] as? JsonArray ?: JsonArray(emptyList()))
                .map { it.jsonObject["code"]!!.jsonPrimitive.content }
        }

        "registers exactly the five contract tool names, in order" {
            tools().toolNames() shouldContainExactly
                listOf(
                    "position_valuation",
                    "portfolio_performance",
                    "cost_basis",
                    "fee_allocation",
                    "reconcile_statement",
                )
        }

        "position_valuation returns the portfolio's live quantities from the MV" {
            val repo = mockk<PositionRepository>()
            every { repo.positionsForPortfolio("p1") } returns
                listOf(
                    Position
                        .newBuilder()
                        .setPortfolioId("p1")
                        .setAssetId("a1")
                        .setQuantity("100")
                        .build(),
                )
            val result =
                tools(
                    repo,
                ).positionValuationCallback(
                    request("position_valuation", buildJsonObject { put("portfolio_id", "p1") }),
                )
            result.isError shouldNotBe true
            sc(result)["positions"]!!
                .jsonArray[0]
                .jsonObject["quantity"]!!
                .jsonPrimitive.content shouldBe "100"
        }

        "position_valuation without portfolio_id is an INVALID_ARGUMENT error" {
            val result = tools().positionValuationCallback(request("position_valuation", buildJsonObject {}))
            result.isError shouldBe true
            sc(result)["errorCode"]!!.jsonPrimitive.content shouldBe "INVALID_ARGUMENT"
        }

        "cost_basis replays the FIFO ledger into proto lots (50 @ 12 remaining)" {
            // buy 100@10, buy 100@12, sell 150@15 → one open lot 50 @ 12 from b2
            val log =
                FakeTransactionLog(
                    byPortfolio =
                        mapOf(
                            "p1" to
                                listOf(
                                    tx("b1", "p1", "a1", TransactionKind.TX_BUY, qty = "100", price = "10", day = 0),
                                    tx("b2", "p1", "a1", TransactionKind.TX_BUY, qty = "100", price = "12", day = 1),
                                    tx("s1", "p1", "a1", TransactionKind.TX_SELL, qty = "150", price = "15", day = 2),
                                ),
                        ),
                )
            val result =
                tools(
                    log = log,
                ).costBasisCallback(request("cost_basis", buildJsonObject { put("portfolio_id", "p1") }))
            result.isError shouldNotBe true
            val lots = sc(result)["lots"]!!.jsonArray
            lots.size shouldBe 1
            lots[0].jsonObject["assetId"]!!.jsonPrimitive.content shouldBe "a1"
            lots[0].jsonObject["remainingQuantity"]!!.jsonPrimitive.content shouldBe "50"
            lots[0].jsonObject["sourceTransactionId"]!!.jsonPrimitive.content shouldBe "b2"
        }

        "fee_allocation spreads a transaction's fee pro-rata by position value (60 / 40)" {
            val repo = mockk<PositionRepository>()
            every { repo.positionsForPortfolio("p1") } returns
                listOf(position("p1", "a1", "600"), position("p1", "a2", "400"))
            val log =
                FakeTransactionLog(
                    byId =
                        mapOf(
                            "t1" to
                                tx(
                                    "t1",
                                    "p1",
                                    "a1",
                                    TransactionKind.TX_BUY,
                                    qty = "10",
                                    price = "5",
                                    day = 0,
                                    fee = "100",
                                ),
                        ),
                )
            val result =
                tools(
                    repo,
                    log,
                ).feeAllocationCallback(request("fee_allocation", buildJsonObject { put("transaction_id", "t1") }))
            result.isError shouldNotBe true
            sc(result)["totalFee"]!!.jsonObject["amount"]!!.jsonPrimitive.content shouldBe "100"
            val byAsset =
                (sc(result)["allocations"] as JsonArray).associate {
                    it.jsonObject["assetId"]!!.jsonPrimitive.content to
                        it.jsonObject["allocatedFee"]!!
                            .jsonObject["amount"]!!
                            .jsonPrimitive.content
                }
            byAsset["a1"] shouldBe "60.0000"
            byAsset["a2"] shouldBe "40.0000"
        }

        "fee_allocation without transaction_id is an INVALID_ARGUMENT error" {
            val result = tools().feeAllocationCallback(request("fee_allocation", buildJsonObject {}))
            result.isError shouldBe true
            sc(result)["errorCode"]!!.jsonPrimitive.content shouldBe "INVALID_ARGUMENT"
        }

        "portfolio_performance computes MWR from the cashflows (−1000 then +1100 over a year → 0.10)" {
            val log =
                FakeTransactionLog(
                    byPortfolio =
                        mapOf(
                            "p1" to
                                listOf(
                                    tx(
                                        "b1",
                                        "p1",
                                        "a1",
                                        TransactionKind.TX_BUY,
                                        qty = "100",
                                        price = "10",
                                        day = 0,
                                        total = "1000",
                                    ),
                                    tx(
                                        "s1",
                                        "p1",
                                        "a1",
                                        TransactionKind.TX_SELL,
                                        qty = "100",
                                        price = "11",
                                        day = 365,
                                        total = "1100",
                                    ),
                                ),
                        ),
                )
            val result =
                tools(log = log).portfolioPerformanceCallback(
                    request(
                        "portfolio_performance",
                        buildJsonObject {
                            put("portfolio_id", "p1")
                        },
                    ),
                )
            result.isError shouldNotBe true
            val portfolio = sc(result)["portfolio"]!!.jsonObject
            portfolio["mwr"]!!.jsonPrimitive.content shouldBe "0.100000"
            portfolio["twr"]!!.jsonPrimitive.content shouldBe "0" // TWR pending valuation snapshots
        }

        "portfolio_performance honours [period_start, period_end] — flows outside the window are excluded" {
            val log =
                FakeTransactionLog(
                    byPortfolio =
                        mapOf(
                            "p1" to
                                listOf(
                                    tx("b1", "p1", "a1", TransactionKind.TX_BUY, "100", "10", day = 0, total = "1000"),
                                    tx(
                                        "s1",
                                        "p1",
                                        "a1",
                                        TransactionKind.TX_SELL,
                                        "100",
                                        "11",
                                        day = 365,
                                        total = "1100",
                                    ),
                                    // a year later, outside the window → must not pollute the IRR
                                    tx(
                                        "b2",
                                        "p1",
                                        "a1",
                                        TransactionKind.TX_BUY,
                                        "100",
                                        "20",
                                        day = 900,
                                        total = "2000",
                                    ),
                                ),
                        ),
                )
            val result =
                tools(log = log).portfolioPerformanceCallback(
                    request(
                        "portfolio_performance",
                        buildJsonObject {
                            put("portfolio_id", "p1")
                            put("period_start", "2026-01-01T00:00:00Z")
                            put("period_end", "2027-06-01T00:00:00Z")
                        },
                    ),
                )
            // only b1 (−1000) + s1 (+1100) fall in the window → IRR 0.10
            sc(result)["portfolio"]!!.jsonObject["mwr"]!!.jsonPrimitive.content shouldBe "0.100000"
            // a bounded window omits the terminal NAV (Stage 3.6) and says so
            messageCodes(result) shouldContain "performance_period_end_nav_pending"
        }

        "portfolio_performance warns on an unparseable period bound instead of silently ignoring it" {
            val result =
                tools().portfolioPerformanceCallback(
                    request(
                        "portfolio_performance",
                        buildJsonObject {
                            put("portfolio_id", "p1")
                            put("period_start", "not-a-date")
                        },
                    ),
                )
            messageCodes(result) shouldContain "invalid_period_start"
        }

        "reconcile_statement returns an empty reconciliation with a message" {
            val result =
                tools().reconcileStatementCallback(
                    request("reconcile_statement", buildJsonObject { put("portfolio_id", "p1") }),
                )
            result.isError shouldNotBe true
            (sc(result)["messages"] as JsonArray).size shouldBeGreaterThanOrEqual 1
        }

        "T6 — completed tools emit proto-faithful structuredContent (camelCase fields per the proto)" {
            val log =
                FakeTransactionLog(
                    byPortfolio =
                        mapOf(
                            "p1" to
                                listOf(
                                    tx("b1", "p1", "a1", TransactionKind.TX_BUY, qty = "100", price = "10", day = 0),
                                ),
                        ),
                )
            val cb =
                tools(
                    log = log,
                ).costBasisCallback(request("cost_basis", buildJsonObject { put("portfolio_id", "p1") }))
            // CostBasisLot proto fields render camelCase: assetId / remainingQuantity / costPerUnit / totalCost
            val lot = sc(cb)["lots"]!!.jsonArray[0].jsonObject
            lot.keys.containsAll(setOf("assetId", "remainingQuantity", "costPerUnit", "totalCost")) shouldBe true
        }
    })

/** A two-map in-memory [TransactionLog] for the tool gate. */
private class FakeTransactionLog(
    private val byPortfolio: Map<String, List<Transaction>> = emptyMap(),
    private val byId: Map<String, Transaction> = emptyMap(),
) : TransactionLog {
    override fun forPortfolio(
        portfolioId: String,
        assetId: String?,
    ): List<Transaction> = byPortfolio[portfolioId].orEmpty().filter { assetId == null || it.assetId == assetId }

    override fun byId(transactionId: String): Transaction? = byId[transactionId]
}

private fun day0Plus(days: Long): Timestamp =
    Timestamp.newBuilder().setSeconds(1_767_225_600L + days * 86_400L).build() // 2026-01-01T00:00:00Z + days

private fun tx(
    id: String,
    portfolioId: String,
    assetId: String,
    kind: TransactionKind,
    qty: String,
    price: String,
    day: Long,
    fee: String = "0",
    total: String = "0",
): Transaction =
    Transaction
        .newBuilder()
        .setTransactionId(id)
        .setPortfolioId(portfolioId)
        .setAssetId(assetId)
        .setKind(kind)
        .setTradeDate(day0Plus(day))
        .setQuantity(qty)
        .setPrice(Money.newBuilder().setAmount(price))
        .setFee(Money.newBuilder().setAmount(fee))
        .setTotal(Money.newBuilder().setAmount(total))
        .build()

private fun position(
    portfolioId: String,
    assetId: String,
    value: String,
): Position =
    Position
        .newBuilder()
        .setPortfolioId(portfolioId)
        .setAssetId(assetId)
        .setQuantity("1")
        .setCurrentValue(Money.newBuilder().setAmount(value))
        .build()
