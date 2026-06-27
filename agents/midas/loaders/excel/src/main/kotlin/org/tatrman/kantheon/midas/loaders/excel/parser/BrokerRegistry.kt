package org.tatrman.kantheon.midas.loaders.excel.parser

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import java.io.File

/**
 * A broker's statement layout (Stage 1.5 T2). Loaded from `brokers/<id>.yaml`; one
 * per broker. `columns` maps each logical field the mapper needs to the header text
 * in the sheet; `kindMap` maps the broker's transaction-type vocabulary to the
 * midas `TransactionKind` enum names (`TX_*`).
 */
data class BrokerTemplate(
    val brokerId: String,
    val displayName: String = brokerId,
    val sheet: String,
    val headerRow: Int,
    val dateFormat: String,
    val columns: Map<String, String>,
    val kindMap: Map<String, String> = emptyMap(),
) {
    /** Logical column keys the parser/mapper understand. */
    companion object Fields {
        const val TRADE_DATE = "trade_date"
        const val SETTLE_DATE = "settle_date"
        const val KIND = "kind"
        const val SYMBOL = "symbol"
        const val QUANTITY = "quantity"
        const val PRICE = "price"
        const val CURRENCY = "currency"
        const val FEE = "fee"
        const val TOTAL = "total"
        const val EXTERNAL_ID = "external_id"
    }
}

/** Thrown when an upload names a broker_id with no registered template. */
class UnknownBrokerException(
    brokerId: String,
) : IllegalArgumentException("no broker template registered for broker_id='$brokerId'")

/**
 * Loads + serves the broker templates from the `brokers/` classpath dir (one YAML
 * per broker). Mirrors
 * the ariadne-mcp `ManifestLoader` idiom (Jackson YAML, snake_case keys). Built once
 * at startup; lookups are by `broker_id`.
 */
class BrokerRegistry private constructor(
    private val templates: Map<String, BrokerTemplate>,
) {
    fun get(brokerId: String): BrokerTemplate = templates[brokerId] ?: throw UnknownBrokerException(brokerId)

    fun getOrNull(brokerId: String): BrokerTemplate? = templates[brokerId]

    fun brokerIds(): Set<String> = templates.keys

    companion object {
        private val log = LoggerFactory.getLogger(BrokerRegistry::class.java)
        private val mapper =
            ObjectMapper(YAMLFactory()).apply {
                registerModule(KotlinModule.Builder().build())
                propertyNamingStrategy = PropertyNamingStrategies.SNAKE_CASE
                configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            }

        fun load(classpathBase: String = "brokers"): BrokerRegistry {
            val url = BrokerRegistry::class.java.classLoader.getResource(classpathBase)
            if (url == null || url.protocol != "file") {
                log.warn("No broker templates at classpath:{} (protocol={})", classpathBase, url?.protocol)
                return BrokerRegistry(emptyMap())
            }
            val templates =
                File(url.toURI())
                    .listFiles()
                    .orEmpty()
                    .filter { it.isFile && it.name.endsWith(".yaml") }
                    .mapNotNull { file ->
                        runCatching { mapper.readValue<BrokerTemplate>(file) }
                            .onFailure { log.warn("Failed to load broker template {}: {}", file, it.message) }
                            .getOrNull()
                    }.associateBy { it.brokerId }
            log.info("Loaded {} broker templates: {}", templates.size, templates.keys)
            return BrokerRegistry(templates)
        }

        /** Build directly from templates (tests / programmatic use). */
        fun of(vararg templates: BrokerTemplate): BrokerRegistry = BrokerRegistry(templates.associateBy { it.brokerId })
    }
}
