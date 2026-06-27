package org.tatrman.kantheon.iris.audit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64

/**
 * One hash-chained audit row (contracts §3.1; the Hebe receipts shape reused
 * constellation-wide). `self_hash = sha256(payload + prev_hash)`; `sig` is an
 * Ed25519 signature over `self_hash`.
 */
data class AuditRecord(
    val seq: Long,
    val ts: Instant,
    val userId: String,
    val eventKind: String,
    val payloadJson: String,
    val segment: String,
    val prevHash: String,
    val selfHash: String,
    val sig: String,
)

/** Ed25519 signer/verifier. A key ref may be injected; otherwise an ephemeral
 *  keypair is generated (dev/test) with a warning. Production loads the keypair
 *  from a configured Secret via [Ed25519Signer.fromKeyRef] (Stage 1.4). */
class Ed25519Signer(
    keyPair: KeyPair? = null,
) {
    private val log = LoggerFactory.getLogger(Ed25519Signer::class.java)
    private val keys: KeyPair =
        keyPair ?: run {
            log.warn("iris audit: no signing key configured — using an ephemeral Ed25519 keypair (dev/test only)")
            KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        }

    fun sign(data: String): String {
        val s = Signature.getInstance("Ed25519")
        s.initSign(keys.private)
        s.update(data.toByteArray())
        return Base64.getEncoder().encodeToString(s.sign())
    }

    fun verify(
        data: String,
        signature: String,
    ): Boolean =
        runCatching {
            val s = Signature.getInstance("Ed25519")
            s.initVerify(keys.public)
            s.update(data.toByteArray())
            s.verify(Base64.getDecoder().decode(signature))
        }.getOrDefault(false)

    companion object {
        /**
         * Build a signer from a configured key ref, or an ephemeral keypair when
         * the ref is null/blank (dev/local). The ref is a **file path** (preferred:
         * a mounted K8s Secret) or **inline PEM text**; either way it must hold both
         * a PKCS#8 `PRIVATE KEY` block and the matching X.509 `PUBLIC KEY` block —
         * the public is required so the chain can be verified after the fact and is
         * not derivable from an Ed25519 private via the standard JCA API.
         * A ref that is set but unreadable/malformed is a hard error (never a silent
         * fall-back to an ephemeral key, which would orphan the existing chain).
         */
        fun fromKeyRef(ref: String?): Ed25519Signer {
            if (ref.isNullOrBlank()) return Ed25519Signer()
            val pem =
                runCatching { Path.of(ref).takeIf { Files.isRegularFile(it) }?.let { Files.readString(it) } }
                    .getOrNull() ?: ref
            val priv =
                pemBlock(pem, "PRIVATE KEY")
                    ?: error("iris audit: signing-key-ref set but no 'PRIVATE KEY' PEM block found")
            val pub =
                pemBlock(pem, "PUBLIC KEY")
                    ?: error("iris audit: signing-key-ref set but no 'PUBLIC KEY' PEM block found")
            val kf = KeyFactory.getInstance("Ed25519")
            val privateKey: PrivateKey =
                runCatching { kf.generatePrivate(PKCS8EncodedKeySpec(priv)) }
                    .getOrElse { error("iris audit: malformed Ed25519 private key in signing-key-ref: ${it.message}") }
            val publicKey: PublicKey =
                runCatching { kf.generatePublic(X509EncodedKeySpec(pub)) }
                    .getOrElse { error("iris audit: malformed Ed25519 public key in signing-key-ref: ${it.message}") }
            return Ed25519Signer(KeyPair(publicKey, privateKey))
        }

        /** Decode one PEM block (`-----BEGIN <label>-----` … `-----END <label>-----`) to DER bytes. */
        private fun pemBlock(
            pem: String,
            label: String,
        ): ByteArray? {
            val begin = "-----BEGIN $label-----"
            val end = "-----END $label-----"
            val start = pem.indexOf(begin)
            if (start < 0) return null
            val from = start + begin.length
            val to = pem.indexOf(end, from)
            if (to < 0) return null
            val body = pem.substring(from, to).replace(Regex("\\s"), "")
            return runCatching { Base64.getDecoder().decode(body) }.getOrNull()
        }
    }
}

