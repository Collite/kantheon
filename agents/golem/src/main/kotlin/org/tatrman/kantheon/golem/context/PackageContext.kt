package org.tatrman.kantheon.golem.context

import org.tatrman.meta.v1.DrillMapDetail
import org.tatrman.meta.v1.ModelBundle
import org.tatrman.meta.v1.ModelBundleEntity
import org.tatrman.meta.v1.ModelBundleQuery
import org.tatrman.veles.client.MetadataGrpcClient
import org.tatrman.plan.v1.QualifiedName
import java.util.concurrent.atomic.AtomicReference

/**
 * An immutable index over one Ariadne `ModelBundle` — entities/queries by qname or
 * id, drill maps by their "from" pattern. Rebuilt only when the bundle's package
 * hashes change; [hash] is the concatenated `package_versions[].content_hash`.
 */
class ModelSnapshot internal constructor(
    val hash: String,
    val entities: List<ModelBundleEntity>,
    val patternQueries: List<ModelBundleQuery>,
    val namedQueries: List<ModelBundleQuery>,
    val drillMaps: List<DrillMapDetail>,
) {
    private val entityByQname: Map<String, ModelBundleEntity> =
        entities.associateBy { canonicalQname(it.objectDescriptor.qualifiedName) }
    private val patternById: Map<String, ModelBundleQuery> =
        patternQueries.associateBy { it.objectDescriptor.localName }
    private val namedById: Map<String, ModelBundleQuery> =
        namedQueries.associateBy { it.objectDescriptor.localName }
    private val drillsByFrom: Map<String, List<DrillMapDetail>> =
        drillMaps.groupBy { canonicalQname(it.fromPattern) }

    /** Entity by canonical qname (`namespace.name`, package-prefixed when present). */
    fun entity(qname: QualifiedName): ModelBundleEntity? = entityByQname[canonicalQname(qname)]

    /** Pattern query by its local id (the form `preferred_queries` / `QueryNode.pattern_id` reference). */
    fun patternQuery(id: String): ModelBundleQuery? = patternById[id]

    fun namedQuery(id: String): ModelBundleQuery? = namedById[id]

    /** Drill maps whose "from" pattern is [qname] (AMEND/DRILL composition, Stage 3.1). */
    fun drillsFrom(qname: QualifiedName): List<DrillMapDetail> = drillsByFrom[canonicalQname(qname)].orEmpty()

    companion object {
        internal fun from(bundle: ModelBundle): ModelSnapshot =
            ModelSnapshot(
                hash = bundle.packageVersionsList.joinToString(",") { "${it.packageName}=${it.contentHash}" },
                entities = bundle.entitiesList,
                patternQueries = bundle.patternQueriesList,
                namedQueries = bundle.namedQueriesList,
                drillMaps = bundle.drillMapsList,
            )
    }
}

/** Canonical string key for a [QualifiedName] — `package:namespace.name`, blanks dropped. */
internal fun canonicalQname(qn: QualifiedName): String {
    val tail = listOf(qn.namespace, qn.name).filter { it.isNotBlank() }.joinToString(".")
    return if (qn.getPackage().isNotBlank()) "${qn.getPackage()}:$tail" else tail
}

/**
 * The pod's view of its domain model, fetched from Ariadne `GetModel` for the
 * Shem's packages and held in memory. `/ready` gates on a populated context; the
 * model is **required** (no offline fallback — unlike prompts). `refresh()` re-pulls
 * and atomically swaps, skipping the rebuild when the package hashes are unchanged.
 */
class PackageContext(
    private val client: MetadataGrpcClient,
    private val packages: List<String>,
    private val locale: String = "",
) {
    private val snapshot = AtomicReference<ModelSnapshot?>(null)

    val isLoaded: Boolean get() = snapshot.get() != null

    /** The current snapshot, or null before the first successful [refresh]. */
    fun currentOrNull(): ModelSnapshot? = snapshot.get()

    fun current(): ModelSnapshot = snapshot.get() ?: error("PackageContext not loaded — call refresh() first")

    /**
     * Pull the model from Ariadne and swap it in. When the new bundle's package
     * hashes match the held snapshot, the existing snapshot is kept (no rebuild)
     * and returned unchanged.
     */
    suspend fun refresh(): ModelSnapshot {
        val bundle = client.getModel(packages = packages, locale = locale, includeDrillMap = true).model
        val incomingHash = bundle.packageVersionsList.joinToString(",") { "${it.packageName}=${it.contentHash}" }
        val held = snapshot.get()
        if (held != null && held.hash == incomingHash) return held
        val next = ModelSnapshot.from(bundle)
        snapshot.set(next)
        return next
    }
}
