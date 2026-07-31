package org.tatrman.kantheon.iris.protocol.config

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory
import org.tatrman.kantheon.iris.protocol.sections.SectionRegistry
import org.tatrman.kantheon.protocol.v1.Verbosity

private val log = LoggerFactory.getLogger(ProtocolConfig::class.java)

/**
 * Typed view of the `iris.protocol` HOCON block (contracts §7).
 *
 * **What a protocol contains is a platform decision, not a user preference**
 * (PT-2) — so everything here is server config, and nothing is reachable from
 * the request. A caller picks neither profile nor verbosity.
 *
 * Every field has a default, and a malformed or missing block degrades to those
 * defaults with a warning rather than failing boot: `/protocol` is a debug
 * surface, and a typo in its config must never stop iris-bff from serving turns.
 */
data class ProtocolConfig(
    val defaultProfile: String = "default",
    val profiles: Map<String, ProtocolProfile> = mapOf("default" to ProtocolProfile()),
    val caps: ProtocolCaps = ProtocolCaps(),
    val sessionSplitThreshold: Int = 12,
    val sources: SourceConfig = SourceConfig(),
    /**
     * The estate this deployment belongs to, printed in the receipts' `generated_by`.
     * It answers "which cluster produced this document?" for a reader holding an
     * exported .md — so a hard-coded value is worse than useless: every estate's
     * documents claimed to come from the same one.
     */
    val estate: String = "kantheon",
) {
    /** The named profile, falling back to [defaultProfile] then to shipped defaults. */
    fun profile(name: String? = null): ProtocolProfile =
        profiles[name ?: defaultProfile] ?: profiles[defaultProfile] ?: ProtocolProfile()

    companion object {
        const val PATH = "iris.protocol"

        fun from(root: Config): ProtocolConfig {
            if (!root.hasPath(PATH)) {
                log.info("no `{}` block; using shipped protocol defaults", PATH)
                return ProtocolConfig()
            }
            return runCatching { parse(root.getConfig(PATH)) }
                .getOrElse { e ->
                    log.warn("`{}` is malformed; falling back to shipped defaults: {}", PATH, e.message)
                    ProtocolConfig()
                }
        }

        private fun parse(c: Config): ProtocolConfig {
            val defaults = ProtocolConfig()
            val profiles =
                if (c.hasPath("profiles")) {
                    c
                        .getConfig("profiles")
                        .root()
                        .keys
                        .associateWith { ProtocolProfile.from(c.getConfig("profiles").getConfig(it), it) }
                } else {
                    defaults.profiles
                }
            return ProtocolConfig(
                defaultProfile = c.stringOr("default-profile", defaults.defaultProfile),
                estate = c.stringOr("estate", defaults.estate),
                profiles = profiles.ifEmpty { defaults.profiles },
                caps = if (c.hasPath("caps")) ProtocolCaps.from(c.getConfig("caps")) else defaults.caps,
                sessionSplitThreshold = c.intOr("session-split-threshold", defaults.sessionSplitThreshold),
                sources = if (c.hasPath("sources")) SourceConfig.from(c.getConfig("sources")) else defaults.sources,
            )
        }

        internal fun Config.stringOr(
            path: String,
            fallback: String,
        ): String = if (hasPath(path)) getString(path) else fallback

        internal fun Config.intOr(
            path: String,
            fallback: Int,
        ): Int = if (hasPath(path)) getInt(path) else fallback

        internal fun Config.boolOr(
            path: String,
            fallback: Boolean,
        ): Boolean = if (hasPath(path)) getBoolean(path) else fallback
    }
}

/**
 * One named profile: a verbosity per registry key plus the LLM-body policy.
 * Unset keys resolve to [ProtocolProfile.DEFAULT_VERBOSITY], so adding a section
 * to the registry does not silently switch it off in every deployed profile.
 */