/**
 * Build one signed, hash-chained audit record from its inputs — the single source
 * of truth for canonicalisation + `self_hash` + signature, shared by every
 * [AuditStore] backend. `seq` is not part of the hash (the hash chains via
 * `prev_hash`), so callers that learn `seq` only after a DB insert pass a
 * placeholder and `copy(seq = …)` the result.
 */
internal fun signedRecord(
    seq: Long,
    ts: Instant,
    userId: String,
    eventKind: String,
    payloadJson: String,
    prevHash: String,
    signer: Ed25519Signer,
): AuditRecord {
    val canonical = canonicalizePayload(payloadJson)
    val selfHash = sha256Hex(canonical + prevHash)
    return AuditRecord(
        seq = seq,
        ts = ts,
        userId = userId,
        eventKind = eventKind,
        payloadJson = canonical,
        segment = segmentOf(ts),
        prevHash = prevHash,
        selfHash = selfHash,
        sig = signer.sign(selfHash),
    )
}

/** Append-only, hash-chained audit log. */
interface AuditStore {
    /** Append an event; assigns seq, chains, and signs. Returns the written row. */
    fun append(
        userId: String,
        eventKind: String,
        payloadJson: String,
        ts: Instant,
    ): AuditRecord

    fun all(): List<AuditRecord>
}

private val SEGMENT_FMT = DateTimeFormatter.ofPattern("yyyy-MM").withZone(ZoneOffset.UTC)

/** The `prev_hash` of the first row in a chain (no predecessor). */
internal const val GENESIS = "GENESIS"

internal fun sha256Hex(input: String): String {
    val md = MessageDigest.getInstance("SHA-256")
    return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
}

internal fun segmentOf(ts: Instant): String = SEGMENT_FMT.format(ts)

private val CANON_JSON = Json { }

/**
 * Canonical JSON for the audit hash: object keys sorted recursively, compact
 * separators. Makes `self_hash` independent of the producer's key order /
 * whitespace, so the chain verifies even if the payload is re-serialised by a
 * different writer (contracts §3.1 `self_hash = sha256(canonical(payload) + prev_hash)`).
 * Non-JSON input is returned verbatim (defensive — callers always pass JSON).
 */
internal fun canonicalizePayload(rawJson: String): String =
    runCatching { sortKeys(CANON_JSON.parseToJsonElement(rawJson)).toString() }.getOrDefault(rawJson)

private fun sortKeys(el: JsonElement): JsonElement =
    when (el) {
        is JsonObject -> JsonObject(el.entries.sortedBy { it.key }.associate { it.key to sortKeys(it.value) })
        is JsonArray -> JsonArray(el.map { sortKeys(it) })
        else -> el
    }

/** In-memory [AuditStore] — the unit/component-test backing (the Exposed-backed
 *  `iris_audit` writer lands with the live path). */
class InMemoryAuditStore(
    private val signer: Ed25519Signer,
) : AuditStore {
    private val rows = mutableListOf<AuditRecord>()
    private val lock = Any()

    override fun append(
        userId: String,
        eventKind: String,
        payloadJson: String,
        ts: Instant,
    ): AuditRecord =
        synchronized(lock) {
            val prevHash = rows.lastOrNull()?.selfHash ?: GENESIS
            val record =
                signedRecord(
                    seq = rows.size + 1L,
                    ts = ts,
                    userId = userId,
                    eventKind = eventKind,
                    payloadJson = payloadJson,
                    prevHash = prevHash,
                    signer = signer,
                )
            rows.add(record)
            record
        }

    override fun all(): List<AuditRecord> = synchronized(lock) { rows.toList() }
}

