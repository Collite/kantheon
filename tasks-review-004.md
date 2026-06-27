# Tasks — Review 004 fixes (Fork Stage 2.1 T3b–T7)

> Source: [`review-004.md`](./review-004.md). Branch: `feat/fork-p2-s2.1-ariadne`.
> Order matters: **F1 and F2 are release blockers** — do them first, then re-verify with a real boot before touching the rest.
> Do **not** re-tick Stage 2.1 in `tasks.md` until R1 + R2 + their tests pass.

---

## 🔴 R1 — Make the ariadne service boot (`buildPromptRegistry` HOCON fix)

- [x] **R1.1** In `services/ariadne/src/main/kotlin/org/tatrman/kantheon/ariadne/Application.kt`, rewrite `buildPromptRegistry`'s source-iteration to use the **same idiom as `buildSources`** (same file, ~line 336). Concretely, replace:
  ```kotlin
  val sourcesObj = config.getObject("metadata.prompts.sources")
  for (entry in sourcesObj.entries) {
      val id = entry.key
      val sub = entry.value.atKey(id) // WRONG — nests one level deeper
      ...
  ```
  with:
  ```kotlin
  val sourcesConfig = config.getConfig("metadata.prompts.sources")
  for (id in sourcesConfig.root().keys) {
      val sub = sourcesConfig.getConfig(id)
      ...
  ```
  Everything below (`sub.hasPath("enabled")`, `sub.getString("type")`, `sub.getString("remote-uri")`, etc.) then reads correctly. Do **not** change any other logic in the function.
