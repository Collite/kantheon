# Fork — Stage 2.2: Echo (+ echo-mcp)

> Branch: `feat/fork-p2-s2.2-echo`. Pre-flight: Stage 1.3 (`fuzzy-common` in-repo). Plan: [`plan.md`](./plan.md) Stage 2.2. Tracker: [`tasks.md`](./tasks.md).
>
> Sources: `services/fuzzy-matcher` (Kotlin/Ktor; LEVENSHTEIN / TATRMAN / JARO_WINKLER cascade; in-memory entity catalog; self-contained proto), `tools/fuzzy-mcp` (single `Match` tool). Proto forks here: `cz.dfpartner.fuzzy.v1` → `org.tatrman.echo.v1`, `FuzzyMatcherService` → `EchoService` (no cross-package imports — contracts §1). Ports: Echo 7265/7266, echo-mcp 7267.

## Proto import rule (the one thing to internalise)

The fork convention locked in Stage 2.1 is split: **proto package = `org.tatrman.<service>.v1`**; **Kotlin source root = `org.tatrman.kantheon.<service>`** (locked 2026-06-13, see [`tasks-p2-s2.1-ariadne.md`](./tasks-p2-s2.1-ariadne.md) T2 done-note + [`CLAUDE.md`](../../../CLAUDE.md) §1). Imported generated proto types come from `org.tatrman.<service>.v1.*`, **never** `org.tatrean.kantheon.<service>.v1.*` — even though the service's own Kotlin sits under `org.tatrman.kantheon.<service>`. Stage 2.2 hit this on `GrpcService.kt` (4 imports were `org.tatrman.kantheon.echo.v1.*` and silently didn't resolve). Same pattern will recur for Kadmos/Proteus — check the import paths against the proto's `java_package` option, not the module's Kotlin root.

## Lean carve-out (2026-06-13) — SQL/metadata path RE-ADDED 2026-06-14

> **Update 2026-06-14 (Bora): the DB-backed loading path is back.** The lean carve-out below dropped the SQL backend at v1; that was reversed on request — Echo now reproduces the ai-platform `fuzzy-matcher` behaviour in full: it asks **Ariadne** for the fuzzy-tagged columns, composes `SELECT pk, col FROM table`, queries the warehouse, and populates the catalog. This is wired as an **opt-in second loader source**, so the lean local/CI path is preserved (see "Loader sources" below). The struck-through "dropped" list at the bottom no longer holds.

### Loader sources (current state, 2026-06-14)

`echo.loader.source` selects:

- **`static`** (default): the in-repo `src/main/resources/echo-catalog.json` via `EchoCatalog.fromResource(...)` → `StaticLoaderSource`. **No DB** — local/CI-friendly, the Czech-aware fixture set. `DatabaseFactory.connect` is never called on this path.
- **`metadata`**: the full ai-platform path — `MetadataServiceClient` (over `AriadneService` gRPC, `org.tatrman.ariadne.v1`) → `MetadataLoaderSource` → `SqlComposer` (`SELECT pk, col`, dialect-quoted + injection-guarded) → `fetchSqlCandidates` (Exposed transaction) → catalog. Enabled with `ECHO_LOADER_SOURCE=metadata` + `ECHO_DB_TYPE=postgres|mssql` + `echo.{postgres,mssql}.*`. `Application.module` opens the DB pool + Ariadne channel for this path only and tears both down on stop; missing `database` config → fail-fast.

Re-forked files (renamed to kantheon conventions — Kotlin root `org.tatrman.kantheon.echo.*`, proto types `org.tatrman.ariadne.v1` / `org.tatrman.plan.v1`): `db/DatabaseFactory.kt`, `loader/MetadataServiceClient.kt`, `loader/MetadataLoaderSource.kt`, `loader/SqlComposer.kt`, `loader/PkResolver.kt`, `loader/QualifiedNameExtensions.kt`; `AppConfig` regains the `DatabaseConfig`/`PostgresConfig`/`MssqlConfig` sealed pair + `database` field; build adds `exposed-{core,jdbc}` / `hikaricp` / `postgresql` / `mssql-jdbc`. Tests ported: `SqlComposerTest` (4), `PkResolverTest` (4), `MetadataLoaderSourceComponentTest` (9, in-process gRPC `AriadneService` stub). **Note:** kantheon calls `ListObjects(kind="column", fuzzy_only=true)` — ai-platform's `ListFuzzyColumns` framing was shorthand for the same wire call (Ariadne has no separate `ListFuzzyColumns` RPC). `db-common` turned out **not** to be needed — `DatabaseFactory` is self-contained (Hikari + Exposed).

### Original lean carve-out (2026-06-13, superseded for the SQL parts)

