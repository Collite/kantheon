package org.tatrman.kantheon.golem.resolution.skills

import org.slf4j.LoggerFactory
import org.tatrman.ttr.lexicon.LexiconArchive
import org.tatrman.ttr.lexicon.OperatorEntry
import org.tatrman.ttr.lexicon.OperatorLibrary
import org.tatrman.ttr.lexicon.sha256
import org.tatrman.ttr.snapshot.SnapshotReadResult
import org.tatrman.ttr.snapshot.SnapshotReader
import java.nio.file.Files
import java.nio.file.Path

private val log = LoggerFactory.getLogger("org.tatrman.kantheon.golem.resolution.skills")

// RV-P5.3 T3 — skill bodies. RV-35/37: an operator word is ordinary VOCABULARY (`op:trend` is
// a lexicon entry the matcher binds like any other term) and its BEHAVIOUR is a Golem-side
// artifact keyed by that same `op:` id. The matcher never reads a body; the Golem never
// re-derives a trigger. This is the Golem's half — the Kotlin half.

/**
 * A body that cannot be trusted or found. Surfaced as a refusal, never a silent skip: an
 * operator the question named and we could not honour changes the answer.
 */
class SkillException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * One operator's behaviour, with its prose split into the three sections an author writes.
 *
 * The parse is a **dumb section split and stays one** — a body is prose FOR THE GOLEM
 * (contracts §2), and the moment it becomes a grammar an author has to learn it.
 */
data class SkillBody(
    val op: String,
    val body: String,
    val version: Int,
    val checksum: String,
    val sourceFile: String = "",
    val retrieval: String = "",
    val formatting: String = "",
    /**
     * Parsed from the `Applicability:` prose. ⚑ **Not from a field** — see [FINDING_REQUIRES].
     */
    val requires: List<String> = emptyList(),
) {
    companion object {
        private val SECTION = Regex("""^(Retrieval|Formatting|Applicability):\s*""", RegexOption.MULTILINE)

        /** `` Applicability: `time-grain` — … `` — the backticked token names the requirement. */
        private val REQUIREMENT = Regex("""`([a-z0-9-]+)`""")

        fun of(
            op: String,
            entry: OperatorEntry,
        ): SkillBody {
            val sections = splitSections(entry.body)
            return SkillBody(
                op = op,
                body = entry.body,
                version = entry.version,
                checksum = entry.checksum,
                sourceFile = entry.source.file,
                retrieval = sections["Retrieval"].orEmpty().trim(),
                formatting = sections["Formatting"].orEmpty().trim(),
                requires = REQUIREMENT.findAll(sections["Applicability"].orEmpty()).map { it.groupValues[1] }.toList(),
            )
        }

        /**
         * ⛑ Written by hand rather than with `Regex.split`, and the reason is a real trap:
         * Python's `re.split` **includes** capturing groups in the result, Kotlin's
         * [Regex.split] **drops** them. golem-py's `_split_sections` relies on the Python
         * behaviour, so a direct transliteration silently yields section *bodies* with no
         * *names* to key them by — every section reads as empty, every `requires:` disappears,
         * and every operator looks applicable. It fails as a wrong answer, not as an
         * exception, which is why it is worth a comment rather than a tidier one-liner.
         */
        private fun splitSections(body: String): Map<String, String> {
            val matches = SECTION.findAll(body).toList()
            return matches
                .mapIndexed { i, m ->
                    val name = m.groupValues[1]
                    val end = matches.getOrNull(i + 1)?.range?.first ?: body.length
                    name to body.substring(m.range.last + 1, end)
                }.toMap()
        }
    }

    /**
     * `sha256:<hex>` over the body bytes — **the compiler's own rule, via the compiler's own
     * function**. `ttr-lexicon` exports [sha256] precisely so a consumer cannot drift from the
     * producer here; golem-py has to re-implement it, and a re-implementation is a thing that
     * can be wrong. A tampered or truncated body must refuse to load rather than shape a
     * retrieval.
     */
    fun verify() {
        if (checksum.isBlank()) return
        val actual = sha256(body.toByteArray(Charsets.UTF_8))
        if (actual != checksum) {
            throw SkillException("$op: body checksum mismatch (declared $checksum, actual $actual)")
        }
    }
}

/**
 * ⚑ **`requires:` does not reach the artifact** — carried from golem-py's P4.3 T3 finding and
 * re-verified against the published `ttr-lexicon:0.11.1`, where `OperatorEntry` is still
 * `{body, version, checksum, source}`.
 *
 * A skill file declares `requires: [ time-grain ]` in frontmatter, `LexiconValidator` parses it
 * into `SkillFile.requires` — and the compiler drops it. The only surviving trace is the body's
 * prose *"Applicability: `time-grain` — …"* line, so the applicability check is parsed OUT OF
 * PROSE, which works today and is exactly as fragile as it sounds.
 *
 * The structural fix is **one field on `OperatorEntry` in `tatrman`**. Both Golems are waiting
 * on the same field, which is the argument for it: two independent implementations have now
 * each paid for the workaround.
 */
const val FINDING_REQUIRES: String = "OperatorEntry carries no `requires:` — parsed from Applicability prose"

