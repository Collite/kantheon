package org.tatrman.kantheon.hebe.security.receipts

import java.security.MessageDigest
import java.util.Base64

/**
 * The receipt hash-chain + Ed25519 signing algorithm, factored out so the file log
 * ([Receipts]) and the Postgres log ([PostgresReceiptsStore]) share one definition
 * (P3 S3.2 T4 — "the same hash-chain + Ed25519 algorithm as the file log"). A link
 * is `self_hash = sha256(canonical(payload))` where the canonical payload **includes
 * `prevHash`** (genesis = [ZERO_HASH] for the first entry), and `sig = Ed25519` over
 * the 32 raw self-hash bytes, encoded base64url under the [SIG_ALGORITHM] prefix.
 * Pure + backend-agnostic, so the chain integrity is unit-provable without a live
 * backend (planning-conventions §4).
 */
object ReceiptChain {
    val ZERO_HASH = "sha256:" + "0".repeat(SHA256_HEX_LEN)
    const val SIG_ALGORITHM = "ed25519:base64url"

    data class Link(
        /** The canonical JSON of the payload (the bytes that were hashed). */
        val canonicalPayload: String,
        val selfHash: String,
        val sig: String,
    )

    /** Builds a chain link for [payload] (which must carry `prevHash`) and signs it. */
    fun link(
        payload: List<Pair<String, Any?>>,
        signingKey: Ed25519PrivateKey,
    ): Link {
        val canonical = CanonicalJson.serializeCanonical(payload)
        val selfHash = selfHashOf(canonical)
        val sig = sign(selfHash, signingKey)
        return Link(canonical, selfHash, sig)
    }

    fun selfHashOf(canonicalPayload: String): String = "sha256:" + sha256Hex(canonicalPayload.toByteArray())

    /**
     * The canonical hashed-payload field set shared by **both** receipt backends (the
     * file [Receipts] log and [PostgresReceiptsStore]) and the [ReceiptVerifier]. The
     * DB-generated `seq` is deliberately **not** included — it is unknown until after
     * the insert, and the chain links via `prevHash`/`selfHash`, not `seq`. This is the
     * single definition of the canonical payload: every append site and the verifier
     * call it, so the file and Postgres chains compute identical `self_hash` values and
     * stay cross-verifiable. Keep the field order/types here, nowhere else.
     */
    @Suppress("LongParameterList")
    fun canonicalEntries(
        ts: String,
        sessionId: String,
        turnId: String,
        tool: String,
        argsRedacted: String,
        risk: String,
        approvalRequired: Boolean,
        durationMs: Long,
        ok: Boolean,
        resultHash: String,
        prevHash: String,
    ): List<Pair<String, Any?>> =
        listOf(
            "ts" to ts,
            "sessionId" to sessionId,
            "turnId" to turnId,
            "tool" to tool,
            "argsRedacted" to argsRedacted,
            "risk" to risk,
            "approval" to mapOf("required" to approvalRequired),
            "durationMs" to durationMs,
            "ok" to ok,
            "resultHash" to resultHash,
            "prevHash" to prevHash,
        )

    fun sign(
        selfHash: String,
        signingKey: Ed25519PrivateKey,
    ): String {
        val sigBytes = signingKey.sign(hexToBytes(selfHash.removePrefix("sha256:")))
        return "$SIG_ALGORITHM:${Base64.getUrlEncoder().encodeToString(sigBytes)}"
    }

    /** Verifies the signature over [selfHash] against an X.509 Ed25519 public key. */
    fun verifySignature(
        selfHash: String,
        sig: String,
        publicKeyBytes: ByteArray,
    ): Boolean {
        val raw = sig.removePrefix("$SIG_ALGORITHM:")
        val sigBytes =
            try {
                Base64.getUrlDecoder().decode(raw)
            } catch (
                @Suppress("SwallowedException") e: IllegalArgumentException,
            ) {
                return false
            }
        return Ed25519Verifier.verify(publicKeyBytes, hexToBytes(selfHash.removePrefix("sha256:")), sigBytes)
    }

    fun sha256Hex(data: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(data)
            .joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray =
        java.util.HexFormat
            .of()
            .parseHex(hex)

    private const val SHA256_HEX_LEN = 64
}
