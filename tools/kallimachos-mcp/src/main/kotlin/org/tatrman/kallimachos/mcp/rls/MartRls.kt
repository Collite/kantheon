package org.tatrman.kallimachos.mcp.rls

/**
 * The caller identity resolved from the forwarded OBO bearer (Argos `bearer`
 * source, security §3.6 — never service identity). Roles drive the mart RLS
 * predicate.
 */
data class Identity(
    val userId: String,
    val roles: Set<String>,
)

/** A notebook's access-control facts (contracts §7 — owner + visibility_roles). */
data class NotebookAcl(
    val id: String,
    val ownerUserId: String,
    val visibilityRoles: List<String>,
)

/**
 * How a `library.*` tool is authorized at the RLS edge. Carried as a property of
 * the tool (not a hand-maintained `when(toolName)` parallel to dispatch) so a new
 * tool MUST declare its scope or be denied — the edge fails closed.
 */
enum class MartScope {
    /** Reads mart content — requires `notebookId` + a passing [MartRls.canRead]. */
    MART_READ,

    /** Mutates marts/ACLs — ops/admin role only. */
    ADMIN_WRITE,

    /** Returns only the caller's own visible marts — the store scopes by principal. */
    CALLER_SCOPED,
}

/**
 * The mart RLS predicate (contracts §7): a caller may read mart `N` iff
 * `N.owner_user_id == caller.user_id` OR `N.visibility_roles ∩ caller_roles ≠ ∅`.
 * The `"*"` admin scope is allowed only for an admin role. Enforced at the
 * kallimachos-mcp edge BEFORE the store is touched; the store filters by the
 * scoped mart as defence-in-depth (architecture §9).
 */
object MartRls {
    const val ADMIN_SCOPE = "*"
    const val ADMIN_ROLE = "kantheon-admin"

    /**
     * The tool → scope map. Browse reads (getSource/getPage/traverse) are
     * MART_READ — they carry `notebookId` and are scoped exactly like getContext;
     * the store cross-checks node↔mart membership as defence-in-depth. A tool not
     * listed here is denied (fail closed).
     */
    val toolScopes: Map<String, MartScope> =
        mapOf(
            "library.getContext" to MartScope.MART_READ,
            "library.search" to MartScope.MART_READ,
            "library.findSimilar" to MartScope.MART_READ,
            "library.getSource" to MartScope.MART_READ,
            "library.getPage" to MartScope.MART_READ,
            "library.traverse" to MartScope.MART_READ,
            "library.createNotebook" to MartScope.ADMIN_WRITE,
            "library.addToNotebook" to MartScope.ADMIN_WRITE,
            "library.listNotebooks" to MartScope.CALLER_SCOPED,
        )

    fun scopeOf(toolName: String): MartScope? = toolScopes[toolName]

    fun canRead(
        caller: Identity,
        acl: NotebookAcl,
    ): Boolean =
        // An empty owner/userId must never match — an owner-less mart or an
        // anonymous (un-decodable bearer) caller would otherwise pass via "" == "".
        (caller.userId.isNotBlank() && acl.ownerUserId.isNotBlank() && acl.ownerUserId == caller.userId) ||
            acl.visibilityRoles.any { it in caller.roles }

    fun canReadAdminScope(caller: Identity): Boolean = ADMIN_ROLE in caller.roles
}
