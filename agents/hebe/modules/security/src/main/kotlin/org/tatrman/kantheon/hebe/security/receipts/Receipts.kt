package org.tatrman.kantheon.hebe.security.receipts

import org.tatrman.kantheon.hebe.api.PartialReceipt
import org.tatrman.kantheon.hebe.api.Receipts as ReceiptsInterface
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

class Receipts(
    private val dir: Path,
    private val signingKey: Ed25519PrivateKey,
) : ReceiptsInterface {
    private val mutex = Mutex()
    private var lastHash: String = ZERO_HASH
    private var currentSeq: Long = 0
    private var fsyncCounter = 0
    private val fsyncBatchSize = 16
    private var currentChannel: java.nio.channels.FileChannel? = null
    private var currentMonth: String = ""

    companion object {
        private val ZERO_HASH = "sha256:" + "0".repeat(64)
    }

    suspend fun init(): Receipts {
        Files.createDirectories(dir)
        writePublicKey()
        loadLastState()
        return this
    }

    private fun writePublicKey() {
        // Always (re)write the seed-derived public key. Earlier builds minted a *random*
        // keypair in `publicKeyBytes()`, so an instance provisioned before the P3 S3.2 fix
        // has a stale `public.key` on disk that can never verify its chain. Writing it on
        // every `init()` is idempotent (same seed → same key) and self-heals those dirs.
        val pubKeyPath = dir.resolve("public.key")
        val pubKeyBytes = signingKey.publicKeyBytes()
        Files.writeString(
            pubKeyPath,
            java.util.Base64
                .getEncoder()
                .encodeToString(pubKeyBytes),
        )
    }

    private fun loadLastState() {
        val files =
            try {
                Files
                    .list(dir)
                    .filter { it.fileName.toString().endsWith(".log") }
                    .sorted()
                    .toList()
            } catch (e: Exception) {
                return
            }
        if (files.isEmpty()) return
        val lastFile = files.last()
        try {
            val lastLine = Files.readAllLines(lastFile).lastOrNull() ?: return
            val lastReceipt = Receipt.fromJson(lastLine)
            currentSeq = lastReceipt.seq + 1
            lastHash = lastReceipt.selfHash
        } catch (e: Exception) {
            currentSeq = 0
            lastHash = ZERO_HASH
        }
    }

    override suspend fun append(partial: PartialReceipt): Long =
        mutex.withLock {
            val seq = currentSeq++
            val ts =
                java.time.Instant
                    .now()
                    .toString()
            val month = ts.substring(0, 7)
            val argsRedactedJson = partial.argsRedacted
            val argsRedactedStr = argsRedactedJson.toString()
            val resultHash = "sha256:${ReceiptChain.sha256Hex(argsRedactedStr.toByteArray())}"

            // One shared chain algorithm + canonical payload (ReceiptChain) — identical to
            // the Postgres backend, so the two logs are cross-verifiable. `seq` is NOT in
            // the hashed payload (it is a local-only ordering field; the PG backend's seq
            // is DB-generated and unknown at hash time).
            val link =
                ReceiptChain.link(
                    ReceiptChain.canonicalEntries(
                        ts = ts,
                        sessionId = partial.sessionId,
                        turnId = partial.turnId,
                        tool = partial.tool,
                        argsRedacted = argsRedactedStr,
                        risk = partial.risk,
                        approvalRequired = false,
                        durationMs = partial.durationMs,
                        ok = partial.ok,
                        resultHash = resultHash,
                        prevHash = lastHash,
                    ),
                    signingKey,
                )
            val selfHash = link.selfHash
            val sig = link.sig

            val receipt =
                Receipt(
                    seq = seq,
                    ts = ts,
                    sessionId = partial.sessionId,
                    turnId = partial.turnId,
                    tool = partial.tool,
                    argsRedacted = argsRedactedStr,
                    risk = partial.risk,
                    approval = ApprovalRecord(required = false),
                    durationMs = partial.durationMs,
                    ok = partial.ok,
                    resultHash = resultHash,
                    prevHash = lastHash,
                    selfHash = selfHash,
                    sig = sig,
                )

            val monthFile = dir.resolve("$month.log")

            if (month != currentMonth) {
                currentChannel?.close()
                currentChannel = null
                currentMonth = month
            }

            var channel = currentChannel
            if (channel == null) {
                channel =
                    FileChannel.open(
                        monthFile,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND,
                        StandardOpenOption.WRITE,
                    )
                currentChannel = channel
            }

            val line = Json.encodeToString(receiptSerializer, receipt) + "\n"
            channel!!.write(java.nio.ByteBuffer.wrap(line.toByteArray()))

            lastHash = receipt.selfHash
            fsyncCounter++
            if (fsyncCounter >= fsyncBatchSize) {
                channel!!.force(true)
                fsyncCounter = 0
            }

            return seq
        }
}

private val receiptSerializer: KSerializer<Receipt> = serializer<Receipt>()

@Serializable
data class Receipt(
    val seq: Long,
    val ts: String,
    val sessionId: String,
    val turnId: String,
    val tool: String,
    val argsRedacted: String,
    val risk: String,
    val approval: ApprovalRecord,
    val durationMs: Long,
    val ok: Boolean,
    val resultHash: String,
    val prevHash: String,
    val selfHash: String,
    val sig: String,
) {
    companion object {
        fun fromJson(json: String): Receipt = Json.decodeFromString(receiptSerializer, json)
    }
}

@Serializable
data class ApprovalRecord(
    val required: Boolean,
    val approved: Boolean? = null,
    val denied: Boolean? = null,
)
