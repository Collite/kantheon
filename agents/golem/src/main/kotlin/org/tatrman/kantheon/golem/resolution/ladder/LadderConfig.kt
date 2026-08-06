package org.tatrman.kantheon.golem.resolution.ladder

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.tatrman.resolver.v1.GapKind
import java.nio.file.Files
import java.nio.file.Path

/**
 * RV-P5.2 T2 — `golem-ladder/v1`, the ladder / gap-policy config (contracts §3, RV-10/12).
 *
 * ONE gap-policy table (RV-10) over a rung vocabulary of FOUR (RV-33). **One schema, two
 * loaders**: this is the Kotlin sibling of `golem_py/ladder.py`, and the conformance fixture
 * under `src/test/resources/ladder/` is golem-py's own shipped file, so the two are proven
 * to read the same bytes the same way rather than merely intended to.
 *
 * A config is read once, at startup, and then governs every turn — so it fails at **LOAD or
 * not at all**. Discovering a typo halfway through a conversation is the failure mode the
 * rejection catalogue exists to prevent, and it is why every unknown key is an error rather
 * than something Jackson quietly ignores.
 *
 * ⚑ **What kantheon ships is NOT the zero-rung file.** RV-27's `policy.*.rungs: []` default is
 * the **open** Golem's posture, stated as such in contracts §3 ("the open Golem ships this
 * file with..."). This is the Kotlin internal-full Golem — plugins, learning, full ladder —
 * so `src/main/resources/golem-ladder.yaml` is contracts §3's normative table as written.
 * Recorded for Bora on the task list rather than chosen silently; it is inert either way at
 * P5.2, because the ladder only runs behind the resolution core, which is off unless wired.
 */
const val LADDER_SCHEMA_ID: String = "golem-ladder/v1"

/** RV-33 — the rung vocabulary is CLOSED at four. A fifth is a design change, not config. */
val RUNG_NAMES: List<String> = listOf("lookup", "local", "capable", "emulated")

/**
 * `lookup` is the only deterministic rung: bounded by the ladder's wall clock and **never**
 * counted against `max_llm_invocations`. That distinction is the reason the vocabulary
 * separates it at all (RV-33).
 */
val DETERMINISTIC_RUNGS: Set<String> = setOf("lookup")

/** A ladder config that cannot be trusted. Always thrown at load, never mid-conversation. */
class LadderConfigException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

enum class AskPolicy(
    @get:JsonProperty val wire: String,
) {
    ESCALATE_THEN_ASK("escalate-then-ask"),
    EMIT_UNLESS_LOAD_BEARING("emit-unless-load-bearing"),
    ASK_IF_LOAD_BEARING("ask-if-load-bearing"),
    DEGRADE_BANNER("degrade-banner"),
    ASK_IMMEDIATELY("ask-immediately"),
    ;

    companion object {
        fun from(value: String): AskPolicy =
            entries.firstOrNull { it.wire == value }
                ?: throw LadderConfigException(
                    "unknown ask policy '$value' — expected one of ${entries.map { it.wire }}",
                )
    }
}

enum class TerminalPosture(
    val wire: String,
) {
    REFUSAL_WITH_GAPS("refusal-with-gaps"),
    BEST_EFFORT_WITH_GAP_NOTES("best-effort-with-gap-notes"),
    ;

    companion object {
        fun from(value: String): TerminalPosture =
            entries.firstOrNull { it.wire == value }
                ?: throw LadderConfigException(
                    "unknown terminal posture '$value' — expected one of ${entries.map { it.wire }}",
                )
    }
}

/** A rung DEFINITION (contracts §3: definitions, not instances). */
data class RungDef(
    val modelClass: String = "",
    /** Falls back to `rung_timeouts_ms` when unset. */
    val timeoutMs: Int? = null,
    /** RV-12: temperature 0 everywhere. A config may say so; it may not say otherwise usefully. */
    val temperature: Double = 0.0,
    val lastResort: Boolean = false,
)

data class GapPolicy(
    val rungs: List<String> = emptyList(),
    val ask: AskPolicy = AskPolicy.ASK_IF_LOAD_BEARING,
)

data class LadderProfile(
    /** A list, or the literal `"*"` for every rung. */
    val rungsAllowed: List<String>?,
    val allRungsAllowed: Boolean,
    val maxLlmInvocations: Int,
    val ladderBudgetMs: Int,
    val hitlRounds: Int,
    /** Names a key of the `terminal:` block. Default `strict` — see the P4.1 T4 ruling. */
    val terminal: String,
) {
    fun allows(rung: String): Boolean = allRungsAllowed || rungsAllowed?.contains(rung) == true
}

