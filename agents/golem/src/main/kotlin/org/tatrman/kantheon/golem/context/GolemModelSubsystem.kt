package org.tatrman.kantheon.golem.context

import com.typesafe.config.Config
import org.slf4j.LoggerFactory
import org.tatrman.ariadne.v1.ResolveAreaResponse
import org.tatrman.kantheon.ariadne.client.GrpcMetadataGrpcClient
import org.tatrman.kantheon.ariadne.client.MetadataGrpcClient
import org.tatrman.kantheon.golem.prompts.PromptStore
import org.tatrman.kantheon.golem.shem.ShemAssembler
import org.tatrman.kantheon.golem.shem.ShemContext
import org.tatrman.kantheon.golem.shem.ShemOverlay
import org.tatrman.kantheon.golem.shem.ShemOverlayParser
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

private val log = LoggerFactory.getLogger("org.tatrman.kantheon.golem.context.GolemModelSubsystem")

/**
 * The Shem + Ariadne-fed model/prompt subsystem of a Golem pod, assembled together
 * because `/ready` gates on all of it. Any member is null when not configured
 * (skeleton / local boot without a mounted Shem); a configured Shem implies the model
 * + prompt stores exist and must load before the pod is ready.
 *
 * The Shem's `AgentCapability` is **assembled at boot** from four sources
 * (golem/contracts §6): the overlay's `source`/`overlay` blocks, the resolved areas'
 * descriptions/tags, and — once the model loads — the model-derived entity/query/term
 * fields. [shem] is a placeholder (overlay-only fields) until [load]/[refresh] swaps
 * in the model-derived fields; admission reads only overlay fields, so it is correct
 * throughout.
 */
