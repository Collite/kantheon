package org.tatrman.kantheon.midas.core.mcp

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.tatrman.kantheon.capabilities.v1.Capability
import org.tatrman.kantheon.capabilities.v1.CostHints
import org.tatrman.kantheon.capabilities.v1.ToolCapability
import java.io.File

/**
 * Loads the five Midas-core `ToolCapability` manifests under
 * `src/main/resources/manifests/tools/` and builds one [Capability] per manifest
 * (the `tools/veles-mcp` pattern, copied — the shared `ManifestYamlLoader` lives
 * in `:tools:capabilities-mcp`, which a service must not depend on). Snake_case YAML
 * keys map to the camelCase Kotlin properties.
 */
internal class ManifestLoader {
    private val log = LoggerFactory.getLogger(ManifestLoader::class.java)
    private val mapper =
        ObjectMapper(YAMLFactory()).apply {
            registerModule(KotlinModule.Builder().build())
            propertyNamingStrategy = PropertyNamingStrategies.SNAKE_CASE
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }

    data class ToolManifest(
        val capabilityId: String? = null,
        val category: String? = null,
        val version: String? = null,
        val description: String? = null,
        val serviceEndpoint: String? = null,
        val searchTags: List<String>? = null,
        val costHints: CostHintsManifest? = null,
    )

    data class CostHintsManifest(
        val typicalLatencyMs: Double? = null,
        val typicalCostUsd: Double? = null,
        val isIdempotent: Boolean? = null,
        val maxConcurrent: Int? = null,
    )

    fun loadAll(classpathBase: String = "manifests/tools"): List<Capability> {
        val url =
            ManifestLoader::class.java.classLoader.getResource(classpathBase) ?: run {
                log.warn("No manifests at classpath:{}; no capabilities to register", classpathBase)
                return emptyList()
            }
        val files: List<File> =
            when (url.protocol) {
                "file" ->
                    File(url.toURI())
                        .listFiles()
                        .orEmpty()
                        .filter { it.isFile && it.name.endsWith(".yaml") }
                        .sortedBy { it.name }
                else -> {
                    log.warn("Unsupported manifests URL protocol '{}'; skipping", url.protocol)
                    return emptyList()
                }
            }
        val caps = mutableListOf<Capability>()
        for (file in files) {
            try {
                val manifest = mapper.readValue<ToolManifest>(file)
                val cap = manifest.toCapability(file.path) ?: continue
                caps.add(cap)
            } catch (e: Exception) {
                log.warn("Failed to load manifest {}: {}", file, e.message)
            }
        }
        log.info("Loaded {} tool capabilities from classpath:{}", caps.size, classpathBase)
        return caps
    }

    private fun ToolManifest.toCapability(sourcePath: String): Capability? {
        if (capabilityId.isNullOrBlank()) {
            log.warn("Manifest {} has blank capability_id; skipping", sourcePath)
            return null
        }
        val builder =
            ToolCapability
                .newBuilder()
                .setCapabilityId(capabilityId)
                .setCategory(category.orEmpty())
                .setVersion(version.orEmpty())
                .setDescription(description.orEmpty())
                .setServiceEndpoint(serviceEndpoint.orEmpty())
                .addAllSearchTags(searchTags.orEmpty())
        costHints?.let { ch ->
            builder.costHints =
                CostHints
                    .newBuilder()
                    .setTypicalLatencyMs(ch.typicalLatencyMs ?: 0.0)
                    .setTypicalCostUsd(ch.typicalCostUsd ?: 0.0)
                    .setIsIdempotent(ch.isIdempotent ?: false)
                    .setMaxConcurrent(ch.maxConcurrent ?: 0)
                    .build()
        }
        return Capability.newBuilder().setTool(builder.build()).build()
    }
}
