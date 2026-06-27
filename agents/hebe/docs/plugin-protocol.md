# hebe Plugin Protocol Specification

This document is the authoritative contract for authors writing plugins for hebe. If you want to ship a plugin, read every section. If something here conflicts with code in the repo, the code wins — file an issue.

---

## Contents

1. [Plugin structure](#1-plugin-structure)
2. [Capabilities and permissions](#2-capabilities-and-permissions)
3. [PluginHost surface](#3-pluginhost-surface)
4. [ABI versioning](#4-abi-versioning)
5. [Signing](#5-signing)
6. [Distribution (OCI)](#6-distribution-oci)
7. [Lifecycle](#7-lifecycle)
8. [Restrictions and trust posture](#8-restrictions-and-trust-posture)
9. [Reference: plugin-template](#9-reference-plugin-template)

---

## 1. Plugin structure

A hebe plugin is a fat-JAR or an extracted directory that PF4J can load. It must contain two manifest files at its root.

### `plugin.properties` (PF4J metadata)

```properties
plugin.id=my-plugin
plugin.class=com.example.MyPlugin
plugin.version=0.2.0
plugin.requires=*           # PF4J host version range; use * until you need a specific version
plugin.provider=Example Corp
plugin.description=Does something useful
plugin.license=MIT
```

All PF4J fields follow [pf4j.org conventions](https://pf4j.org).

### `plugin.toml` (hebe manifest)

```toml
hebe_api_version = "0.1.x"   # semver range; must match the running hebe version

[capabilities]
# Declare every capability your plugin needs. Undeclared capabilities are denied at runtime.
tool = true          # plugin contributes Tool implementations

[permissions]
http_client = true            # allows calling host.http()
env_read = true               # allows calling host.env("NAME")
secrets = ["my-plugin.api-key"]   # named secrets the host will inject as handles

[http]
allowlist = [
    "api.example.com",
    "api.another.com",
]

[signing]
signature = ""        # hex-encoded Ed25519 signature over the archive layer's SHA-256
publisher_key = ""    # hex-encoded Ed25519 public key; must be in config.plugins.publisher_keys
```

---

## 2. Capabilities and permissions

| Capability / Permission | Declared in `plugin.toml` | What it enables |
|---|---|---|
| `tool = true` | `[capabilities]` | Plugin may contribute `Tool` implementations |
| `http_client = true` | `[permissions]` | `host.http()` returns a `GatedHttpClient`; domains enforced against the `[http].allowlist` |
| `env_read = true` | `[permissions]` | `host.env("NAME")` returns the value if `NAME` does not match `*_TOKEN`, `*_SECRET`, or `*_KEY` patterns |
| `secrets = ["name"]` | `[permissions]` | `host.secret("name")` returns a `SecretHandle`; the actual value is never exposed to the plugin JAR |

Reserved for future capabilities (do not use): `channel`, `memory`, `observer`.

Calling `host.http()` without declaring `http_client = true` throws `PluginCapabilityException` immediately. There is no warning-only mode.

---

## 3. PluginHost surface

The `PluginHost` interface is the only seam through which a plugin reaches host-side facilities. The full interface is in `modules/plugin-api/src/main/kotlin/com/hebe/plugin/PluginHost.kt`.

```kotlin
interface PluginHost {
    val pluginId: String
    val manifest: PluginManifest

    fun http(): GatedHttpClient       // requires http_client
    fun env(name: String): String?    // requires env_read; filtered against sensitive patterns
    fun secret(name: String): SecretHandle?   // requires secrets:<name>

    val observer: Observer            // emit structured events for OTel + ring-buffer
    val log: org.slf4j.Logger         // JSON-structured; session_id + plugin fields auto-added
}
```

`GatedHttpClient.get()` / `.post()` throw `PluginCapabilityException` if the target URL does not match the allowlist declared in `plugin.toml`. The URL check happens before the network call.

`SecretHandle` is an opaque wrapper. Pass it back to `GatedHttpClient` or other host APIs; never try to deserialize or log it.

---

## 4. ABI versioning

`hebe_api_version` in `plugin.toml` is a SemVer range using the `x` wildcard syntax:

| Range | Matches |
|---|---|
| `"0.1.x"` | Any `0.1.*` release |
| `"0.x"` | Any `0.*.*` release (not recommended — too wide) |
| `"0.2.0"` | Exactly `0.2.0` |

hebe never removes or changes the signature of a type in the `api` or `plugin-api` module within a major version. Fields can be added to data classes; they will never be removed while the major is stable.

A plugin whose `hebe_api_version` range does not include the running hebe version is rejected at the RESOLVED state with a clear error and never started. Check `hebe plugin list` for the reported error.

---

## 5. Signing

Signing is optional in development but should be required in production.

### Generating a keypair

```bash
# Using openssl (Ed25519)
openssl genpkey -algorithm ed25519 -out plugin-signing.pem
openssl pkey -in plugin-signing.pem -pubout -out plugin-signing-pub.pem
```

### Signing the plugin archive

The signature is over the SHA-256 digest of the plugin archive layer (the `.tar.gz`):

```bash
ARCHIVE_HASH=$(sha256sum my-plugin-0.2.0.tar.gz | awk '{print $1}')
SIG=$(echo -n "$ARCHIVE_HASH" | openssl pkeyutl -sign -inkey plugin-signing.pem | xxd -p -c 256)
```

Set `signing.signature` and `signing.publisher_key` in `plugin.toml` before publishing.

### Host configuration

```toml
# ~/.hebe/config.toml
[plugins]
publisher_keys = ["<hex-encoded-public-key>"]
plugin_signature_mode = "required"   # optional | required | disabled
```

| `signature_mode` | Unsigned plugin | Signed with unknown key | Signed with known key |
|---|---|---|---|
| `optional` | WARN, load | WARN, load | load |
| `required` | REJECT | REJECT | load |
| `disabled` | load | load | load |

Default is `optional`. **Set `required` for any production deployment.**

---

## 6. Distribution (OCI)

hebe uses the OCI distribution spec to publish and pull plugins. The ORAS Java SDK handles the actual registry interaction.

### Artifact shape

| Layer | Media type | Content |
|---|---|---|
| 0 (required) | `application/vnd.hebe.plugin.archive.v1.tar+gzip` | `plugin.toml` + `plugin.properties` + `classes/` + `lib/` |
| 1 (optional) | `application/vnd.hebe.plugin.signature.v1+ed25519` | Binary Ed25519 over the archive layer SHA-256 |

Manifest media type: `application/vnd.hebe.plugin.v1+json`.

### Publishing (using the plugin-template Gradle task)

```bash
cd plugin-template
./gradlew publishPlugin -Pregistry=acr.example.com/hebe-plugins
# pushes my-plugin:0.2.0 to the registry
```

### Installing

```bash
# From OCI registry
hebe plugin install acr.example.com/hebe-plugins/my-plugin:0.2.0

# Sideload from local path (development)
hebe plugin install ./my-plugin-0.2.0.jar
```

### Auth

- **Azure Container Registry**: `DefaultAzureCredential` chain is tried in order (env vars → managed identity → `az login` token).
- **Other registries**: Docker config file (`~/.docker/config.json`) or ORAS auth file are used as fallback.

---

## 7. Lifecycle

```
CREATED   ← PF4J scans ~/.hebe/plugins/; classloader created; classes loaded
    ↓
RESOLVED  ← PF4J validates plugin.properties
              hebe: parse plugin.toml, verify signature, check hebe_api_version
              REJECT here → plugin stays CREATED; error in `hebe plugin list`
    ↓
STARTED   ← plugin.start() (PF4J)
              hebe: build PluginHost with capability gates
              plugin.init(host) called
              plugin.tools(host) registered as "<pluginId>:<toolName>"
              ObserverEvent.PluginLoaded emitted
    ↓
STOPPED   ← plugin.teardown() called (hebe)
              hebe: deregisters tools from ToolRegistry
              plugin.stop() (PF4J)
    ↓
UNLOADED  ← classloader closed
              warn if classes still pinned in memory (GC dependent)
```

**Classloader isolation:** plugins see only `api` and `plugin-api`. Attempting to import `org.tatrman.kantheon.hebe.core.*` or any other internal module fails to load. This is verified by a negative test in the CI suite.

**Restart on update:** PF4J classloader leaks are a known JVM issue. For production plugin updates, restart the hebe process. Hot-reload is deferred to v2.

---

## 8. Restrictions and trust posture

### What the classloader enforces

- Plugins may only import classes from `org.tatrman.kantheon.hebe.api.*`, `org.tatrman.kantheon.hebe.plugin.*`, and their declared third-party dependencies (bundled in `lib/`).
- They must not bundle `hebe-api` or `plugin-api` JARs in `implementation` scope — these are provided by the host. Bundling them causes `ClassCastException` at runtime.
- They must not reference `Class.forName("org.tatrman.kantheon.hebe.core.*")` or any other internal package. This is caught by the negative-load test.

### What the classloader does NOT enforce

JVM plugins run in the same process as hebe. The classloader provides **dependency-version isolation, not security isolation**. A plugin can:

- Call `Runtime.exec()` or `ProcessBuilder` directly.
- Open arbitrary network connections bypassing `GatedHttpClient`.
- Read or write arbitrary files.
- Access other classes in the JVM heap via reflection.

**Trust model: only install plugins from sources you control or publishers whose Ed25519 key you have explicitly added to `config.plugins.publisher_keys`.** Treat a plugin install with the same level of trust as running an arbitrary binary.

The `signature_mode = required` configuration reduces the risk of supply-chain substitution but does not sandbox the plugin. There is no subprocess sandbox in v1; that is a v2 deliverable.

---

## 9. Reference: plugin-template

The in-tree `plugin-template/` directory is a complete, buildable example. It demonstrates:

- `HebePlugin` subclass with `tools()` and `init()`.
- A minimal `Tool` implementation.
- `plugin.toml` + `plugin.properties`.
- The `publishPlugin` Gradle task for pushing to an OCI registry.
- Unit tests using MockK for the `PluginHost`.

Start there. Copy, rename, and build on it.
