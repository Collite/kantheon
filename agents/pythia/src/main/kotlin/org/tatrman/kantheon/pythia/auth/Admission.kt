package org.tatrman.kantheon.pythia.auth

/** The authenticated caller (PD-8) — user identity + role set from the bearer. */
data class Principal(
    val userId: String,
    val roles: Set<String>,
)

/**
 * Request admission (PD-8, kantheon-security §3.3): validate the inbound bearer
 * and resolve the caller principal. Phase 1 ships a structural stub —
 * `Bearer <userId>` or `Bearer <userId>#role1,role2`; a blank/absent bearer is
 * rejected. Real JWKS validation against Keycloak is integration-deferred
 * (planning-conventions §4); the route surface (403 on reject, role-filtered
 * reads) is identical.
 */
fun interface Admission {
    fun authenticate(bearer: String?): Principal?
}

class BearerAdmission : Admission {
    override fun authenticate(bearer: String?): Principal? {
        val raw =
            bearer
                ?.removePrefix("Bearer ")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return null
        val (uid, rolePart) = raw.split("#", limit = 2).let { it[0] to it.getOrNull(1) }
        if (uid.isBlank()) return null
        val roles =
            rolePart
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?.toSet() ?: emptySet()
        return Principal(uid, roles)
    }
}

/** Roles that may read any user's investigation (the constrain-and-disclose admin path). */
val ADMIN_ROLES = setOf("kantheon-admin", "pythia-admin")

/** May [principal] read an investigation owned by [ownerUserId]? (PD-8 visibility re-check.) */
fun Principal.canRead(ownerUserId: String): Boolean = userId == ownerUserId || isAdmin()

/** Whether the principal holds an admin role (may read/list across users). */
fun Principal.isAdmin(): Boolean = roles.any { it in ADMIN_ROLES }
