package org.tatrman.pinakes.catalog

/**
 * The asset catalogue — what raw asset was staged, where (`assetRef`), and which
 * logical feed it belongs to (the feed binds the pipeline, architecture §7). The
 * write path's provenance root: asset → run → corpus entries (lineage lands in
 * Phase 3).
 *
 * In-memory at P1 (the running profile + spec double); the PG-backed schema
 * (`V1__assets.sql`) is the deploy path, wired when Pinakes gains its own small
 * schema on the one PG (plan §8).
 */
data class AssetRecord(
    val id: String,
    val assetRef: String,
    val sourceFeed: String,
    val mimeType: String,
    val originalName: String,
    val stagedAt: String = "",
)

interface AssetCatalog {
    fun record(asset: AssetRecord): AssetRecord

    fun get(id: String): AssetRecord?

    fun list(sourceFeed: String? = null): List<AssetRecord>
}

class InMemoryAssetCatalog : AssetCatalog {
    private val assets = linkedMapOf<String, AssetRecord>()

    override fun record(asset: AssetRecord): AssetRecord {
        assets[asset.id] = asset
        return asset
    }

    override fun get(id: String): AssetRecord? = assets[id]

    override fun list(sourceFeed: String?): List<AssetRecord> =
        assets.values.filter { sourceFeed == null || it.sourceFeed == sourceFeed }.toList()
}
