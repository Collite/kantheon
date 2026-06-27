package org.tatrman.kantheon.bffbase.tenant

import org.tatrman.kantheon.bffbase.auth.CallerIdentity

/**
 * Produces the `X-Tenant-Id` header that downstream services (Midas-core) read,
 * and guards the tenant-confinement invariant: a request must never name a
 * tenant other than the one in the caller's JWT. Identity is bearer-only at the
 * edge — the tenant travels as a forwarded header, never as service identity.
 */
object TenantHeaderForwarder {
    const val HEADER = "X-Tenant-Id"

    /** The `(name, value)` header pair to attach to a downstream call. */
    fun header(caller: CallerIdentity): Pair<String, String> = HEADER to caller.tenantId

    /**
     * True when a tenant named in the request path/body is absent or equals the
     * caller's JWT tenant. A mismatch must be rejected by the route (403
     * `TENANT_MISMATCH`) — Sysifos never lets a caller act across tenants.
     */
    fun matches(
        caller: CallerIdentity,
        requestedTenant: String?,
    ): Boolean = requestedTenant == null || requestedTenant == caller.tenantId
}
