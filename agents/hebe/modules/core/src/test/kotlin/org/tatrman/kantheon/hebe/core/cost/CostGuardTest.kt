package org.tatrman.kantheon.hebe.core.cost

import org.tatrman.kantheon.hebe.api.Observer
import org.tatrman.kantheon.hebe.config.CostSection
import org.tatrman.kantheon.hebe.config.HebeConfig
import org.tatrman.kantheon.hebe.memory.db.DbFactory
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CostGuardTest {
    private val observer = mockk<Observer>(relaxed = true)
    private val config =
        mockk<HebeConfig>(relaxed = true).also {
            io.mockk.every { it.cost } returns CostSection(dailyUsdCap = 1.0, perTurnTokenCap = 1000)
        }

    private fun makeGuard(
        dailyUsdCap: Double = 1.0,
        perTurnTokenCap: Int = 1000,
    ): CostGuard {
        val db = DbFactory.openInMemory()
        val cfg = mockk<HebeConfig>(relaxed = true)
        io.mockk.every { cfg.cost } returns
            CostSection(
                dailyUsdCap = dailyUsdCap,
                perTurnTokenCap = perTurnTokenCap,
            )
        return CostGuard(db.dataSource, cfg, observer)
    }

    @Test
    fun `checkAllowed allows when no spend`() =
        runTest {
            val guard = makeGuard()
            val result = guard.checkAllowed("turn1")
            assertEquals(CostGuard.CheckResult.Allow, result)
        }

    @Test
    fun `checkAllowed denies per-turn when tokens exceed cap`() =
        runTest {
            val guard = makeGuard(perTurnTokenCap = 100)
            val result = guard.checkAllowed("turn1", usedTokens = 101)
            assertTrue(result is CostGuard.CheckResult.DenyPerTurn)
        }

    @Test
    fun `checkAllowed denies daily when spend exceeds cap`() =
        runTest {
            val guard = makeGuard(dailyUsdCap = 0.000001)
            guard.recordCall(
                turnId = "t1",
                model = "gpt-4",
                tokensIn = 100,
                tokensOut = 50,
                costMicrosUsd = 1000L,
                durationMs = 100L,
            )
            val result = guard.checkAllowed("turn2")
            assertTrue(result is CostGuard.CheckResult.DenyDaily)
        }

    @Test
    fun `recordCall persists and is queryable via daily spend`() =
        runTest {
            val guard = makeGuard(dailyUsdCap = 100.0)
            guard.recordCall(
                turnId = "t1",
                model = "gpt-4o",
                tokensIn = 200,
                tokensOut = 100,
                costMicrosUsd = 500_000L,
                durationMs = 250L,
            )
            val result = guard.checkAllowed("t2")
            assertEquals(CostGuard.CheckResult.Allow, result)
        }

    @Test
    fun `recordCall persists the real cost_micros_usd and tokens_cached (not hardcoded zeros)`() =
        runTest {
            val db = DbFactory.openInMemory()
            val cfg =
                mockk<HebeConfig>(relaxed = true).also {
                    io.mockk.every { it.cost } returns CostSection(dailyUsdCap = 100.0, perTurnTokenCap = 1000)
                }
            val guard = CostGuard(db.dataSource, cfg, observer)

            guard.recordCall(
                turnId = "t1",
                model = "gpt-4o",
                tokensIn = 200,
                tokensOut = 100,
                costMicrosUsd = 750_000L,
                durationMs = 250L,
                tokensCached = 42,
            )

            db.dataSource.connection.use { conn ->
                conn
                    .prepareStatement(
                        "SELECT cost_micros_usd, tokens_cached FROM llm_calls WHERE turn_id = 't1'",
                    ).use { stmt ->
                        val rs = stmt.executeQuery()
                        assertTrue(rs.next())
                        assertEquals(750_000L, rs.getLong("cost_micros_usd"))
                        assertEquals(42, rs.getInt("tokens_cached"))
                    }
            }
        }

    @Test
    fun `daily spend sums recorded gateway cost and trips the cap`() =
        runTest {
            // Wired-behaviour assertion: a gateway turn whose provider returned a
            // real cost makes queryDailySpend non-zero and trips DenyDaily — the
            // previously-broken cap is now enforceable.
            val guard = makeGuard(dailyUsdCap = 0.5)
            guard.recordCall(
                turnId = "gw-turn",
                model = "gw-model",
                tokensIn = 1000,
                tokensOut = 500,
                costMicrosUsd = 600_000L, // $0.60 > $0.50 cap
                durationMs = 120L,
                tokensCached = 10,
            )
            val result = guard.checkAllowed("next-turn")
            assertTrue(result is CostGuard.CheckResult.DenyDaily)
            assertEquals(0.6, (result as CostGuard.CheckResult.DenyDaily).spentUsd, 1e-9)
        }
}
