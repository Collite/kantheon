package org.tatrman.kantheon.hebe.memory.search

import org.tatrman.kantheon.hebe.api.HitSource
import org.tatrman.kantheon.hebe.api.MemoryHit

object Rrf {
    fun fuse(
        ftsHits: List<RankedHit>,
        vecHits: List<RankedHit>,
        k0: Int = 60,
        k: Int = 10,
    ): List<MemoryHit> {
        val scoreMap = mutableMapOf<String, ScoredHit>()
        for ((rank, hit) in ftsHits.withIndex()) {
            val key = hit.key
            val score = 1.0 / (k0 + rank + 1)
            scoreMap[key] =
                scoreMap
                    .getOrPut(key) {
                        ScoredHit(hit.docPath, hit.chunkIdx, hit.snippet, HitSource.Fts, 0.0)
                    }.addScore(score, HitSource.Fts)
        }
        for ((rank, hit) in vecHits.withIndex()) {
            val key = hit.key
            val score = 1.0 / (k0 + rank + 1)
            scoreMap[key] =
                scoreMap
                    .getOrPut(key) {
                        ScoredHit(hit.docPath, hit.chunkIdx, hit.snippet, HitSource.Vector, 0.0)
                    }.addScore(score, HitSource.Vector)
        }
        return scoreMap.values
            .sortedByDescending { it.score }
            .take(k)
            .map { it.toMemoryHit() }
    }

    data class RankedHit(
        val docPath: String,
        val chunkIdx: Int,
        val snippet: String,
    ) {
        val key: String get() = "$docPath::$chunkIdx"
    }

    private data class ScoredHit(
        val docPath: String,
        val chunkIdx: Int,
        val snippet: String,
        val source: HitSource,
        val score: Double,
    ) {
        fun addScore(
            extra: Double,
            newSource: HitSource,
        ): ScoredHit {
            val combinedSource = if (source == newSource) source else HitSource.Both
            return ScoredHit(docPath, chunkIdx, snippet, combinedSource, score + extra)
        }

        fun toMemoryHit() = MemoryHit(docPath, chunkIdx, snippet, score, source)
    }
}