class GolemModelSubsystem(
    val shem: ShemContext?,
    val packageContext: PackageContext?,
    val promptStore: PromptStore?,
    private val ariadneClient: MetadataGrpcClient?,
    private val overlay: ShemOverlay? = null,
    private val areaResults: List<ResolveAreaResponse> = emptyList(),
) {
    private val resolvedAreas = AtomicReference(areaResults)

    /** True once the configured Shem's model + prompts are loaded (always true when no Shem). */
    val isReady: Boolean
        get() =
            if (shem == null) {
                true
            } else {
                (packageContext?.isLoaded ?: false) && (promptStore?.isLoaded ?: false)
            }

    /** Pull model + prompts (warn-and-continue — a failed load leaves the pod not-ready, retryable via /v1/refresh). */
    suspend fun load() {
        try {
            packageContext?.refresh()
            reassemble()
        } catch (e: Exception) {
            log.warn(
                "initial model load from Ariadne failed: {} — pod stays not-ready, retry via /v1/refresh",
                e.message,
            )
        }
        // PromptStore reads the mounted Shem bundle (or bundled fallback) — does not throw.
        promptStore?.refresh()
    }

    /**
     * Re-pull the model from Ariadne, re-assemble the Shem's model-derived fields, and
     * re-read the prompts from the mounted Shem bundle (ops `/v1/refresh`). Prompts are
     * remount-driven, so this just re-reads whatever is currently mounted (a remount
     * supplies new content); the area resolution is cached from boot.
     */
    suspend fun refresh() {
        packageContext?.refresh()
        reassemble()
        promptStore?.refresh()
    }

    /** Swap the freshly-loaded model into the Shem's `AgentCapability` (no-op without a Shem/model). */
    private fun reassemble() {
        val ctx = shem ?: return
        val ov = overlay ?: return
        val model = packageContext?.currentOrNull() ?: return
        ctx.update(ShemAssembler.assemble(ov, resolvedAreas.get(), model))
    }

    fun close() = ariadneClient?.close()

    companion object {
        /**
         * Assemble from config. `<golem.shem.dir>/shem.yaml` is the `kantheon.shem/v1`
         * overlay; the model comes from Ariadne (`golem.ariadne.host`), the prompts from
         * the mounted Shem bundle (`golem.shem.dir`, default `/etc/golem/shem`). A mounted
         * overlay + Ariadne client wire the model store; the prompt store is wired whenever
         * an overlay is present (prompts are remount-driven, not Ariadne-fed). No `shem.yaml`
         * → an empty subsystem (skeleton boot stays ready).
         *
         * The Shem's `AgentCapability` is assembled at boot: each `source.areas` entry is
         * resolved to its packages (`ResolveArea`), the union (deduped, order-preserving)
         * feeds `GetModel`, and the loaded model supplies `area_entities`/`preferred_queries`/
         * `area_terminology`. Until the model loads, [shem] carries the overlay-only fields.
         */
        fun fromConfig(config: Config): GolemModelSubsystem {
            val shemDir = Path.of(config.optionalString("golem.shem.dir") ?: "/etc/golem/shem")
            val locale = config.optionalString("golem.locale").orEmpty().ifBlank { "cs" }
            return build(shemDir, buildAriadneClient(config), locale)
        }

        /**
         * Wire the subsystem from a mounted Shem dir + an (optional) Ariadne client.
         * Shared by [fromConfig] and the boot component spec — the only difference is
         * where the client comes from (config-built gRPC vs an injected mock).
         */
        internal fun build(
            shemDir: Path,
            client: MetadataGrpcClient?,
            locale: String,
        ): GolemModelSubsystem {
            val overlayFile = shemDir.resolve("shem.yaml")
            if (!Files.isRegularFile(overlayFile)) {
                log.info("no Shem overlay at {} — skeleton boot (no Shem)", overlayFile)
                client?.close()
                return GolemModelSubsystem(null, null, null, null)
            }

            val overlay = ShemOverlayParser.parse(Files.readString(overlayFile))
            log.info(
                "loaded Shem overlay '{}' (areas {}) from {}",
                overlay.source.id,
                overlay.source.areas,
                overlayFile,
            )

            // Placeholder Shem — overlay-only fields; model-derived fields fill in at load().
            val shem = ShemContext(ShemAssembler.identity(overlay))

            val promptStore = PromptStore(shemDir = shemDir, locale = locale)
            log.info("Golem prompts mounted from {} (locale {})", shemDir, locale)

            if (client == null) {
                log.warn(
                    "Shem '{}' loaded but golem.ariadne.host is unset — model will not load (prompts still mounted)",
                    shem.golemId,
                )
                return GolemModelSubsystem(shem, null, promptStore, null, overlay)
            }

            // Resolve each area to its packages, union (deduped, order-preserving).
            val (areaResults, packages) = resolveAreas(client, overlay.source.areas)
            return GolemModelSubsystem(
                shem = shem,
                packageContext = PackageContext(client, packages = packages, locale = locale),
                promptStore = promptStore,
                ariadneClient = client,
                overlay = overlay,
                areaResults = areaResults,
            )
        }

        /** Resolve each area to its package set, returning the per-area responses + their deduped union. */
        private fun resolveAreas(
            client: MetadataGrpcClient,
            areas: List<String>,
        ): Pair<List<ResolveAreaResponse>, List<String>> {
            val results = mutableListOf<ResolveAreaResponse>()
            val packages = linkedSetOf<String>()
            for (area in areas) {
                val resp =
                    try {
                        kotlinx.coroutines.runBlocking { client.resolveArea(area) }
                    } catch (e: Exception) {
                        log.warn("ResolveArea('{}') failed at boot: {} — area contributes no packages", area, e.message)
                        continue
                    }
                if (!resp.found) {
                    log.warn("ResolveArea('{}') returned found=false — area contributes no packages", area)
                }
                results += resp
                packages += resp.packagesList
            }
            log.info("Shem areas {} resolved to packages {}", areas, packages)
            return results to packages.toList()
        }

        private fun buildAriadneClient(config: Config): MetadataGrpcClient? {
            val host = config.optionalString("golem.ariadne.host")?.takeIf { it.isNotBlank() } ?: return null
            val port = if (config.hasPath("golem.ariadne.port")) config.getInt("golem.ariadne.port") else 7261
            log.info("Ariadne client → {}:{}", host, port)
            return GrpcMetadataGrpcClient(host = host, port = port)
        }

        private fun Config.optionalString(path: String): String? = if (hasPath(path)) getString(path) else null
    }
}
