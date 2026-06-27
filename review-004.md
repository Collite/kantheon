# Review 004 — Fork Phase 2, Stage 2.1 T3b–T7 (Ariadne prompt-serving, ariadne-mcp, k8s, manifests)

> Reviewer: Claude (per [`reviews.md`](./reviews.md)). Date: 2026-06-13. Branch: `feat/fork-p2-s2.1-ariadne` (commit `a2217ab`, "T3b–T7").
> Scope: everything that landed after review-003 — the `GetPrompts` RPC + prompt sources (T3b), the `tools/ariadne-mcp` fork (T4), k8s + Jib (T5), capability manifests + heartbeat (T6), e2e/tracker (T7). Plan: [`docs/implementation/v1/fork/tasks-p2-s2.1-ariadne.md`](./docs/implementation/v1/fork/tasks-p2-s2.1-ariadne.md); contracts: [`docs/architecture/fork/contracts.md`](./docs/architecture/fork/contracts.md) §1.1, §2, §7.

## Verdict

**The design is right and most of the code is clean — but the stage is NOT ready.** Two independent **boot/wiring defects** sit on the exact path the dev deferred ("Live-K3s round-trip … left for the deployment pipeline"), and both are invisible to the unit suite because **no test boots the service module or exercises the in-cluster env wiring**. As written:

1. **The ariadne service crashes on startup** with the default `application.conf` (the prompt-registry config reader misuses the HOCON API). 🔴
2. **ariadne-mcp never connects to ariadne in-cluster** (it reads `METADATA_GRPC_*` env vars while k8s sets `ARIADNE_GRPC_*`, and ignores its own `metadata{}` config block). Every tool — including `get_prompts` — returns "not wired". 🔴

So the Stage DONE criteria ("ariadne-mcp answers from the fixture model **and serves the fixture prompts** on local K3s") cannot currently be met. The "847 tests green / lint green / jibBuildTar / kustomize renders" claims are all true but none of them **boot the JVM with the real config**, which is why both defects slipped through.

The good news: both are small, localized fixes (one HOCON idiom, one config-read), the prompt-serving *logic* itself is sound, and the gRPC client + MCP tool are correctly zero-logic.

Severity: 🔴 boot/deploy-breaking · 🟡 contract/architecture divergence to record · 🟢 minor.

---

## 🔴 F1 — The ariadne service does not start: `buildPromptRegistry` misuses the HOCON API

`services/ariadne/src/main/kotlin/.../Application.kt:498–500`:

```kotlin
for (entry in sourcesObj.entries) {
    val id = entry.key
    val sub = entry.value.atKey(id) // returns Config for the sub-block   <-- WRONG
    if (sub.hasPath("enabled") && !sub.getBoolean("enabled")) continue
    val type = sub.getString("type")     // throws ConfigException.Missing
```

`ConfigValue.atKey(id)` does **not** unwrap the block — it *nests* the value one level deeper, producing `{ <id>: { type, … } }`. So `sub.hasPath("type")` is `false` and `sub.getString("type")` throws `ConfigException.Missing: No configuration setting found for key 'type'`.

Verified empirically (typesafe-config 1.4.5):

```
id=github-prompts
  hasPath(type) = false
  getString(type) THREW: Missing atKey(github-prompts): No configuration setting found for key 'type'
```

The default `application.conf` ships a `metadata.prompts.sources` block (`github-prompts` + `classpath-prompts`), and `module()` calls `buildPromptRegistry(config)` unconditionally (line 177). **Therefore the real service throws on boot.** It's green in CI only because **nothing boots the module** — `GetPromptsSpec` constructs `PromptRegistry(listOf(ClasspathPromptSource(...)))` directly, never going through `buildPromptRegistry`.

Note the *model* side does it correctly two functions up (`buildSources`, line 336–338): `config.getConfig("metadata.sources")` then `sourcesConfig.getConfig(key)`. The prompt side deviated from the working idiom.

**Fix:** mirror `buildSources` exactly — iterate `config.getConfig("metadata.prompts.sources")` and read each block with `.getConfig(key)` (or, if keeping the entries loop, `entry.value.atKey(id).getConfig(id)`). Then add a test that actually calls `buildPromptRegistry` against the real `application.conf` (or a representative HOCON string with `type=git`/`type=resources` blocks) and asserts a non-null registry with the expected source ids — this is the test that would have caught it.

---

## 🔴 F2 — ariadne-mcp never connects in-cluster: env-var / config mismatch

`tools/ariadne-mcp/src/main/kotlin/.../mcp/Application.kt:46–55`:

```kotlin
val grpcHost = System.getenv("METADATA_GRPC_HOST") ?: ""
val grpcPort = System.getenv("METADATA_GRPC_PORT")?.toIntOrNull() ?: 7204
val grpcClient = if (grpcHost.isNotBlank()) GrpcMetadataGrpcClient(grpcHost, grpcPort) else null
```