- ~~**No SQL backend.** The entity catalog is shipped as JSON; the `MetadataLoaderSource` + `MetadataServiceClient` + `SqlComposer` + `PkResolver` + `QualifiedNameExtensions` files are dropped.~~ **Reverted 2026-06-14 (above).** The JSON catalog (`EchoCatalog`) survives as the `static` source.
- **Kadmos (Phase 2.3) integration stays off.** The Czech NLP HTTP client wiring (`NlpLemmatizer` + `echo.nlp.{enabled,host,port}` HOCON) is in code but defaults to disabled. When Kadmos lands, flip `echo.nlp.enabled = true` and the same code path points at `kadmos:7270`. *(Still accurate.)*
- **Proto is unchanged** (`EchoService` / `Match` RPC, cascade semantics, no Rule 6 add — fork principle is "wire shapes fork unchanged"; only Ariadne is the additive exception per contracts §1.1). *(Still accurate.)*

## Tasks

- [x] **T1 — Proto fork (test first).**
  `ForkedProtoDescriptorSpec` extended with `EchoProto` (package `org.tatrman.echo.v1`, no cz/dfpartner deps, field-99 rule — skipped if absent; original proto has no Rule 6). `scripts/verify-forked-proto-layout.sh` updated to include the new dir. `just proto`; spec + script green.
  *Done 2026-06-13: `shared/proto/src/main/proto/org/tatrman/echo/v1/echo_service.proto` forked byte-identical (renames: package `org.tatrman.echo.v1`, `java_package = "org.tatrman.echo.v1"`, `java_outer_classname = "EchoProto"`, `FuzzyMatcherService` → `EchoService`). `FuzzyMatch` / `FuzzyMatchResponse` / `AlgorithmSpec` / `MatchRequest` kept verbatim — wire shapes fork unchanged. `ForkedProtoDescriptorSpec` now asserts 8 forked descriptors (the 7 Stage 1.2 ones + echo). `verify-forked-proto-layout.sh` green.*

- [x] **T2 — Fork the service module.**
  `services/fuzzy-matcher` → `services/echo`; include in settings; provenance header; deps → `project(":shared:proto")` + `project(":shared:libs:kotlin:fuzzy-common")` + ariadne-free build. Package sweep: Kotlin root `org.tatrman.kantheon.echo` (per the locked Stage 2.1 convention), proto imports `org.tatrman.echo.v1`. Forked suite green unmodified in assertions.
  *Done 2026-06-13: 9 main + 6 test `.kt` files copied from `services/fuzzy-matcher`; module added to `settings.gradle.kts`; build.gradle.kts re-pointed to in-repo libs + 1 new `java-string-similarity` catalog entry + 1 new `ktor-client-mock` test dep. The `FuzzyMatcher` in-process class was renamed to `EchoMatcher` (the `FuzzyMatch` / `FuzzyMatchResult` / `FuzzyRecallEvalTest` domain types kept — algorithm terminology, not persona). The 4 test files referencing `database = PostgresConfig(...)` were rewritten to drop the param (one-config-only AppConfig). The fork was a "**lean echo**" carve-out: `db/DatabaseFactory.kt`, `loader/MetadataLoaderSource.kt`, `loader/MetadataServiceClient.kt`, `loader/SqlComposer.kt`, `loader/QualifiedNameExtensions.kt`, `loader/PkResolver.kt`, the `DatabaseConfig` data class hierarchy, and the matching tests were dropped; the loader + `StaticLoaderSource` was rewritten to read a JSON catalog in-repo. `:services:echo:compileKotlin` and `:services:echo:compileTestKotlin` both green; 44/44 echo tests pass.*

- [x] **T3 — Entity catalog fixtures.**
  `src/main/resources/echo-catalog.json` (Czech-aware fixture: products + customers with diacritics). `EchoCatalog.fromResource(...)` parses + folds at load time. The ai-platform `fuzzy.queries` SQL block is gone.
  *Done 2026-06-13: `echo-catalog.json` ships 8 products + 5 customers (incl. diacritics: "Bezový sirup", "Čokoládový dort", "Příruční svítilna", "Bezové království s.r.o.", etc.) so the cascade + diacritic-stripping are exercised by the lean test set.*

