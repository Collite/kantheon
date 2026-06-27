# M6 — Plugins (PF4J spike → production loader)

PF4J spike (M6.T1), `PluginManager` wrapper, manifest parser, `PluginHost` capability gates, signature verification, ABI compatibility, lifecycle wiring, OCI client, install/list/remove, auto_pull, plugin template repo.

**Done when:** the PF4J spike passes (M6.T1) AND the production loader pulls, verifies, and runs a signed plugin from ACR end-to-end (M6.T9).

References: [`../v1-architecture.md`](../v1-architecture.md) §§4, 8, 11, 12.

---

## M6.T1 — **PF4J spike: hello-world plugin loaded from a local JAR**

**Status**: pending  
**Size**: L  
**Depends on**: M0.T6  
**Blocks**: M6.T2 onwards

> This is the **derisking spike**. Scope is deliberately narrow: load a plugin JAR from a local directory and invoke its tool. No manifest validation, no signature, no OCI pull. The point is to discover any classloader / ABI / Kotlin-on-PF4J surprises before the rest of M6 builds on top.

### Goal

Build a hello-world plugin in the same repo (or a sibling), load it via PF4J inside the running hebe process, and invoke its `say_hello` tool from a chat turn. Verify that the plugin classloader cannot see `com.hebe.core.*` or `koog`.

### Files to create

- `modules/plugins/build.gradle.kts` (edit)
- `modules/plugins/src/main/kotlin/com/hebe/plugins/PluginManager.kt` (new — minimal PF4J wrapper)
- `plugin-template/` (new — Gradle subproject; not a `:modules:` member)
- `plugin-template/build.gradle.kts` (new)
- `plugin-template/src/main/kotlin/com/example/HelloPlugin.kt` (new)
- `plugin-template/src/main/kotlin/com/example/SayHelloTool.kt` (new)
- `plugin-template/src/main/resources/plugin.properties` (new)
- `plugin-template/src/main/resources/plugin.toml` (new — hebe manifest stub)
- Spike test that runs end-to-end

### Detailed work

1. `modules/plugins/build.gradle.kts`:

   ```kotlin
   dependencies {
       api(project(":modules:plugin-api"))
       implementation(project(":modules:api"))
       implementation(project(":modules:observability"))
       implementation(libs.pf4j)
   }
   ```

2. `PluginManager` (minimal for the spike — no manifest validation, no signing):

   ```kotlin
   class PluginManager(private val pluginDir: Path, private val host: PluginHostFactory) {
       private val pf4j = DefaultPluginManager(pluginDir)
       fun start() {
           pf4j.loadPlugins()
           pf4j.startPlugins()
       }
       fun tools(): List<Tool> = pf4j.getExtensions(HebePlugin::class.java)
           .flatMap { plugin ->
               val pluginHost = host.create(plugin.wrapper.pluginId)
               plugin.init(pluginHost)
               plugin.tools(pluginHost)
           }
       fun stop() = pf4j.stopPlugins().also { pf4j.unloadPlugins() }
   }
   ```