Three problems in one:
- It reads **`METADATA_GRPC_HOST` / `METADATA_GRPC_PORT`**, but the committed k8s deployment (`tools/ariadne-mcp/k8s/base/deployment.yaml:30–33`) sets **`ARIADNE_GRPC_HOST=ariadne` / `ARIADNE_GRPC_PORT=7261`**. The names don't match → `grpcHost` is `""` in the pod → `grpcClient = null` → **every metadata tool, including `get_prompts`, returns the "not wired" error**.
- The default port `7204` is the **stale ai-platform port** (ariadne gRPC is `7261`, contracts §7).
- The wrapper's own `application.conf` declares a correct `metadata { host="ariadne"; port="7261"; host=${?ARIADNE_GRPC_HOST}; port=${?ARIADNE_GRPC_PORT} }` block — and the code **never reads it**. The config block is dead.

This directly contradicts the T5 done-note ("env-var prefix swapped from `METADATA_*` to `ARIADNE_*`"): the swap was applied to `application.conf` and the k8s manifest but **not to the code that actually reads the env**, so the swap is a no-op where it counts. T4 explicitly required "gRPC channel target → `ariadne:7261` (config, not hardcoded — `application.conf` + env override, ktor-configurator pattern)" — the code bypasses config entirely.

**Fix:** read the target from `config` (it's already loaded at line 38 and already resolves the `ARIADNE_GRPC_*` overrides):

```kotlin
val grpcHost = config.getString("metadata.host")   // "ariadne" / ${?ARIADNE_GRPC_HOST}
val grpcPort = config.getString("metadata.port").toInt()  // 7261 / ${?ARIADNE_GRPC_PORT}
```

(With the `"ariadne"` default the client is always built in-cluster, which is correct; if you still want an explicit "disabled" mode for local-without-cluster, gate on a blank override rather than a missing `METADATA_*` var.) Drop the `7204` literal. Add a tiny test asserting the client target is read from config so the wiring can't silently rot again.

---

## 🟡 F3 — `GetPrompts` proto deviates from contracts §1.1, and the contract wasn't updated

The implemented wire (`shared/proto/.../ariadne/v1/ariadne.proto:1046–1059`) differs from the shape frozen in `contracts.md` §1.1:

| contracts §1.1 | implemented | delta |
|---|---|---|
| `GetPromptsResponse.content_hash = 2` | `tree_hash = 2` | renamed |
| `GetPromptsResponse.loaded_at = 3` (ISO-8601) | `source_commit = 3` (git SHA) | **semantics changed; `loaded_at` dropped** |
| `PromptDef{name,locale,content}` | `+ content_hash = 4` | field added |

The changes are *improvements* (per-file `content_hash` is genuinely useful; `source_commit` ties the tree to a commit), and `reviews.md` requires deviations from the plan/contract to be flagged. They were not reconciled into the doc. **Fix:** update `contracts.md` §1.1 to the shipped shape and add one line noting `loaded_at` was dropped (the per-source load time is still available via `GetStatus`, so the wire loss is acceptable — say so).

---

## 🟡 F4 — Capability registration bypasses the 6 authored manifests (the "flagship shim")

T6 authored six `ToolCapability` YAMLs under `tools/ariadne-mcp/src/main/resources/manifests/tools/` (one per tool, each with its own `capability_id`, e.g. `ariadne.get_prompts:v1`). But `registerWithCapabilities` → `ariadneMcpCapability()` (Application.kt:292–315) **ignores those files** and hand-builds a *single* `Capability` with a synthetic `capability_id="ariadne.get_model:v1"`, folding the other five tools into `search_tags` + the description. So:
- The six manifests are **dead resources** — authored but never loaded.
- The registry will surface **one** capability (`get_model`), not six; `get_prompts` is discoverable only as a tag on `get_model`, not by its own `ariadne.get_prompts:v1` id. T7's "registry shows the meta toolset incl. `get_prompts`" is only weakly satisfied.

`CapabilitiesClient.startupRegister` takes a single `Capability`, but it's trivially callable N times (each returns its own handle) — registering all six (loaded from the manifest YAMLs) is the intended T6 behavior and needs no new client API. The carve-out is at least **honestly documented** in the code comment (good, not silent), and a future `capability_set` proto is referenced. **Fix (preferred):** load the six manifest YAMLs and register each in a loop. **Or**, if deferring to the multi-capability envelope, delete/relabel the six manifests so they don't read as wired, and have the single shim keep a neutral id (not one that impersonates `get_model`).

---

## 🟡 F5 — ariadne-mcp still identifies itself as `meta-mcp` on the wire/telemetry

The logger (`"ariadne-mcp"`) and `McpTelemetry("ariadne-mcp", …)` were renamed, but three identity strings were missed in `mcp/Application.kt`:
- `McpKtorConfig(serviceName = "meta-mcp", …)` (line 67)
- `Server(serverInfo = Implementation(name = "meta-mcp", version = "0.1.0"))` (line 93) — the MCP server **announces itself to clients as `meta-mcp`**
- `println("Meta MCP Server running …")` (line 262)

**Fix:** rename all three to `ariadne-mcp` (and bump/version-source the `0.1.0` from the catalog if that's the convention elsewhere).

---

## 🟢 Minor

- **F6 — `sha256Hex` duplicated four times** (`PromptSnapshot`, `FileBasedPromptSource`, `ClasspathPromptSource`, `GitArchivePromptSource`), identical body each time. Extract one `internal fun sha256Hex(...)` in the prompts package. Relatedly, `PromptSnapshot.treeHash` (the lazy property) is now **dead** — `MetadataServiceImpl.getPrompts` recomputes the hash over the *filtered* set and never reads `snap.treeHash`. Either route the service through it (after filtering) or delete the property.
- **F7 — `getPrompts` KDoc overstates laziness.** The doc says "on a `prompts_agent_not_found` warning from the primary, the fallback (classpath) is tried", implying short-circuit, but `reg.load(agentId)` eagerly loads **every** source on every call (a git-tree walk *and* a classpath walk), then picks the first non-empty. Harmless at v1 volume, but the git walk-per-call is wasteful and the comment is misleading. Fix the comment (or make source resolution lazy).
- **F8 — fully-qualified `java.nio.file.Files` / `java.security.MessageDigest` inline** throughout the prompt sources forces ktlint into awkward wraps (e.g. `GitArchivePromptSource.kt:45–47`, `:65–71`). Add imports for readability — consistent with the rest of the module.

---

## What's correct (no action)

- **Prompt-serving logic is sound:** first-non-empty-source-wins across the registry, Rule-6 warnings aggregated from every attempted source, locale filter applied *after* source selection with `tree_hash` recomputed over the filtered set (so the agent's cache key matches what it received), blank `agent_id` → `ERROR`, unconfigured registry → `WARNING` (not a crash). Nullability of `promptRegistry` cleanly models the legacy "agent fetches its own prompts" path.
- **The MCP `get_prompts` tool and `GrpcMetadataGrpcClient.getPrompts` are correctly zero-logic** (contracts §2): one gRPC call, JSON↔proto only, ariadne Rule-6 messages surfaced into the MCP text payload. Same discipline as the rest of the wrapper.
- **Proto KDoc is excellent** — the filename/locale convention, the "raw YAML, agent owns `{{ }}`" contract, and the one-poller-two-trees note are all documented at the wire.
- **Fixtures mirror the real `ai-models` tree** (`prompts/{golem,resolver,themis}/` with `system.yaml` / `system.cs.yaml` / `tool.search.yaml`).
- **Service-side ports + env are correct** (`application.conf`: HTTP 7260 / gRPC 7261, `ARIADNE_*` overrides; k8s base matches). The defect is only on the **mcp** side (F2) and the **prompt-config reader** (F1).
- T1 follow-up genuinely cleared (236/236 ariadne after the review-003 `ucetnictvi` scoping).

---

## Tracker accuracy

`tasks.md` marks Stage 2.1 `[x]`. Given F1 (service won't boot) and F2 (wrapper won't connect), **the Stage is not done** — un-tick it until F1+F2 are fixed and a boot-level check (module-boot test or an actual local-K3s round-trip) passes. T7 deferred precisely the test that would have caught both; that deferral is the gap.

---

*See [`tasks-review-004.md`](./tasks-review-004.md) for the fix checklist.*

---

## Re-review (2026-06-13, after commit `1ec3e0e`)

Re-reviewed the dev's review-004 fix commit against the actual code (not the checklist). **R1, R3, R4, R5, R6, R7 are genuinely and correctly done** — `buildPromptRegistry` now mirrors `buildSources`; `ModuleBootSpec` + `PromptRegistryConfigSpec` cover the boot path; `ManifestLoader` parses the six YAMLs and registers one capability each (no more `get_model` impersonator); identity strings renamed; `Sha256.kt` extracted, dead `treeHash` removed, KDoc/imports cleaned. The two "live" specs are legitimate (real in-process gRPC server on the ariadne side; production `getPromptsCallback` against a stubbed backend on the mcp side).

**One blocker was not actually fixed: R2.** The commit left `main()` still reading `System.getenv("METADATA_GRPC_HOST") ?: ""` with the stale `7204` default, so the in-cluster connect bug (F2) was fully live. Worse, `GrpcTargetConfigSpec` was **vacuous** — it re-implemented the config read inline (`config.getString("metadata.host")`) and asserted that, never calling production code, while its own comment claimed "the production main() reads metadata.host". That hollow test is exactly why a release-blocker shipped marked-done.

**Fixed in this re-review pass (uncommitted):**
- Extracted `internal fun buildGrpcClient(config): MetadataGrpcClient?` (reads `metadata.host`/`metadata.port`, blank host → `null` warn-and-continue, port defaults to 7261); `main()` now calls it. No `METADATA_GRPC_*` / `7204` left in the code path.
- Rewrote `GrpcTargetConfigSpec` to exercise the **production** `buildGrpcClient` — 3 cases, all green.
- Verified: `:tools:ariadne-mcp:test` BUILD SUCCESSFUL (GrpcTargetConfigSpec 3/3), `:tools:ariadne-mcp:ktlintCheck` green.

**Verdict:** all review-004 findings are now resolved in the working tree. R3.3 (re-tick Stage 2.1 in `tasks.md`) is the only thing left — appropriately still deferred until this fix is committed and the suite re-runs green.
