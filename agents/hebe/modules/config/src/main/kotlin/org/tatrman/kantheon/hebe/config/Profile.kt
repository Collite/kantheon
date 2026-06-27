package org.tatrman.kantheon.hebe.config

/**
 * The four deployment profiles and their axis-default bundles (contracts §5.1).
 *
 * A preset is a `Map<axisKey, wireToken>` — the exact §5.1 matrix row. The
 * resolver overlays file- and env-level overrides on top, then parses the merged
 * map into a typed [Axes]. Keeping presets as wire tokens (not enums) means the
 * §5.1 matrix transcribes here verbatim and the *same* parse path validates
 * preset values and overrides alike.
 *
 * Where §5.1 lists a slash choice for a profile, the preset picks the first as
 * the default (both remain overridable):
 *  - `server` `security.secrets_backend` → `file` (headless; keychain needs a desktop keyring).
 *  - `server` `tools.posture` → `full` (server is operator-trusted; `restricted` is an opt-in).
 */
enum class Profile(
    val token: String,
    val axisDefaults: Map<String, String>,
) {
    LOCAL(
        "local",
        mapOf(
            "storage.backend" to "sqlite",
            "fs.durability" to "persistent",
            "workspace.backend" to "files",
            "receipts.backend" to "file",
            "platform.reach" to "none",
            // platform.availability is "—" for local (no platform to be available).
            "llm.source" to "byok",
            "security.platform_identity" to "none",
            "security.console_auth" to "password",
            "security.secrets_backend" to "keychain",
            "otel.enabled" to "false",
            "capabilities.enabled" to "false",
            "tools.posture" to "full",
        ),
    ),
    PERSONAL(
        "personal",
        mapOf(
            "storage.backend" to "sqlite",
            "fs.durability" to "persistent",
            "workspace.backend" to "files",
            "receipts.backend" to "file",
            "platform.reach" to "remote",
            "platform.availability" to "intermittent",
            "llm.source" to "gateway_with_byok_fallback",
            "security.platform_identity" to "keycloak",
            "security.console_auth" to "password",
            "security.secrets_backend" to "keychain",
            "otel.enabled" to "false",
            "capabilities.enabled" to "optional",
            "tools.posture" to "full",
        ),
    ),
    SERVER(
        "server",
        mapOf(
            "storage.backend" to "postgres",
            "fs.durability" to "persistent",
            "workspace.backend" to "files",
            "receipts.backend" to "file",
            "platform.reach" to "remote",
            "platform.availability" to "always",
            "llm.source" to "gateway",
            "security.platform_identity" to "keycloak",
            "security.console_auth" to "oidc",
            "security.secrets_backend" to "file",
            "otel.enabled" to "true",
            "capabilities.enabled" to "true",
            "tools.posture" to "full",
        ),
    ),
    K8S(
        "k8s",
        mapOf(
            "storage.backend" to "postgres",
            "fs.durability" to "ephemeral",
            "workspace.backend" to "postgres",
            "receipts.backend" to "postgres",
            "platform.reach" to "in_cluster",
            "platform.availability" to "always",
            "llm.source" to "gateway",
            "security.platform_identity" to "keycloak",
            "security.console_auth" to "oidc",
            "security.secrets_backend" to "k8s",
            "otel.enabled" to "true",
            "capabilities.enabled" to "true",
            "tools.posture" to "restricted",
        ),
    ),
    ;

    companion object {
        fun byToken(token: String): Profile? = entries.firstOrNull { it.token == token }
    }
}
