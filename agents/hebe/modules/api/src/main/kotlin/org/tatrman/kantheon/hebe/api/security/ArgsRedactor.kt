package org.tatrman.kantheon.hebe.api.security

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class ArgsRedactor(
    private val extraSensitiveKeys: List<String> = emptyList(),
) {
    private val defaultSensitiveKeys =
        listOf(
            "api_key",
            "apikey",
            "token",
            "secret",
            "password",
            "auth",
            "bearer",
            "signature",
            "cookie",
            "email",
            "phone",
            "passwd",
            "pwd",
            "access_token",
            "refresh_token",
            "client_secret",
            "private_key",
            "secret_key",
        )

    private val allSensitiveKeys: List<String> by lazy {
        (defaultSensitiveKeys + extraSensitiveKeys).map { it.lowercase() }
    }

    fun redact(args: JsonObject): JsonObject {
        val map = mutableMapOf<String, JsonElement>()
        for ((key, value) in args.entries) {
            val redactedKey = key.lowercase()
            if (redactedKey in allSensitiveKeys) {
                map[key] = JsonPrimitive("[REDACTED]")
            } else {
                map[key] = redactRecursive(value)
            }
        }
        return JsonObject(map)
    }

    private fun redactRecursive(element: JsonElement): JsonElement =
        when (element) {
            is JsonPrimitive -> element
            is JsonObject -> redact(element)
            is JsonArray -> JsonArray(element.map { redactRecursive(it) })
            else -> element
        }

    companion object {
        val INSTANCE = ArgsRedactor()
    }
}
