package org.tatrman.kantheon.hebe.security.receipts

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

sealed interface VerifyResult {
    data class Ok(
        val records: Int,
        val lastSelfHash: String,
    ) : VerifyResult

    data class Failed(
        val recordSeq: Long,
        val reason: String,
    ) : VerifyResult
}

class ReceiptVerifier {
    fun verify(
        file: Path,
        publicKey: ByteArray,
        expectedFirstPrevHash: String = ZERO_HASH,
    ): VerifyResult {
        if (!Files.exists(file)) {
            return VerifyResult.Failed(0, "File not found: $file")
        }

        val lines = Files.readAllLines(file)
        if (lines.isEmpty()) {
            return VerifyResult.Ok(0, ZERO_HASH)
        }

        var prevHash = expectedFirstPrevHash
        var seq = 0L

        for (line in lines) {
            if (line.isBlank()) continue

            val receipt =
                try {
                    Receipt.fromJson(line)
                } catch (e: Exception) {
                    return VerifyResult.Failed(seq, "Failed to parse receipt: ${e.message}")
                }

            if (receipt.seq != seq) {
                return VerifyResult.Failed(seq, "Expected seq $seq but found ${receipt.seq}")
            }

            if (receipt.prevHash != prevHash) {
                return VerifyResult.Failed(
                    seq,
                    "prev_hash mismatch (expected ${prevHash.take(20)}..., got ${receipt.prevHash.take(20)}...)",
                )
            }

            val canonical = buildCanonical(receipt)
            val computedSelfHash = "sha256:${sha256Hex(canonical.toByteArray())}"

            if (computedSelfHash != receipt.selfHash) {
                return VerifyResult.Failed(seq, "self_hash mismatch")
            }

            val sigPrefix = "ed25519:base64url:"
            if (!receipt.sig.startsWith(sigPrefix)) {
                return VerifyResult.Failed(seq, "Invalid signature format")
            }

            val signatureBytes =
                try {
                    java.util.Base64
                        .getUrlDecoder()
                        .decode(receipt.sig.removePrefix(sigPrefix))
                } catch (e: Exception) {
                    return VerifyResult.Failed(seq, "Failed to decode signature")
                }

            if (!Ed25519Verifier.verify(publicKey, hexToBytes(receipt.selfHash.removePrefix("sha256:")), signatureBytes)) {
                return VerifyResult.Failed(seq, "Signature verification failed")
            }

            prevHash = receipt.selfHash
            seq++
        }

        return VerifyResult.Ok(seq.toInt(), prevHash)
    }

    fun verifyDirectory(
        dir: Path,
        publicKey: ByteArray,
    ): VerifyResult {
        val files =
            Files
                .list(dir)
                .filter { it.fileName.toString().endsWith(".log") }
                .sorted()
                .toList()

        if (files.isEmpty()) {
            return VerifyResult.Ok(0, ZERO_HASH)
        }

        var lastSelfHash = ZERO_HASH
        var totalRecords = 0

        for (file in files) {
            val result = verify(file, publicKey, lastSelfHash)
            when (result) {
                is VerifyResult.Failed -> return result
                is VerifyResult.Ok -> {
                    lastSelfHash = result.lastSelfHash
                    totalRecords += result.records
                }
            }
        }

        return VerifyResult.Ok(totalRecords, lastSelfHash)
    }

    private fun buildCanonical(receipt: Receipt): String =
        CanonicalJson.serializeCanonical(
            // Same single source of truth as the append paths — `seq` is NOT hashed.
            ReceiptChain.canonicalEntries(
                ts = receipt.ts,
                sessionId = receipt.sessionId,
                turnId = receipt.turnId,
                tool = receipt.tool,
                argsRedacted = receipt.argsRedacted,
                risk = receipt.risk,
                approvalRequired = receipt.approval.required,
                durationMs = receipt.durationMs,
                ok = receipt.ok,
                resultHash = receipt.resultHash,
                prevHash = receipt.prevHash,
            ),
        )

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }

    private fun hexToBytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    companion object {
        private val ZERO_HASH = "sha256:" + "0".repeat(64)
    }
}
