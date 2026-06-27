package org.tatrman.kantheon.iris.feedback

import com.typesafe.config.ConfigFactory
import org.tatrman.kantheon.iris.infra.ExposedFeedbackStore
import org.tatrman.kantheon.iris.infra.ExposedSessionStore
import org.tatrman.kantheon.iris.infra.IrisDatabase
import java.io.File
import java.time.Instant

/**
 * `just feedback-export` entrypoint (PD-3, contracts §2.9). Reads `iris_feedback`
 * (+ `iris_turns` for the question text) from the configured Postgres, runs the
 * pure [FeedbackExporter], and writes per-corpus JSONL under `eval/candidates/`
 * (override via arg 0). Requires `iris.db.enabled = true`. Human curation gates
 * promotion into the gate corpora — this only stages candidates.
 */
fun main(args: Array<String>) {
    val outDir = File(args.getOrNull(0) ?: "eval/candidates")
    val config = ConfigFactory.load()
    require(config.getBoolean("iris.db.enabled")) {
        "feedback-export needs iris.db.enabled = true (set IRIS_DB_* env / system properties)"
    }
    val database = IrisDatabase(config)
    database.migrateAndConnect()
    try {
        val feedback = ExposedFeedbackStore(database.connection)
        val sessions = ExposedSessionStore(database.connection)
        val rows = feedback.all()
        val byCorpus = FeedbackExporter.export(rows) { turnId -> sessions.getTurn(turnId)?.question }
        val stamp = Instant.now().toString().replace(":", "-")
        var written = 0
        byCorpus.forEach { (corpus, lines) ->
            // The corpus name is an agent id (internally sourced, but not validated as a
            // path segment) — slugify so it can never escape outDir via `..`/separators.
            val safeCorpus = corpus.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "_unknown" }
            val dir = File(outDir, safeCorpus).apply { mkdirs() }
            File(dir, "feedback-$stamp.jsonl").writeText(lines.joinToString("\n", postfix = "\n"))
            written += lines.size
            println("  $safeCorpus: ${lines.size} candidate(s) → ${File(dir, "feedback-$stamp.jsonl").path}")
        }
        println("feedback-export: ${rows.size} row(s) → $written candidate line(s) across ${byCorpus.size} corpora")
    } finally {
        database.close()
    }
}
