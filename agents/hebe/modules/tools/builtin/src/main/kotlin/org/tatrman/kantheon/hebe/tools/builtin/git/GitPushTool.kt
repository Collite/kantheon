package org.tatrman.kantheon.hebe.tools.builtin.git

import org.tatrman.kantheon.hebe.api.RiskLevel
import org.tatrman.kantheon.hebe.api.Tool
import org.tatrman.kantheon.hebe.api.ToolContext
import org.tatrman.kantheon.hebe.api.ToolResult
import org.tatrman.kantheon.hebe.api.ToolSpec
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.eclipse.jgit.lib.RepositoryBuilder
import org.slf4j.LoggerFactory

class GitPushTool(
    private val workspaceRoot: Path,
) : Tool {
    private val logger = LoggerFactory.getLogger(javaClass)

    override val spec =
        ToolSpec(
            name = "git_push",
            description = "Push to a remote git repository using shell-out. Always requires approval.",
            schema =
                buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put(
                        "properties",
                        buildJsonObject {
                            put(
                                "dir",
                                buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                    put("description", JsonPrimitive("Repo directory (default: workspace root)"))
                                },
                            )
                            put(
                                "remote",
                                buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                    put("description", JsonPrimitive("Remote name (default: origin)"))
                                    put("default", JsonPrimitive("origin"))
                                },
                            )
                            put(
                                "branch",
                                buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                    put("description", JsonPrimitive("Branch to push (default: current branch)"))
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
        val dirStr = args["dir"]?.jsonPrimitive?.content
        val remote = args["remote"]?.jsonPrimitive?.content ?: "origin"
        val branch = args["branch"]?.jsonPrimitive?.content

        val cwd =
            if (dirStr != null) {
                val absPath = workspaceRoot.resolve(dirStr)
                val normalized = absPath.normalize()
                val rootRealPath =
                    try {
                        workspaceRoot.toRealPath()
                    } catch (_: Exception) {
                        workspaceRoot.toAbsolutePath()
                    }
                if (!normalized.startsWith(rootRealPath)) {
                    return ToolResult.Err("dir outside workspace: $dirStr")
                }
                absPath
            } else {
                workspaceRoot
            }

        logger.debug("git_push dir={} remote={} branch={}", cwd, remote, branch)

        val isGitRepo = RepositoryBuilder().findGitDir(cwd.toFile()) != null
        if (!isGitRepo) {
            return ToolResult.Err("not a git repo: $cwd")
        }

        // Build args list — no shell interpolation, so any remote/branch value is safe
        val cmdArgs = mutableListOf("git", "push", remote)
        if (branch != null) cmdArgs.add(branch)

        return withContext(Dispatchers.IO) {
            try {
                val process =
                    ProcessBuilder(cmdArgs)
                        .directory(cwd.toFile())
                        .start()

                val completed = process.waitFor(120, TimeUnit.SECONDS)
                if (!completed) {
                    process.destroy()
                    try {
                        Thread.sleep(1_000)
                    } catch (_: Exception) {
                    }
                    if (process.isAlive) process.destroyForcibly()
                    return@withContext ToolResult.Err("push timed out after 120s")
                }

                val stdout = process.inputStream.bufferedReader().readText()
                val stderr = process.errorStream.bufferedReader().readText()
                val exitCode = process.exitValue()

                if (exitCode == 0) {
                    ToolResult.Ok(
                        buildJsonObject {
                            put("stdout", JsonPrimitive(stdout))
                            put("stderr", JsonPrimitive(stderr))
                        },
                    )
                } else {
                    ToolResult.Err("push failed: ${stderr.take(500)}")
                }
            } catch (e: Exception) {
                ToolResult.Err("push error: ${e.message}")
            }
        }
    }
}
