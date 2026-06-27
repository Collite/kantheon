package org.tatrman.kantheon.golem.prompts

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

private val log = LoggerFactory.getLogger(PromptStore::class.java)

/** Where a [PromptSnapshot]'s content came from. */
enum class PromptSource { MOUNTED, BUNDLED }

/** The canonical prompt names (locale is the directory, never part of the key). */
val PROMPT_NAMES: List<String> = listOf("intent", "free-sql", "chip-topup")

/**
 * An immutable set of prompts keyed by name. Content is stored **verbatim** —
 * `{{ }}` substitution placeholders are preserved, never interpolated here (the
 * graph nodes substitute at use time). Keys are the canonical names
 * (`intent` / `free-sql` / `chip-topup`); the locale is the mount subdirectory.
 */
class PromptSnapshot internal constructor(
    val source: PromptSource,
    private val byName: Map<String, String>,
) {
    fun get(name: String): String? = byName[name]

    val names: Set<String> get() = byName.keys

    companion object {
        internal fun mounted(byName: Map<String, String>): PromptSnapshot =
            PromptSnapshot(source = PromptSource.MOUNTED, byName = byName)

        internal fun bundled(byName: Map<String, String>): PromptSnapshot =
            PromptSnapshot(source = PromptSource.BUNDLED, byName = byName)
    }
}

/** Offline source of last resort — the prompts bundled into the pod image. */
fun interface PromptFallback {
    /** Prompt name → raw YAML content. Empty when no bundled prompts are present. */
    fun load(): Map<String, String>
}

/**
 * Loads bundled prompt YAML from the classpath (`prompts/<locale>/<name>.yaml`).
 * The live set is the mounted Shem bundle; these copies are the offline fallback
 * for local boot / tests. Missing files are skipped, so an empty `prompts/<locale>/`
 * yields an empty fallback.
 */
class ClasspathPromptFallback(
    private val locale: String = "cs",
    private val names: List<String> = PROMPT_NAMES,
    private val base: String = "prompts",
) : PromptFallback {
    override fun load(): Map<String, String> {
        val dir = if (locale.isBlank()) base else "$base/$locale"
        return names
            .mapNotNull { n ->
                javaClass.classLoader
                    .getResource("$dir/$n.yaml")
                    ?.readText()
                    ?.let { n to it }
            }.toMap()
    }
}

/**
 * The pod's prompt set. Primary source is the mounted Shem bundle at
 * `<shemDir>/prompts/<locale>/<name>.yaml`; when the mount is absent/empty and no
 * set is yet loaded, falls back to the bundled classpath YAML. Reloads (remount-
 * driven) swap atomically and never downgrade a good mounted set to bundled.
 */
class PromptStore(
    private val shemDir: Path,
    private val locale: String = "cs",
    private val names: List<String> = PROMPT_NAMES,
    private val fallback: PromptFallback = ClasspathPromptFallback(locale, names),
) {
    private val snapshot = AtomicReference<PromptSnapshot?>(null)

    val isLoaded: Boolean get() = snapshot.get() != null

    fun currentOrNull(): PromptSnapshot? = snapshot.get()

    fun current(): PromptSnapshot = snapshot.get() ?: error("PromptStore not loaded — call refresh() first")

    /** Convenience: the raw content of one prompt, or null if absent / not loaded. */
    fun prompt(name: String): String? = snapshot.get()?.get(name)

    private fun loadFromMount(): Map<String, String> {
        val dir = if (locale.isBlank()) shemDir.resolve("prompts") else shemDir.resolve("prompts").resolve(locale)
        return names
            .mapNotNull { n ->
                val p = dir.resolve("$n.yaml")
                if (p.isRegularFile()) {
                    try {
                        n to p.readText()
                    } catch (e: Exception) {
                        log.warn("failed to read mounted prompt {}: {}", p, e.message)
                        null
                    }
                } else {
                    null
                }
            }.toMap()
    }

    /**
     * Read the mounted Shem prompt bundle and swap it in. When the mount is absent
     * or empty: keep the current set if any, else load the bundled fallback. Reloads
     * never downgrade a loaded mounted set to bundled.
     */
    fun refresh(): PromptSnapshot {
        val mounted =
            try {
                val byName = if (Files.isDirectory(shemDir)) loadFromMount() else emptyMap()
                if (byName.isEmpty()) {
                    log.warn("no mounted prompts under '{}' (locale '{}') — using held/bundled set", shemDir, locale)
                    null
                } else {
                    PromptSnapshot.mounted(byName)
                }
            } catch (e: Exception) {
                log.warn("reading mounted prompts under '{}' failed: {} — using held/bundled set", shemDir, e.message)
                null
            }

        if (mounted != null) {
            snapshot.set(mounted)
            return mounted
        }

        snapshot.get()?.let { return it } // never downgrade a loaded set
        val bundled = PromptSnapshot.bundled(fallback.load())
        log.info("PromptStore loaded {} bundled prompt(s) (offline fallback)", bundled.names.size)
        snapshot.set(bundled)
        return bundled
    }
}