/** One layer of operator bodies — one archive, or one hand-built map in a test. */
class SkillLayer(
    val bodies: Map<String, SkillBody>,
    val schemaVersion: String = OperatorLibrary.SCHEMA_VERSION,
    val archiveId: String = "",
) {
    operator fun contains(op: String): Boolean = op in bodies

    fun get(op: String): SkillBody =
        bodies[op] ?: throw SkillException(
            "$op is not in the loaded operator library — the question named an operator this estate cannot honour",
        )

    companion object {
        /**
         * Parse `docs/operator-library.json`. The schema id is checked against the artifact
         * type's own constant, so a schema bump is a compile-and-fail rather than a silent
         * mis-read.
         */
        fun fromJson(
            text: String,
            archiveId: String = "",
        ): SkillLayer {
            val library =
                try {
                    OperatorLibrary.fromJson(text)
                } catch (e: Exception) {
                    throw SkillException("operator library at '$archiveId' is unreadable: ${e.message}", e)
                }
            if (library.schemaVersion != OperatorLibrary.SCHEMA_VERSION) {
                throw SkillException(
                    "unknown operator-library schema '${library.schemaVersion}' " +
                        "(expected '${OperatorLibrary.SCHEMA_VERSION}')",
                )
            }
            val bodies =
                library.operators.mapValues { (op, entry) ->
                    SkillBody.of(op, entry).also { it.verify() }
                }
            return SkillLayer(bodies, library.schemaVersion, archiveId)
        }

        /**
         * Pull the operator library out of a `kind: "lexicon"` archive.
         *
         * Two calls, because the toolchain owns both halves: [SnapshotReader] decompresses and
         * untars (returning a structured `Failure` rather than throwing on a corrupt file — an
         * unbounded decompress on an untrusted archive is a memory bomb, and this reader is the
         * one the producer wrote), and [LexiconArchive] names the document.
         */
        fun fromArchive(path: Path): SkillLayer {
            val bytes =
                try {
                    Files.readAllBytes(path)
                } catch (e: Exception) {
                    throw SkillException("operator library at '$path' cannot be read: ${e.message}", e)
                }
            return when (val result = SnapshotReader.read(bytes)) {
                is SnapshotReadResult.Failure -> throw SkillException("'$path': ${result.reason}")
                is SnapshotReadResult.Ok -> {
                    val kind = result.contents.manifest.kind
                    if (kind != LexiconArchive.KIND) {
                        // A model archive mounted where a lexicon archive belongs is a
                        // deployment mistake, and it must say so rather than yield zero
                        // operators — zero operators looks exactly like "this estate declared
                        // no actions", which is a legitimate state.
                        throw SkillException("'$path' is a '$kind' archive, not a '${LexiconArchive.KIND}' one")
                    }
                    val doc =
                        result.contents.docs[LexiconArchive.OPERATORS]
                            ?: throw SkillException("'$path': archive has no docs/${LexiconArchive.OPERATORS}")
                    fromJson(doc, archiveId = path.toString())
                }
            }
        }
    }
}

/**
 * **Estate over stdlib** — the first layer that has the op wins, the same precedence the
 * compiler enforces for triggers, applied to bodies.
 *
 * ⚑ Carried from golem-py: the Golem does **not** ship a second copy of the stdlib bodies. The
 * compiler already merges stdlib with estate overrides into every archive (estate wins,
 * recorded in each entry's `source`), so a second copy here would be the same prose maintained
 * twice in two languages — the drift RV-37 avoids by making bodies artifacts in the first
 * place. The layered API is kept because a platform deployment may mount an estate archive
 * *over* a stdlib one.
 */
class LayeredSkillLibrary(
    val layers: List<SkillLayer> = emptyList(),
) {
    operator fun contains(op: String): Boolean = layers.any { op in it }

    fun get(op: String): SkillBody =
        layers.firstOrNull { op in it }?.get(op)
            ?: throw SkillException(
                "$op is not in any loaded operator library — the question named an operator this estate cannot honour",
            )

    /** Every op this Golem holds a body for, deduplicated with the winning layer first. */
    fun known(): List<String> = layers.flatMap { it.bodies.keys }.distinct().sorted()

    companion object {
        val EMPTY = LayeredSkillLibrary()

        /** Layers in the order given: **estate first, stdlib last**. */
        fun of(vararg paths: Path): LayeredSkillLibrary = LayeredSkillLibrary(paths.map { SkillLayer.fromArchive(it) })

        /**
         * The mount convention is P3.3 T2's — `*_LEXICON_ARCHIVE_PATH`, the same path every
         * other archive reader takes — so an estate mounts ONE ConfigMap and every reader, in
         * either language, sees the same bytes. Absent ⇒ an empty library, which is the
         * honest state for an estate that has declared no operators (and the state the fast
         * path reads as "this Golem was taught no actions").
         */
        fun fromEnv(
            env: Map<String, String> = System.getenv(),
            key: String = "GOLEM_LEXICON_ARCHIVE_PATH",
        ): LayeredSkillLibrary {
            val raw =
                env[key]?.takeIf { it.isNotBlank() } ?: run {
                    log.info("$key unset — no operator library; the fast path will refuse every operator")
                    return EMPTY
                }
            // Multiple mounts are colon-separated, estate first.
            val paths = raw.split(':').filter { it.isNotBlank() }.map { Path.of(it) }
            return LayeredSkillLibrary(paths.map { SkillLayer.fromArchive(it) })
        }
    }
}
