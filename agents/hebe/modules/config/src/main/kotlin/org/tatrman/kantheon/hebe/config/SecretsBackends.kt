package org.tatrman.kantheon.hebe.config

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64

/**
 * The `security.secrets_backend` axis (P2 Stage 2.3 T2) selects one of three
 * [SecretStoreProvider] implementations behind the same seam:
 *
 *  - `keychain` — the OS keychain ([OsKeychainSecretStore]); `local`/`personal`.
 *  - `file` — a `0600`-permissioned JSON file ([FileSecretStore]); `server`.
 *  - `k8s` — a mounted K8s Secret (dir of files + env) ([K8sSecretStore]); `k8s`.
 *
 * Every secret-ref scheme (`keychain:`/`secret:`/`file:`/`env:`/bare) resolves
 * through whichever impl this returns — see [SecretRef].
 */
object SecretsStoreFactory {
    fun create(
        backend: SecretsBackend,
        dataDir: Path,
    ): SecretStoreProvider =
        when (backend) {
            SecretsBackend.KEYCHAIN -> OsKeychainSecretStore.create(dataDir)
            SecretsBackend.FILE -> FileSecretStore(dataDir.resolve("secrets.json"))
            SecretsBackend.K8S -> K8sSecretStore()
        }
}

/**
 * A permissioned-file secret store (`server` option). Secrets live as a flat
 * `name -> base64(value)` JSON map in a `0600` file under the data dir. Not
 * encrypted at rest (the file perms + a trusted host are the boundary — the same
 * posture as a mounted env file); a passphrase-encrypted variant already exists
 * for the keyless desktop fallback.
 */
class FileSecretStore(
    private val file: Path,
) : SecretStoreProvider {
    private val lock = Any()

    /** POSIX filesystems can enforce `0600`; on others (e.g. Windows) we degrade. */
    private val isPosix: Boolean = file.fileSystem.supportedFileAttributeViews().contains("posix")

    private fun read(): MutableMap<String, String> =
        synchronized(lock) {
            if (!Files.exists(file)) return mutableMapOf()
            val text = Files.readString(file).trim()
            if (text.isEmpty() || text == "{}") return mutableMapOf()
            // Minimal flat-JSON parse ("a":"b") — values are base64, no nesting.
            text
                .removeSurrounding("{", "}")
                .split(',')
                .filter { it.isNotBlank() }
                .associate { pair ->
                    val (k, v) = pair.split(':', limit = 2)
                    k.trim().removeSurrounding("\"") to v.trim().removeSurrounding("\"")
                }.toMutableMap()
        }

    private fun write(map: Map<String, String>) =
        synchronized(lock) {
            Files.createDirectories(file.parent)
            // Create the file 0600 *before* any content is written, so the secrets
            // are never group/world-readable even for an instant (writeString would
            // otherwise create at the umask, typically 0644). On POSIX the parent
            // dir is tightened best-effort; the file's own 0600 is the boundary.
            if (isPosix) {
                runCatching {
                    Files.setPosixFilePermissions(file.parent, PosixFilePermissions.fromString("rwx------"))
                }
            }
            if (!Files.exists(file)) {
                if (isPosix) {
                    Files.createFile(
                        file,
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
                    )
                } else {
                    Files.createFile(file)
                }
            }
            val body = map.entries.joinToString(",") { "\"${it.key}\":\"${it.value}\"" }
            Files.writeString(file, "{$body}")
            if (isPosix) {
                // Re-assert 0600 and FAIL LOUDLY if it cannot be enforced — a secrets
                // file we cannot secure must not silently remain readable.
                try {
                    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
                } catch (e: java.io.IOException) {
                    throw IllegalStateException("cannot enforce 0600 on secrets file $file", e)
                }
            }
        }

    override suspend fun get(key: String): ByteArray? = read()[key]?.let { Base64.getDecoder().decode(it) }

    override suspend fun set(
        key: String,
        value: ByteArray,
    ) {
        val map = read()
        map[key] = Base64.getEncoder().encodeToString(value)
        write(map)
    }

    override suspend fun delete(key: String): Boolean {
        val map = read()
        val removed = map.remove(key) != null
        if (removed) write(map)
        return removed
    }

    override suspend fun list(): List<String> = read().keys.toList()
}

/**
 * Reads secrets from a mounted K8s Secret (`k8s` profile). Kubernetes projects a
 * Secret as a directory of files (one per key) and/or env vars. Lookups try the
 * mount dir first (`<mountDir>/<key>`), then `HEBE_SECRET_<KEY>` env. Read-only:
 * the cluster owns the Secret, so `set`/`delete` are no-ops that report failure.
 */
class K8sSecretStore(
    private val mountDir: Path = Path.of(System.getenv("HEBE_SECRETS_DIR") ?: "/var/run/secrets/hebe"),
    private val env: Map<String, String> = System.getenv(),
) : SecretStoreProvider {
    private fun envKey(key: String) = "HEBE_SECRET_" + key.replace('.', '_').replace('-', '_').uppercase()

    override suspend fun get(key: String): ByteArray? {
        val f = mountDir.resolve(key)
        if (Files.exists(f)) return Files.readAllBytes(f)
        return env[envKey(key)]?.toByteArray()
    }

    override suspend fun set(
        key: String,
        value: ByteArray,
    ) {
        // Cluster-owned; mutation is not Hebe's job. No-op.
    }

    override suspend fun delete(key: String): Boolean = false

    override suspend fun list(): List<String> =
        if (Files.isDirectory(mountDir)) {
            Files.list(mountDir).use { stream -> stream.map { it.fileName.toString() }.toList() }
        } else {
            emptyList()
        }
}
