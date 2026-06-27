@file:Suppress("MaxLineLength", "NewLineAtEndOfFile")

package org.tatrman.kantheon.hebe.scheduler.maintenance

import org.tatrman.kantheon.hebe.memory.db.DbFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.sql.Timestamp
import kotlinx.coroutines.test.runTest

private fun fakeLlmProvider() =
    object : org.tatrman.kantheon.hebe.api.LlmProvider {
        override suspend fun chat(req: org.tatrman.kantheon.hebe.api.ChatRequest) =
            kotlinx.coroutines.flow.flowOf(
                org.tatrman.kantheon.hebe.api.StreamEvent
                    .TextDelta("This is a summary of the conversation."),
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
        conn.prepareStatement("INSERT INTO conversations(id, channel, user_id, started_at) VALUES (?, ?, ?, ?)").use { ps ->
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
        conn.prepareStatement("INSERT INTO messages(id, conversation_id, role, content, ts) VALUES (?, ?, ?, ?, ?)").use { ps ->
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

class SummariserTest :
    StringSpec({
        "loadActiveConversations finds conversations with messages" {
            runTest {
                val db = DbFactory.openInMemory()
                insertConversation(db, "conv1")
                insertMessage(db, "conv1", "user", "hello world test content here", System.currentTimeMillis() - 3600_000)
                val summariser = Summariser(db, fakeLlmProvider())
                val result = summariser.run()
                result.isSuccess shouldBe true
                db.close()
            }
        }

        "summariser skips conversations with no messages" {
            runTest {
                val db = DbFactory.openInMemory()
                insertConversation(db, "empty-conv")
                val summariser = Summariser(db, fakeLlmProvider())
                val result = summariser.run()
                result.isSuccess shouldBe true
                result.getOrNull() shouldBe 0
                db.close()
            }
        }

        "summariser processes conversations with sufficient messages" {
            runTest {
                val db = DbFactory.openInMemory()
                insertConversation(db, "conv1")
                val baseTs = System.currentTimeMillis() - 3600_000
                repeat(5) { i ->
                    insertMessage(
                        db,
                        "conv1",
                        "user",
                        "message content number $i with sufficient tokens to pass threshold",
                        baseTs + i * 60_000,
                    )
                }
                val summariser = Summariser(db, fakeLlmProvider())
                val result = summariser.run()
                result.isSuccess shouldBe true
                db.close()
            }
        }
    })
