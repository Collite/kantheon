@file:OptIn(ExperimentalUuidApi::class)

package org.tatrman.kantheon.midas.core.repository

import org.jetbrains.exposed.v1.core.LikePattern
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.tatrman.kantheon.midas.core.infra.ClientsTable
import org.tatrman.kantheon.midas.core.infra.toProtoTimestamp
import org.tatrman.kantheon.midas.core.infra.toUuidColumn
import org.tatrman.kantheon.midas.core.infra.toUuidString
import org.tatrman.kantheon.midas.core.tenant.TenantContext
import org.tatrman.kantheon.midas.v1.Client
import org.tatrman.kantheon.midas.v1.ClientStatus
import shared.libs.db.common.DatabaseConnection
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi

/**
 * Clients repository (Stage 1.3). Every method runs through [TenantContext.
 * withTenant] so RLS scopes the query to the caller's tenant — the repository
 * never adds a manual `tenant_id =` filter; the database enforces it.
 */
class ClientRepository(
    private val db: DatabaseConnection,
    private val clock: () -> Instant = Instant::now,
) {
    fun create(
        tenantId: String,
        userId: String,
        client: Client,
    ): Client =
        TenantContext.withTenant(db, tenantId) {
            val id = UUID.randomUUID()
            val now = OffsetDateTime.ofInstant(clock(), ZoneOffset.UTC)
            ClientsTable.insert {
                it[clientId] = id.toString().toUuidColumn()
                it[ClientsTable.tenantId] = tenantId.toUuidColumn()
                it[name] = client.name
                it[contactEmail] = client.contactEmail.ifBlank { null }
                it[contactPhone] = client.contactPhone.ifBlank { null }
                it[status] = "ACTIVE"
                it[createdAt] = now
                it[createdByUserId] = userId
                it[updatedAt] = now
                it[updatedByUserId] = userId
            }
            getInTx(id) ?: error("client vanished after insert")
        }

    fun get(
        tenantId: String,
        id: UUID,
    ): Client? = TenantContext.withTenant(db, tenantId) { getInTx(id) }

    fun list(
        tenantId: String,
        page: Int,
        size: Int,
        status: String?,
        namePrefix: String?,
    ): Pair<List<Client>, Int> =
        TenantContext.withTenant(db, tenantId) {
            val safeSize = size.coerceIn(1, MAX_PAGE_SIZE)
            val safePage = page.coerceAtLeast(0)
            val query = ClientsTable.selectAll()
            status?.let { s -> query.andWhere { ClientsTable.status eq s } }
            namePrefix?.let { p -> query.andWhere { ClientsTable.name like (LikePattern.ofLiteral(p) + "%") } }
            val total = query.count().toInt()
            val pageRows =
                query
                    .orderBy(ClientsTable.name to SortOrder.ASC)
                    .limit(safeSize)
                    .offset(safePage.toLong() * safeSize)
                    .map { it.toClient() }
            pageRows to total
        }

    fun update(
        tenantId: String,
        userId: String,
        id: UUID,
        client: Client,
    ): Client? =
        TenantContext.withTenant(db, tenantId) {
            val now = OffsetDateTime.ofInstant(clock(), ZoneOffset.UTC)
            val updated =
                ClientsTable.update({ ClientsTable.clientId eq id.toString().toUuidColumn() }) {
                    it[name] = client.name
                    it[contactEmail] = client.contactEmail.ifBlank { null }
                    it[contactPhone] = client.contactPhone.ifBlank { null }
                    it[updatedAt] = now
                    it[updatedByUserId] = userId
                }
            if (updated == 0) null else getInTx(id)
        }

    fun archive(
        tenantId: String,
        userId: String,
        id: UUID,
    ): Client? =
        TenantContext.withTenant(db, tenantId) {
            val now = OffsetDateTime.ofInstant(clock(), ZoneOffset.UTC)
            val updated =
                ClientsTable.update({ ClientsTable.clientId eq id.toString().toUuidColumn() }) {
                    it[status] = "ARCHIVED"
                    it[updatedAt] = now
                    it[updatedByUserId] = userId
                }
            if (updated == 0) null else getInTx(id)
        }

    private fun getInTx(id: UUID): Client? =
        ClientsTable
            .selectAll()
            .where { ClientsTable.clientId eq id.toString().toUuidColumn() }
            .firstOrNull()
            ?.toClient()

    @Suppress("ktlint:standard:function-naming")
    private fun ResultRow.toClient(): Client {
        val b =
            Client
                .newBuilder()
                .setClientId(this[ClientsTable.clientId].toUuidString())
                .setTenantId(this[ClientsTable.tenantId].toUuidString())
                .setName(this[ClientsTable.name])
                .setStatus(ClientStatus.valueOf("CLIENT_" + this[ClientsTable.status]))
                .setCreatedAt(this[ClientsTable.createdAt].toProtoTimestamp())
                .setUpdatedAt(this[ClientsTable.updatedAt].toProtoTimestamp())
                .setCreatedByUserId(this[ClientsTable.createdByUserId])
                .setUpdatedByUserId(this[ClientsTable.updatedByUserId])
        this[ClientsTable.contactEmail]?.let { b.contactEmail = it }
        this[ClientsTable.contactPhone]?.let { b.contactPhone = it }
        return b.build()
    }
}
