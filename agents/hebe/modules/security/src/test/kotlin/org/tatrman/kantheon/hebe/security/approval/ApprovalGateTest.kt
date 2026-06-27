package org.tatrman.kantheon.hebe.security.approval

import io.mockk.mockk
import org.junit.jupiter.api.Test

class ApprovalGateTest {
    @Test
    fun `triggerEstop sets flag`() {
        val repo = mockk<PendingApprovalsRepo>()
        val gate = ApprovalGate(repo, ttlMillis = 60_000)

        gate.triggerEstop()

        org.junit.jupiter.api.Assertions
            .assertTrue(gate.estopTriggered)
    }

    @Test
    fun `estopFlag is initially false`() {
        val repo = mockk<PendingApprovalsRepo>()
        val gate = ApprovalGate(repo, ttlMillis = 60_000)

        org.junit.jupiter.api.Assertions
            .assertFalse(gate.estopTriggered)
    }
}
