@file:Suppress("MaxLineLength", "NewLineAtEndOfFile")

package org.tatrman.kantheon.hebe.scheduler.maintenance

import org.tatrman.kantheon.hebe.memory.db.DbFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.sql.Timestamp
import kotlinx.coroutines.test.runTest

class DailyDigestTest :
    StringSpec({
        "run on empty day returns false" {
            runTest {
                val db = DbFactory.openInMemory()
                val digest = DailyDigest(db, fakeLlmProvider())
                val result = digest.run()

                result.isSuccess shouldBe true
                result.getOrNull() shouldBe false
                db.close()
            }
        }

        "run with yesterday messages writes digest" {
            runTest {
                val db = DbFactory.openInMemory()
                insertConversation(db, "conv1")
                val yesterday = System.currentTimeMillis() - 86400_000
                insertMessage(db, "conv1", "user", "hello world test message", yesterday)

                val digest = DailyDigest(db, fakeLlmProvider())
                val result = digest.run()

                result.isSuccess shouldBe true
                db.close()
            }
        }

        "run with no messages does not write digest" {
            runTest {
                val db = DbFactory.openInMemory()
                val digest = DailyDigest(db, fakeLlmProvider())
                val result = digest.run()

                result.isSuccess shouldBe true
                result.getOrNull() shouldBe false
                db.close()
            }
        }
    })

private fun fakeLlmProvider() =
    object : org.tatrman.kantheon.hebe.api.LlmProvider {
        override suspend fun chat(req: org.tatrman.kantheon.hebe.api.ChatRequest) =
            kotlinx.coroutines.flow.flowOf(
                org.tatrman.kantheon.hebe.api.StreamEvent
                    .TextDelta("# Daily Digest 2025-06-15\n\n## Conversations\n- test\n"),
                org.tatrman.kantheon.hebe.api.StreamEvent.Done,
            )

        override fun capabilities() =
            org.tatrman.kantheon.hebe.api.ProviderCapabilities(streaming = false, toolUse = false, multimodal = false, maxContextTokens = 0)
    }

private fun insertConversation(
    db: org.tatrman.kantheon.hebe.memory.db.Db,
    id: String,
) {
    db.dataSource.connection.use { conn ->
        conn
            .prepareStatement(
                "INSERT INTO conversations(id, channel, user_id, started_at) VALUES (?, ?, ?, ?)",
            ).use { ps ->
                ps.setString(1, id)
                ps.setString(2, "test")
                ps.setString(3, "testuser")
                ps.setTimestamp(4, Timestamp(System.currentTimeMillis()))
                ps.executeUpdate()
            }
    }
}

private fun insertMessage(
    db: org.tatrman.kantheon.hebe.memory.db.Db,
    convId: String,
    role: String,
    content: String,
    ts: Long,
) {
    db.dataSource.connection.use { conn ->
        conn
            .prepareStatement(
                "INSERT INTO messages(id, conversation_id, role, content, ts) VALUES (?, ?, ?, ?, ?)",
            ).use { ps ->
                ps.setString(
                    1,
                    java.util.UUID
                        .randomUUID()
                        .toString(),
                )
                ps.setString(2, convId)
                ps.setString(3, role)
                ps.setString(4, content)
                ps.setTimestamp(5, Timestamp(ts))
                ps.executeUpdate()
            }
    }
}
