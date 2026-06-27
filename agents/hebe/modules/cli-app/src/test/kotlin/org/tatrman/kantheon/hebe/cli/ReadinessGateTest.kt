package org.tatrman.kantheon.hebe.cli

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Readiness gate (Hebe P3 S3.3 T4): ready (200) only when every precondition holds;
 * each missing dependency keeps it 503.
 */
class ReadinessGateTest {
    private val gate = ReadinessGate()
    private val allUp =
        ReadinessGate.Checks(
            configResolved = true,
            pgReachable = true,
            migrationsAtHead = true,
            channelsUp = true,
        )

    @Test
    fun `ready and 200 only when all preconditions hold`() {
        gate.ready(allUp) shouldBe true
        gate.statusCode(allUp) shouldBe ReadinessGate.HTTP_OK
    }

    @Test
    fun `each missing precondition keeps it not-ready with 503`() {
        listOf(
            allUp.copy(configResolved = false),
            allUp.copy(pgReachable = false),
            allUp.copy(migrationsAtHead = false),
            allUp.copy(channelsUp = false),
        ).forEach { checks ->
            gate.ready(checks) shouldBe false
            gate.statusCode(checks) shouldBe ReadinessGate.HTTP_UNAVAILABLE
        }
    }

    @Test
    fun `report names the blocking dependency`() {
        gate.report(allUp.copy(pgReachable = false))["postgres"] shouldBe false
        gate.report(allUp)["postgres"] shouldBe true
    }
}
