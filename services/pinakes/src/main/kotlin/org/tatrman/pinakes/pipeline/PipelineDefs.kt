package org.tatrman.pinakes.pipeline

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.Serializable
import org.tatrman.pinakes.v1.StageKind

/**
 * Pipeline definitions loaded from YAML (`pinakes.pipelines.path`, contracts §11).
 * Each feed declares its pipeline (per-source binding, architecture §7). The
 * conformed `embed` block must agree with the corpus dimension (enforced by
 * [PipelineRegistry.register]).
 */
@Serializable
private data class EmbedDef(
    val modelId: String,
    val dimensions: Int,
    val modelVersion: String,
)

@Serializable
private data class PipelineDef(
    val id: String,
    val displayName: String = "",
    val sourceFeed: String,
    val stages: List<String>,
    val embed: EmbedDef,
)

@Serializable
private data class PipelineDefsFile(
    val pipelines: List<PipelineDef> = emptyList(),
)

object PipelineDefs {
    fun fromYaml(text: String): List<Pipeline> {
        val file = Yaml.default.decodeFromString(PipelineDefsFile.serializer(), text)
        return file.pipelines.map { def ->
            Pipeline(
                id = def.id,
                displayName = def.displayName.ifBlank { def.id },
                sourceFeed = def.sourceFeed,
                stages = def.stages.map { StageKind.valueOf(it.trim().uppercase()) },
                embed = EmbedSpec(def.embed.modelId, def.embed.dimensions, def.embed.modelVersion),
            )
        }
    }

    /**
     * The built-in mechanical pipeline (EXTRACT→CHUNK→LOAD→EMBED) for the default
     * feed — used when no YAML defs are present. compile/link/resolve join in S3.2.
     */
    fun mechanicalDefault(embed: EmbedSpec): Pipeline =
        Pipeline(
            id = "mechanical-default",
            displayName = "Mechanical default",
            sourceFeed = "default",
            stages = listOf(StageKind.EXTRACT, StageKind.CHUNK, StageKind.LOAD, StageKind.EMBED),
            embed = embed,
        )
}
