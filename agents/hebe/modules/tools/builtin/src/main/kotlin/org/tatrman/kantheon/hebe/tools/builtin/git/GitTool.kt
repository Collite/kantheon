package org.tatrman.kantheon.hebe.tools.builtin.git

import org.tatrman.kantheon.hebe.api.RiskLevel
import org.tatrman.kantheon.hebe.api.Tool
import org.tatrman.kantheon.hebe.api.ToolContext
import org.tatrman.kantheon.hebe.api.ToolResult
import org.tatrman.kantheon.hebe.api.ToolSpec
import java.io.File
import java.nio.file.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.Status
import org.slf4j.LoggerFactory

class GitTool(
    private val workspaceRoot: Path,
) : Tool {
    private val logger = LoggerFactory.getLogger(javaClass)

    override val spec =
        ToolSpec(
            name = "git",
            description =
                "Git operations: clone, status, diff, log, branch, commit. " +
                    "High-risk operations (clone) require approval at Supervised level.",
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
                                    put("description", JsonPrimitive("git verb: clone | status | diff | log | branch | commit"))
                                },
                            )
                            put(
                                "url",
                                buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                    put("description", JsonPrimitive("URL for clone"))
                                },
                            )
                            put(
                                "dir",
                                buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                    put("description", JsonPrimitive("Repo directory for non-clone ops"))
                                },
                            )
                            put(
                                "message",
                                buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                    put("description", JsonPrimitive("Commit message"))
                                },
                            )
                        },
                    )
                },
            pathScope = org.tatrman.kantheon.hebe.api.PathScope.ConfiguredRoots,
        )

    override val risk = RiskLevel.Medium

    override val readOnly: Boolean = false

    override fun effectiveRequiresApproval(args: JsonObject): Boolean = args["verb"]?.jsonPrimitive?.content?.lowercase() == "clone"

    override suspend fun invoke(
        args: JsonObject,
        ctx: ToolContext,
    ): ToolResult {
        val verb =
            args["verb"]?.jsonPrimitive?.content
                ?: return ToolResult.Err("missing required argument: verb")

        logger.debug("git verb={}", verb)

        return when (verb.lowercase()) {
            "clone" -> gitClone(args)
            "status" -> gitStatus(args)
            "diff" -> gitDiff(args)
            "log" -> gitLog(args)
            "branch" -> gitBranch(args)
            "commit" -> gitCommit(args)
            else -> ToolResult.Err("unknown git verb: $verb")
        }
    }

    private fun verbFromArgs(args: JsonObject): String = args["verb"]?.jsonPrimitive?.content ?: ""

    private fun gitClone(args: JsonObject): ToolResult {
        val url =
            args["url"]?.jsonPrimitive?.content
                ?: return ToolResult.Err("missing required argument: url")
        val dirStr = args["dir"]?.jsonPrimitive?.content ?: return ToolResult.Err("missing required argument: dir")

        return try {
            val targetDir = workspaceRoot.resolve(dirStr).toFile()
            Git
                .cloneRepository()
                .setURI(url)
                .setDirectory(targetDir)
                .setCloneAllBranches(false)
                .call()
            ToolResult.Ok(JsonPrimitive("cloned to: $dirStr"))
        } catch (e: Exception) {
            ToolResult.Err("clone failed: ${e.message}")
        }
    }

    private fun gitStatus(args: JsonObject): ToolResult {
        val dir = resolveRepoDir(args) ?: return ToolResult.Err("dir is required for status")
        return try {
            val git = Git.open(dir)
            val status: Status = git.status().call()
            val result =
                buildJsonObject {
                    put("clean", JsonPrimitive(status.isClean))
                    put("added", buildJsonArray { status.added.forEach { add(JsonPrimitive(it)) } })
                    put("changed", buildJsonArray { status.changed.forEach { add(JsonPrimitive(it)) } })
                    put("modified", buildJsonArray { status.modified.forEach { add(JsonPrimitive(it)) } })
                    put("untracked", buildJsonArray { status.untracked.forEach { add(JsonPrimitive(it)) } })
                    put("removed", buildJsonArray { status.removed.forEach { add(JsonPrimitive(it)) } })
                }
            ToolResult.Ok(result)
        } catch (e: Exception) {
            ToolResult.Err("status failed: ${e.message}")
        }
    }

    private fun gitDiff(args: JsonObject): ToolResult {
        val dir = resolveRepoDir(args) ?: return ToolResult.Err("dir is required for diff")
        return try {
            val git = Git.open(dir)
            val diff = git.diff().call()
            val result =
                buildJsonObject {
                    put("count", JsonPrimitive(diff.size))
                    put(
                        "diffs",
                        buildJsonArray {
                            diff.forEach { d ->
                                add(
                                    buildJsonObject {
                                        put("oldId", JsonPrimitive(d.oldId.name()))
                                        put("newId", JsonPrimitive(d.newId.name()))
                                    },
                                )
                            }
                        },
                    )
                }
            ToolResult.Ok(result)
        } catch (e: Exception) {
            ToolResult.Err("diff failed: ${e.message}")
        }
    }

    private fun gitLog(args: JsonObject): ToolResult {
        val dir = resolveRepoDir(args) ?: return ToolResult.Err("dir is required for log")
        val limit = args["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 20
        return try {
            val git = Git.open(dir)
            val commits =
                git
                    .log()
                    .setMaxCount(limit)
                    .call()
                    .toList()
            val result =
                buildJsonArray {
                    commits.forEach { commit ->
                        add(
                            buildJsonObject {
                                put("sha", JsonPrimitive(commit.id.abbreviate(7).name()))
                                put("message", JsonPrimitive(commit.fullMessage))
                                put("author", JsonPrimitive(commit.authorIdent.name))
                                put("time", JsonPrimitive(commit.commitTime.toLong()))
                            },
                        )
                    }
                }
            ToolResult.Ok(result)
        } catch (e: Exception) {
            ToolResult.Err("log failed: ${e.message}")
        }
    }

    private fun gitBranch(args: JsonObject): ToolResult {
        val dir = resolveRepoDir(args) ?: return ToolResult.Err("dir is required for branch")
        return try {
            val git = Git.open(dir)
            val branches = git.branchList().call()
            val result =
                buildJsonArray {
                    branches.forEach { ref ->
                        add(JsonPrimitive(ref.name))
                    }
                }
            ToolResult.Ok(result)
        } catch (e: Exception) {
            ToolResult.Err("branch list failed: ${e.message}")
        }
    }

    private fun gitCommit(args: JsonObject): ToolResult {
        val dir = resolveRepoDir(args) ?: return ToolResult.Err("dir is required for commit")
        val message =
            args["message"]?.jsonPrimitive?.content
                ?: return ToolResult.Err("missing required argument: message")
        val pathsArg = args["paths"]?.jsonPrimitive?.content

        return try {
            val git = Git.open(dir)
            if (pathsArg != null) {
                git.add().addFilepattern(pathsArg).call()
            } else {
                git.add().addFilepattern(".").call()
            }
            val commit = git.commit().setMessage(message).call()
            ToolResult.Ok(JsonPrimitive("committed: ${commit.id.abbreviate(7).name()}"))
        } catch (e: Exception) {
            ToolResult.Err("commit failed: ${e.message}")
        }
    }

    private fun resolveRepoDir(args: JsonObject): File? {
        val dirStr = args["dir"]?.jsonPrimitive?.content ?: return workspaceRoot.toFile()
        val absPath = workspaceRoot.resolve(dirStr)
        val normalized = absPath.normalize()
        val rootRealPath =
            try {
                workspaceRoot.toRealPath()
            } catch (_: Exception) {
                workspaceRoot.toAbsolutePath()
            }
        if (!normalized.startsWith(rootRealPath)) {
            return null
        }
        return absPath.toFile()
    }
}
