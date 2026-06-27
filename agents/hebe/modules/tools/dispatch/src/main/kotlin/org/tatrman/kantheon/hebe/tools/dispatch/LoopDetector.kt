package org.tatrman.kantheon.hebe.tools.dispatch

import org.tatrman.kantheon.hebe.api.ParsedToolCall
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class LoopDetector {
    private val turnFingerprints = mutableMapOf<String, MutableMap<String, Int>>()
    private val turnMutex = Mutex()

    suspend fun fingerprint(
        turnId: String,
        call: ParsedToolCall,
    ): Int =
        withContext(Dispatchers.Default) {
            val key = sha256("${call.name}${canonicalJson(call.args)}")
            turnMutex.withLock {
                val map = turnFingerprints.getOrPut(turnId) { mutableMapOf() }
                val count = (map[key] ?: 0) + 1
                map[key] = count
                count
            }
        }

    suspend fun shouldWarn(
        turnId: String,
        call: ParsedToolCall,
    ): Boolean {
        val key = sha256("${call.name}${canonicalJson(call.args)}")
        return turnMutex.withLock {
            val map = turnFingerprints[turnId] ?: return@withLock false
            (map[key] ?: 0) == 3
        }
    }

    suspend fun shouldForceText(
        turnId: String,
        call: ParsedToolCall,
    ): Boolean {
        val key = sha256("${call.name}${canonicalJson(call.args)}")
        return turnMutex.withLock {
            val map = turnFingerprints[turnId] ?: return@withLock false
            (map[key] ?: 0) >= 5
        }
    }

    suspend fun clearTurn(turnId: String) {
        turnMutex.withLock {
            turnFingerprints.remove(turnId)
        }
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun canonicalJson(obj: JsonObject): String {
        val sorted = obj.entries.sortedBy { it.key }
        return sorted.joinToString(",", "{", "}") { (k, v) ->
            "\"$k\":${canonicalValue(v)}"
        }
    }

    private fun canonicalValue(element: kotlinx.serialization.json.JsonElement): String =
        when (element) {
            is JsonPrimitive -> {
                val value = element.content
                if (element.isString) "\"$value\"" else value
            }
            is JsonObject -> canonicalJson(element)
            else -> element.toString()
        }
}
