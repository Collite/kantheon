package org.tatrman.kantheon.hebe.api

import org.tatrman.kantheon.hebe.api.workspace.WorkspacePath
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

interface Tool {
    val spec: ToolSpec
    val risk: RiskLevel
    val requiresApproval: Boolean get() = risk == RiskLevel.High
    val readOnly: Boolean get() = false

    /**
     * Per-invocation approval gate. Override in multi-verb tools where some verbs need
     * approval and others don't. The dispatcher calls this instead of [requiresApproval]
     * at Full autonomy level so the actual args are available.
     */
    fun effectiveRequiresApproval(args: JsonObject): Boolean = requiresApproval

    suspend fun invoke(
        args: JsonObject,
        ctx: ToolContext,
    ): ToolResult
}

@Serializable
data class ToolSpec(
    val name: String,
    val description: String,
    val schema: JsonObject,
    val pathScope: PathScope = PathScope.WorkspaceOnly,
)

interface ToolContext {
    val sessionId: String
    val turnId: String
    val userId: String
    val requestor: Channel
    val workspace: WorkspacePath
    val approvalGate: ApprovalGate
    val observer: Observer
    val secretLookup: SecretLookup
}

interface ApprovalGate {
    fun requestIfNeeded(
        tool: Tool,
        args: JsonObject,
        turnId: String,
        channel: String,
        threadExtId: String? = null,
    ): kotlinx.coroutines.flow.Flow<ApprovalStatus>

    suspend fun awaitApproval(
        tool: Tool,
        args: JsonObject,
        turnId: String,
        channel: String,
        threadExtId: String? = null,
    ): Boolean

    fun resolve(
        approvalId: String,
        approved: Boolean,
    ): Boolean
}

enum class ApprovalStatus {
    Pending,
    Approved,
    Denied,
    Expired,
}

@Serializable
sealed interface ToolResult {
    @Serializable
    @SerialName("ok")
    data class Ok(
        val content: JsonElement,
        val artifacts: List<Artifact> = emptyList(),
    ) : ToolResult

    @Serializable
    @SerialName("err")
    data class Err(
        val message: String,
        val retriable: Boolean = false,
    ) : ToolResult

    @Serializable
    @SerialName("needs_approval")
    data class NeedsApproval(
        val prompt: String,
        val payload: JsonObject,
    ) : ToolResult
}

@Serializable
data class Artifact(
    val mime: String,
    val bytes: ByteArray,
    val name: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Artifact
        if (mime != other.mime) return false
        if (!bytes.contentEquals(other.bytes)) return false
        if (name != other.name) return false
        return true
    }

    override fun hashCode(): Int {
        var result = mime.hashCode()
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + (name?.hashCode() ?: 0)
        return result
    }
}
