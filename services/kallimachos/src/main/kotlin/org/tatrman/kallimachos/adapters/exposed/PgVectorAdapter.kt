package org.tatrman.kallimachos.adapters.exposed

import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.tatrman.kallimachos.adapters.vector.PartVectorRecord
import org.tatrman.kallimachos.adapters.vector.VectorHit
import org.tatrman.kallimachos.adapters.vector.VectorPort
import org.tatrman.kallimachos.embeddings.EmbedConfig

/**
 * The live vector plane on pgvector. Upsert is keyed by
 * `(part_id, model_id, model_version)` (idempotent re-embed); KNN uses the
 * cosine operator `<=>` over the ivfflat index, joined to `parts` for the
 * source-id mart filter. KNN filters to the conformed model. Integration-verified
 * (the unit gate uses the in-memory adapter).
 *
 * The embedding vector is inlined as a `'[…]'::vector` literal — the floats are
 * server-produced (never user input), which sidesteps Exposed param binding for
 * the pgvector type.
 */
class PgVectorAdapter(
    private val config: EmbedConfig,
) : VectorPort {
    override fun upsert(vectors: List<PartVectorRecord>) {
        if (vectors.isEmpty()) return
        val tx = TransactionManager.current()
        // One multi-row upsert per call rather than a round-trip per vector — a
        // source's parts land in a single statement. Floats are server-produced
        // (never user input), so inlining the literal is safe.
        val values =
            vectors.joinToString(",") { v ->
                val lit = v.vector.joinToString(",", "[", "]")
                "(${v.partId}, '${v.modelId}', '${v.modelVersion}', '$lit'::vector)"
            }
        tx.exec(
            "INSERT INTO doc_vectors(part_id, model_id, model_version, embedding) " +
                "VALUES $values " +
                "ON CONFLICT (part_id, model_id, model_version) DO UPDATE SET embedding = EXCLUDED.embedding",
        )
    }

    override fun knn(
        query: FloatArray,
        k: Int,
        allowedSourceIds: Set<Long>?,
    ): List<VectorHit> {
        if (allowedSourceIds != null && allowedSourceIds.isEmpty()) return emptyList()
        val lit = query.joinToString(",", "[", "]")
        val sourceFilter =
            if (allowedSourceIds ==
                null
            ) {
                ""
            } else {
                "AND p.source_id IN (${allowedSourceIds.joinToString(",")})"
            }
        val limit = k.coerceAtLeast(1)
        val sql =
            "SELECT dv.part_id AS part_id, p.source_id AS source_id, " +
                "1 - (dv.embedding <=> '$lit'::vector) AS score " +
                "FROM doc_vectors dv JOIN parts p ON p.id = dv.part_id " +
                "WHERE dv.model_id = '${config.modelId}' AND dv.model_version = '${config.modelVersion}' " +
                "$sourceFilter ORDER BY dv.embedding <=> '$lit'::vector LIMIT $limit"
        return TransactionManager.current().exec(sql) { rs ->
            val hits = mutableListOf<VectorHit>()
            while (rs.next()) {
                hits += VectorHit(rs.getLong("part_id"), rs.getLong("source_id"), rs.getDouble("score"))
            }
            hits.toList()
        } ?: emptyList()
    }
}