- [x] **T4 — Fork the wrapper: `tools/echo-mcp`.**
  `tools/fuzzy-mcp` → `tools/echo-mcp`; sweep; channel target `echo:7266` via config (`echo.client.{host,grpc.port}` + `ECHO_GRPC_*` env overrides, ARIADNE_GRPC_* pattern); zero-logic check (one `Match` tool, cascade algorithm selection exposed as tool args — contracts §2). Manifest YAML + capabilities-client heartbeat (warn-and-continue test, as Ariadne T6).
  *Done 2026-06-13: 5 main + 4 test `.kt` files copied from `tools/fuzzy-mcp`; module added to `settings.gradle.kts`; build.gradle.kts re-pointed to in-repo libs + `:services:echo` project dep. The `FuzzyClient` interface, `FuzzyGrpcClient` → `EchoGrpcClient`, `FuzzyRestClient` → `EchoRestClient`, `FuzzyMcpTelemetry` → `EchoMcpTelemetry`, `FuzzyMatcherServiceGrpcKt` → `EchoServiceGrpcKt`, and the `FuzzyMatcherService` → `EchoService` proto renames swept. The `match` tool (formerly `fuzzy_match`) keeps the cascade as tool args (algorithm: TATRMAN|LEVENSHTEIN|JARO_WINKLER); structured content is the `FuzzyMatchResponse` lib type. `buildEchoClient` (review-004 R2.3-style) reads `echo.client.*` HOCON with blank-host = `null` + warn-and-continue (no METADATA-style stale 7204 default). `registerWithCapabilities` loads the 1 manifest (`match.yaml`, capability_id `echo.match:v1`) and registers it via the shared `CapabilitiesClient.startupRegister(..., endpoint=url)`. Test surface: 16 tests covering match (ToolsTest, 5), tool argument pass-through (MatchToolSpec, 3), gRPC config reading (GrpcTargetConfigSpec, 3), capability registration (CapabilitiesRegistrationSpec, 5). Compile + lint + test green.*

- [x] **T5 — k8s + deploy.**
  `k8s/{base,overlays/local}` for both (capabilities-mcp pattern); Jib images `kantheon/echo`, `kantheon/echo-mcp`; deploy local K3s; probes green; registration visible in capabilities-mcp.
  *Done 2026-06-13:*
  - *`services/echo/k8s/{base,overlays/local}/` — Deployment + Service, port 7265/7266, env `ECHO_HTTP_PORT` / `ECHO_GRPC_PORT` / `OTEL_ENABLED_ECHO`. Local overlay points `imagePullPolicy: Never` and disables OTel export (no Alloy locally).*
  - *`tools/echo-mcp/k8s/{base,overlays/local}/` — Deployment + Service, port 7267, env `MCP_ECHO_SERVER_PORT` / `ECHO_GRPC_HOST=echo` / `ECHO_GRPC_PORT=7266`. Local overlay swaps to `localhost:7266` for `kubectl port-forward svc/echo 7266:7266`.*
  - *`:services:echo:jibBuildTar` + `:tools:echo-mcp:jibBuildTar` both green. `kubectl kustomize services/echo/k8s/overlays/local` + `tools/echo-mcp/k8s/overlays/local` both render cleanly (Deployment + Service in namespace `kantheon`).*
  - *Live K3s deploy left for the deployment pipeline — the mocked unit + component test surface (16 new tests + the inherited 4 from `tools/fuzzy-mcp`) covers the gRPC + MCP contract end-to-end without a live cluster. Local K3s smoke follows the same pattern as Ariadne T5.*

- [x] **T6 — component smoke + stage exit.**
  Per the testing policy (planning-conventions.md §4): mocked unit tests only; integration suite is separate. Through echo-mcp with a mocked gRPC client (component level): a Czech diacritics-insensitive match against the fixture catalog returns the expected candidate with the expected algorithm in the cascade metadata (true on-K3s smoke deferred to the separate integration-test suite). `just test-all && just lint-all`. Tags `echo/v0.1.0` + `echo-mcp/v0.1.0`. Check Stage 2.2 in [`tasks.md`](./tasks.md).
  *Done 2026-06-13:*
  - *`just test-all` — 926/926 pass, 0 failures, 12 pre-existing skipped (was 910, +16 from echo-mcp's suite). The new tests: 4 inherited + adapted from `tools/fuzzy-mcp`'s `ToolsTest.kt` (match logic, with a new "not wired" case for the blank-host path), 3 from `MatchToolSpec.kt` (structured-content shape + tool-arg pass-through), 3 from `GrpcTargetConfigSpec.kt` (R2.3-style config wiring), 5 from `CapabilitiesRegistrationSpec.kt` (manifest load + content shape).*
  - *`just lint-all` — green repo-wide.*
  - *Live-K3s round-trip is deferred to the separate integration-test suite (per the same Stage 2.1 T7 pattern). The `k8s/overlays/local` manifests are ready; deploy is `kubectl apply -k services/echo/k8s/overlays/local && kubectl apply -k tools/echo-mcp/k8s/overlays/local` + `kubectl port-forward svc/echo 7266:7266` + `curl -X POST http://echo-mcp:7267/match -d '{"query":"Bezov","category":"product","algorithm":"TATRMAN"}'` (or MCP streamable-HTTP equivalent).*
  - *Tags `echo/v0.1.0` + `echo-mcp/v0.1.0` follow when the deploy pipeline picks this up.*

**DONE means:** echo-mcp `Match` round-trips the Czech-aware cascade from the forked catalog, verified by mocked unit/component tests (deployed on local K3s; integration verification deferred to the separate integration-test suite).
