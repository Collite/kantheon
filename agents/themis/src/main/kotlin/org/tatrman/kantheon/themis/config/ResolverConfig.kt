package org.tatrman.kantheon.themis.config

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.tatrman.kantheon.llm.client.LlmGatewayEndpoint

/** Adapt a generic [EndpointConfig] to the shared llm-gateway-client's endpoint type. */
fun EndpointConfig.toLlmGatewayEndpoint(): LlmGatewayEndpoint =
    LlmGatewayEndpoint(host = host, port = port, timeoutMs = timeoutMs)

data class ServerConfig(
    val port: Int,
    val host: String,
)

data class ResolverAppConfig(
    val server: ServerConfig,
    val nlp: EndpointConfig,
    val fuzzy: EndpointConfig,
    val llmGateway: EndpointConfig,
    val hmac: HmacConfig,
    val cache: CacheConfig,
    val hitl: HitlConfig,
    val eval: EvalConfig,
    // Phase 3 Stage 3.3: capabilities-mcp registry endpoint (routeToAgent).
    val capabilities: EndpointConfig = EndpointConfig("capabilities-mcp", 7501, 5000L),
)

data class EndpointConfig(
    val host: String,
    val port: Int,
    val timeoutMs: Long,
)

data class HmacConfig(
    val secretKey: String,
    val previousKey: String = "",
    val keyVersion: Int = 1,
)

data class CacheConfig(
    val nlpLruSize: Int,
    val resolutionLruSize: Int,
)

data class HitlConfig(
    val confidenceThreshold: Double,
    val maxRounds: Int,
)

data class EvalConfig(
    val corpusPath: String,
)

private fun Config.stringOrEnv(
    path: String,
    envKey: String,
    default: String = "",
): String =
    try {
        getString(path)
    } catch (_: Exception) {
        System.getenv(envKey) ?: default
    }

private fun Config.intOrEnv(
    path: String,
    envKey: String,
    default: Int,
): Int =
    try {
        getInt(path)
    } catch (_: Exception) {
        System.getenv(envKey)?.toIntOrNull() ?: default
    }

private fun Config.longOrEnv(
    path: String,
    envKey: String,
    default: Long,
): Long =
    try {
        getLong(path)
    } catch (_: Exception) {
        System.getenv(envKey)?.toLongOrNull() ?: default
    }

private fun Config.doubleOrEnv(
    path: String,
    envKey: String,
    default: Double,
): Double =
    try {
        getDouble(path)
    } catch (_: Exception) {
        System.getenv(envKey)?.toDoubleOrNull() ?: default
    }

fun resolverConfigFrom(config: Config): ResolverAppConfig {
    val serverSection = config.getConfig("server")
    val resolverSection = config.getConfig("themis")
    val nlpSection = resolverSection.getConfig("nlp")
    val fuzzySection = resolverSection.getConfig("fuzzy")
    val llmSection = resolverSection.getConfig("llm-gateway")
    val hmacSection = resolverSection.getConfig("hmac")
    val cacheSection = resolverSection.getConfig("cache")
    val hitlSection = resolverSection.getConfig("hitl")
    val evalSection = resolverSection.getConfig("eval")
    val capabilitiesSection =
        if (resolverSection.hasPath(
                "capabilities",
            )
        ) {
            resolverSection.getConfig("capabilities")
        } else {
            ConfigFactory.empty()
        }

    return ResolverAppConfig(
        server =
            ServerConfig(
                port = serverSection.intOrEnv("port", "SERVER_PORT", 7171),
                host = serverSection.stringOrEnv("host", "SERVER_HOST", "0.0.0.0"),
            ),
        nlp =
            EndpointConfig(
                host = nlpSection.stringOrEnv("host", "NLP_SERVICE_HOST", "nlp-service"),
                port = nlpSection.intOrEnv("port", "NLP_SERVICE_PORT", 8000),
                timeoutMs = nlpSection.longOrEnv("timeout-ms", "NLP_SERVICE_TIMEOUT_MS", 30000L),
            ),
        fuzzy =
            EndpointConfig(
                host = fuzzySection.stringOrEnv("host", "FUZZY_SERVICE_HOST", "fuzzy-mcp"),
                port = fuzzySection.intOrEnv("port", "FUZZY_SERVICE_PORT", 7143),
                timeoutMs = fuzzySection.longOrEnv("timeout-ms", "FUZZY_SERVICE_TIMEOUT_MS", 15000L),
            ),
        llmGateway =
            EndpointConfig(
                host = llmSection.stringOrEnv("host", "LLM_GATEWAY_HOST", "llm-gateway"),
                port = llmSection.intOrEnv("port", "LLM_GATEWAY_PORT", 8090),
                timeoutMs = llmSection.longOrEnv("timeout-ms", "LLM_GATEWAY_TIMEOUT_MS", 60000L),
            ),
        hmac =
            HmacConfig(
                secretKey = hmacSection.stringOrEnv("secret-key", "HMAC_SECRET_KEY", "dev-secret-change-in-production"),
                previousKey = hmacSection.stringOrEnv("previous-key", "HMAC_PREVIOUS_KEY", ""),
                keyVersion = hmacSection.intOrEnv("key-version", "HMAC_KEY_VERSION", 1),
            ),
        cache =
            CacheConfig(
                nlpLruSize = cacheSection.intOrEnv("nlp-lru-size", "NLP_CACHE_SIZE", 1000),
                resolutionLruSize = cacheSection.intOrEnv("resolution-lru-size", "RESOLUTION_CACHE_SIZE", 1000),
            ),
        hitl =
            HitlConfig(
                confidenceThreshold = hitlSection.doubleOrEnv("confidence-threshold", "CONFIDENCE_THRESHOLD", 0.75),
                maxRounds = hitlSection.intOrEnv("max-rounds", "HITL_MAX_ROUNDS", 3),
            ),
        eval =
            EvalConfig(
                corpusPath = evalSection.stringOrEnv("corpus-path", "EVAL_CORPUS_PATH", "eval/corpus/seed.jsonl"),
            ),
        capabilities =
            EndpointConfig(
                host = capabilitiesSection.stringOrEnv("host", "CAPABILITIES_MCP_HOST", "capabilities-mcp"),
                port = capabilitiesSection.intOrEnv("port", "CAPABILITIES_MCP_PORT", 7501),
                timeoutMs = capabilitiesSection.longOrEnv("timeout-ms", "CAPABILITIES_MCP_TIMEOUT_MS", 5000L),
            ),
    )
}
