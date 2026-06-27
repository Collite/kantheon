package org.tatrman.kantheon.hebe.tools.builtin.shell

import org.tatrman.kantheon.hebe.api.RiskLevel
import org.tatrman.kantheon.hebe.api.Tool
import org.tatrman.kantheon.hebe.api.ToolContext
import org.tatrman.kantheon.hebe.api.ToolResult
import org.tatrman.kantheon.hebe.api.ToolSpec
import org.tatrman.kantheon.hebe.api.workspace.WorkspacePath
import org.tatrman.kantheon.hebe.security.estop.EmergencyStop
import java.nio.file.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

class ShellTool(
    private val workspaceRoot: Path,
    private val emergencyStop: EmergencyStop? = null,
) : Tool {
    private val logger = LoggerFactory.getLogger(javaClass)

    override val spec =
        ToolSpec(
            name = "shell",
            description =
                "Run a shell command via bash. Approval required at Supervised autonomy level. " +
                    "Note: v1 targets macOS/Linux only; Windows is deferred to v1.1.",
            schema =
                buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("required", buildJsonArray { add(JsonPrimitive("cmd")) })
                    put(
                        "properties",
                        buildJsonObject {
                            put(
                                "cmd",
                                buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                    put("description", JsonPrimitive("Shell command to execute"))
                                },
                            )
                            put(
                                "cwd",
                                buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                    put("description", JsonPrimitive("Working directory (default: workspace root)"))
                                },
                            )
                            put(
                                "timeout_ms",
                                buildJsonObject {
                                    put("type", JsonPrimitive("integer"))
                                    put("description", JsonPrimitive("Timeout in milliseconds (default: 60000, max: 600000)"))
                                    put("default", JsonPrimitive(60000))
                                },
                            )
                        },
                    )
                },
            pathScope = org.tatrman.kantheon.hebe.api.PathScope.WorkspaceOnly,
        )

    override val risk = RiskLevel.High
    override val readOnly = false

    override suspend fun invoke(
        args: JsonObject,
        ctx: ToolContext,
    ): ToolResult {
        val cmd =
            args["cmd"]?.jsonPrimitive?.content
                ?: return ToolResult.Err("missing required argument: cmd")

        val cwdStr = args["cwd"]?.jsonPrimitive?.content
        val timeoutMs = args["timeout_ms"]?.jsonPrimitive?.content?.toLongOrNull() ?: 60_000L

        val cwd =
            if (cwdStr != null) {
                val wp = WorkspacePath(cwdStr)
                val absPath = workspaceRoot.resolve(wp.value)
                val normalized = absPath.normalize()
                val rootRealPath =
                    try {
                        workspaceRoot.toRealPath()
                    } catch (_: Exception) {
                        workspaceRoot.toAbsolutePath()
                    }
                if (!normalized.startsWith(rootRealPath)) {
                    return ToolResult.Err("cwd outside workspace: $cwdStr")
                }
                absPath
            } else {
                workspaceRoot
            }

        logger.debug("shell cmd={} cwd={} timeout={}ms", cmd, cwd, timeoutMs)

        val result = ProcessRunner.run(cmd, cwd, timeoutMs, emptyMap(), emergencyStop)

        return if (result.timedOut) {
            ToolResult.Err("timeout after ${timeoutMs}ms")
        } else if (result.exitCode == 0) {
            ToolResult.Ok(
                buildJsonObject {
                    put("stdout", JsonPrimitive(result.stdout))
                    put("stderr", JsonPrimitive(result.stderr))
                    put("exitCode", JsonPrimitive(result.exitCode))
                },
            )
        } else {
            ToolResult.Err("exit ${result.exitCode}: ${result.stderr.take(500)}")
        }
    }
}
