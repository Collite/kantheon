package org.tatrman.kantheon.hebe.core.cost

import org.tatrman.kantheon.hebe.api.Observer
import org.tatrman.kantheon.hebe.api.ObserverEvent
import org.tatrman.kantheon.hebe.config.HebeConfig
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import javax.sql.DataSource
import org.slf4j.LoggerFactory

class CostGuard(
    private val ds: DataSource,
    private val config: HebeConfig,
    private val observer: Observer,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    sealed interface CheckResult {
        data object Allow : CheckResult

        data class DenyDaily(
            val spentUsd: Double,
            val capUsd: Double,
        ) : CheckResult

        data class DenyPerTurn(
            val usedTokens: Int,
            val capTokens: Int,
        ) : CheckResult
    }

    suspend fun checkAllowed(
        turnId: String,
        usedTokens: Int = 0,
    ): CheckResult {
        val today = LocalDate.now(ZoneOffset.UTC)
        val dailyCap = config.cost.dailyUsdCap
        val perTurnCap = config.cost.perTurnTokenCap

        val spentToday = queryDailySpend(today)
        if (spentToday >= dailyCap) {
            logger.warn("daily budget exceeded: spent={} cap={}", spentToday, dailyCap)
            return CheckResult.DenyDaily(spentToday, dailyCap)
        }

        if (usedTokens >= perTurnCap) {
            logger.warn("per-turn token cap exceeded: used={} cap={}", usedTokens, perTurnCap)
            return CheckResult.DenyPerTurn(usedTokens, perTurnCap)
        }

        return CheckResult.Allow
    }

    suspend fun recordCall(
        turnId: String,
        model: String,
        tokensIn: Int,
        tokensOut: Int,
        costMicrosUsd: Long?,
        durationMs: Long = 0,
        tokensCached: Int = 0,
    ) {
        ds.connection.use { conn ->
            val id =
                java.util.UUID
                    .randomUUID()
                    .toString()
            val now = Instant.now().toEpochMilli()
            val cost =
                costMicrosUsd ?: run {
                    logger.warn("cost not returned by provider; cap may be unenforceable")
                    0L
                }
            val ms = durationMs

            conn
                .prepareStatement(
                    """
                    INSERT INTO llm_calls (id, conversation_id, turn_id, model, tokens_in, tokens_out, tokens_cached, cost_micros_usd, ms, ts)
                    VALUES (?, NULL, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { stmt ->
                    stmt.setString(1, id)
                    stmt.setString(2, turnId)
                    stmt.setString(3, model)
                    stmt.setInt(4, tokensIn)
                    stmt.setInt(5, tokensOut)
                    stmt.setInt(6, tokensCached)
                    stmt.setLong(7, cost)
                    stmt.setLong(8, ms)
                    stmt.setLong(9, now)
                    stmt.executeUpdate()
                }
        }

        observer.event(
            ObserverEvent.LlmCall(
                turnId = turnId,
                tokensIn = tokensIn,
                tokensOut = tokensOut,
                ms = durationMs,
            ),
        )
    }

    private fun queryDailySpend(date: LocalDate): Double {
        val startOfDay = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val endOfDay =
            date
                .plusDays(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()

        return ds.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    SELECT COALESCE(SUM(cost_micros_usd), 0) as total_cost
                    FROM llm_calls
                    WHERE ts >= ? AND ts < ?
                    """.trimIndent(),
                ).use { stmt ->
                    stmt.setLong(1, startOfDay)
                    stmt.setLong(2, endOfDay)
                    val rs = stmt.executeQuery()
                    if (rs.next()) {
                        val micros = rs.getLong("total_cost")
                        micros / 1_000_000.0
                    } else {
                        0.0
                    }
                }
        }
    }
}
