package org.tatrman.kantheon.hebe.config

import org.tomlj.TomlTable

/**
 * Bridges the on-disk `config.toml` + process environment to [RawAxisConfig],
 * then to a resolved [Axes] (P2 Stage 2.1; contracts §5.2). The axis keys are
 * read by their dotted TOML path (`[storage] backend` → `storage.backend`);
 * env overrides use the `HEBE_<AXIS_KEY>` convention (`storage.backend` →
 * `HEBE_STORAGE_BACKEND`). A `config.toml` with no `profile` key and no axis
 * keys (i.e. every pre-profile standalone config) resolves to the `local`
 * preset — byte-for-byte the previous behaviour.
 */
object AxesLoader {
    /** The dotted axis keys, matching contracts §5.1 / the [Profile] presets. */
    val AXIS_KEYS =
        listOf(
            "storage.backend",
            "fs.durability",
            "workspace.backend",
            "receipts.backend",
            "platform.reach",
            "platform.availability",
            "llm.source",
            "security.platform_identity",
            "security.console_auth",
            "security.secrets_backend",
            "otel.enabled",
            "capabilities.enabled",
            "tools.posture",
        )

    fun envKey(axisKey: String): String = "HEBE_" + axisKey.replace('.', '_').uppercase()

    fun fromToml(
        toml: TomlTable,
        env: Map<String, String> = System.getenv(),
    ): RawAxisConfig {
        val fileAxes = AXIS_KEYS.mapNotNull { key -> tomlAxisValue(toml, key)?.let { key to it } }.toMap()
        val envAxes = AXIS_KEYS.mapNotNull { key -> env[envKey(key)]?.let { key to it } }.toMap()
        return RawAxisConfig(
            profile = toml.getString("profile"),
            envProfile = env["HEBE_PROFILE"],
            fileAxes = fileAxes,
            envAxes = envAxes,
            instanceId = toml.getString("instance_id"),
            envInstanceId = env["HEBE_INSTANCE_ID"],
            toolsEnable = tomlStringList(toml, "tools.enable"),
            envToolsEnable = env["HEBE_TOOLS_ENABLE"]?.let { csv(it) },
            toolsDisable = tomlStringList(toml, "tools.disable"),
            envToolsDisable = env["HEBE_TOOLS_DISABLE"]?.let { csv(it) },
            boundUser = toml.getString("bound_user"),
            envBoundUser = env["HEBE_BOUND_USER"],
        )
    }

    /** Reads a TOML array of strings by dotted path; empty if absent or wrong type. */
    private fun tomlStringList(
        toml: TomlTable,
        dottedKey: String,
    ): List<String> {
        val arr = if (toml.contains(dottedKey)) toml.getArray(dottedKey) else null
        return if (arr == null) {
            emptyList()
        } else {
            (0 until arr.size()).mapNotNull { arr.get(it) as? String }
        }
    }

    private fun csv(raw: String): List<String> = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    fun resolve(
        toml: TomlTable,
        env: Map<String, String> = System.getenv(),
    ): Axes = ProfileResolver.resolve(fromToml(toml, env))

    /**
     * Reads one axis's TOML value by dotted path, normalising to the wire token
     * the resolver expects: booleans (`otel.enabled`) become `"true"`/`"false"`;
     * `capabilities.enabled` may be a boolean *or* the string `"optional"`.
     */
    private fun tomlAxisValue(
        toml: TomlTable,
        dottedKey: String,
    ): String? =
        when (val v = if (toml.contains(dottedKey)) toml.get(dottedKey) else null) {
            null -> null
            is Boolean -> if (v) "true" else "false"
            is String -> v
            else -> v.toString()
        }
}
