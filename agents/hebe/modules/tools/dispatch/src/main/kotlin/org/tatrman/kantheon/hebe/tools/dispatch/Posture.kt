package org.tatrman.kantheon.hebe.tools.dispatch

/**
 * Tool posture (P2 Stage 2.4; architecture §6 layer 3, contracts §5.2). The
 * `tools.posture` axis decides whether the dangerous tool families run; the
 * `enable`/`disable` lists are per-instance opt-ins. Enforced at the
 * [ToolDispatcher] — a denied call is a **receipted** refusal (it flows through
 * the same receipt path as any state change; the mutation-funnel detekt rule
 * guards this).
 *
 * Decoupled from the `config` module's `Posture` enum on purpose (dispatch must
 * not depend on config); cli-app maps `axes.tools.posture` → [ToolPosture].
 */
enum class ToolPosture {
    /** Everything allowed except what `disable` removes (local/personal/server). */
    FULL,

    /** Only the safe families run; the dangerous four are off unless `enable`d (k8s). */
    RESTRICTED,
}

/** The tool families posture reasons about. */
enum class ToolFamily(
    val token: String,
) {
    SHELL("shell"),
    KUBECTL("kubectl"),
    GIT("git"),
    FILESYSTEM("filesystem"),
    MEMORY("memory"),
    HTTP("http"),
    WEB_SEARCH("web-search"),
    SCHEDULING("scheduling"),
    KANTHEON("kantheon"),
    OTHER("other"),
    ;

    companion object {
        /** The families `restricted` blocks (contracts §5.2). */
        val DANGEROUS = setOf(SHELL, KUBECTL, GIT, FILESYSTEM)

        /**
         * Classifies a tool by name (Hebe tags families by naming convention —
         * built-ins are named `shell`, `git_*`, `file_read`, `memory_*`, …). An
         * unrecognised name is [OTHER] (allowed under `restricted` — only the
         * explicit dangerous four are blocked).
         */
        fun of(toolName: String): ToolFamily {
            val n = toolName.lowercase()
            return when {
                n.contains("kubectl") || n.contains("kube") -> KUBECTL
                n == "shell" || n == "bash" || n == "sh" || n.contains("exec") || n.contains("command") || n.contains("shell") -> SHELL
                n == "git" || n.startsWith("git_") || n.startsWith("git.") -> GIT
                n.contains("file") || n.contains("fs_") || n.startsWith("fs.") || n.contains("dir") || n.contains("path") -> FILESYSTEM
                n.startsWith("memory") || n.startsWith("mem_") || n.contains("recall") || n.contains("remember") -> MEMORY
                n.contains("web_search") || n.contains("search_web") || n.contains("websearch") -> WEB_SEARCH
                n.startsWith("http") || n.contains("fetch") -> HTTP
                n.startsWith("schedule") || n.startsWith("routine") || n.startsWith("cron") -> SCHEDULING
                n.startsWith("kantheon") || n.startsWith("iris") -> KANTHEON
                else -> OTHER
            }
        }
    }
}

/** The dispatcher's decision for a tool under the active posture. */
sealed interface PostureDecision {
    data object Allow : PostureDecision

    data class Deny(
        val family: ToolFamily,
    ) : PostureDecision
}

/**
 * Resolves a tool name to [PostureDecision.Allow]/[PostureDecision.Deny] under
 * the posture + opt-in lists. `enable`/`disable` entries match either a family
 * token (`"git"`) or a specific tool name.
 */
class PostureGate(
    private val posture: ToolPosture,
    private val enable: Set<String> = emptySet(),
    private val disable: Set<String> = emptySet(),
) {
    fun decide(toolName: String): PostureDecision {
        val family = ToolFamily.of(toolName)
        // `disable` removes a tool/family under any posture (incl. full).
        if (toolName in disable || family.token in disable) return PostureDecision.Deny(family)
        val explicitlyEnabled = toolName in enable || family.token in enable
        return when (posture) {
            ToolPosture.FULL -> PostureDecision.Allow
            ToolPosture.RESTRICTED ->
                if (family in ToolFamily.DANGEROUS && !explicitlyEnabled) {
                    PostureDecision.Deny(family)
                } else {
                    PostureDecision.Allow
                }
        }
    }

    companion object {
        /** The default for callers that don't set a posture (full, no opt-ins). */
        fun unrestricted() = PostureGate(ToolPosture.FULL)
    }
}
