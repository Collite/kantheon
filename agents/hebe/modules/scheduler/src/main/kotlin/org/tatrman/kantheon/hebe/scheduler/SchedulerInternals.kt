@file:Suppress("MatchingDeclarationName", "NewLineAtEndOfFile")

package org.tatrman.kantheon.hebe.scheduler

import org.tatrman.kantheon.hebe.api.ApprovalGate
import org.tatrman.kantheon.hebe.api.ApprovalStatus
import org.tatrman.kantheon.hebe.api.LlmProvider
import org.tatrman.kantheon.hebe.api.MemoryStore
import org.tatrman.kantheon.hebe.api.Observer
import org.tatrman.kantheon.hebe.api.Tool
import org.tatrman.kantheon.hebe.core.compaction.PreemptivePruner
import org.tatrman.kantheon.hebe.core.cost.CostGuard
import org.tatrman.kantheon.hebe.tools.dispatch.ToolDispatcher
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonObject

data class Services(
    val memory: MemoryStore,
    val dispatcher: ToolDispatcher,
    val llmProvider: LlmProvider,
    val costGuard: CostGuard,
    val compactor: PreemptivePruner,
    val observer: Observer,
)

object DenyAllApprovalGate : ApprovalGate {
    override fun requestIfNeeded(
        tool: Tool,
        args: JsonObject,
        turnId: String,
        channel: String,
        threadExtId: String?,
    ) = flowOf(ApprovalStatus.Denied)

    override suspend fun awaitApproval(
        tool: Tool,
        args: JsonObject,
        turnId: String,
        channel: String,
        threadExtId: String?,
    ): Boolean = false

    override fun resolve(
        approvalId: String,
        approved: Boolean,
    ): Boolean = false
}
