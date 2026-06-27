package org.tatrman.kantheon.hebe.tools.builtin.k8s

import org.tatrman.kantheon.hebe.api.RiskLevel
import org.tatrman.kantheon.hebe.api.Tool
import org.tatrman.kantheon.hebe.api.ToolContext
import org.tatrman.kantheon.hebe.api.ToolResult
import org.tatrman.kantheon.hebe.api.ToolSpec
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

class KubectlTool(
    private val workspaceRoot: Path,
) : Tool {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val MUTATING_VERBS =
        setOf(
            "apply",
            "create",
            "delete",
            "patch",
            "replace",
            "scale",
            "rollout",
            "cordon",
            "drain",
            "uncordon",
            "taint",
            "label",
            "annotate",
            "exec",
            "port-forward",
            "set",
            "edit",
        )

    override val spec =
        ToolSpec(
            name = "kubectl",
            description =
                "Run kubectl commands. Read-only verbs are Medium risk. " +
                    "Mutating verbs are High risk and always require approval.",
            schema =
                buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("required", buildJsonArray { add(JsonPrimitive("verb")) })
                    put(
                        "properties",
                        buildJsonObject {
                            put(
                                "verb",
                                buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                    put("description", JsonPrimitive("kubectl verb (get, describe, apply, delete, etc.)"))
                                },
                            )
                            put(
                                "args",
                                buildJsonObject {
                                    put("type", JsonPrimitive("array"))
                                    put("description", JsonPrimitive("Additional kubectl arguments"))
                                },
                            )
                            put(
                                "kubeconfig",
                                buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                    put("description", JsonPrimitive("Optional path to kubeconfig file"))
                                },
                            )
                            put(
                                "context",
                                buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                    put("description", JsonPrimitive("Optional kubeconfig context"))
                                },
                            )
                        },
                    )
                },
            pathScope = org.tatrman.kantheon.hebe.api.PathScope.WorkspaceOnly,
        )

    override val risk = RiskLevel.Medium

    override val readOnly: Boolean = false

    override fun effectiveRequiresApproval(args: JsonObject): Boolean {
        val verb = args["verb"]?.jsonPrimitive?.content ?: return true
        return verb in MUTATING_VERBS
    }

    override suspend fun invoke(
        args: JsonObject,
        ctx: ToolContext,
    ): ToolResult {
        val verb =
            args["verb"]?.jsonPrimitive?.content
                ?: return ToolResult.Err("missing required argument: verb")
        val extraArgsArr =
            args["args"]?.let { arg ->
                if (arg is kotlinx.serialization.json.JsonArray) {
                    arg.mapNotNull { it.jsonPrimitive?.content }
                } else {
                    null
                }
            } ?: emptyList()
        val kubeconfig = args["kubeconfig"]?.jsonPrimitive?.content
        val context = args["context"]?.jsonPrimitive?.content

        logger.debug("kubectl verb={}", verb)

        val cmdArgs = buildKubectlArgsList(verb, extraArgsArr, kubeconfig, context)

        return runKubectl(cmdArgs)
    }

    private fun runKubectl(cmdArgs: List<String>): ToolResult {
        return try {
            val processBuilder = ProcessBuilder(cmdArgs)
            processBuilder.directory(workspaceRoot.toFile())
            val process = processBuilder.start()

            val completed = process.waitFor(120, TimeUnit.SECONDS)
            if (!completed) {
                process.destroy()
                try {
                    Thread.sleep(1000)
                } catch (_: Exception) {
                }
                if (process.isAlive) process.destroyForcibly()
                return ToolResult.Err("timeout after 120s")
            }

            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.exitValue()

            if (exitCode == 0) {
                ToolResult.Ok(
                    buildJsonObject {
                        put("stdout", JsonPrimitive(stdout))
                        put("stderr", JsonPrimitive(stderr))
                        put("exitCode", JsonPrimitive(exitCode))
                    },
                )
            } else {
                ToolResult.Err("kubectl failed: ${stderr.take(500)}")
            }
        } catch (e: Exception) {
            ToolResult.Err("kubectl error: ${e.message}")
        }
    }

    private fun buildKubectlArgsList(
        verb: String,
        extraArgs: List<String>,
        kubeconfig: String?,
        context: String?,
    ): List<String> {
        val parts = mutableListOf<String>()
        parts.add("kubectl")
        kubeconfig?.let { parts.addAll(listOf("--kubeconfig", it)) }
        context?.let { parts.addAll(listOf("--context", it)) }
        parts.add(verb)
        parts.addAll(extraArgs)
        return parts
    }
}
