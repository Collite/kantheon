package org.tatrman.kantheon.hebe.config

/**
 * The resolved **axis model** (Hebe arc P2 Stage 2.1; contracts §5).
 *
 * The four deployment profiles (`local`/`personal`/`server`/`k8s`) are *named
 * presets over orthogonal axes* — `profile` only selects a bundle of axis
 * defaults at boot. Subsystems read **axes**, never the profile name (there is
 * no `when(profile)` anywhere downstream). Every axis is individually
 * overridable; the resolved, validated, immutable result is this type.
 *
 * No `String` axis value escapes [ProfileResolver] — every axis is a sealed
 * enum, so an invalid value fails fast at resolution, not at use.
 */
data class Axes(
    val storage: StorageAxis,
    val fs: FsAxis,
    val workspace: WorkspaceAxis,
    val receipts: ReceiptsAxis,
    val platform: PlatformAxis,
    val llm: LlmAxis,
    val security: SecurityAxis,
    val otel: OtelAxis,
    val capabilities: CapabilitiesAxis,
    val tools: ToolsAxis,
    /**
     * Instance identity (contracts §5.2). Required for postgres backends (fail
     * fast if missing); `"local"` otherwise. Keys the PG schema name
     * (`hebe_<instance_id>`, Phase 3) and the registration agent id
     * (`hebe-<instance_id>`, Phase 3 Stage 3.4) — wired now, consumed later.
     */
    val instanceId: String,
    /**
     * The Keycloak user this instance acts as (contracts §5.2). Free string, not
     * a preset value — resolved from `bound_user` / `HEBE_BOUND_USER`. Required
     * when `security.platform_identity = keycloak` (boot fails fast otherwise);
     * `null` on identity-less profiles (`local`). Used for channel-identity
     * binding and OBO (Phase 4).
     */
    val boundUser: String? = null,
)

data class StorageAxis(
    val backend: StorageBackend,
)

data class FsAxis(
    val durability: Durability,
)

data class WorkspaceAxis(
    val backend: WorkspaceBackend,
)

data class ReceiptsAxis(
    val backend: ReceiptsBackend,
)

/**
 * `availability` is `null` on `local` (the matrix shows `—`): with
 * `reach = none` there is no platform to be available or not.
 */
data class PlatformAxis(
    val reach: PlatformReach,
    val availability: Availability?,
)

data class LlmAxis(
    val source: LlmSource,
)

data class SecurityAxis(
    val platformIdentity: PlatformIdentity,
    val consoleAuth: ConsoleAuth,
    val secretsBackend: SecretsBackend,
)

data class OtelAxis(
    val enabled: Boolean,
)

data class CapabilitiesAxis(
    val enabled: CapabilitiesEnabled,
)

/**
 * `posture` is a preset axis value; [enable]/[disable] are per-instance opt-in
 * lists (contracts §5.2) sourced from `[tools] enable`/`disable` in `config.toml`
 * (or `HEBE_TOOLS_ENABLE`/`HEBE_TOOLS_DISABLE`, comma-separated). They are not
 * part of the profile presets — they default empty and overlay the posture.
 */
data class ToolsAxis(
    val posture: Posture,
    val enable: Set<String> = emptySet(),
    val disable: Set<String> = emptySet(),
)

// ── Axis value enums ─────────────────────────────────────────────────────────
// Each carries its `config.toml` wire token so parsing/serialisation is a single
// source of truth (no scattered string literals).

enum class StorageBackend(
    val token: String,
) {
    SQLITE("sqlite"),
    POSTGRES("postgres"),
    ;

    companion object : AxisCodec<StorageBackend>("storage.backend", entries, StorageBackend::token)
}

enum class Durability(
    val token: String,
) {
    PERSISTENT("persistent"),
    EPHEMERAL("ephemeral"),
    ;

    companion object : AxisCodec<Durability>("fs.durability", entries, Durability::token)
}

enum class WorkspaceBackend(
    val token: String,
) {
    FILES("files"),
    POSTGRES("postgres"),
    ;

    companion object : AxisCodec<WorkspaceBackend>("workspace.backend", entries, WorkspaceBackend::token)
}

enum class ReceiptsBackend(
    val token: String,
) {
    FILE("file"),
    POSTGRES("postgres"),
    ;

    companion object : AxisCodec<ReceiptsBackend>("receipts.backend", entries, ReceiptsBackend::token)
}

enum class PlatformReach(
    val token: String,
) {
    NONE("none"),
    REMOTE("remote"),
    IN_CLUSTER("in_cluster"),
    ;

    companion object : AxisCodec<PlatformReach>("platform.reach", entries, PlatformReach::token)
}

enum class Availability(
    val token: String,
) {
    ALWAYS("always"),
    INTERMITTENT("intermittent"),
    ;

    companion object : AxisCodec<Availability>("platform.availability", entries, Availability::token)
}

enum class LlmSource(
    val token: String,
) {
    BYOK("byok"),
    GATEWAY("gateway"),
    GATEWAY_WITH_BYOK_FALLBACK("gateway_with_byok_fallback"),
    ;

    companion object : AxisCodec<LlmSource>("llm.source", entries, LlmSource::token)
}

enum class PlatformIdentity(
    val token: String,
) {
    NONE("none"),
    KEYCLOAK("keycloak"),
    ;

    companion object : AxisCodec<PlatformIdentity>("security.platform_identity", entries, PlatformIdentity::token)
}

enum class ConsoleAuth(
    val token: String,
) {
    PASSWORD("password"),
    OIDC("oidc"),
    ;

    companion object : AxisCodec<ConsoleAuth>("security.console_auth", entries, ConsoleAuth::token)
}

enum class SecretsBackend(
    val token: String,
) {
    KEYCHAIN("keychain"),
    FILE("file"),
    K8S("k8s"),
    ;

    companion object : AxisCodec<SecretsBackend>("security.secrets_backend", entries, SecretsBackend::token)
}

enum class CapabilitiesEnabled(
    val token: String,
) {
    DISABLED("false"),
    OPTIONAL("optional"),
    ENABLED("true"),
    ;

    companion object : AxisCodec<CapabilitiesEnabled>("capabilities.enabled", entries, CapabilitiesEnabled::token)
}

enum class Posture(
    val token: String,
) {
    FULL("full"),
    RESTRICTED("restricted"),
    ;

    companion object : AxisCodec<Posture>("tools.posture", entries, Posture::token)
}

/**
 * Parses one axis's wire token into its enum, failing fast (with the axis key in
 * the message) on an unknown value — the "no String axis value escapes the
 * resolver" guarantee.
 */
open class AxisCodec<T : Enum<T>>(
    val key: String,
    private val values: List<T>,
    private val token: (T) -> String,
) {
    fun parse(raw: String): T =
        values.firstOrNull { token(it).equals(raw, ignoreCase = false) }
            ?: throw ConfigValidationException(
                "invalid value '$raw' for axis '$key' — expected one of ${values.joinToString(", ") { token(it) }}",
            )
}

/** Thrown when axis resolution or validation fails — always boots-stopping. */
class ConfigValidationException(
    message: String,
) : RuntimeException(message)