data class ProtocolProfile(
    val name: String = "default",
    val sections: Map<String, Verbosity> = DEFAULT_SECTIONS,
    val llmUserContent: Verbosity = Verbosity.VERBOSITY_FULL,
    val llmSystemContent: Verbosity = Verbosity.VERBOSITY_SUMMARY,
) {
    /**
     * Resolved verbosity for a registry key.
     *
     * [SectionRegistry.RECEIPTS] always answers `FULL` regardless of what the
     * profile said — PT-13 makes receipts non-configurable, and enforcing it here
     * means no caller can reach a code path that switches them off.
     */
    fun verbosityFor(key: String): Verbosity =
        if (key == SectionRegistry.RECEIPTS) {
            Verbosity.VERBOSITY_FULL
        } else {
            sections[key] ?: DEFAULT_VERBOSITY
        }

    companion object {
        val DEFAULT_VERBOSITY: Verbosity = Verbosity.VERBOSITY_FULL

        /** contracts §7's shipped `default` profile. */
        val DEFAULT_SECTIONS: Map<String, Verbosity> =
            mapOf(
                "protocol.section.header" to Verbosity.VERBOSITY_FULL,
                "protocol.section.resolution" to Verbosity.VERBOSITY_FULL,
                "protocol.section.llm-calls" to Verbosity.VERBOSITY_SUMMARY,
                "protocol.section.query" to Verbosity.VERBOSITY_FULL,
                "protocol.section.plan" to Verbosity.VERBOSITY_FULL,
                "protocol.section.sql" to Verbosity.VERBOSITY_FULL,
                "protocol.section.security" to Verbosity.VERBOSITY_FULL,
                "protocol.section.execution" to Verbosity.VERBOSITY_FULL,
                "protocol.section.service-logs" to Verbosity.VERBOSITY_SUMMARY,
                "protocol.section.errors" to Verbosity.VERBOSITY_FULL,
                "protocol.section.participants" to Verbosity.VERBOSITY_FULL,
            )

        fun from(
            c: Config,
            name: String,
        ): ProtocolProfile {
            val sections = mutableMapOf<String, Verbosity>()
            if (c.hasPath("sections")) {
                val s = c.getConfig("sections")
                s.root().keys.forEach { shortName ->
                    val key = SectionRegistry.keyForShortName(shortName)
                    when {
                        key == null ->
                            log.warn("profile '{}': unknown section '{}' ignored", name, shortName)

                        key == SectionRegistry.RECEIPTS ->
                            // PT-13: not a typo to fix silently — an operator who wrote this
                            // believes they turned receipts off. Say so, then ignore it.
                            log.warn(
                                "profile '{}': `{}` is not configurable (PT-13 — receipts are always rendered); ignoring",
                                name,
                                shortName,
                            )

                        else -> parseVerbosity(s.getString(shortName), name, shortName)?.let { sections[key] = it }
                    }
                }
            }
            val llm = if (c.hasPath("llm-calls")) c.getConfig("llm-calls") else ConfigFactory.empty()
            return ProtocolProfile(
                name = name,
                sections = sections.ifEmpty { DEFAULT_SECTIONS },
                llmUserContent =
                    parseVerbosity(llm.stringOrNull("user-content"), name, "llm-calls.user-content")
                        ?: Verbosity.VERBOSITY_FULL,
                llmSystemContent =
                    parseVerbosity(llm.stringOrNull("system-content"), name, "llm-calls.system-content")
                        ?: Verbosity.VERBOSITY_SUMMARY,
            )
        }

        private fun Config.stringOrNull(path: String): String? = if (hasPath(path)) getString(path) else null

        private fun parseVerbosity(
            raw: String?,
            profile: String,
            where: String,
        ): Verbosity? =
            when (raw?.lowercase()) {
                null -> null
                "off" -> Verbosity.VERBOSITY_OFF
                "summary" -> Verbosity.VERBOSITY_SUMMARY
                "full" -> Verbosity.VERBOSITY_FULL
                else -> {
                    log.warn("profile '{}': '{}' is not a verbosity for {}; using default", profile, raw, where)
                    null
                }
            }
    }
}

/** Hard caps applied during assembly (PT-10); a hit sets `Section.truncated`. */
data class ProtocolCaps(
    val serviceLogsLines: Int = 200,
    val llmMessageChars: Int = 4_000,
    val sqlChars: Int = 20_000,
) {
    companion object {
        fun from(c: Config): ProtocolCaps {
            val d = ProtocolCaps()
            return with(ProtocolConfig) {
                ProtocolCaps(
                    serviceLogsLines = c.intOr("service-logs-lines", d.serviceLogsLines),
                    llmMessageChars = c.intOr("llm-message-chars", d.llmMessageChars),
                    sqlChars = c.intOr("sql-chars", d.sqlChars),
                )
            }
        }
    }
}

/** Where the assembler federates from (contracts §7 `sources`). */
data class SourceConfig(
    val gatewayBaseUrl: String = "",
    val lokiBaseUrl: String = "",
    val tempoBaseUrl: String = "",
    /**
     * Loki tenant for `X-Scope-OrgID`. Empty = single-tenant Loki, header omitted.
     *
     * Not derivable and not guessable: a Loki with `auth_enabled: true` rejects every
     * query without it (401), and the tenant id is whatever the collector writes under
     * — `hartland` on that estate, something else on the next. Found the hard way: the
     * client shipped without it and every live query 401'd.
     */
    val lokiTenant: String = "",
    val translateExplainEnabled: Boolean = true,
) {
    companion object {
        fun from(c: Config): SourceConfig {
            val d = SourceConfig()
            return with(ProtocolConfig) {
                SourceConfig(
                    gatewayBaseUrl = c.stringOr("gateway-base-url", d.gatewayBaseUrl),
                    lokiBaseUrl = c.stringOr("loki-base-url", d.lokiBaseUrl),
                    lokiTenant = c.stringOr("loki-tenant", d.lokiTenant),
                    tempoBaseUrl = c.stringOr("tempo-base-url", d.tempoBaseUrl),
                    translateExplainEnabled =
                        if (c.hasPath("translate-explain")) {
                            c.getConfig("translate-explain").boolOr("enabled", d.translateExplainEnabled)
                        } else {
                            d.translateExplainEnabled
                        },
                )
            }
        }
    }
}
