package org.tatrman.kantheon.hebe.config

/**
 * Resolves a secret *reference* to its value (P2 Stage 2.2; contracts §5.2).
 * The `[llm].api_key_ref` and similar fields carry a scheme:
 *
 *  - `keychain:NAME` / `secret:NAME` / `file:NAME` → looked up in the active
 *    [SecretStoreProvider] (whichever the `security.secrets_backend` axis
 *    selected via [SecretsStoreFactory] — keychain | file | k8s)
 *  - `env:NAME` → read from the process environment
 *  - bare `NAME` (no scheme) → treated as a secret-store key (back-compat with
 *    the existing `api_key_secret` field)
 *
 * The scheme names a *namespace intent*, not a different backend per call — all
 * store-backed schemes delegate to the one configured [SecretStoreProvider]
 * (Stage 2.3 T2). `env:` is the one exception (always the process env).
 */
object SecretRef {
    suspend fun resolve(
        ref: String,
        secretStore: SecretStoreProvider,
        env: Map<String, String> = System.getenv(),
    ): String? {
        if (ref.isBlank()) return null
        val (scheme, name) =
            ref.indexOf(':').let { i ->
                if (i >= 0) ref.substring(0, i) to ref.substring(i + 1) else "" to ref
            }
        return when (scheme) {
            "env" -> env[name]
            "keychain", "secret", "file", "" -> secretStore.get(name)?.let { String(it, Charsets.UTF_8) }
            else -> throw ConfigValidationException(
                "unknown secret-ref scheme '$scheme' in '$ref' — expected keychain:, secret:, file:, env:, or a bare key",
            )
        }
    }
}
