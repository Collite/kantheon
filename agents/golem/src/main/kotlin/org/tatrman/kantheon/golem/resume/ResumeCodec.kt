package org.tatrman.kantheon.golem.resume

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** An option offered in an entity/intent clarification (pin-by-id Δ3 carries `entityTypeRef`/`resolvedId`). */
data class ResumeOption(
    val id: String,
    val display: String,
    val entityTypeRef: String = "",
    val resolvedId: String = "",
)

/** The char-span an entity clarification covered, for the text-splice fallback (Δ3). */
data class ClarificationSpan(
    val charStart: Int,
    val charEnd: Int,
    val coveredText: String,
)

/**
 * The signed resume payload (golem `resume_tokens.py` port). Carries everything a resumed
 * turn needs: the clarification kind, the original question, the partial plan, the options,
 * the entity span (splice fallback), and the Resolver RESUME token (pin-by-id).
 */
data class ResumePayload(
    val threadId: String,
    val turnId: String,
    val kind: String,
    val userText: String,
    val pickedPlanJson: String = "{}",
    val options: List<ResumeOption> = emptyList(),
    val clarificationSpan: ClarificationSpan? = null,
    val resolverResumeToken: String = "",
    val issuedAt: Long = 0,
    // Caller binding (B1): the admitted caller the token was minted for. `resume` rejects a
    // token whose (userId, tenantId) doesn't match the resuming caller — HMAC stops forgery,
    // this stops cross-user/cross-tenant REPLAY of a leaked-but-valid token.
    val userId: String = "",
    val tenantId: String = "",
    // Request rehydration (M5): the original turn's golemId + locale, so the resumed turn
    // keeps its agent identity + locale instead of synthesising a bare 2-field request.
    val golemId: String = "",
    val locale: String = "",
)

/** Raised when a resume token is malformed, tampered, or expired. */
class ResumeTokenException(
    message: String,
) : RuntimeException(message)

/**
 * HMAC-SHA256 resume-token codec (port of golem `resume_tokens.py`). Token form is
 * `base64url(payload_json).base64url(hmac_sha256(payload_b64))` — padding stripped. Golem
 * mints and verifies its own tokens, so the JSON layout is self-consistent rather than
 * byte-compatible with the Python encoder. `decode` verifies the signature (constant-time)
 * and the TTL before returning the payload.
 */
class ResumeCodec(
    secret: String,
    private val ttlSeconds: Long = 300,
    private val now: () -> Instant = Instant::now,
) {
    private val keyBytes = secret.toByteArray(Charsets.UTF_8)
    private val json = Json { ignoreUnknownKeys = true }
    private val urlEncoder = Base64.getUrlEncoder().withoutPadding()
    private val urlDecoder = Base64.getUrlDecoder()

    fun encode(payload: ResumePayload): String {
        val withTime = if (payload.issuedAt > 0) payload else payload.copy(issuedAt = now().epochSecond)
        val payloadB64 = urlEncoder.encodeToString(toJson(withTime).toByteArray(Charsets.UTF_8))
        val sigB64 = urlEncoder.encodeToString(hmac(payloadB64))
        return "$payloadB64.$sigB64"
    }

    fun decode(token: String): ResumePayload {
        val parts = token.split(".")
        if (parts.size != 2) throw ResumeTokenException("malformed token")
        val (payloadB64, sigB64) = parts
        val expected = hmac(payloadB64)
        val actual = runCatching { urlDecoder.decode(sigB64) }.getOrElse { throw ResumeTokenException("bad signature") }
        if (!MessageDigest.isEqual(expected, actual)) throw ResumeTokenException("bad signature")
        val payloadJson =
            runCatching { String(urlDecoder.decode(payloadB64), Charsets.UTF_8) }
                .getOrElse { throw ResumeTokenException("unreadable payload") }
        val payload =
            runCatching { fromJson(payloadJson) }.getOrElse { throw ResumeTokenException("unreadable payload") }
        val age = now().epochSecond - payload.issuedAt
        if (payload.issuedAt <= 0 || age > ttlSeconds) throw ResumeTokenException("token expired")
        return payload
    }

    private fun hmac(payloadB64: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(keyBytes, "HmacSHA256"))
        return mac.doFinal(payloadB64.toByteArray(Charsets.US_ASCII))
    }

    private fun toJson(p: ResumePayload): String =
        buildJsonObject {
            put("threadId", p.threadId)
            put("turnId", p.turnId)
            put("kind", p.kind)
            put("userText", p.userText)
            put("pickedPlanJson", p.pickedPlanJson)
            put("resolverResumeToken", p.resolverResumeToken)
            put("issuedAt", p.issuedAt)
            put("userId", p.userId)
            put("tenantId", p.tenantId)
            put("golemId", p.golemId)
            put("locale", p.locale)
            put(
                "options",
                buildJsonArray {
                    p.options.forEach { o ->
                        add(
                            buildJsonObject {
                                put("id", o.id)
                                put("display", o.display)
                                put("entityTypeRef", o.entityTypeRef)
                                put("resolvedId", o.resolvedId)
                            },
                        )
                    }
                },
            )
            p.clarificationSpan?.let { s ->
                put(
                    "clarificationSpan",
                    buildJsonObject {
                        put("charStart", s.charStart)
                        put("charEnd", s.charEnd)
                        put("coveredText", s.coveredText)
                    },
                )
            }
        }.toString()

    private fun fromJson(s: String): ResumePayload {
        val o = json.parseToJsonElement(s).jsonObject

        fun str(k: String) = o[k]?.jsonPrimitive?.content ?: ""
        val span =
            (o["clarificationSpan"] as? JsonObject)?.let { sp ->
                ClarificationSpan(
                    charStart = sp["charStart"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    charEnd = sp["charEnd"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    coveredText = sp["coveredText"]?.jsonPrimitive?.content ?: "",
                )
            }
        val options =
            (o["options"]?.jsonArray ?: emptyList<JsonObject>()).map { el ->
                val opt = el.jsonObject
                ResumeOption(
                    id = opt["id"]?.jsonPrimitive?.content ?: "",
                    display = opt["display"]?.jsonPrimitive?.content ?: "",
                    entityTypeRef = opt["entityTypeRef"]?.jsonPrimitive?.content ?: "",
                    resolvedId = opt["resolvedId"]?.jsonPrimitive?.content ?: "",
                )
            }
        return ResumePayload(
            threadId = str("threadId"),
            turnId = str("turnId"),
            kind = str("kind"),
            userText = str("userText"),
            pickedPlanJson = o["pickedPlanJson"]?.jsonPrimitive?.content ?: "{}",
            options = options,
            clarificationSpan = span,
            resolverResumeToken = str("resolverResumeToken"),
            issuedAt = o["issuedAt"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
            userId = str("userId"),
            tenantId = str("tenantId"),
            golemId = str("golemId"),
            locale = str("locale"),
        )
    }
}
