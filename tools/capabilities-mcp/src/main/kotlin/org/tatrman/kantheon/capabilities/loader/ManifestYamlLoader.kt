package org.tatrman.kantheon.capabilities.loader

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.oshai.kotlinlogging.KotlinLogging
import org.tatrman.kantheon.capabilities.registry.InMemoryRegistry
import org.tatrman.kantheon.capabilities.v1.AgentCapability
import org.tatrman.kantheon.capabilities.v1.AgentKind
import org.tatrman.kantheon.capabilities.v1.Capability
import org.tatrman.kantheon.capabilities.v1.CostHints
import org.tatrman.kantheon.capabilities.v1.HitlProfile
import org.tatrman.kantheon.capabilities.v1.IntentKind
import org.tatrman.kantheon.capabilities.v1.LocaleDefault
import org.tatrman.kantheon.capabilities.v1.Predicate
import org.tatrman.kantheon.capabilities.v1.TermDef
import org.tatrman.kantheon.capabilities.v1.ToolCapability
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * Loads source-controlled `AgentCapability` / `ToolCapability` YAML fixtures
 * into an [InMemoryRegistry] at boot.
 *
 * Loader rules (`contracts.md` §3.4):
 *  - Scan `agents/` and `tools/` subdirs for `*` `.yaml` files under [classpathBase] or [filesystemBase].
 *  - Invalid YAML logs + skips (does not crash).
 *  - Loaded fixtures register with `fromFixture = true` so the TTL pruner skips them.
 *  - Runtime registrations override fixtures on natural-id collision (runtime wins).
 */
class ManifestYamlLoader(
    private val classpathBase: String? = null,
    private val filesystemBase: Path? = null,
) {
    init {
        require((classpathBase == null) xor (filesystemBase == null)) {
            "ManifestYamlLoader requires exactly one of classpathBase / filesystemBase"
        }
    }

    data class Skipped(
        val path: String,
        val reason: String,
    )

    data class LoadReport(
        val loaded: Int,
        val skipped: List<Skipped>,
    )

    fun loadAll(registry: InMemoryRegistry): LoadReport {
        val skipped = mutableListOf<Skipped>()
        var loaded = 0
        agentEntries().forEach { (label, stream) ->
            stream.use { s ->
                runCatching { parseAgent(s.bufferedReader().readText()) }
                    .onSuccess {
                        registry.register(Capability.newBuilder().setAgent(it).build(), fromFixture = true)
                        loaded++
                        logger.info { "loaded agent manifest $label → ${it.agentId}" }
                    }.onFailure {
                        logger.warn(it) { "skipping agent manifest $label" }
                        skipped += Skipped(label, it.message ?: it::class.simpleName.orEmpty())
                    }
            }
        }
        toolEntries().forEach { (label, stream) ->
            stream.use { s ->
                runCatching { parseTool(s.bufferedReader().readText()) }
                    .onSuccess {
                        registry.register(Capability.newBuilder().setTool(it).build(), fromFixture = true)
                        loaded++
                        logger.info { "loaded tool manifest $label → ${it.capabilityId}" }
                    }.onFailure {
                        logger.warn(it) { "skipping tool manifest $label" }
                        skipped += Skipped(label, it.message ?: it::class.simpleName.orEmpty())
                    }
            }
        }
        return LoadReport(loaded = loaded, skipped = skipped)
    }

    private fun agentEntries(): Sequence<Pair<String, InputStream>> = subdirEntries("agents")

    private fun toolEntries(): Sequence<Pair<String, InputStream>> = subdirEntries("tools")

    private fun subdirEntries(subdir: String): Sequence<Pair<String, InputStream>> =
        when {
            classpathBase != null -> classpathYamls("${classpathBase.trimEnd('/')}/$subdir")
            else -> filesystemYamls(filesystemBase!!.resolve(subdir))
        }

    private fun classpathYamls(base: String): Sequence<Pair<String, InputStream>> {
        // Resources are discovered by attempting a directory listing on the classloader URL.
        val url = javaClass.getResource(base) ?: return emptySequence()
        return when (url.protocol) {
            "file" ->
                File(url.toURI())
                    .listFiles()
                    .orEmpty()
                    .asSequence()
                    .filter { it.isFile && it.name.endsWith(".yaml") }
                    .sortedBy { it.name }
                    .map { f -> f.name to f.inputStream() }

            else -> emptySequence() // jar:/ scanning not needed at v1; classpath base is `src/main/resources`
        }
    }

    private fun filesystemYamls(dir: Path): Sequence<Pair<String, InputStream>> {
        if (!Files.isDirectory(dir)) return emptySequence()
        return Files
            .list(dir)
            .filter { it.fileName.toString().endsWith(".yaml") }
            .sorted()
            .toList()
            .asSequence()
            .map { p -> p.fileName.toString() to Files.newInputStream(p) }
    }

    companion object {
        private val mapper: ObjectMapper =
            ObjectMapper(YAMLFactory())
                .registerModule(KotlinModule.Builder().build())
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

        fun parseAgent(yaml: String): AgentCapability {
            val node: AgentYaml = mapper.readValue(yaml)
            require(node.agentKind != null) { "agent_kind is required" }
            require(!node.agentId.isNullOrBlank()) { "agent_id is required" }

            val builder =
                AgentCapability
                    .newBuilder()
                    .setAgentKind(AgentKind.valueOf(node.agentKind))
                    .setAgentId(node.agentId)
                    .setDisplayName(node.displayName.orEmpty())
                    .addAllIntentKindsSupported(node.intentKindsSupported.orEmpty().map(IntentKind::valueOf))
                    .setDescriptionForRouter(node.descriptionForRouter.orEmpty())
                    .addAllExampleQuestions(node.exampleQuestions.orEmpty())
                    .addAllCounterExamples(node.counterExamples.orEmpty())
                    .addAllCapabilityRefs(node.capabilityRefs.orEmpty())
                    .setServiceEndpoint(node.serviceEndpoint.orEmpty())
                    .setHealthCheckPath(node.healthCheckPath.orEmpty())
                    .setTypicalLatencyMs(node.typicalLatencyMs ?: 0)
                    .setTypicalCostUsd(node.typicalCostUsd ?: 0.0)
                    .setHitlDefault(node.hitlDefault?.let(HitlProfile::valueOf) ?: HitlProfile.HITL_PROFILE_UNSPECIFIED)
                    .setAreaName(node.areaName.orEmpty())
                    .addAllAreaEntities(node.areaEntities.orEmpty())
                    .addAllPreferredQueries(node.preferredQueries.orEmpty())
                    .addAllPreferredCapabilities(node.preferredCapabilities.orEmpty())
                    .setStyleAddendum(node.styleAddendum.orEmpty())
                    // Hebe P3 S3.4 — registry transports non_routable (16) + visibility_roles (17).
                    .setNonRoutable(node.nonRoutable ?: false)
                    .addAllVisibilityRoles(node.visibilityRoles.orEmpty())

            node.areaTerminology.orEmpty().forEach { td ->
                builder.addAreaTerminology(
                    TermDef
                        .newBuilder()
                        .setTerm(td.term.orEmpty())
                        .setDefinition(td.definition.orEmpty())
                        .addAllSynonyms(td.synonyms.orEmpty())
                        .build(),
                )
            }
            node.localeDefaults.orEmpty().forEach { ld ->
                builder.addLocaleDefaults(
                    LocaleDefault
                        .newBuilder()
                        .setLocale(ld.locale.orEmpty())
                        .setGreeting(ld.greeting.orEmpty())
                        .setDateFormat(ld.dateFormat.orEmpty())
                        .setCurrency(ld.currency.orEmpty())
                        .build(),
                )
            }
            return builder.build()
        }

        fun parseTool(yaml: String): ToolCapability {
            val node: ToolYaml = mapper.readValue(yaml)
            require(!node.capabilityId.isNullOrBlank()) { "capability_id is required" }

            val builder =
                ToolCapability
                    .newBuilder()
                    .setCapabilityId(node.capabilityId)
                    .setCategory(node.category.orEmpty())
                    .setVersion(node.version.orEmpty())
                    .setDescription(node.description.orEmpty())
                    .setServiceEndpoint(node.serviceEndpoint.orEmpty())
                    .addAllSearchTags(node.searchTags.orEmpty())

            node.preconditions.orEmpty().forEach { p ->
                builder.addPreconditions(
                    Predicate
                        .newBuilder()
                        .setExpression(p.expression.orEmpty())
                        .setDescription(p.description.orEmpty())
                        .build(),
                )
            }
            node.costHints?.let { ch ->
                builder.costHints =
                    CostHints
                        .newBuilder()
                        .setTypicalLatencyMs(ch.typicalLatencyMs ?: 0.0)
                        .setTypicalCostUsd(ch.typicalCostUsd ?: 0.0)
                        .setIsIdempotent(ch.isIdempotent ?: false)
                        .setMaxConcurrent(ch.maxConcurrent ?: 0)
                        .build()
            }
            return builder.build()
        }
    }
}

