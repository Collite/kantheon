package org.tatrman.kantheon.hebe.security.policy

import org.tatrman.kantheon.hebe.api.LeakDetector
import org.tatrman.kantheon.hebe.api.Observer
import org.tatrman.kantheon.hebe.api.ObserverEvent
import org.tatrman.kantheon.hebe.api.ToolResult

class LeakDetector(
    private val patterns: List<SecretPatterns.Pattern> = SecretPatterns.defaultPatterns,
    private val observer: Observer? = null,
) : LeakDetector {
    override fun scan(result: ToolResult): ToolResult {
        if (result !is ToolResult.Ok) return result

        val contentStr = result.content.toString()

        for (pattern in patterns) {
            if (pattern.regex.containsMatchIn(contentStr)) {
                observer?.event(
                    ObserverEvent.LeakDetected(
                        toolName = "unknown",
                        rule = pattern.name,
                        severity = pattern.severity.name,
                    ),
                )
                return ToolResult.Err(
                    "output blocked: leak detector matched rule: ${pattern.name}",
                    retriable = false,
                )
            }
        }

        if (hasHighEntropyToken(contentStr)) {
            return ToolResult.Err(
                "output blocked: leak detector detected high-entropy content",
                retriable = false,
            )
        }

        return result
    }

    private fun hasHighEntropyToken(content: String): Boolean {
        val candidatePattern = Regex("[A-Za-z0-9_-]{32,}")
        val candidates = candidatePattern.findAll(content).map { it.value }

        for (candidate in candidates) {
            if (SecretPatterns.isWhitelisted(candidate)) continue
            if (SecretPatterns.hasHighEntropy(candidate)) return true
        }

        return false
    }
}