data class LadderConfig(
    val schemaId: String,
    val rungs: Map<String, RungDef>,
    /** Insertion-ordered: the policy table's order **is** the ladder's climb order. */
    val policy: Map<GapKind, GapPolicy>,
    val rungTimeoutsMs: Map<String, Int>,
    val profiles: Map<String, LadderProfile>,
    val terminal: Map<String, TerminalPosture>,
) {
    fun profile(name: String): LadderProfile = profiles[name] ?: throw LadderConfigException("unknown profile '$name'")

    /**
     * A gap kind with no policy row gets the conservative default: no rung may run, and an
     * ask fires only if the gap is load-bearing.
     */
    fun gapPolicy(kind: GapKind): GapPolicy = policy[kind] ?: GapPolicy()

    fun timeoutMs(rung: String): Int = rungs[rung]?.timeoutMs ?: rungTimeoutsMs[rung] ?: 0

    fun terminalPosture(profileName: String): TerminalPosture = terminal.getValue(profile(profileName).terminal)

    /**
     * The rungs that MAY run for this set of open gap kinds, in **policy-table order** — the
     * order the config author wrote, which is the ladder's own semantics.
     *
     * ⛑ Carried verbatim from golem-py's P4 review, because the bug is a language-independent
     * trap and Kotlin has its own version of it: iterating the *caller's* set of gap kinds
     * makes the climb order depend on the set's iteration order. In Python that was
     * `PYTHONHASHSEED`; here a `HashSet<GapKind>` iterates by enum hash, which is stable
     * within a JVM run but is still not the author's order, and a `sortedSet` would silently
     * impose enum-declaration order instead. Walking the **table** is the only reading that
     * matches what the config says. Deterministic below the LLM line is the whole thesis.
     */
    fun eligibleRungs(
        openKinds: Collection<GapKind>,
        profileName: String,
    ): List<String> {
        val prof = profile(profileName)
        val open = openKinds.toSet()
        val out = LinkedHashSet<String>()
        policy.forEach { (kind, pol) ->
            if (kind in open) {
                pol.rungs.forEach { rung -> if (prof.allows(rung)) out.add(rung) }
            }
        }
        return out.toList()
    }

    companion object {
        private val mapper =
            ObjectMapper(YAMLFactory())
                .registerKotlinModule()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)

        fun load(path: Path): LadderConfig =
            try {
                parse(Files.readString(path), path.toString())
            } catch (e: LadderConfigException) {
                throw LadderConfigException("$path: ${e.message}", e)
            }

        fun parse(
            yaml: String,
            origin: String = "<string>",
        ): LadderConfig {
            val root: JsonNode =
                try {
                    mapper.readValue(yaml)
                } catch (e: Exception) {
                    throw LadderConfigException("$origin: not readable as YAML — ${e.message}", e)
                }
            if (!root.isObject) throw LadderConfigException("$origin: not a YAML mapping")

            // A typo in a key name is the silent failure this schema most invites: a
            // misspelled `profiles:` would leave the defaults in place and nobody would know.
            val known = setOf("schema", "rungs", "policy", "rung_timeouts_ms", "profiles", "terminal")
            val unknown =
                root
                    .fieldNames()
                    .asSequence()
                    .filterNot { it in known }
                    .toList()
            if (unknown.isNotEmpty()) {
                throw LadderConfigException("unknown top-level key(s) $unknown — known keys are ${known.sorted()}")
            }

            val schemaId = root.path("schema").asText("")
            if (schemaId != LADDER_SCHEMA_ID) {
                throw LadderConfigException("unknown schema id '$schemaId' (expected '$LADDER_SCHEMA_ID')")
            }

            val rungs = readRungs(root.path("rungs"))
            val rungTimeouts = readRungTimeouts(root.path("rung_timeouts_ms"))
            val policy = readPolicy(root.path("policy"), rungs.keys)
            val terminal = readTerminal(root.path("terminal"))
            val profiles = readProfiles(root.path("profiles"), rungs.keys, terminal.keys)

            return LadderConfig(schemaId, rungs, policy, rungTimeouts, profiles, terminal)
        }

        private fun readRungs(node: JsonNode): Map<String, RungDef> {
            if (node.isMissingNode || node.isNull) return emptyMap()
            val out = LinkedHashMap<String, RungDef>()
            node.properties().forEach { (name, def) ->
                if (name !in RUNG_NAMES) {
                    throw LadderConfigException(
                        "unknown rung '$name' — the vocabulary is closed at $RUNG_NAMES (RV-33)",
                    )
                }
                val unknown =
                    def
                        .fieldNames()
                        .asSequence()
                        .filterNot {
                            it in setOf("model_class", "timeout_ms", "temperature", "last_resort")
                        }.toList()
                if (unknown.isNotEmpty()) {
                    throw LadderConfigException("rungs[$name] has unknown key(s) $unknown")
                }
                val timeout = if (def.has("timeout_ms")) def.get("timeout_ms").asInt() else null
                if (timeout != null && timeout <= 0) {
                    throw LadderConfigException("rungs[$name].timeout_ms must be > 0")
                }
                out[name] =
                    RungDef(
                        modelClass = def.path("model_class").asText(""),
                        timeoutMs = timeout,
                        temperature = def.path("temperature").asDouble(0.0),
                        lastResort = def.path("last_resort").asBoolean(false),
                    )
            }
            return out
        }

        private fun readRungTimeouts(node: JsonNode): Map<String, Int> {
            if (node.isMissingNode || node.isNull) return emptyMap()
            val out = LinkedHashMap<String, Int>()
            node.properties().forEach { (name, value) ->
                if (name !in RUNG_NAMES) throw LadderConfigException("rung_timeouts_ms names unknown rung '$name'")
                val ms = value.asInt()
                if (ms <= 0) throw LadderConfigException("rung_timeouts_ms[$name] must be > 0")
                out[name] = ms
            }
            return out
        }

        private fun readPolicy(
            node: JsonNode,
            definedRungs: Set<String>,
        ): Map<GapKind, GapPolicy> {
            if (node.isMissingNode || node.isNull) return emptyMap()
            val out = LinkedHashMap<GapKind, GapPolicy>()
            node.properties().forEach { (name, row) ->
                // The gap taxonomy is the contract between the core and the loop. A policy row
                // for a kind the core cannot emit is dead config that reads as coverage.
                val kind =
                    GapKind.entries.firstOrNull { it.name == "GAP_KIND_$name" }
                        ?: throw LadderConfigException(
                            "policy names unknown gap kind '$name' — the core emits " +
                                GapKind.entries
                                    .filter { it.name.startsWith("GAP_KIND_G") }
                                    .map { it.name.removePrefix("GAP_KIND_") },
                        )
                val unknown =
                    row
                        .fieldNames()
                        .asSequence()
                        .filterNot { it in setOf("rungs", "ask") }
                        .toList()
                if (unknown.isNotEmpty()) throw LadderConfigException("policy[$name] has unknown key(s) $unknown")
                val rungs = row.path("rungs").map { it.asText() }
                rungs.forEach { rung ->
                    if (rung !in definedRungs) {
                        throw LadderConfigException(
                            "policy[$name] names rung '$rung', which is not defined under `rungs:`",
                        )
                    }
                }
                val ask =
                    if (row.has("ask")) AskPolicy.from(row.get("ask").asText()) else AskPolicy.ASK_IF_LOAD_BEARING
                out[kind] = GapPolicy(rungs, ask)
            }
            return out
        }

        private fun readTerminal(node: JsonNode): Map<String, TerminalPosture> {
            if (node.isMissingNode || node.isNull) return emptyMap()
            val out = LinkedHashMap<String, TerminalPosture>()
            node.properties().forEach { (name, value) -> out[name] = TerminalPosture.from(value.asText()) }
            return out
        }

        private fun readProfiles(
            node: JsonNode,
            definedRungs: Set<String>,
            definedPostures: Set<String>,
        ): Map<String, LadderProfile> {
            if (node.isMissingNode || node.isNull) return emptyMap()
            val out = LinkedHashMap<String, LadderProfile>()
            node.properties().forEach { (name, row) ->
                val unknown =
                    row
                        .fieldNames()
                        .asSequence()
                        .filterNot {
                            it in
                                setOf(
                                    "rungs_allowed",
                                    "max_llm_invocations",
                                    "ladder_budget_ms",
                                    "hitl_rounds",
                                    "terminal",
                                )
                        }.toList()
                if (unknown.isNotEmpty()) throw LadderConfigException("profiles[$name] has unknown key(s) $unknown")

                val allowedNode = row.path("rungs_allowed")
                val all = allowedNode.isTextual && allowedNode.asText() == "*"
                val allowed = if (all) null else allowedNode.map { it.asText() }
                allowed?.forEach { rung ->
                    if (rung !in definedRungs) {
                        throw LadderConfigException(
                            "profile '$name' allows rung '$rung', which is not defined under `rungs:`",
                        )
                    }
                }
                val budgets =
                    listOf("max_llm_invocations", "ladder_budget_ms", "hitl_rounds").associateWith {
                        row.path(it).asInt(0)
                    }
                budgets.forEach { (key, value) ->
                    if (value < 0) throw LadderConfigException("profiles[$name].$key: budgets cannot be negative")
                }
                val posture = row.path("terminal").asText("strict")
                if (posture !in definedPostures) {
                    throw LadderConfigException(
                        "profile '$name' names terminal posture '$posture', which `terminal:` does not define",
                    )
                }
                out[name] =
                    LadderProfile(
                        rungsAllowed = allowed,
                        allRungsAllowed = all,
                        maxLlmInvocations = budgets.getValue("max_llm_invocations"),
                        ladderBudgetMs = budgets.getValue("ladder_budget_ms"),
                        hitlRounds = budgets.getValue("hitl_rounds"),
                        terminal = posture,
                    )
            }
            return out
        }

        /** kantheon's shipped default, overridable by `GOLEM_LADDER_CONFIG`. */
        fun loadDefault(): LadderConfig {
            System.getenv("GOLEM_LADDER_CONFIG")?.let { return load(Path.of(it)) }
            val stream =
                LadderConfig::class.java.getResourceAsStream("/golem-ladder.yaml")
                    ?: throw LadderConfigException("no /golem-ladder.yaml on the classpath")
            return stream.bufferedReader().use { parse(it.readText(), "classpath:/golem-ladder.yaml") }
        }
    }
}
