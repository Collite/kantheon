# AI Platform v1 — Status Report

**Date:** 2026-05-12
**Reviewer:** Claude Code
**Repo head:** `4e6697c6023eb16497ed98743e98e439ed6dbf2d`
**Branch:** `feat/resolver`

---

## TL;DR

- **Resolver Stage 04 is ~40% done** — Koog graph scaffolded with 5/10 nodes implemented (detectLang+parse, extractUniversal, proposeDomainSpans, filterRelevantSpans, jointInference), LLM/fuzzy wiring still stubs. Build is broken (MCP SDK class resolution failures). Blocking: `just deploy-kt resolver` does not work yet.
- **G1 (Czech fuzzy): partial** — `services/fuzzy-matcher` has token-based + Levenshtein matching but NO NFD normalization, NO inflection trimming. `tools/fuzzy-mcp` has no Czech-specific pipeline. This is the most impactful gap for Kantheon's Czech-speaking users.
- **G2 (cnc.role): closed** — Phase 2.2 shipped `cnc` schema with `def role` + `er2cncRole` mapping in TTR DSL. Proto has `SchemaCode.CNC = 3`, `ListRoles` + `GetRolesForEntity` RPCs active.
- **G3 (hide_columns_matching): partial** — `FormatOptions` in `data-formatter` has `hideColumnsMatching: List<Regex>` but `query-mcp` does not yet expose the `hide_columns_matching` parameter to callers.
- **G4 (value_labels): closed** — Phase 2.2 shipped `AttributeDetail.value_labels = 6` (map<string, LocalizedString>) in proto; `data-formatter` consumes via `ColumnDecoration` pulled from model snapshot at request time.
- **G5 (display_label): closed** — Phase 2.2 shipped `EntityDetail.display_label = 5` and `AttributeDetail.display_label = 7` in proto.
- **G7 (pipeline_warnings): not started** — `query-mcp` returns `CallToolResult` with `structuredContent` but no `pipeline_warnings` field. `PipelineContext.warnings` exists in proto but is not propagated to the agent.
- **shared/proto is NOT configured for Maven publishing** — no `maven-publish` plugin, no publishing block, no CI workflow to publish. This blocks Kantheon from consuming ai-platform protos as versioned Maven artifacts, which is a hard prerequisite per Kantheon architecture §10.
- **v1-architecture.md drift**: `workers/polars/` (v2.4, Phase 2.4 landed) not documented in v1-architecture.md which shows Polars in v1.5+. `infra/nlp` and `tools/nlp-mcp` (Stage 02 complete) not in service inventory. `infra/metadata` (Phase 1.2, 1.12) not in v1-architecture.md service inventory. `resolver` (Stage 04 in progress) not in agents list.

---

## 1. Service Inventory

