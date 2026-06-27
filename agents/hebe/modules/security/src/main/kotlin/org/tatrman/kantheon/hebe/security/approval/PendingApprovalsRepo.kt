@file:Suppress("MagicNumber")

package org.tatrman.kantheon.hebe.security.approval

import java.time.Instant
import javax.sql.DataSource

class PendingApprovalsRepo(
    private val ds: DataSource,
) {
    fun insert(approval: PendingApproval) {
        ds.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    INSERT INTO pending_approvals
                    (id, turn_id, tool, args_redacted, prompt, channel, thread_ext_id, created_at, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { stmt ->
                    stmt.setString(1, approval.id)
                    stmt.setString(2, approval.turnId)
                    stmt.setString(3, approval.tool)
                    stmt.setString(4, approval.argsRedacted)
                    stmt.setString(5, approval.prompt)
                    stmt.setString(6, approval.channel)
                    stmt.setString(7, approval.threadExtId)
                    stmt.setLong(8, approval.createdAt.toEpochMilli())
                    stmt.setLong(9, approval.expiresAt.toEpochMilli())
                    stmt.executeUpdate()
                }
        }
    }

    fun resolve(
        id: String,
        approved: Boolean,
    ): Boolean {
        ds.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    UPDATE pending_approvals
                    SET resolved_at = ?, approved = ?
                    WHERE id = ? AND resolved_at IS NULL
                    """.trimIndent(),
                ).use { stmt ->
                    stmt.setLong(1, Instant.now().toEpochMilli())
                    stmt.setInt(2, if (approved) 1 else 0)
                    stmt.setString(3, id)
                    return stmt.executeUpdate() > 0
                }
        }
    }

    fun markExpired(id: String): Boolean {
        ds.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    UPDATE pending_approvals
                    SET resolved_at = ?, approved = 0
                    WHERE id = ? AND resolved_at IS NULL
                    """.trimIndent(),
                ).use { stmt ->
                    stmt.setLong(1, Instant.now().toEpochMilli())
                    stmt.setString(2, id)
                    return stmt.executeUpdate() > 0
                }
        }
    }

    fun findUnresolved(): List<PendingApproval> {
        val now = Instant.now().toEpochMilli()
        val result = mutableListOf<PendingApproval>()
        ds.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    SELECT id, turn_id, tool, args_redacted, prompt, channel, thread_ext_id, created_at, expires_at
                    FROM pending_approvals
                    WHERE resolved_at IS NULL AND expires_at < ?
                    """.trimIndent(),
                ).use { stmt ->
                    stmt.setLong(1, now)
                    val rs = stmt.executeQuery()
                    while (rs.next()) {
                        result.add(
                            PendingApproval(
                                id = rs.getString("id"),
                                turnId = rs.getString("turn_id"),
                                tool = rs.getString("tool"),
                                argsRedacted = rs.getString("args_redacted"),
                                prompt = rs.getString("prompt"),
                                channel = rs.getString("channel"),
                                threadExtId = rs.getString("thread_ext_id"),
                                createdAt = Instant.ofEpochMilli(rs.getLong("created_at")),
                                expiresAt = Instant.ofEpochMilli(rs.getLong("expires_at")),
                            ),
                        )
                    }
                }
        }
        return result
    }

    fun scanUnresolved(): List<PendingApproval> {
        val result = mutableListOf<PendingApproval>()
        ds.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    SELECT id, turn_id, tool, args_redacted, prompt, channel, thread_ext_id, created_at, expires_at
                    FROM pending_approvals
                    WHERE resolved_at IS NULL
                    """.trimIndent(),
                ).use { stmt ->
                    val rs = stmt.executeQuery()
                    while (rs.next()) {
                        result.add(
                            PendingApproval(
                                id = rs.getString("id"),
                                turnId = rs.getString("turn_id"),
                                tool = rs.getString("tool"),
                                argsRedacted = rs.getString("args_redacted"),
                                prompt = rs.getString("prompt"),
                                channel = rs.getString("channel"),
                                threadExtId = rs.getString("thread_ext_id"),
                                createdAt = Instant.ofEpochMilli(rs.getLong("created_at")),
                                expiresAt = Instant.ofEpochMilli(rs.getLong("expires_at")),
                            ),
                        )
                    }
                }
        }
        return result
    }
}

data class PendingApproval(
    val id: String,
    val turnId: String,
    val tool: String,
    val argsRedacted: String,
    val prompt: String,
    val channel: String,
    val threadExtId: String?,
    val createdAt: Instant,
    val expiresAt: Instant,
)