// Jackson DTOs — snake_case is mapped to camelCase via PropertyNamingStrategies.SNAKE_CASE.
internal data class AgentYaml(
    val agentKind: String? = null,
    val agentId: String? = null,
    val displayName: String? = null,
    val intentKindsSupported: List<String>? = null,
    val descriptionForRouter: String? = null,
    val exampleQuestions: List<String>? = null,
    val counterExamples: List<String>? = null,
    val capabilityRefs: List<String>? = null,
    val serviceEndpoint: String? = null,
    val healthCheckPath: String? = null,
    val typicalLatencyMs: Int? = null,
    val typicalCostUsd: Double? = null,
    val hitlDefault: String? = null,
    val areaName: String? = null,
    val areaEntities: List<String>? = null,
    val areaTerminology: List<TermDefYaml>? = null,
    val preferredQueries: List<String>? = null,
    val preferredCapabilities: List<String>? = null,
    val styleAddendum: String? = null,
    val localeDefaults: List<LocaleDefaultYaml>? = null,
    // Hebe P3 S3.4 — non_routable (Hebe registers true; excluded from the routing view)
    // and visibility_roles (PD-8; transported, Themis filters per-caller).
    val nonRoutable: Boolean? = null,
    val visibilityRoles: List<String>? = null,
)

internal data class TermDefYaml(
    val term: String? = null,
    val definition: String? = null,
    val synonyms: List<String>? = null,
)

internal data class LocaleDefaultYaml(
    val locale: String? = null,
    val greeting: String? = null,
    val dateFormat: String? = null,
    val currency: String? = null,
)

internal data class ToolYaml(
    val capabilityId: String? = null,
    val category: String? = null,
    val version: String? = null,
    val description: String? = null,
    val serviceEndpoint: String? = null,
    val searchTags: List<String>? = null,
    val preconditions: List<PredicateYaml>? = null,
    val costHints: CostHintsYaml? = null,
)

internal data class PredicateYaml(
    val expression: String? = null,
    val description: String? = null,
)

internal data class CostHintsYaml(
    val typicalLatencyMs: Double? = null,
    val typicalCostUsd: Double? = null,
    val isIdempotent: Boolean? = null,
    val maxConcurrent: Int? = null,
)