3. **`plugin-template/`** is a sibling Gradle build (NOT included in `settings.gradle.kts`'s `:modules:*`). It's the template plugin authors will start from. For the spike it's the test fixture.

   `plugin-template/build.gradle.kts`:

   ```kotlin
   plugins {
       kotlin("jvm") version "2.2.0"
   }
   dependencies {
       compileOnly(rootProject.project(":modules:plugin-api"))     // hard rule: compileOnly, never `implementation`
       compileOnly(rootProject.project(":modules:api"))
   }
   tasks.jar {
       from(configurations.runtimeClasspath.get().filter { it.exists() }.map { if (it.isDirectory) it else zipTree(it) })
       archiveBaseName.set("hello-plugin")
   }
   ```

4. **`HelloPlugin.kt`**:

   ```kotlin
   package com.example
   import com.hebe.plugin.HebePlugin
   import com.hebe.plugin.PluginHost
   import com.hebe.api.Tool
   import org.pf4j.PluginWrapper

   class HelloPlugin(wrapper: PluginWrapper) : HebePlugin(wrapper) {
       override fun tools(host: PluginHost): List<Tool> = listOf(SayHelloTool(host))
   }
   ```

5. **`SayHelloTool.kt`**:

   ```kotlin
   package com.example
   class SayHelloTool(private val host: PluginHost) : Tool {
       override val spec = ToolSpec(
           name = "hello:say_hello",
           description = "Print a greeting.",
           schema = buildJsonObject { put("type", "object") },
       )
       override val risk = RiskLevel.Low
       override suspend fun invoke(args: JsonObject, ctx: ToolContext): ToolResult {
           return ToolResult.Ok(JsonPrimitive("hello from plugin"))
       }
   }
   ```

6. **`plugin.properties`**:

   ```properties
   plugin.id=hello
   plugin.class=com.example.HelloPlugin
   plugin.version=0.1.0
   plugin.provider=hebe-spike
   plugin.dependencies=
   ```

7. **`plugin.toml`** (stub for the spike — only `hebeApiVersion`, no permissions):

   ```toml
   hebe_api_version = "0.1.x"
   capabilities = ["tool"]
   permissions = []
   allowlist_domains = []
   ```

8. **Spike test**:

   ```kotlin
   class PluginSpikeTest : FunSpec({
       test("hello plugin loads from local jar and tool callable") {
           // build the plugin jar via Gradle programmatically OR use a pre-built fixture
           val pluginDir = tempdir().also { copyHelloPluginTo(it) }
           val pm = PluginManager(pluginDir.toPath(), TestPluginHostFactory)
           pm.start()
           val tools = pm.tools()
           tools.map { it.spec.name } shouldContain "hello:say_hello"
           val result = tools.first().invoke(buildJsonObject {}, mockToolContext())
           result.shouldBeInstanceOf<ToolResult.Ok>()
       }
   })
   ```

9. **Negative test (the load-time isolation check)**:

   - In the plugin source, add a debug method `internalAccessTest()` that tries `Class.forName("com.hebe.core.agent.HebeAgent")` and reports the result.
   - Verify it throws `ClassNotFoundException`. If it succeeds, the classloader hierarchy is wrong and we have a bug.
   - This is the **load-bearing check** of the spike. If this passes, the rest of M6 has a stable foundation.

### Tests / verification

- Spike test passes.
- Plugin classloader can't see `com.hebe.core.*`.
- Plugin classloader CAN see `com.hebe.api.*` and `com.hebe.plugin.*`.

### Acceptance criteria

- ✅ Hello plugin built as a JAR.
- ✅ `PluginManager.start()` loads it.
- ✅ `PluginManager.tools()` returns `[SayHelloTool]`.
- ✅ Tool invocation returns the expected `Ok`.
- ✅ Negative classloader-isolation test passes.

### Pitfalls

- PF4J's classloader by default is parent-first; we want **child-first for the plugin's own classes** but **parent-first for `hebe-api` and `plugin-api`**. Configure via `DefaultPluginManager.createPluginClassLoader` override; if it's awkward, document and revisit in M6.T2.
- Kotlin metadata: PF4J needs to find the `HebePlugin` subclass. Make sure the plugin JAR's manifest entry `plugin.class` points at the FQCN exactly.
- The `compileOnly` dep on `plugin-api`/`api` is **load-bearing**. If the plugin uses `implementation`, it bundles those classes and the host's copy collides — instant `ClassCastException` at the boundary.

### References

- `v1-architecture.md` §8 (plugin model)
- [`../hebe-brainstorming-responses.md`](../hebe-brainstorming-responses.md) §6.2

---

## M6.T2 — `PluginManagerWrapper` (PF4J `DefaultPluginManager` subclass + classloader rules)

**Status**: pending  
**Size**: M  
**Depends on**: M6.T1  
**Blocks**: M6.T3 onwards

### Goal

Productionise the spike's `PluginManager` into a configurable wrapper with explicit classloader policy, so the negative test continues to pass after the rest of M6 lands.

### Files to create

- `modules/plugins/src/main/kotlin/com/hebe/plugins/HebePluginManager.kt` (new — extends `DefaultPluginManager`)
- `modules/plugins/src/main/kotlin/com/hebe/plugins/HebePluginClassLoader.kt` (new)
- Tests including isolation negative test

### Detailed work

1. `HebePluginClassLoader` extends PF4J's `PluginClassLoader`:
   - Parent classloader = a "plugin-api" classloader that exposes only `com.hebe.api.*` and `com.hebe.plugin.*`.
   - Strategy: child-first for plugin's own classes + bundled `lib/`, parent-first for `hebe-api` + `plugin-api`.

2. `HebePluginManager.createPluginClassLoader(...)` returns the above.

3. The "plugin-api classloader" is built once at startup: a `URLClassLoader` over the `hebe-api` and `plugin-api` JARs only (extracted via `ClassLoader.getSystemResource`, or built explicitly).

4. Test: a synthetic plugin JAR containing a class that references `com.hebe.core.agent.HebeAgent` fails to load with `NoClassDefFoundError` at the right moment.

### Tests / verification

- Isolation check: `Class.forName("com.hebe.core.*")` fails inside plugins.
- Visibility check: `Class.forName("com.hebe.api.Tool")` succeeds.
- Two plugins with conflicting third-party deps (e.g. both bundle `okhttp` at different versions) coexist (each sees its own).

### Acceptance criteria

- ✅ Classloader rules documented.
- ✅ Negative test stays green.
- ✅ Cross-plugin dep isolation verified.

### Pitfalls

- PF4J's existing `PluginClassLoader.PARENT_FIRST` flag isn't a clean fit; we need per-package rules. Subclass `loadClass` with a small allowlist.

### References

- `v1-architecture.md` §8

---

## M6.T3 — `plugin.toml` parser + manifest model

**Status**: pending  
**Size**: M  
**Depends on**: M0.T8, M6.T2  
**Blocks**: M6.T4–T6

### Goal

Parse `plugin.toml` into `PluginManifest`. Errors point to row/col. Missing required fields rejected.

### Files to create

- `modules/plugins/src/main/kotlin/com/hebe/plugins/manifest/ManifestParser.kt` (new)
- `modules/plugins/src/main/kotlin/com/hebe/plugins/manifest/ManifestError.kt` (new)
- Tests

### Detailed work

1. Required fields in `plugin.toml`:
   - `hebe_api_version` (string, semver-ish range)
   - `capabilities` (array)
   - `permissions` (array)
   - `allowlist_domains` (array, may be empty)

   Optional:
   - `signature` (base64url string)
   - `publisher_key` (hex string)

2. Permission parsing: handle three shapes:
   - `"http_client"` → `Permission.HttpClient`.
   - `"env_read"` → `Permission.EnvRead`.
   - `"secrets:foo"` → `Permission.Secret("foo")`.

3. Capability parsing maps to the enum.

4. Parser uses `tomlj` (already in catalogue from M0.T2). Position info attached to errors.

### Tests / verification

- Golden valid manifest parses.
- Missing `hebe_api_version` → error with line/col.
- Unknown capability → error.

### Acceptance criteria

- ✅ All shapes parse.
- ✅ Errors are diagnostic (line/col).

### References

- `v1-architecture.md` §8 (manifest)

---

## M6.T4 — `PluginHost` impl with capability gates

**Status**: pending  
**Size**: L  
**Depends on**: M6.T3, M3.T4 (domain matcher), M0.T9 (secrets)  
**Blocks**: M6.T7 (lifecycle)

### Goal

Build a per-plugin `PluginHost` instance whose `http()` / `env(name)` / `secret(name)` are gated by the plugin's manifest. Calling a method without the corresponding permission throws `PluginCapabilityException`.

### Files to create

- `modules/plugins/src/main/kotlin/com/hebe/plugins/host/RealPluginHost.kt` (new)
- `modules/plugins/src/main/kotlin/com/hebe/plugins/host/GatedHttpClientImpl.kt` (new)
- `modules/plugins/src/main/kotlin/com/hebe/plugins/host/HostFactory.kt` (new)
- Tests

### Detailed work

1. `RealPluginHost(pluginId, manifest, secretStore, observer, logger, ...)`:
   - `http()`: returns a `GatedHttpClientImpl` only if `Permission.HttpClient` is in the manifest. Else throws.
   - `env(name)`: returns the env var value if `Permission.EnvRead` is present AND the name doesn't match the redactor's denylist patterns (`*_TOKEN`, `*_SECRET`, `*_KEY`). Else null.
   - `secret(name)`: returns `SecretHandle(name)` if `Permission.Secret(name)` is in the manifest. The host injects the value when the plugin uses the handle in `GatedHttpClient.post(... auth = SecretHandle("foo"))`.

2. `GatedHttpClientImpl` validates each request URL against `manifest.allowlistDomains` AND the global SSRF guard from M3.T4. Rejects others.

3. Auth-handle resolution: when a request specifies an auth handle, the host appends `Authorization: Bearer <secretValue>` (or whatever the configured scheme is) before sending. The plugin never sees the raw secret.

4. `HostFactory.create(pluginId, manifest)`: factory used by `HebePluginManager` to build a host instance per plugin.

### Tests / verification

- Plugin with no `http_client` permission calling `host.http()` throws.
- Plugin with `http_client` but URL outside `allowlist_domains` → `PluginCapabilityException` from `get/post`.
- Secret injection: plugin uses `SecretHandle`; HTTP request observed at the boundary contains `Authorization: Bearer ...`; the plugin code never references the raw value.

### Acceptance criteria

- ✅ All three gates enforced.
- ✅ Allowlist enforced on every HTTP request.
- ✅ Secret values never visible to plugin code.

### Pitfalls

- The "plugin can't see the secret" claim depends on us *not* exposing the value via `env()` for keys it could otherwise reach. Double-check the env redactor patterns.

### References

- `v1-architecture.md` §8 (capability gates)

---

## M6.T5 — Ed25519 signature verification

**Status**: pending  
**Size**: M  
**Depends on**: M6.T3, M0.T9  
**Blocks**: M6.T7

### Goal

Verify plugin signatures on load. `signature_mode = optional | required | disabled`, default `optional`.

### Files to create

- `modules/plugins/src/main/kotlin/com/hebe/plugins/signature/SignatureVerifier.kt` (new)
- Tests

### Detailed work

1. Mode (from `config.security.plugin_signature_mode`):
   - `disabled` — never check.
   - `optional` (v1 default) — if `signature` + `publisher_key` present in manifest, verify; if missing, log a warning and load.
   - `required` — refuse to load any plugin without a valid signature from a `publisher_key` listed in `config.plugins.publisher_keys`.

2. Signature payload: SHA-256 of the plugin **archive** (the `.jar` or the tarball for OCI) before extraction. Stored in `manifest.signature` as base64url.

3. Verification: Bouncy Castle Ed25519 over the archive hash, against the manifest's `publisher_key`. If `mode = required`, also verify the publisher key is in the trusted list.

4. Outcome:
   - `Verified(publisherKey)` → log + load.
   - `Unsigned` + `optional` → warn + load.
   - `Unsigned` + `required` → refuse.
   - `BadSignature` → refuse always.

### Tests / verification

- Signed plugin under `required` → loads.
- Unsigned under `required` → refused.
- Unsigned under `optional` → loads with warning.
- Tampered (matching publisher key but wrong sig) → refused.

### Acceptance criteria

- ✅ Three modes implemented.
- ✅ Default optional.
- ✅ Tests cover all four outcomes.

### References

- `v1-architecture.md` §8

---

## M6.T6 — ABI compatibility check

**Status**: pending  
**Size**: S  
**Depends on**: M6.T3  
**Blocks**: M6.T7

### Goal

Refuse plugins whose `hebe_api_version` doesn't match the host's API version.

### Files to create

- `modules/plugins/src/main/kotlin/com/hebe/plugins/abi/AbiChecker.kt` (new)
- `modules/plugin-api/src/main/kotlin/com/hebe/plugin/AbiVersion.kt` (new — exposed constant)
- Tests

### Detailed work

1. Host version constant: `AbiVersion.CURRENT = "0.1.0"` in `plugin-api`.

2. Manifest version is a SemVer range. v1 supports the simplified subset:
   - Exact: `"0.1.0"`.
   - Caret: `"0.1.x"` (matches `0.1.*`).
   - Range: `">=0.1.0 <0.2.0"` (whitespace-separated bounds).

3. `AbiChecker.check(manifest, hostVersion): Result<Unit, String>` returns Ok or a clear error.

### Tests / verification

- Match cases pass; mismatch cases fail with a remediation hint ("plugin requires 0.2.x but host is 0.1.x; upgrade hebe or downgrade plugin").

### Acceptance criteria

- ✅ Exact, caret, range supported.
- ✅ Mismatch produces actionable error.

### References

- `v1-architecture.md` §11

---

## M6.T7 — Plugin lifecycle wiring into `ToolRegistry`

**Status**: pending  
**Size**: M  
**Depends on**: M6.T2, M6.T3, M6.T4, M6.T5, M6.T6, M2.T6 (registry)  
**Blocks**: M6.T9

### Goal

Glue: when PF4J starts a plugin, build the host, run `init`, register tools. When PF4J stops a plugin, unregister tools and call `teardown`.

### Files to create

- `modules/plugins/src/main/kotlin/com/hebe/plugins/Lifecycle.kt` (new)
- `modules/plugins/src/main/kotlin/com/hebe/plugins/PluginRegistration.kt` (new — bookkeeping)
- Tests

### Detailed work

1. `Lifecycle.afterStart(pluginWrapper)`:
   - Read manifest.
   - Verify signature → ABI check → if either fails, log + stop the plugin via PF4J and return.
   - Build `PluginHost` via `HostFactory.create(pluginId, manifest)`.
   - Cast `pluginWrapper.plugin` to `HebePlugin`; call `init(host)` and collect `tools(host)`.
   - For each tool, register in `ToolRegistry` with a namespaced name `<pluginId>:<tool.spec.name>`. Track registrations in `PluginRegistration` so we can unregister precisely.

2. `Lifecycle.beforeStop(pluginWrapper)`:
   - Look up registered tools in `PluginRegistration`.
   - `ToolRegistry.unregister(name)` for each.
   - Call `plugin.teardown()`.

3. Errors during `init`/`teardown` are caught + logged + reported via observer; they never crash the host.

### Tests / verification

- Hello-world plugin loaded → `say_hello` tool dispatchable as `hello:say_hello`.
- Stop the plugin → tool no longer in registry.

### Acceptance criteria

- ✅ Tools registered with namespaced ids.
- ✅ Unregistration on stop.
- ✅ Errors don't crash the host.

### References

- `v1-architecture.md` §11

---

## M6.T8 — OCI client (ORAS Java SDK) wrapper

**Status**: pending  
**Size**: L  
**Depends on**: M0.T9 (secrets), M0.T2 (catalogue includes oras)  
**Blocks**: M6.T9

### Goal

`OciClient.pull(ref): Path` returns a tarball at `~/.hebe/cache/oci/<sha256>/`. Authenticated against ACR via `DefaultAzureCredential` chain; falls back to ORAS auth file / docker config for non-Azure registries.

### Files to create

- `modules/plugins/src/main/kotlin/com/hebe/plugins/oci/OciClient.kt` (new)
- `modules/plugins/src/main/kotlin/com/hebe/plugins/oci/AzureAuthChain.kt` (new)
- Tests with a local OCI registry (Docker container via Testcontainers; mark `@Tag("integration")`)

### Detailed work

1. Use `land.oras:oras-java-sdk` or equivalent. **Confirm versioned APIs at PR time** — this SDK is young and may have changed.

2. `OciClient.pull(ref: String): PulledArtifact` flow:
   - Parse `ref = registry/repo:tag`.
   - Resolve auth:
     - If registry hostname matches `*.azurecr.io`, use `AzureAuthChain` (DefaultAzureCredential → token → exchange for ACR refresh+access tokens). The chain reads env (`AZURE_CLIENT_ID/SECRET/TENANT_ID`), MSI (`IMDS_ENDPOINT`), `az login` cache (`~/.azure/`), in that order.
     - Else look up `~/.docker/config.json` or `~/.config/oras/config.json` for `auths[registry].auth` (base64 user:pass).
   - Pull manifest, verify media type `application/vnd.hebe.plugin.v1+json`.
   - Pull layer 0 (the archive) into the cache directory by sha256.
   - Optional: pull the signature layer (`application/vnd.hebe.plugin.signature.v1+ed25519`) and pass to the verifier in M6.T9.

3. Idempotent: if the cache already has the artifact (matched by digest), short-circuit.

4. Returns `PulledArtifact(archivePath: Path, signaturePath: Path?, digest: String)`.

### Tests / verification

- Integration test: spin up a local zot/registry, push a synthetic artifact via the SDK, pull it via `OciClient`. Assert digest match.
- Auth chain test: env-var-only path works against a registry that requires basic auth.

### Acceptance criteria

- ✅ ACR auth chain works on a real ACR instance (manual test).
- ✅ Local registry round-trip in CI.
- ✅ Idempotent.

### Pitfalls

- ACR token exchange: the bearer token from AAD doesn't work directly; you must POST to `https://<registry>/oauth2/exchange` to get an ACR-specific access token. ORAS Java SDK may handle this automatically — confirm.
- Layer media types are hebe-specific; if a registry rejects them, fall back to `application/vnd.oci.image.layer.v1.tar+gzip`.

### References

- `v1-architecture.md` §12 (OCI/ACR flow)

---

## M6.T9 — `hebe plugin install <oci-ref>` (pull → verify → extract → load)

**Status**: pending  
**Size**: M  
**Depends on**: M6.T7, M6.T8  
**Blocks**: M6.T11

### Goal

End-to-end install flow as a single CLI command. After this lands the production loader is functional.

### Files to create

- `modules/cli-app/src/main/kotlin/com/hebe/cli/commands/Plugin.kt` (edit — implement `install`)
- `modules/plugins/src/main/kotlin/com/hebe/plugins/install/InstallFlow.kt` (new)
- Tests

### Detailed work

1. `InstallFlow.install(ref: String): InstallResult`:
   - Pull via `OciClient`.
   - Verify signature via `SignatureVerifier`.
   - Verify ABI via `AbiChecker`.
   - Extract archive into `~/.hebe/plugins/<name>-<version>/`.
   - Notify the running `HebePluginManager` to load the new plugin (or instruct the user to restart, depending on the lifecycle strategy — since hot-reload is deferred per `hebe-brainstorming-responses.md` §6.6, **v1 requires a restart**; the install command exits successfully with a "restart hebe to activate" notice).

2. Persist install records in `settings`:

   ```sql
   key = "plugins.installed", value = JSON([{name, version, ref, installed_at}])
   ```

3. CLI: `hebe plugin install acr.example.com/hebe-plugins/linear:0.3.1`. Output:

   ```
   Pulling acr.example.com/hebe-plugins/linear:0.3.1 …
   Verified signature (publisher: hebe-team, ed25519:abc…).
   ABI compatible (plugin: 0.1.x, host: 0.1.0).
   Extracted to ~/.hebe/plugins/linear-0.3.1/
   Restart hebe to load this plugin.
   ```

### Tests / verification

- Local OCI registry → install → restart → `hebe tool list` shows the new tool.
- Bad signature in `required` mode → install refuses.

### Acceptance criteria

- ✅ Full pull-verify-extract-record round-trip.
- ✅ Restart-required message clear.
- ✅ Persisted install records.

### References

- `v1-architecture.md` §12

---

## M6.T10 — `hebe plugin install <local-path>` (sideload)

**Status**: pending  
**Size**: S  
**Depends on**: M6.T7  
**Blocks**: nothing direct; useful for plugin authors

### Goal

Bypass OCI for local development. Same final steps as M6.T9 minus the pull.

### Files to create / modify

- `modules/cli-app/src/main/kotlin/com/hebe/cli/commands/Plugin.kt` (edit)
- Tests

### Detailed work

1. Detect arg shape: if it looks like a path (`./foo.jar`, `/abs/path/`), treat as sideload; else treat as OCI ref.

2. Sideload path: copy/extract into `~/.hebe/plugins/<name>-<version>/`, run signature + ABI check (with the `signature_mode = disabled` shortcut for unsigned local builds — opt-in via `--unsigned` flag).

### Tests / verification

- Sideload a built `plugin-template` JAR; restart; tool callable.

### Acceptance criteria

- ✅ Local path detection.
- ✅ `--unsigned` flag for dev.

---

## M6.T11 — `hebe plugin list` / `hebe plugin remove`

**Status**: pending  
**Size**: M  
**Depends on**: M6.T9  
**Blocks**: M9.T1 (doctor reports plugins)

### Goal

Inspection + uninstall.

### Files to create / modify

- `modules/cli-app/src/main/kotlin/com/hebe/cli/commands/Plugin.kt` (edit)
- Tests

### Detailed work

1. `hebe plugin list`:

   ```
   ID      VERSION  STATUS    CAPABILITIES   PERMISSIONS
   linear  0.3.1    started   [tool]         [http_client, secrets:linear_api_key]
   hello   0.1.0    error     [tool]         []
   ```

   - Reads PF4J state + the `settings.plugins.installed` record.
   - "error" status indicates the plugin failed to load (signature, ABI, etc.); details available via `hebe plugin show <name>`.

2. `hebe plugin remove <name>`:
   - Stop via `HebePluginManager.unloadPlugin(id)`.
   - Delete `~/.hebe/plugins/<name>-<version>/`.
   - Update `settings.plugins.installed`.

3. `hebe plugin show <name>` (bonus): full manifest + last error if any.

### Tests / verification

- Install + list + remove round-trip.

### Acceptance criteria

- ✅ list output matches the table.
- ✅ remove cleans up filesystem.

### References

- `v1-architecture.md` §12

---

## M6.T12 — `auto_pull` on boot

**Status**: pending  
**Size**: S  
**Depends on**: M6.T9  
**Blocks**: nothing

### Goal

If `config.plugins.auto_pull = ["linear:0.3.1", ...]`, pull missing plugins at boot.

### Files to create / modify

- `modules/cli-app/src/main/kotlin/com/hebe/cli/AppComponents.kt` (edit — add to boot sequence)
- Tests

### Detailed work

1. After `Db.open` and before `PluginManager.start()`, iterate `auto_pull`:
   - If `~/.hebe/plugins/<name>-<version>/` exists, skip.
   - Else `InstallFlow.install("${config.plugins.registry}/${name}:${version}")`.

2. Failures are non-fatal: log + continue; `doctor` reports.

### Tests / verification

- Missing entries pulled at boot.
- Network failure → boot continues with a warning.

### Acceptance criteria

- ✅ Auto-pull on boot.
- ✅ Non-fatal on failure.

### References

- `v1-architecture.md` §19 (boot sequence)

---

## M6.T13 — `plugin-template/` (Gradle template)

**Status**: pending  
**Size**: M  
**Depends on**: M6.T1 (the spike already produced a minimal template), M6.T7  
**Blocks**: nothing direct; enables internal plugin authors

### Goal

A polished Gradle template repo for internal plugin authors. Includes the manifest skeleton, sample tool, ORAS publish task, README.

### Files to create

- `plugin-template/README.md` (new — author-facing docs)
- `plugin-template/build.gradle.kts` (edit — finalise)
- `plugin-template/settings.gradle.kts` (new — standalone)
- `plugin-template/src/main/kotlin/com/example/MyPlugin.kt` (template)
- `plugin-template/src/main/resources/plugin.properties` (template with placeholders)
- `plugin-template/src/main/resources/plugin.toml` (template)
- `plugin-template/src/main/kotlin/com/example/MyTool.kt` (template)
- `plugin-template/src/test/kotlin/com/example/MyToolTest.kt` (template)
- `plugin-template/buildSrc/src/main/kotlin/oras-publish.gradle.kts` (new — Gradle task wrapping `oras push`)

### Detailed work

1. Standalone `settings.gradle.kts` so the template can be copied to a new repo without bringing the rest of hebe.

2. `oras-publish` task signs (Ed25519) + builds the OCI artifact + pushes to the configured registry. Inputs from environment: `KOKLYP_PUBLISHER_KEY` (private key path), `KOKLYP_REGISTRY`, `KOKLYP_PLUGIN_NAME`.

3. README explains: how to add tools, the manifest, capabilities/permissions, the publish flow, the install flow on the hebe side.

4. Author signs: `gradle hebeSign` produces a detached signature; `gradle hebePublish` runs sign + push.

### Tests / verification

- Manual: copy `plugin-template/` to a fresh dir, edit `MyPlugin`, run `gradle hebePublish` against a local registry, then `hebe plugin install` round-trips.

### Acceptance criteria

- ✅ Template builds standalone.
- ✅ Sign + push tasks documented.
- ✅ README walks through the full author flow.

### References

- `v1-specs.md` §2.13 (plugin SDK as internal Gradle template)