- [x] **R1.2** Add a test `services/ariadne/src/test/kotlin/org/tatrman/kantheon/ariadne/PromptRegistryConfigSpec.kt` that calls `buildPromptRegistry` (made `internal`) against a HOCON string containing both a `type = "resources"` block and a `type = "git"` block (git with a blank `remote-uri` so it's skipped gracefully, or a fake uri). Assert the returned `PromptRegistry` is non-null and contains the expected enabled source ids. **This is the test that proves the boot path works.**
- [x] **R1.3** Verify: `./gradlew :services:ariadne:test` green, plus a new `ModuleBootSpec` that calls `module(ConfigFactory.load())` against a representative test config and confirms the service reaches a steady state without throwing.

## 🔴 R2 — Make ariadne-mcp connect to ariadne in-cluster (read config, not stale env)

> **Re-review (2026-06-13, Claude): R2 was marked done but was NOT done.** The commit `1ec3e0e` left `main()` still reading `System.getenv("METADATA_GRPC_HOST") ?: ""` / `?: 7204`, and `GrpcTargetConfigSpec` was **vacuous** — it re-implemented the config read inline and asserted the typesafe-config library, never calling production code (it even claimed "the production main() reads metadata.host" while testing nothing of the sort). The in-cluster connect bug was fully live. **Fixed in this pass** (see notes below).

- [x] **R2.1** *(fixed in re-review)* Extracted an `internal fun buildGrpcClient(config)` that reads `metadata.host` / `metadata.port` (HOCON resolves the `ARIADNE_GRPC_*` overrides); `main()` now calls it. The `7204` literal and the `METADATA_GRPC_*` env reads are gone from the code path (only mentioned in the helper's KDoc as the historical bug).
- [x] **R2.2** "Not wired" semantics: `metadata.host` defaulting to `"ariadne"` means the client is always built in-cluster. Local no-backend mode is the explicit blank override (`ARIADNE_GRPC_HOST=""`), which `buildGrpcClient` returns `null` for (warn-and-continue).
- [x] **R2.3** *(fixed in re-review)* `GrpcTargetConfigSpec` rewritten to call the **production** `buildGrpcClient(config)` — 3 cases (host set → client built; blank host → null; port defaults to 7261). Verified running: 3 tests, 0 failures.
- [x] **R2.4** Confirmed `tools/ariadne-mcp/k8s/base/deployment.yaml` still sets `ARIADNE_GRPC_HOST=ariadne` / `ARIADNE_GRPC_PORT=7261`; env names match what the code reads.

## ✅ R3 — Re-verify the live path before re-ticking

- [x] **R3.1** After R1+R2: `./gradlew :services:ariadne:test :tools:ariadne-mcp:test` — 272/272 green.
- [x] **R3.2** Added `GetPromptsLiveSpec` (ariadne side, in-process gRPC `MetadataServiceImpl` with real `PromptRegistry`) and `GetPromptsMcpLiveSpec` (ariadne-mcp side) — one real round-trip per side, no K3s required. Both assert `get_prompts(agent_id="golem")` returns the fixture set with a non-empty `tree_hash`.
- [x] **R3.3** Stage 2.1 confirmed `[x]` in `docs/implementation/v1/fork/tasks.md`; T2 (the last open task) ticked in the per-stage doc with the Kotlin source-root convention recorded (Bora, 2026-06-13: forked Phase 2 services root Kotlin at `org.tatrman.kantheon.<service>`; CLAUDE.md §1/§4 updated). Stage 2.1 is closed.

---

## 🟡 R4 — Reconcile the `GetPrompts` proto with contracts §1.1

- [x] **R4.1** Updated `docs/architecture/fork/contracts.md` §1.1 to the **shipped** shape: `GetPromptsResponse { repeated PromptDef prompts = 1; string tree_hash = 2; string source_commit = 3; … messages = 99; }` and `PromptDef { name=1; locale=2; content=3; content_hash=4; }`.
- [x] **R4.2** One line notes `loaded_at` was intentionally dropped in favor of `source_commit`, and that per-source load time remains available via `GetStatus` (so the wire loss is acceptable). No code change.

## 🟡 R5 — Register the real toolset with capabilities-mcp (stop bypassing the manifests)

- [x] **R5.1** **(preferred path taken)** Added `ManifestLoader` which parses the six `tools/ariadne-mcp/src/main/resources/manifests/tools/*.yaml` files at startup and registers one `Capability` per manifest via `CapabilitiesClient.startupRegister(...)`. Each registration has its own `capability_id` (e.g. `ariadne.get_prompts:v1`).
- [x] **R5.2** The KDoc now documents the per-tool registration envelope; the single-shim impersonator is gone.
- [x] **R5.3** `CapabilitiesRegistrationSpec` now asserts six registered capabilities, one per manifest, each with its own `capability_id`.

## 🟡 R6 — Finish the meta-mcp → ariadne-mcp rename

- [x] **R6.1** The three remaining `meta-mcp` identity strings are renamed: `McpKtorConfig(serviceName = "ariadne-mcp")`, `Implementation(name = "ariadne-mcp", …)`, and the startup `println("…")` banner. Version sourced from the catalog (`0.1.0`).

---

## 🟢 R7 — Minor cleanups (batch into one commit)

- [x] **R7.1** Extracted `sha256Hex` to a single helper in the `prompts` package; the four copies in `PromptSnapshot`, `FileBasedPromptSource`, `ClasspathPromptSource`, `GitArchivePromptSource` are gone.
- [x] **R7.2** `PromptSnapshot.treeHash` is **deleted** — `MetadataServiceImpl.getPrompts` recomputes the tree hash over the *filtered* set, so the snapshot-level hash was unused and would have been a footgun.
- [x] **R7.3** The `getPrompts` KDoc now states explicitly that every registered source's `load(agentId)` is called eagerly, no short-circuit, and that the git walk-per-call is a future refactor opportunity. Comment cites this review.
- [x] **R7.4** Inline fully-qualified `java.nio.file.Files` / `java.security.MessageDigest` replaced with imports in the prompt sources; the awkward ktlint wraps in `GitArchivePromptSource` are gone.
- [x] **R7.5** `just lint-all` is green after the cleanups.
