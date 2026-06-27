package org.tatrman.kantheon.sysifos.bff.session

import com.google.protobuf.util.Timestamps
import org.tatrman.kantheon.sysifos.v1.SysifosSession
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Session surface (contracts §3.1). Sysifos has no database — a session is a
 * lightweight, in-memory handle scoping a user's draft scratch and SSE stream.
 * A session is visible only to its owning user. Draft scratch durability across
 * refresh is a v1.x concern (plan §5 risk); the store is in-memory for v1.
 */
interface SessionStore {
    /** A brand-new session for the caller; becomes the caller's current session. */
    fun create(
        userId: String,
        tenantId: String,
    ): SysifosSession

    /** The caller's current session, created on first read (get-or-create). */
    fun current(
        userId: String,
        tenantId: String,
    ): SysifosSession

    /** A session by id, only if owned by [userId] (else null — never leak existence). */
    fun get(
        sessionId: String,
        userId: String,
    ): SysifosSession?
}

class InMemorySessionStore : SessionStore {
    private val byId = ConcurrentHashMap<String, SysifosSession>()

    // The current session is scoped to (userId, tenantId): a user active in two
    // tenants must not have one tenant's session (and its draft scratch + SSE
    // stream) reused for the other.
    private val currentByUserTenant = ConcurrentHashMap<String, String>()

    private fun userTenantKey(
        userId: String,
        tenantId: String,
    ) = "$userId\u0000$tenantId"

    override fun create(
        userId: String,
        tenantId: String,
    ): SysifosSession {
        val now = Timestamps.fromMillis(System.currentTimeMillis())
        val session =
            SysifosSession
                .newBuilder()
                .setSessionId(UUID.randomUUID().toString())
                .setUserId(userId)
                .setTenantId(tenantId)
                .setCreatedAt(now)
                .setLastActiveAt(now)
                .build()
        byId[session.sessionId] = session
        currentByUserTenant[userTenantKey(userId, tenantId)] = session.sessionId
        return session
    }

    override fun current(
        userId: String,
        tenantId: String,
    ): SysifosSession =
        currentByUserTenant[userTenantKey(userId, tenantId)]?.let { byId[it] } ?: create(userId, tenantId)

    override fun get(
        sessionId: String,
        userId: String,
    ): SysifosSession? = byId[sessionId]?.takeIf { it.userId == userId }
}
