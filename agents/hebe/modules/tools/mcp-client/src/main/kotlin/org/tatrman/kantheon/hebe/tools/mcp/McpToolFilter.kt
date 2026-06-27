package org.tatrman.kantheon.hebe.tools.mcp

import org.tatrman.kantheon.hebe.config.McpClientServerConfig
import org.slf4j.LoggerFactory

@Suppress("MagicNumber")
class McpToolFilter {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val maxMessagePreviewLen = 50

    fun applicableTools(
        serverConfig: McpClientServerConfig,
        allToolNames: List<String>,
        userMessage: String,
    ): List<String> {
        val always = serverConfig.alwaysTools.toSet()
        val dynamic = serverConfig.dynamicTools.toSet()
        val keywords = serverConfig.dynamicKeywords

        val alwaysIncluded = allToolNames.filter { it in always }

        val dynamicIncluded =
            if (keywords.isNotEmpty() && userMessage.isNotBlank()) {
                val lowerMessage = userMessage.lowercase()
                allToolNames.filter { name ->
                    name in dynamic && keywords.any { keyword -> lowerMessage.contains(keyword.lowercase()) }
                }
            } else {
                emptyList()
            }

        val result = alwaysIncluded + dynamicIncluded
        logger.debug(
            "Tool filter for server '{}': always={}, dynamic={}, message='{}' -> {}",
            serverConfig.name,
            alwaysIncluded,
            dynamicIncluded,
            userMessage.take(maxMessagePreviewLen),
            result,
        )
        return result.distinct()
    }
}