/**
 * Verify a hash chain: each row's `self_hash` recomputes from `payload + prev_hash`,
 * `prev_hash` links to the predecessor's `self_hash` (GENESIS for the first), and
 * the signature validates. Returns true iff the whole chain is intact.
 */
fun verifyChain(
    rows: List<AuditRecord>,
    signer: Ed25519Signer,
): Boolean {
    var prev = GENESIS
    var expectedSeq = 1L
    for (row in rows.sortedBy { it.seq }) {
        // seq must be gap-free 1..N: a deleted middle row whose neighbours still
        // link would otherwise pass the hash check undetected.
        if (row.seq != expectedSeq) return false
        if (row.prevHash != prev) return false
        if (sha256Hex(row.payloadJson + row.prevHash) != row.selfHash) return false
        if (!signer.verify(row.selfHash, row.sig)) return false
        prev = row.selfHash
        expectedSeq++
    }
    return true
}

/** Result of a per-segment verification (Stage 4.3 `/v1/audit/verify`). */
data class SegmentVerification(
    val ok: Boolean,
    val brokenAtSeq: Long? = null,
)

/**
 * Verify one monthly segment in isolation (PD-8, contracts §3.1): each row's
 * `self_hash` recomputes from `payload + prev_hash`, links to its predecessor,
 * and its signature validates. To catch **deletion** (not just mutation), the
 * segment is verified with seq-contiguity and is anchored to its neighbours:
 *
 *  - [priorRecord] — the row immediately preceding this segment in the global
 *    chain (null when this is the first segment). Its `self_hash` must equal the
 *    segment's first `prev_hash` and `prior.seq + 1` seeds the seq cursor, so a
 *    **deleted leading row** (or a tampered boundary) is caught.
 *  - [nextRecord] — the row immediately following this segment (null when this is
 *    the last segment). Its `prev_hash`/`seq` must chain onto the segment's
 *    terminal, so a **deleted trailing row** (truncation — the likeliest tamper
 *    for an archived tail) is caught.
 *
 * Interior deletion is caught by the `prev_hash` link **and** the seq cursor.
 * An empty segment with neither neighbour ⇒ ok (a fully-deleted, isolated
 * segment cannot be detected without its neighbours; verifying those catches the
 * resulting gap). Returns the seq of the first broken row on failure.
 */
fun verifySegment(
    rows: List<AuditRecord>,
    signer: Ed25519Signer,
    priorRecord: AuditRecord? = null,
    nextRecord: AuditRecord? = null,
): SegmentVerification {
    val sorted = rows.sortedBy { it.seq }
    var prev: String? = priorRecord?.selfHash
    var expectedSeq: Long? = priorRecord?.let { it.seq + 1 }
    for (row in sorted) {
        // Seed the cursor/anchor from the first row when no prior was supplied
        // (the genuine first segment chains from GENESIS at seq 1).
        if (expectedSeq == null) expectedSeq = row.seq
        if (prev == null && row.seq == 1L) prev = GENESIS
        if (row.seq != expectedSeq) return SegmentVerification(false, row.seq)
        if (prev != null && row.prevHash != prev) return SegmentVerification(false, row.seq)
        if (sha256Hex(row.payloadJson + row.prevHash) != row.selfHash) return SegmentVerification(false, row.seq)
        if (!signer.verify(row.selfHash, row.sig)) return SegmentVerification(false, row.seq)
        prev = row.selfHash
        expectedSeq = expectedSeq + 1
    }
    // Trailing-truncation guard: the following segment's first row must chain
    // (hash + seq) onto this segment's terminal.
    if (nextRecord != null && prev != null && expectedSeq != null) {
        if (nextRecord.prevHash != prev || nextRecord.seq != expectedSeq) {
            return SegmentVerification(false, sorted.lastOrNull()?.seq ?: priorRecord?.seq ?: nextRecord.seq)
        }
    }
    return SegmentVerification(true)
}
