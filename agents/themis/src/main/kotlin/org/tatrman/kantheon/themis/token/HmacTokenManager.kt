package org.tatrman.kantheon.themis.token

import org.tatrman.kantheon.themis.config.ResolverAppConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.tatrman.kantheon.themis.client.NlpAnalyzeResult
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class HmacTokenManager(
    private val config: ResolverAppConfig,
) {
    private val secretBytes = config.hmac.secretKey.toByteArray(StandardCharsets.UTF_8)
    private val previousSecretBytes =
        if (config.hmac.previousKey.isNotEmpty()) {
            config.hmac.previousKey.toByteArray(StandardCharsets.UTF_8)
        } else {
            null
        }
    private val keyVersion = config.hmac.keyVersion
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class TokenPayload(
        val question: String,
        val parseHash: String,
        val domainCandidates: Map<String, List<String>>,
        val universalEntities: List<UniversalEntityData>,
        val ambiguityAsked: String,
        val roundCounter: Int,
        val issuedAt: Long,
        val keyVersion: Int = 1,
        val nlpLanguage: String = "",
        val nlpLanguageConfidence: Double = 0.0,
        val nlpEngineUsed: String = "",
        val nlpTokens: String = "[]",
        val nlpSentences: String = "[]",
        val nlpParagraphs: String = "[]",
        val nlpEntities: String = "[]",
        val nlpTraceId: String = "",
        val nlpElapsedMs: Long = 0,
        val nlpMessages: String = "[]",
        // Phase 3 Stage 3.4 additions. Defaults let pre-Phase-3 tokens decode cleanly.
        val profileAtIssue: String = "CHAT_QUICK",
        val alternatesOffered: List<String> = emptyList(),
    )

    @Serializable
    data class UniversalEntityData(
        val rawText: String,
        val entityType: String,
        val normalizedValue: String,
        val charStart: Int,
        val charEnd: Int,
    )

    fun createResumeToken(
        question: String,
        parseHash: String,
        domainCandidates: Map<String, List<String>>,
        universalEntities: List<UniversalEntityData>,
        ambiguityAsked: String,
        roundCounter: Int,
        nlpLanguage: String = "",
        nlpLanguageConfidence: Double = 0.0,
        nlpEngineUsed: String = "",
        nlpTokens: String = "[]",
        nlpSentences: String = "[]",
        nlpParagraphs: String = "[]",
        nlpEntities: String = "[]",
        nlpTraceId: String = "",
        nlpElapsedMs: Long = 0,
        nlpMessages: String = "[]",
        profileAtIssue: String = "CHAT_QUICK",
        alternatesOffered: List<String> = emptyList(),
    ): String {
        val payload =
            TokenPayload(
                question = question,
                parseHash = parseHash,
                domainCandidates = domainCandidates,
                universalEntities = universalEntities,
                ambiguityAsked = ambiguityAsked,
                roundCounter = roundCounter,
                issuedAt = Instant.now().toEpochMilli(),
                keyVersion = keyVersion,
                nlpLanguage = nlpLanguage,
                nlpLanguageConfidence = nlpLanguageConfidence,
                nlpEngineUsed = nlpEngineUsed,
                nlpTokens = nlpTokens,
                nlpSentences = nlpSentences,
                nlpParagraphs = nlpParagraphs,
                nlpEntities = nlpEntities,
                nlpTraceId = nlpTraceId,
                nlpElapsedMs = nlpElapsedMs,
                nlpMessages = nlpMessages,
                profileAtIssue = profileAtIssue,
                alternatesOffered = alternatesOffered,
            )
        val payloadJson = json.encodeToString(payload)
        val payloadB64 =
            Base64.getUrlEncoder().withoutPadding().encodeToString(
                payloadJson.toByteArray(StandardCharsets.UTF_8),
            )

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secretBytes, "HmacSHA256"))
        val signatureBytes = mac.doFinal(payloadB64.toByteArray(StandardCharsets.UTF_8))
        val signatureB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(signatureBytes)

        return "$payloadB64.$signatureB64"
    }

    fun verifyAndDecode(token: String): TokenPayload? {
        return try {
            val parts = token.split(".")
            if (parts.size != 2) return null
            val payloadB64 = parts[0]
            val signatureB64 = parts[1]

            // Try current key first, then previous key for rotation grace period
            val currentAndPrevious =
                listOf(secretBytes to "current") +
                    (previousSecretBytes?.let { listOf(it to "previous") } ?: emptyList())
            for ((keyBytes, _) in currentAndPrevious) {
                val mac = Mac.getInstance("HmacSHA256")
                mac.init(SecretKeySpec(keyBytes, "HmacSHA256"))
                val expectedSigBytes = mac.doFinal(payloadB64.toByteArray(StandardCharsets.UTF_8))
                val encoder = Base64.getUrlEncoder().withoutPadding()
                val expectedSigB64 = encoder.encodeToString(expectedSigBytes)

                if (signatureB64 == expectedSigB64) {
                    val payloadBytes = Base64.getUrlDecoder().decode(payloadB64)
                    val payloadJson = String(payloadBytes, StandardCharsets.UTF_8)
                    return json.decodeFromString<TokenPayload>(payloadJson)
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    fun nlpAnalyzeResultFromPayload(payload: TokenPayload): NlpAnalyzeResult =
        NlpAnalyzeResult(
            language = payload.nlpLanguage,
            languageConfidence = payload.nlpLanguageConfidence,
            engineUsed = payload.nlpEngineUsed,
            tokens = json.decodeFromString(payload.nlpTokens),
            sentences = json.decodeFromString(payload.nlpSentences),
            paragraphs = json.decodeFromString(payload.nlpParagraphs),
            entities = json.decodeFromString(payload.nlpEntities),
            traceId = payload.nlpTraceId,
            elapsedMs = payload.nlpElapsedMs,
            messages = json.decodeFromString(payload.nlpMessages),
        )
}
