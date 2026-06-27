@file:Suppress("detekt:MagicNumber")

package org.tatrman.kantheon.hebe.security.receipts

import org.tatrman.kantheon.hebe.api.PartialReceipt
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.sql.DataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tatrman.kantheon.hebe.api.Receipts as ReceiptsInterface

/**
 * Postgres [ReceiptsInterface] backed by the `receipts` table (V7, contracts §4.3)
 * for `receipts.backend = postgres` (`fs.durability = ephemeral`, `k8s`). Same chain
 * + Ed25519 algorithm as the file [Receipts] log via the shared [ReceiptChain]; the
 * `seq` is DB-`GENERATED ALWAYS AS IDENTITY` (so it is *not* part of the hashed
 * payload — the chain links via `prev_hash`/`self_hash`). Append-only: the app role
 * gets no UPDATE/DELETE (granted at provisioning, Stage 3.3). One pod per instance
 * (architecture §5.1), so an in-process [Mutex] serialises the read-last-then-append.
 * Real-Postgres behaviour is verified in the integration suite (planning-conventions
 * §4); the chain algorithm itself is unit-proven in `ReceiptsChainSpec`.
 */
class PostgresReceiptsStore(
    private val dataSource: DataSource,
    private val signingKey: Ed25519PrivateKey,
) : ReceiptsInterface {
    private val mutex = Mutex()

    override suspend fun append(partial: PartialReceipt): Long =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                dataSource.connection.use { conn ->
                    // The pool runs with autoCommit=false (PgDbFactory, instance isolation);
                    // pin it here too so the INSERT is durably committed regardless of the
                    // DataSource's default — without this the append-only receipt is rolled
                    // back on connection return and silently lost.
                    conn.autoCommit = false
                    try {
                        val prevHash = lastSelfHash(conn) ?: ReceiptChain.ZERO_HASH
                        val ts = OffsetDateTime.now(ZoneOffset.UTC)
                        val argsRedacted = partial.argsRedacted
                        val resultHash = "sha256:${ReceiptChain.sha256Hex(argsRedacted.toByteArray())}"
                        // `ts.toInstant().toString()` (ISO-8601 UTC `…Z`) matches the file
                        // log's `Instant.now().toString()`, so the canonical payloads — and
                        // thus self_hashes — are identical across backends.
                        val payload =
                            ReceiptChain.canonicalEntries(
                                ts = ts.toInstant().toString(),
                                sessionId = partial.sessionId,
                                turnId = partial.turnId,
                                tool = partial.tool,
                                argsRedacted = argsRedacted,
                                risk = partial.risk,
                                approvalRequired = false,
                                durationMs = partial.durationMs,
                                ok = partial.ok,
                                resultHash = resultHash,
                                prevHash = prevHash,
                            )
                        val link = ReceiptChain.link(payload, signingKey)
                        val seq = insert(conn, ts, prevHash, link)
                        conn.commit()
                        seq
                    } catch (
                        @Suppress("TooGenericExceptionCaught") ex: Exception,
                    ) {
                        conn.rollback()
                        throw ex
                    }
                }
            }
        }

    private fun lastSelfHash(conn: java.sql.Connection): String? {
        conn.prepareStatement("SELECT self_hash FROM receipts ORDER BY seq DESC LIMIT 1").use { ps ->
            val rs = ps.executeQuery()
            return if (rs.next()) rs.getString(1) else null
        }
    }

    @Suppress("LongParameterList")
    private fun insert(
        conn: java.sql.Connection,
        ts: OffsetDateTime,
        prevHash: String,
        link: ReceiptChain.Link,
    ): Long {
        conn
            .prepareStatement(
                """
                INSERT INTO receipts (ts, payload, prev_hash, self_hash, sig)
                VALUES (?, ?::jsonb, ?, ?, ?)
                RETURNING seq
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, ts)
                ps.setString(2, link.canonicalPayload)
                ps.setString(3, prevHash)
                ps.setString(4, link.selfHash)
                ps.setString(5, link.sig)
                val rs = ps.executeQuery()
                rs.next()
                return rs.getLong(1)
            }
    }
}
