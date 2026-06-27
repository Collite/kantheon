package org.tatrman.kantheon.hebe.gateway

import org.tatrman.kantheon.hebe.security.receipts.Receipt
import org.tatrman.kantheon.hebe.security.receipts.ReceiptVerifier
import org.tatrman.kantheon.hebe.security.receipts.VerifyResult
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.slf4j.LoggerFactory

object ReceiptsRoutes {
    private val logger = LoggerFactory.getLogger(ReceiptsRoutes::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private const val DEFAULT_LIMIT = 100

    fun register(
        routing: Route,
        receiptsDir: Path,
    ) {
        routing.get("/api/receipts") { listReceiptsHandler(receiptsDir, call) }
        routing.get("/api/receipts/verify") { verifyReceiptsHandler(receiptsDir, call) }
    }

    private suspend fun listReceiptsHandler(
        receiptsDir: Path,
        call: ApplicationCall,
    ) {
        val sinceParam = call.request.queryParameters["since"]
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT
        val since = sinceParam?.let { parseInstantOrNull(it) }

        try {
            val receipts = loadReceipts(receiptsDir, since, limit)
            call.respondText(
                buildJsonObject {
                    put("receipts", JsonPrimitive(json.encodeToString(receipts)))
                }.toString(),
                contentType = ContentType.Application.Json,
            )
        } catch (
            @Suppress("TooGenericExceptionCaught", "SwallowedException")
            e: Exception,
        ) {
            logger.error("failed to list receipts", e)
            call.respondText(
                buildJsonObject { put("error", JsonPrimitive(e.message ?: "Failed to list receipts")) }.toString(),
                status = HttpStatusCode.InternalServerError,
                contentType = ContentType.Application.Json,
            )
        }
    }

    private suspend fun verifyReceiptsHandler(
        receiptsDir: Path,
        call: ApplicationCall,
    ) {
        val fileParam = call.request.queryParameters["file"]

        try {
            val verifier = ReceiptVerifier()
            val publicKeyBytes = getPublicKey(receiptsDir)

            val result =
                if (fileParam != null) {
                    val filePath = receiptsDir.resolve(fileParam)
                    verifier.verify(filePath, publicKeyBytes)
                } else {
                    verifier.verifyDirectory(receiptsDir, publicKeyBytes)
                }

            val responseJson =
                when (result) {
                    is VerifyResult.Ok ->
                        buildJsonObject {
                            put("ok", JsonPrimitive(true))
                            put("records", JsonPrimitive(result.records))
                            put("lastSeq", JsonPrimitive(result.lastSelfHash))
                        }
                    is VerifyResult.Failed ->
                        buildJsonObject {
                            put("ok", JsonPrimitive(false))
                            put("recordSeq", JsonPrimitive(result.recordSeq))
                            put("errors", JsonPrimitive(result.reason))
                        }
                }
            call.respondText(responseJson.toString(), contentType = ContentType.Application.Json)
        } catch (
            @Suppress("TooGenericExceptionCaught", "SwallowedException")
            e: Exception,
        ) {
            logger.error("receipts verification failed", e)
            call.respondText(
                buildJsonObject {
                    put("ok", JsonPrimitive(false))
                    put("errors", JsonPrimitive(e.message ?: "Verification failed"))
                }.toString(),
                status = HttpStatusCode.InternalServerError,
                contentType = ContentType.Application.Json,
            )
        }
    }

    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "LoopWithTooManyJumpStatements")
    private fun loadReceipts(
        dir: Path,
        since: Instant?,
        limit: Int,
    ): List<Receipt> {
        val receipts = mutableListOf<Receipt>()
        if (!Files.exists(dir)) return receipts

        val files =
            Files
                .list(dir)
                .filter { it.fileName.toString().endsWith(".log") }
                .sorted()
                .toList()

        for (file in files.reversed()) {
            val lines = Files.readAllLines(file)
            for (line in lines.reversed()) {
                if (line.isBlank()) continue
                val receipt = parseReceiptOrNull(line) ?: continue
                if (since != null && Instant.parse(receipt.ts).isBefore(since)) break
                receipts.add(receipt)
                if (receipts.size >= limit) break
            }
            if (receipts.size >= limit) break
        }

        return receipts.take(limit)
    }

    private fun parseInstantOrNull(text: String): Instant? =
        try {
            Instant.parse(text)
        } catch (
            @Suppress("TooGenericExceptionCaught", "SwallowedException")
            e: Exception,
        ) {
            logger.debug("failed to parse instant", e)
            null
        }

    @Suppress("SwallowedException")
    private fun parseReceiptOrNull(line: String): Receipt? =
        try {
            Receipt.fromJson(line)
        } catch (
            @Suppress("TooGenericExceptionCaught")
            e: Exception,
        ) {
            logger.warn("failed to parse receipt line", e)
            null
        }

    private fun getPublicKey(dir: Path): ByteArray {
        val pubKeyPath = dir.resolve("public.key")
        check(Files.exists(pubKeyPath)) { "Public key not found at $pubKeyPath" }
        val encoded = Files.readString(pubKeyPath).trim()
        return java.util.Base64
            .getDecoder()
            .decode(encoded)
    }
}