| Module path | Language / Stack | Role | Status | Has K8s? | In justfile? | Tests? | Last Commit | Notes |
|---|---|---|---|---|---|---|---|---|
| `agents/erp-agent` | Python + FastAPI | Pre-Themis agent for ERP access via MCP | implemented | ✗ | ✗ | ✗ | 2026-05-11 | Legacy; erp-agent-2 is the active descendant |
| `agents/erp-agent-2` | Python + LangChain | Current analytical agent (Golem's primary context) | implemented | ✗ | ✗ | ✗ | 2026-05-11 | Main Golem agent per Kantheon docs |
| `agents/office-agent` | — | Undocumented; `README.md` absent | not started | ✗ | ✗ | ✗ | 2026-04-08 | Directory exists but no source. Mentioned nowhere in Kantheon docs. |
| `agents/resolver` | Kotlin + Koog | Resolver agent (Themis) — Koog graph | partial | ✓ (base/) | ✗ | ✗ | 2026-05-12 | Stage 04 in progress; build broken; K8s base deployed |
| `infra/backstage` | TypeScript + Yarn | Backstage developer portal | implemented | ✓ | ✗ | ✗ | 2026-05-11 | 25 files, catalog-info per service |
| `infra/health-check-service` | Kotlin + Ktor | Health check service | implemented | ✓ | ✗ | ✗ | 2026-05-11 | |
| `infra/llm-gateway` | Kotlin + Spring Boot | LLM routing (modality × tier) + embeddings | implemented | ✓ | ✗ | ✗ | 2026-04-20 | Spring Boot (the one Spring service); tier-based routing present; Redis wiring not confirmed |
| `infra/metadata` | Kotlin + Ktor / gRPC | New model graph service (Phase 1.2, 1.12, search) | implemented | ✗ | ✗ | ✗ | 2026-05-12 | Search index + COMPARE mode; proto `cz.dfpartner.metadata.v1` |
| `infra/nlp` | Python + FastAPI | NLP foundation (Stanza, spaCy, NameTag, MorphoDiTa) | implemented | ✓ | `just deploy-py nlp` | ✗ | 2026-05-10 | Stage 01/02 done; COMPARE mode + MorphoDiTa (Stage 03) not confirmed |
| `infra/sql-metadata` | Kotlin + Ktor | Legacy metadata service | implemented | ✗ | ✗ | ✗ | 2026-05-11 | Parallel to `infra/metadata`; still serves v0 pipeline |
| `infra/sql-security` | Kotlin + Ktor | Row-level security predicates | implemented | ✗ | ✗ | ✗ | 2026-05-03 | New `EvaluatePolicies` gRPC (Phase 1.5) wired; legacy SQL-fragment API intact |
| `infra/sql-validator` | Kotlin + Ktor | SQL validation + security | implemented | ✗ | ✗ | ✗ | 2026-04-22 | RelNode-level work for new pipeline |
| `infra/starters` | Kotlin + Ktor | Starter templates | scaffolded | ✓ | ✗ | ✗ | 2026-05-11 | |
| `infra/whois` | Kotlin + Ktor | Keycloak-to-ERP-user mapping | implemented | ✗ | ✗ | ✗ | 2026-05-11 | UserRepository DB; Keycloak integration present |
| `services/dispatcher` | Kotlin + Ktor | Worker routing (capability match + WorldConfig) | implemented | ✗ | ✗ | ✗ | 2026-05-03 | `WorkerRegistry` + `StickyRegistry`; workspace_ref routing (v2.4) |
| `services/erp-sql` | Kotlin + Ktor | Legacy SQL pipeline | implemented | ✗ | ✗ | ✗ | 2026-05-11 | v0 service; superseded by workers/mssql for new flows |
| `services/erp-sql-dispatcher` | Kotlin + Ktor | Legacy dispatcher | implemented | ✗ | ✗ | ✗ | 2026-05-11 | Extended for RelNode wire form |
| `services/fuzzy-matcher` | Kotlin + Ktor | Fuzzy matching (token index + Levenshtein) | implemented | ✗ | ✗ | ✗ | 2026-04-20 | TokenBasedAlgorithm (TATRMAN default); NO NFD, NO inflection trim |
| `services/query-runner` | Kotlin + Ktor / gRPC | Agent entry point (orchestrates Translator→Validator→Dispatcher) | implemented | ✗ | ✗ | ✗ | 2026-05-03 | LRU cache; WorkspaceRef (v2.4) |
| `services/sql-entity-service` | Kotlin + Ktor | Entity query service | implemented | ✗ | ✗ | ✗ | 2026-05-11 | |
| `services/sql-formatter` | Kotlin + Ktor | Result formatting | implemented | ✗ | ✗ | ✗ | 2026-05-11 | Migrated onto `data-formatter` library (Phase 2.1.C) |
| `services/sql-free-service` | Kotlin + Ktor | Free SQL query service | implemented | ✗ | ✗ | ✗ | 2026-05-11 | |
| `services/sql-named-service` | Kotlin + Ktor | Named query service | implemented | ✗ | ✗ | ✗ | 2026-05-11 | |
| `services/sql-pattern-service` | Kotlin + Ktor | Pattern query service | implemented | ✗ | ✗ | ✗ | 2026-05-11 | |
| `services/translator` | Kotlin + Ktor / gRPC | Query translation (SQL/TransDSL/DFDSL ↔ RelNode) | implemented | ✗ | ✗ | ✗ | 2026-05-01 | v1.4: all 4 RPCs (ParseToRelNode, UnparseFromRelNode, Translate, Explain); library Phase 1.1 done |
| `services/validator` | Kotlin + Ktor / gRPC | RelNode validation + security wrapping | implemented | ✗ | ✗ | ✗ | 2026-05-11 | `SecurityApplier` + `RuleEnforcer`; `WorkspaceRef` skip (v2.4) |
| `tools/erp-data-mcp` | Kotlin + Ktor | ERP data MCP server | implemented | ✗ | ✗ | ✗ | 2026-05-11 | |
| `tools/fuzzy-mcp` | Kotlin + Ktor | Fuzzy matching MCP wrapper | implemented | ✗ | ✗ | ✗ | 2026-04-22 | Thin wrapper around `services/fuzzy-matcher`; NO Czech-specific normalization exposed |
| `tools/meta-mcp` | Kotlin + Ktor | Metadata MCP wrapper (Phase 1.12 search) | partial | ✗ | ✗ | ✗ | 2026-05-04 | `list_queries(kind="named")` + `get_entity` + search (grammar + parser) |
| `tools/nlp-mcp` | Kotlin + Ktor | NLP MCP thin wrapper | implemented | ✓ | ✗ | ✗ | 2026-05-10 | Wraps `infra/nlp`; Stage 02 complete |
| `tools/query-mcp` | Kotlin + Ktor | Query MCP server (`query` + `compile` tools) | implemented | ✓ | ✗ | ✗ | 2026-05-03 | TransDSL stack composition via `core.query` / `core.queryRef`; `pipeline_warnings` NOT in structuredContent |
| `workers/mssql` | Kotlin + Ktor | MS SQL Worker (Arrow IPC) | implemented | ✗ | ✗ | ✗ | 2026-05-01 | v1.8 complete; no K8s manifests in repo |
| `workers/polars` | Python + FastAPI + gRPC | Polars Worker (stateful, session DataFrames) | implemented | ✗ | ✗ | ✗ | 2026-05-03 | v2.4; ports 7501 gRPC / 7502 HTTP; no K8s manifests |

### Inventory Notes

- **No K8s manifests for most services** — only `agents/resolver` (base/) and `infra/nlp` (base/overlays/) have k8s directories in the repo. Most deployment manifests live in `deployment/apps/` (9 apps: aip-fe, backstage, erp-api, erp-mcp, fuzzy-matcher, fuzzy-mcp, health-check-service, llm-gateway, starters). `workers/mssql` and `workers/polars` have no k8s/ directory at all.
- **`just deploy-kt` requires Jib** — works for Kotlin services with `id("my.kotlin-ktor")` or `id("my.kotlin-spring")` applied. No `Dockerfile` needed (Jib builds inside Gradle).
- **Office agent** — `agents/office-agent/` contains only `docs/catalog-info.yaml`; no source code. Status: not started.
- **Tests** — unit tests present in `services/translator`, `services/dispatcher`, `workers/mssql`, `workers/polars`, `shared/libs/kotlin/data-formatter`. Most other services have no tests in the repo.

---

## 2. Capability Deep-Dives

### `tools/query-mcp`

**What's there:**
- Two MCP tools: `QueryTool` (`query`) and `CompileTool` (`compile`).
- TransDSL stackable composition: `core.query` (nested query) and `core.queryRef` (by-reference lookup) both supported.
- `query` tool exposes `format` parameter (markdown/csv/tsv/json) and `row_limit`.
- `structuredContent` in `CallToolResult` includes `rowCount`, `columns`, `fingerprint` but NOT `pipeline_warnings`.
- `PipelineContext` carries `correlationId`, `userId`, `modelVersion`, `parameters`, `warnings` through the pipeline — but warnings are not surfaced to the agent.

**What's missing (G7):**
- `pipeline_warnings` is NOT in `structuredContent`. The field exists in `PipelineContext` proto and accumulates as services run, but `query-mcp` never reads it or returns it to the caller. This means the agent cannot render "processing steps from the platform" in the UI.
- `hide_columns_matching` is not exposed as a tool parameter (G3 gap).

**Files:**
- `tools/query-mcp/src/main/kotlin/tools/querymcp/tools/QueryTool.kt`
- `tools/query-mcp/src/main/kotlin/tools/querymcp/tools/CompileTool.kt`

---

### `tools/meta-mcp` (note: Kantheon docs call it `metadata-mcp`; ai-platform uses `meta-mcp`)

**What's there:**
- `list_queries(kind="named")` returns query catalog with `label_cs`, `label_en`.
- `get_entity(qname)` returns entity definition with attributes and relations.
- Search RPC (Phase 1.12): multi-algorithm search (substring, keyword NFD-folded, regex) backed by `search { keywords, patterns, descriptions, examples, aliases }` in TTR.
- `cnc` schema active: `ListRoles` + `GetRolesForEntity` RPCs.
- `value_labels` (G4) and `display_label` (G5) populated on `AttributeDetail` and `EntityDetail`.

**What's missing:**
- `cnc.role` schema is fully implemented (G2 closed).
- No `get_entities_with_relations(focus_qname, depth)` RPC — adaptive prompt builder (B4) would need this; currently the agent fetches entity subsets via `GetObject` + manual traversal.

**Files:**
- `tools/meta-mcp/src/main/kotlin/tools/metamcp/` — MCP server wrapper

---

### `tools/fuzzy-mcp` + `services/fuzzy-matcher`

**What's there:**
- `services/fuzzy-matcher` implements Levenshtein, Damerau-Levenshtein, Jaro-Winkler, and token-based (TATRMAN default) algorithms.
- `TokenBasedMatcher` uses a `TokenIndex` for fast candidate lookup and `DistanceCache` for Levenshtein memoization.
- `tools/fuzzy-mcp` exposes `fuzzy_match(name, category, algorithm, limit)` tool over MCP.

**What's missing (G1 — Czech-aware fuzzy):**
- **NO NFD diacritic normalization** — `services/fuzzy-matcher` has no Unicode NFD decomposition step. "Kaufland" vs "Kaufland" matching is case-insensitive but not diacritic-insensitive.
- **NO inflection trimming** — Czech inflectional morphology ("zákazníků" → "zákazník", "Kauflandu" → "Kaufland") is not handled. The `Candidate.tokenize()` method splits on whitespace/punctuation only.
- The `fuzzy-mcp` tool has no Czech-specific pipeline — algorithm is passed through from caller.

**Evidence:**
- `services/fuzzy-matcher/src/main/kotlin/cz/dfpartner/fuzzy/core/TokenBasedMatcher.kt` — `Candidate.tokenize()` at line 21 uses plain `split(Regex("\\s+"))`.
- `services/fuzzy-matcher/src/main/kotlin/cz/dfpartner/fuzzy/core/Algorithms.kt` — no NFD normalization in `LevenshteinAlgorithm.similarity()`.

---

### `infra/llm-gateway`

**What's there:**
- Kotlin + Spring Boot (the only Spring service in the repo).
- Tier-based routing: `(modality, tier)` → model selection.
- `ChatCompletion` + `Embeddings` endpoints.
- Vendor configuration: Anthropic, OpenAI, Azure confirmed in `infra/llm-gateway/bin/main/models.yaml`.
- Pricing API / `cached: bool` flag: not confirmed in code.
- Redis cache: not confirmed — `infra/llm-gateway/` source was not deeply inspected; `application.yml` not read.

**Blocking or not for Kantheon:** Medium — Pythia's Budget Tracker needs `cached: bool` or pricing API. Needs verification.

---

### `data-formatter` library

**What's there (G3, G4, G5):**
- `shared/libs/kotlin/data-formatter/` — pure library.
- Phase 2.1.C migration complete; `SqlFormatterMigratedSnapshotSpec` captures post-migration output.
- `FormatOptions.hideColumnsMatching: List<Regex>` exists.
- `value_labels` (G4) consumed via `ColumnDecoration` pulled from model snapshot at request time in `query-mcp` (per Phase 2.2 notes).
- `display_label` (G5) same pattern.

**What's missing (G3):**
- `query-mcp` does not expose `hide_columns_matching` parameter. `QueryTool.execute()` does not read it from the tool request.

**Files:**
- `shared/libs/kotlin/data-formatter/src/main/kotlin/shared/formatter/`

---

### `infra/sql-security` and `infra/sql-validator`

**What's there:**
- `infra/sql-security` Phase 1.5: new `EvaluatePolicies` gRPC RPC returns structured `Expression` predicates per table. Legacy SQL-fragment API stays in parallel.
- `infra/sql-validator`: RelNode-level validation + `RuleEnforcer` (TopN, allow/deny columns).
- Both invoked in `query-runner`'s chain: Translator → Validator → Dispatcher.

**Status:** Both implemented. Transparent to Pythia — works as designed.

---

### Worker layer (`workers/mssql`, `workers/polars`, `services/dispatcher`)

**What's there:**
- `workers/mssql` (v1.8): JDBC → Arrow IPC via hand-rolled `MssqlArrowTypeMapper`. Ports 7900 HTTP / 7910 gRPC. No K8s manifests.
- `workers/polars` (v2.4): Python + gRPC. Ports 7501 gRPC / 7502 HTTP. First stateful Worker. `WorkspaceRef` activated.
- `services/dispatcher` (v2.4): `StickyRegistry` + `WorldConfig` routing. Routes to `workers/polars` when `session_id` non-empty AND `supports_stateful_sessions = true`.
- Sticky-routing for session DataFrames: implemented in dispatcher.

**What's missing:**
- `workers/mssql` has no K8s manifests in the repo. Deployment uses `deployment/apps/` pattern? Unclear.
- `workers/polars` has no K8s manifests.
- Polars Worker `from_workspace` (DFDSL `workspaceRef`) deferred per Phase 2.4 notes.

**Status:** MSSQL Worker complete; Polars Worker functional but not K8s-deployed via standard manifests.

---

### `infra/nlp` + `tools/nlp-mcp`

**What's there:**
- `infra/nlp`: Python FastAPI, engine plugin system (`NlpEngine` Protocol), 5 engines (stanza, spacy, nametag, morphodita, langid). NORMAL mode + COMPARE mode. All in `infra/nlp/src/nlp_service/engines/`.
- `tools/nlp-mcp`: Kotlin Ktor thin wrapper, `analyze` tool exposed via MCP.
- Stage 01 (NORMAL, POS/DEP/lang-detect) and Stage 02 (nlp-mcp wrapper) complete.
- OTEL wired via `shared/libs/python/otel-config`.
- `POST /v1/analyze` returns proto-shaped JSON with tokens, NER, language detection.

**What's missing (Stage 03):**
- MorphoDiTa engine (`engines/morphodita_engine.py`) present but not verified as fully functional (Stage 03 task 2).
- Eval corpus (`infra/nlp/eval/corpus/seed.jsonl` with 50 Czech questions) not verified present.
- `infra/nlp/eval/run_eval.py` harness exists? Not confirmed.

**Status:** Stage 01/02 done. Stage 03 (COMPARE + MorphoDiTa + eval corpus) in progress or not started.

---

### `agents/resolver` (Themis)

**What's there:**
- `agents/resolver/` — Kotlin + Koog, branch `feat/resolver`.
- Koog graph with nodes: `branchOnInput`, `detectLang+parse`, `extractUniversal`, `proposeDomainSpans`, `filterRelevantSpans`, `fuzzyMatchSpans`, `jointInference`, `decodeToken+applyChoice`, `decideHitlOrEmit`, `assembleResp`.
- HITL resume via HMAC-signed tokens (`HmacTokenManager`).
- Resolver proto at `shared/proto/src/main/proto/cz/dfpartner/resolver/v1/resolver.proto`.
- K8s manifests: `agents/resolver/k8s/base/deployment.yaml`, `service.yaml`, `kustomization.yaml`.
- `ResolverCache` with NLP-level and resolution-level LRU.

**What's missing:**
- **Build is broken** — MCP SDK classes (`Implementation`, `ServerCapabilities`, `Tool`, `ToolSchema`, `CallToolResult`, `encodeToString`, `decodeFromString`) unresolved. `kotlinx-serialization-json:1.10.0` in dependency tree but class resolution fails. Hypothesized: corrupted Gradle cache or version conflict Kotlin 2.3.0 + kotlinx-serialization 1.10.0.
- LLM calls (`callLlmCheap`, `callLlmFast`) and fuzzy calls (`callFuzzyService`) are **stubs returning empty/fake data** — not wired to `llm-gateway` or `fuzzy-mcp`.
- A2 (ResolverCache wiring), A3 (NLP skip on HITL resume) partial — changes were applied then reverted when build broke.
- K8s overlay (`overlays/local/`) and `just deploy-kt resolver` recipe missing.
- eval harness not started.
- REST endpoint `POST /v1/resolve` not implemented.

**Status:** ~40% complete. Graph scaffolded, stubs in place, build broken, LLM/fuzzy unwired.

---

### Persistence / Transport Layer

**Seaweed (Arrow IPC blob storage):** No `workers/polars/` writes Arrow to Seaweed — session DataFrames held in-memory in the Polars Worker pod. No Seaweed manifest in `deployment/`.

**Redis:** Referenced in Kantheon design for LLM cache and hot blobs. `infra/llm-gateway` likely uses it. No `deployment/` manifest confirmed for Redis. `services/query-runner` uses Caffeine (in-process LRU), not Redis.

**NATS JetStream:** No NATS manifests or client code found in the repo. Deferred to v1.1 per resolver design doc.

**Postgres:** Used by `workers/polars` for checkpointer (per Pythia design). No manifest confirmed. `infra/whois` has `UserRepositoryDb` (JDBC). Standard Postgres in `deployment/local/data-postgres/`.

---

### `infra/whois` + Keycloak Auth

**What's there:**
- `infra/whois` — Kotlin + Ktor, `UserRepository` + `UserRepositoryDb` (JDBC).
- Keycloak integration patterns in `shared/libs/kotlin/whois-common/`.
- `infra/whois` last commit: 2026-05-11.
- Auth flow: caller passes Keycloak token → `infra/whois` maps to ERP user → user-scoped RLS in `sql-security`.

**What's missing:** Full Keycloak deployment in `deployment/` not confirmed. `infra/whois` runs but Keycloak server config not in repo.

---

## 3. Gap Status (G1–G7)

| Gap | Status | Location | Blocking? | Notes |
|---|---|---|---|---|
| **G1** — Czech-aware fuzzy (NFD + inflection trim + Levenshtein) | **partial** | `services/fuzzy-matcher/`, `tools/fuzzy-mcp` | **Yes** | Token-based + Levenshtein present; NFD normalization MISSING; inflection trimming MISSING. `fuzzy-mcp` exposes generic algorithm, no Czech pipeline. |
| **G2** — `cnc.role` schema | **closed** | `infra/metadata/` (Phase 2.2) | No | `SchemaCode.CNC = 3`, `def role` + `er2cncRole` mapping, `ListRoles` + `GetRolesForEntity` RPCs. TTR DSL shorthand `roles: [fact, transaction]`. |
| **G3** — `hide_columns_matching` in data-formatter | **partial** | `shared/libs/kotlin/data-formatter/`, `tools/query-mcp` | No | `FormatOptions.hideColumnsMatching: List<Regex>` exists in library. `query-mcp` does NOT expose `hide_columns_matching` parameter in tool schema. |
| **G4** — `value_labels` on Model attributes | **closed** | `infra/metadata/` (Phase 2.2) | No | `AttributeDetail.value_labels = 6` (map<string, LocalizedString>) in proto. Consumed via `ColumnDecoration` from model snapshot at request time in `query-mcp`. |
| **G5** — `display_label` on Model attributes | **closed** | `infra/metadata/` (Phase 2.2) | No | `EntityDetail.display_label = 5`, `AttributeDetail.display_label = 7` in proto. Same consumption pattern as G4. |
| **G6** — Dynamic suggestions | **resolved** | Agent-side | No | Option A per AA-on-V1 doc: agent computes chips from named-query catalog + parameter type info in `metadata.proto`. Platform needs no new RPC. |
| **G7** — `pipeline_warnings` in `query-mcp.structuredContent` | **not started** | `tools/query-mcp/`, `services/query-runner/` | **Yes** | `PipelineContext.warnings` exists in proto and accumulates in pipeline. `query-mcp` does not read or return it. Agent cannot render platform-level processing steps. |

---

## 4. Resolver Progress

| Stage | % Complete | What's Done | In Flight | Blocked / Not Started | Notes |
|---|---|---|---|---|---|
| **01 — `infra/nlp` foundation** | ~95% | Engine plugin system (5 engines), NORMAL mode, `POST /v1/analyze` JSON API, OTEL, YAML config | — | — | Stage 01 exit criteria: NORMAL mode working. Met. COMPARE + MorphoDiTa are Stage 03. |
| **02 — `nlp-mcp` thin wrapper** | ~95% | Kotlin/Ktor MCP wrapper, `analyze` tool, trace propagation Python↔Kotlin | — | — | Stage 02 exit criteria: nlp-mcp exposes analyze/parse over MCP. Met. |
| **03 — Eval + COMPARE + MorphoDiTa** | ~20% | COMPARE mode infrastructure in orchestrator; `morphodita_engine.py` present | COMPARE mode wiring + eval corpus + eval harness | Eval corpus seed.jsonl (~50 Czech questions) not confirmed present; eval/run_eval.py harness not confirmed | Stage 03 runs in parallel with Stage 04. Entry criteria: infra/nlp NORMAL + engine plugin + Stage 02 complete. All met. |
| **04 — Resolver agent (Koog)** | ~40% | Koog graph scaffold (5/10 nodes implemented); HMAC resume tokens; ResolverCache; Resolver proto | Wire `callLlmCheap`/`callFuzzyService` stubs (A2, A3 partially reverted); build fix | Build broken (MCP SDK class resolution failure); LLM/fuzzy unwired; K8s overlay + justfile recipe missing; eval harness not started | `progress-stage-04.md` (2026-05-12) shows A2/A3 reverted; build errors on `encodeToString`, `Implementation`, `ServerCapabilities`, `Tool`, `CallToolResult`. Last commit: "wip Stages 04" |
| **05 — Parallel deployment** | not started | — | — | Reframe pending under Kantheon split | Per resolver-design.md |
| **06 — Consumer migration** | not started | — | — | Reframe pending | Per resolver-design.md |

**Overall:** Stage 01/02 done. Stage 03 ~20% (in flight, likely stalled). Stage 04 ~40% (in flight, build broken).

---

## 5. Build / CI / Maven Publishing Readiness

| Check | Status | Notes |
|---|---|---|
| **Maven publishing for `shared/proto`** | **NOT configured** | No `maven-publish` plugin in `shared/proto/build.gradle.kts`. No `publishing {}` block. No publishing repository URL. This is a hard blocker for Kantheon bootstrap. |
| **Maven publishing for `shared/libs/kotlin/*`** | **NOT confirmed** | `shared/libs/kotlin/` has: `data-formatter`, `db-common`, `erp-sql-common`, `erp-sql-metadata`, `fuzzy-common`, `ktor-configurator`, `logging-config`, `otel-config`, `query-translator`, `ttr-parser`, `ttr-writer`, `whois-common`. No `maven-publish` confirmed in any of their `build.gradle.kts` files. |
| **CI workflow for Maven publishing** | **NOT found** | `.github/workflows/` checked — `ci.yml` exists but does not publish to any Maven repository. No release-tag-triggered publish job. |
| **Target Maven repository** | **unknown** | No repository URL found in any build config. Unclear whether Artifactory, GitHub Packages, OSS Sonatype, or private. |
| **Build-convention plugins** (`id("my.kotlin-ktor")`, etc.) | **present, local only** | Defined in `gradle-build/` convention plugins. Not published to Maven. Consumed locally via `includeBuild` in settings.gradle.kts. Kantheon would need them as Maven artifacts. |
| **`just init` recipe** | **sensible** | Runs `./gradlew :shared:proto:assemble` + `just py-sync-all` + `just vue-install-all`. Does not publish Maven artifacts. |
| **`just proto-all` recipe** | **functional** | `./gradlew :shared:proto:assemble` generates Kotlin + Python + JS bindings. Generates `libs/shared-proto/` packages. |
| **`justfile` accuracy vs CLAUDE.md** | **drift** | `just deploy-py nlp` present but no `just deploy-py infra/nlp` (just uses `infra/nlp` as path). `just deploy-py` for Python services uses `docker build` not Jib. Most services not in justfile (no `just deploy-kt dispatcher`, etc.). |

**What would need to change for Kantheon consumption:**
1. Configure `maven-publish` on `shared/proto/` with repository URL + credentials.
2. Configure `maven-publish` on each `shared/libs/kotlin/*` library.
3. Add CI workflow to publish on release tags (`<service>/v<version>` format).
4. Publish Gradle convention plugins (`my.kotlin-ktor`, `my.kotlin-spring`) to Maven or document alternative for Kantheon.
5. Clarify target Maven repository (Artifactory? GitHub Packages?).

---

## 6. Cross-Cutting Infrastructure

**OpenTelemetry:**
- `shared/libs/kotlin/otel-config/` — `createOpenTelemetrySdk()` present, consumed by all Kotlin services.
- `shared/libs/python/otel-config/` — `setup_otel()` present, consumed by Python services (infra/nlp, workers/polars).
- Deployment: Grafana Alloy → Tempo / Prometheus / Loki. `deployment/local/` has wiremock + postgres + init jobs.
- `just debug-tunnel` forwards DB (7432), Wiremock to localhost. Not confirmed which other services.

**Backstage catalog:**
- `infra/backstage/` exists with `catalog-info.yaml` per service. 25 files in infra/backstage/.
- `agents/erp-agent/docs/catalog-info.yaml`, `agents/erp-agent-2/docs/catalog-info.yaml` present.
- Most services do NOT have `catalog-info.yaml` in their own directory (they live in `infra/backstage/examples/` as templates).

**ArgoCD app-of-apps:**
- `deployment/` structure has `base/` and `overlays/local/` pattern. Kustomize used throughout.
- `deployment/apps/` contains 9 app overlays (aip-fe, backstage, erp-api, erp-mcp, fuzzy-matcher, fuzzy-mcp, health-check-service, llm-gateway, starters).
- Resolver Stage 04 task: "Add to ArgoCD app-of-apps if the platform's pattern requires it." Not done.

**Local K3s + `just debug-tunnel`:**
- Forwards: Postgres (7432), Wiremock. Additional services? Not fully documented.
- `local.env` pattern + per-service `.secrets.env` documented in CLAUDE.md.

**Flyway migrations:**
- `infra/sql-security/` has `resources/db/migration/` (V1__init.sql). Others? Not confirmed.

**`local.env` + secrets pattern:**
- Documented in CLAUDE.md. `app-config` just recipe builds config + secrets from `local.env` + `llm.secrets.env` + `erp.secrets.env` + `back.secrets.env`.

---

## 7. Doc-vs-Code Drift

| Doc | What it says | What's actually true | Why it matters |
|---|---|---|---|
| `docs/v1/v1-architecture.md` §6 (Service inventory) | Lists `metadata`, `metadata-mcp`, `query-translator` (lib), `translator`, `validator`, `query-runner`, `dispatcher`, `workers/mssql/` | `infra/metadata` (Phase 1.2, 1.12 search) NOT listed; `tools/meta-mcp` (not `metadata-mcp`) NOT listed; `workers/polars` (v2.4, Phase 2.4) NOT listed; `tools/nlp-mcp` NOT listed | v1-architecture.md is stale — new services landed without updating the doc |
| `docs/v1/v1-architecture.md` §11 (Phase roadmap) | Phase 2.4 "Polars Worker" is v1.5+ | `workers/polars/` landed in v2.4 (Phase 2.4 complete) | Architecture doc shows Polars in v1.5+ but it shipped in Phase 2.4 |
| `docs/v1/v1-architecture.md` §9 (Component decomposition) | Describes `query-translator` library inside `shared/libs/kotlin/query-translator/` | Correct. Library exists and is embedded by `services/translator/` | No drift |
| `resolver-design.md` §Resolver agent — Koog graph | 10 nodes listed with responsibilities | `agents/resolver/` has 10 nodes but only 5 implemented, 5 are stubs | Stage 04 is in progress; design is ahead of implementation |
| `kantheon/docs/v1/kantheon-architecture.md` §10 | "Kantheon depends on ai-platform via **Maven**" — ai-platform publishes `shared/proto` + `shared/libs/kotlin/*` as versioned Maven artifacts | `shared/proto` has NO `maven-publish` plugin. None of the `shared/libs/kotlin/*` libraries have `maven-publish`. No CI publish workflow. | **Hard blocker for Kantheon bootstrap** |
| `kantheon/docs/v1/kantheon-architecture.md` §2 | Lists `tools/capabilities-mcp` as unified registry | `tools/capabilities-mcp/` does NOT exist in ai-platform | Naming: Kantheon calls it `capabilities-mcp`; ai-platform doesn't have this service |
| `golem/docs/aip-v1/Analytical Agent on V1.md` §5 G1 | "Audit `fuzzy-mcp`'s current behaviour — add NFD + inflection trimming + Levenshtein" | `fuzzy-matcher` has Levenshtein; NFD MISSING; inflection trim MISSING | G1 remains partial — the core Czech normalization is not implemented |
| `golem/docs/aip-v1/Analytical Agent on V1.md` §5 G7 | "`query-mcp` returns `pipeline_warnings` in `structuredContent`" | `query-mcp` does NOT return `pipeline_warnings`; `PipelineContext.warnings` exists in proto but is not propagated | G7 not started — agent cannot see platform-level warnings |

---

## 8. Risks and Unknowns

1. **Spring Boot version of `infra/llm-gateway` not pinned in `libs.versions.toml`** — couldn't verify it's current. The `bora_jvm_stack` notes this is the one Spring service; version from `gradle/libs.versions.toml` not confirmed.

2. **No evidence of integration tests for `fuzzy-mcp` Czech path** — `services/fuzzy-matcher` has unit tests (`TokenBasedMatcherTest.kt`) but no integration tests confirming NFD or inflection handling. May be tested manually.

3. **`office-agent` is undocumented and has no source code** — directory exists (`agents/office-agent/`) but only `docs/catalog-info.yaml`. Its role is unclear; possibly abandoned or placeholder.

4. **`workers/mssql` and `workers/polars` have no K8s manifests in the repo** — deployment via `deployment/apps/` pattern? Or via `just deploy-kt` (Jib)? The `workers/` directories have no `k8s/` subdirectory. Unclear how these are deployed to K3s.

5. **Redis wiring** — `infra/llm-gateway` may use Redis for LLM response caching; `services/query-runner` uses Caffeine (in-process). No Redis deployment manifest confirmed in `deployment/`. Pythia's Budget Tracker needs `cached: bool` from llm-gateway — cannot verify this is implemented.

6. **Keycloak deployment** — `infra/whois` maps Keycloak tokens to ERP users; Keycloak server itself not in `deployment/`. Auth flow from caller → query-mcp → whosi → sql-security is described but the Keycloak server deployment is not in the repo.

7. **`just deploy-kt resolver`** does not exist — Stage 04 task 12 (Jib build + K8s manifests + `just deploy-kt resolver` recipe) is not done. Only `k8s/base/` exists; no `overlays/local/`.

8. **`infra/nlp` Stage 03 (eval corpus) status unclear** — `infra/nlp/eval/corpus/seed.jsonl` and `infra/nlp/eval/run_eval.py` not verified present. Stage 03 runs in parallel with Stage 04; its completion gates Stage 04 exit criteria.

9. **Resolver build fix** — The MCP SDK class resolution failure (unresolved `Implementation`, `ServerCapabilities`, `Tool`, `CallToolResult`, `encodeToString`, `decodeFromString`) requires Gradle cache invalidation or dependency version fix. `progress-stage-04.md` documents this as the current blocker.

10. **Naming drift: `metadata-mcp` vs `meta-mcp`** — Kantheon docs refer to `metadata-mcp`; ai-platform directory is `tools/meta-mcp`. Proto package is `cz.dfpartner.metadata.v1`. MCP tool name not verified. Flagged in brief §2.2.

---

## 9. Recommended Next Steps for Bora

**Priority 1 — Unblock Kantheon bootstrap:**
1. **Set up Maven publishing for `shared/proto` + `shared/libs/kotlin/*`** — add `maven-publish` plugin + `publishing {}` block + repository URL (Artifactory/GitHub Packages). Add CI workflow publishing on release tags. Without this, Kantheon cannot consume ai-platform protos as versioned artifacts. This is the single most blocking gap.

**Priority 2 — Close G1 (Czech fuzzy) and G7 (pipeline_warnings):**
2. **Implement G1: Add NFD normalization + Czech inflection trimming to `fuzzy-matcher`** — `services/fuzzy-matcher` needs a Czech normalization pipeline before `Candidate.tokenize()`. NFD decomposition + regex stripping of diacritics + inflection stemmer (Czech-specific). This is the most impactful gap for Kantheon's Czech-speaking users.
3. **Implement G7: Propagate `PipelineContext.warnings` through `query-mcp` to the agent** — `query-runner` has access to `PipelineContext.warnings`; `query-mcp` needs to read it and return `pipeline_warnings` in `CallToolResult.structuredContent`. Without this, the agent cannot render the "why did the platform do that?" panel.

**Priority 3 — Unblock Resolver Stage 04:**
4. **Fix `agents/resolver` build** — The MCP SDK class resolution failure (likely Gradle cache corruption or Kotlin 2.3.0 + kotlinx-serialization 1.10.0 version conflict). Clear `~/.gradle/caches/` or resolve version conflict. Then re-apply A2/A3 (ResolverCache wiring + NLP skip on HITL resume).
5. **Wire LLM calls (`callLlmCheap`/`callLlmFast`)** in `ResolverGraph.kt` — reference `infra/sql-validator/LlmGatewayClient.kt` for HTTP + ChatCompletion pattern. Then wire `callFuzzyService` to `tools/fuzzy-mcp`.
6. **Add `just deploy-kt resolver`** recipe + `k8s/overlays/local/` to complete deployment story.

**Priority 4 — Close Stage 03 + Stage 04 exit criteria:**
7. **Verify / complete Stage 03 (eval corpus + COMPARE + MorphoDiTa)** — confirm `infra/nlp/eval/corpus/seed.jsonl` (~50 Czech questions) exists and `infra/nlp/eval/run_eval.py` harness works. Stage 03 eval corpus gates Stage 04 exit criteria.
8. **Create `workers/mssql/` K8s manifests** — `workers/mssql/` has no `k8s/` directory. For `just deploy-kt workers/mssql` to work, need `k8s/base/` + `k8s/overlays/local/`. Same for `workers/polars/`.

**Priority 5 — Documentation cleanup:**
9. **Update `docs/v1/v1-architecture.md` §6 service inventory** — add `infra/metadata`, `tools/meta-mcp`, `tools/nlp-mcp`, `workers/polars`, `agents/resolver`. The doc is stale.
10. **Decide what to do with `office-agent`** — directory exists but has no source. Possibly archive or repurpose. Undocumented in Kantheon.

---

## Appendix A — Commands Run

```bash
# Service inventory
ls -la services/ tools/ infra/ agents/
git log -1 --format='%ai %s' -- <path>  # per directory
ls <dir>/k8s/ 2>/dev/null

# Proto packages
ls shared/proto/src/main/proto/cz/dfpartner/

# Shared libs
ls shared/libs/kotlin/
ls shared/libs/python/

# Justfile
cat justfile | grep "deploy-kt\|deploy-py"

# Resolver status
cat progress-stage-04.md
cat fwd-stage-04.md
git log -1 --format='%ai %s' -- agents/resolver/

# G1 check
grep -r "NFD\|normalize\|diacritic\|inflection" services/fuzzy-matcher/src/

# G7 check
grep -r "pipeline_warnings" tools/query-mcp/src/

# Maven publishing
grep -r "maven-publish\|publishing" shared/proto/build.gradle.kts
grep -r "maven-publish\|publishing" shared/libs/kotlin/*/build.gradle.kts
```

## Appendix B — Key Files Inspected

**Architecture / design:**
- `docs/v1/v1-architecture.md`
- `docs/v1/requirements.md`
- `resolver-design.md`
- `tasks-resolver-stage-01-infra-nlp.md`
- `tasks-resolver-stage-03-eval-compare.md`
- `tasks-resolver-stage-04-resolver-agent.md`
- `progress-stage-04.md`
- `fwd-stage-04.md`

**Reference docs:**
- `golem/docs/aip-v1/Analytical Agent on V1.md` (§4 G1–G7, §5 gaps)
- `kantheon/docs/v1/kantheon-architecture.md` (§10 cross-repo coupling)

**Code:**
- `services/fuzzy-matcher/src/main/kotlin/cz/dfpartner/fuzzy/core/TokenBasedMatcher.kt`
- `services/fuzzy-matcher/src/main/kotlin/cz/dfpartner/fuzzy/core/Algorithms.kt`
- `tools/query-mcp/src/main/kotlin/tools/querymcp/tools/QueryTool.kt`
- `tools/query-mcp/src/main/kotlin/tools/querymcp/tools/CompileTool.kt`
- `infra/nlp/src/nlp_service/engines/` (all 5 engines)
- `agents/resolver/src/main/kotlin/agents/resolver/koog/ResolverGraph.kt`
- `justfile`