package org.tatrman.kantheon.iris.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import org.tatrman.kantheon.iris.audit.AuditRecord
import org.tatrman.kantheon.iris.audit.AuditStore
import org.tatrman.kantheon.iris.audit.Ed25519Signer
import org.tatrman.kantheon.iris.audit.verifySegment

@Serializable
data class AuditVerifyDto(
    val segment: String,
    val ok: Boolean,
    val count: Int,
    val brokenAtSeq: Long? = null,
)

/**
 * Audit completion (PD-8, contracts §3.1): `GET /v1/audit/verify?segment=YYYY-MM`
 * recomputes a monthly segment's hash chain + signatures so a rotated-out segment
 * can be archived with confidence. Admin-gated (the `iris.audit.admin-role`
 * bearer role; default `iris-admin`). Each segment is anchored independently, so
 * verifying one does not require the others (kantheon-security §4.3).
 */
fun Route.auditRoutes(
    audit: AuditStore,
    signer: Ed25519Signer,
    auth: BearerAuthenticator,
    adminRole: String = "iris-admin",
) {
    get("/v1/audit/verify") {
        val caller = call.requireCaller(auth) ?: return@get
        if (!BearerRoles.hasRole(caller.bearer, adminRole)) {
            return@get call.respond(HttpStatusCode.Forbidden, ErrorBody("forbidden", "Requires the $adminRole role"))
        }
        val segment = call.request.queryParameters["segment"]
        if (segment.isNullOrBlank()) {
            return@get call.respond(HttpStatusCode.BadRequest, ErrorBody("bad_request", "segment=YYYY-MM is required"))
        }
        val all: List<AuditRecord> = audit.all().sortedBy { it.seq }
        val rows: List<AuditRecord> = all.filter { it.segment == segment }
        // Anchor the segment to its immediate neighbours so leading/trailing
        // deletion (not just in-place mutation) is caught (see [verifySegment]).
        val priorRecord = rows.firstOrNull()?.let { first -> all.lastOrNull { it.seq < first.seq } }
        val nextRecord = rows.lastOrNull()?.let { last -> all.firstOrNull { it.seq > last.seq } }
        val result = verifySegment(rows, signer, priorRecord, nextRecord)
        call.respond(AuditVerifyDto(segment, result.ok, rows.size, result.brokenAtSeq))
    }
}
