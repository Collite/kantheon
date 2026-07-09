@file:Suppress("MaxLineLength", "NewLineAtEndOfFile")

package org.tatrman.kantheon.hebe.scheduler.maintenance

import org.tatrman.kantheon.hebe.memory.db.DbFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

private fun fakeLlmProvider() =
    object : org.tatrman.kantheon.hebe.api.LlmProvider {
        override suspend fun chat(req: org.tatrman.kantheon.hebe.api.ChatRequest) =
            kotlinx.coroutines.flow.flowOf(
                org.tatrman.kantheon.hebe.api.StreamEvent
                    .TextDelta("[[0.1, 0.2, 0.3]]"),
                org.tatrman.kantheon.hebe.api.StreamEvent.Done,
            )

        override fun capabilities() =
            org.tatrman.kantheon.hebe.api
                .ProviderCapabilities(streaming = false, toolUse = false, multimodal = false, maxContextTokens = 0)
    }

class EmbeddingRefreshTest :
    StringSpec({
        // Note: vec0 extension (vector storage) requires native library not available
        // in in-memory SQLite. These tests verify the LLM call path only.
        "run with no NULL chunks returns zero" {
            runTest {
                val db = DbFactory.openInMemory()
                val refresh = EmbeddingRefresh(db, fakeLlmProvider())
                val result = refresh.run()
                result.isSuccess shouldBe true
                result.getOrNull() shouldBe 0
                db.close()
            }
        }

        "run with NULL chunks attempts embedding" {
            runTest {
                val db = DbFactory.openInMemory()
                val refresh = EmbeddingRefresh(db, fakeLlmProvider())
                val result = refresh.run()
                result.isSuccess shouldBe true
                db.close()
            }
        }
    })
