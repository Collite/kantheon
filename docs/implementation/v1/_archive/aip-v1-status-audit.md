# AI Platform v1 — Status Audit

**Date:** 2026-05-12
**Reviewer:** Claude Code
**Repo head:** `55fdd018213e4a2068fb8ea7df2d50da30742f49`
**Branch:** `feat/resolver`

---

## TL;DR

1. **agents/resolver (Stage 04) is committed as "complete"** at HEAD (`55fdd01 stage 04 complete`, 2026-05-12). The commit includes 21 unit tests, 6 integration tests, eval harness, README, and prompt templates. However, `progress-stage-04.md` (unstaged modification in working tree) describes a BLOCKED state from a prior session — the committed HEAD supersedes this. The build error described there (`encodeToString` unresolved) is mitigated by `libs.kotlinx.ser.json` being explicitly declared in `build.gradle.kts`, but the error's root cause (Gradle cache corruption) is not confirmed resolved.

2. **K8s manifests for agents/resolver are staged for deletion.** Three manifest files (`base/deployment.yaml`, `base/kustomization.yaml`, `overlays/local/kustomization.yaml`) exist in HEAD but are staged-for-deletion in the working tree. The resolver is not currently deployable to K3s — `just deploy-kt resolver` runs, but no manifests will apply.

3. **agents/erp-agent-2 is deleted in the working tree** (all files show `D` in git status, not staged). The directory `agents/golem` (Python, LangGraph) exists untracked and is the current operational agent. Settings.gradle.kts does not include `agents/erp-agent-2` or `agents/golem`.

4. **G3 (`hide_columns_matching`) and G7 (`pipeline_warnings`) are implemented** — `shared/libs/kotlin/data-formatter` has `HideColumnsSpec.kt` and `FormatOptions.hideColumnsMatching`; `tools/query-mcp` has `PipelineWarnings.kt` with tests. G4 (`value_labels`) and G5 (`display_label`) are in the generated proto (`AttributeDetail`, `EntityDetail`) but not confirmed wired through the data-formatter. G1 (Czech NFD diacritic-fold) is **not implemented** — `fuzzy-matcher` uses only `lowercase()`.

5. **No maven-publish configuration exists** in `shared/proto/build.gradle.kts`. The Kantheon architecture doc specifies ai-platform must publish shared libs and proto as Maven artifacts, but no publish plugin or repository URL is configured.

---

## 1. Service Inventory

### agents/

| Module path | Stack | Role | Status | K8s | In justfile | Tests | Last commit | Notes |
|---|---|---|---|---|---|---|---|---|
| `agents/golem` | Python / FastAPI | Current operational analytical agent (ERP Q&A, LangGraph) | partial | no | yes (auto-detected) | no | untracked | Untracked dir; erp-agent-2 is deleted in working tree; golem is its replacement |
| `agents/resolver` | Kotlin / Ktor | Intent and entity resolver (Koog graph → plain Kotlin coroutines) | partial | staged-for-deletion | yes (auto-detected) | yes (21 unit + 6 integration) | 2026-05-12 | K8s manifests staged for deletion; build not confirmed compilable; LLM/fuzzy calls use real HTTP (not stubs in prod) |

### services/

| Module path | Stack | Role | Status | K8s | In justfile | Tests | Last commit | Notes |
|---|---|---|---|---|---|---|---|---|
| `services/dispatcher` | Kotlin / Ktor | gRPC dispatcher with sticky-routing and worker registry | implemented | no | yes | yes | 2026-05-03 | `StickyRegistry`, `CapabilityPoller` present |
| `services/erp-sql` | Kotlin / Ktor | Legacy ERP SQL execution service | archived | no | yes | no | 2026-04-15 | No K8s, no tests; pre-V1 legacy |
| `services/erp-sql-dispatcher` | Kotlin / Ktor | Legacy ERP SQL dispatcher | archived | no | yes | no | 2026-04-20 | No K8s, no tests; pre-V1 legacy |
| `services/fuzzy-matcher` | Kotlin / Ktor | Levenshtein-based fuzzy string matching with token index | partial | no | yes | yes | 2026-04-20 | `TATRMAN` algo present; no NFD/diacritic fold; see G1 |
| `services/query-runner` | Kotlin / Ktor | Compiled-plan cache and dispatch to workers | implemented | no | yes | yes | 2026-05-03 | Has CompiledPlanCache, retry policy |
| `services/sql-entity-service` | Kotlin / Ktor | Entity lookup / SQL entity service | partial | no | yes | yes | 2026-04-22 | No K8s manifests |
| `services/sql-formatter` | Kotlin / Ktor | Table formatter (uses data-formatter lib) | partial | no | yes | yes | 2026-05-03 | Uses `FormatOptions()` without `hideColumnsMatching` filled in; see G3 |
| `services/sql-free-service` | Kotlin / Ktor | Free-form SQL query service | scaffolded | no | yes | no | 2026-04-20 | No tests, no K8s |
| `services/sql-named-service` | Kotlin / Ktor | Named query execution service | partial | no | yes | yes | 2026-04-22 | No K8s |
| `services/sql-pattern-service` | Kotlin / Ktor | Pattern-based query service | partial | no | yes | yes | 2026-04-22 | No K8s |
| `services/translator` | Kotlin / Ktor | Query translator (SQL / TransDSL / DFDSL) | implemented | no | yes | yes | 2026-05-01 | Core translation pipeline |
| `services/validator` | Kotlin / Ktor | SQL validation pipeline (TopN, syntax, security wrap) | implemented | no | yes | yes | 2026-05-10 | `Gatekeeper`, `TopNEnforcer`, `SqlSyntaxChecker`, `SqlFixer` |

### tools/

| Module path | Stack | Role | Status | K8s | In justfile | Tests | Last commit | Notes |
|---|---|---|---|---|---|---|---|---|
| `tools/erp-data-mcp` | Kotlin / Ktor | MCP wrapper for legacy ERP data | archived | no | yes | no | 2026-04-22 | No tests, no K8s |
| `tools/fuzzy-mcp` | Kotlin / Ktor | MCP wrapper for fuzzy-matcher service | implemented | no | yes | yes | 2026-04-22 | gRPC + REST client dual-mode |
| `tools/meta-mcp` | Kotlin / Ktor | MCP wrapper for metadata service | partial | no | yes | yes | 2026-05-04 | `get_entity_details` present; `list_queries(kind="named")` not verified; no `cnc.role` |
| `tools/nlp-mcp` | Kotlin / Ktor | MCP wrapper for infra/nlp | partial | yes | yes | yes | 2026-05-10 | K8s manifests present; `analyze` tool had array-parse bug (review-002); fix status unclear |
| `tools/query-mcp` | Kotlin / Ktor | Primary query execution MCP tool (compile + query) | implemented | no | yes | yes | 2026-05-03 | `compile` and `query` tools present; `pipelineWarnings` in structured content; session_id sticky-routing param |

### infra/

| Module path | Stack | Role | Status | K8s | In justfile | Tests | Last commit | Notes |
|---|---|---|---|---|---|---|---|---|
| `infra/backstage` | Node/Yarn | Backstage developer portal | partial | no | yes | yes | 2026-04-08 | No K8s manifests in service dir |
| `infra/health-check-service` | Kotlin / Ktor | Health check aggregator | scaffolded | no | yes | no | 2026-04-17 | No tests, no K8s |
| `infra/llm-gateway` | Kotlin / Spring Boot | LLM gateway with async job queue | partial | no | yes | yes | 2026-04-20 | Spring Boot; Azure OpenAI wired; Anthropic commented out; no embeddings endpoint; no pricing API; no Redis cache confirmed |
| `infra/metadata` | Kotlin / Ktor | Metadata service (TTR model registry) | implemented | no | yes | yes | 2026-05-12 | Active recent development; `export/`, `graph/`, `registry/`, `search/`, `reconcile/` packages |
| `infra/nlp` | Python / FastAPI | NLP engine (Stanza, spaCy, NameTag, MorphoDiTa, langid) | partial | yes | yes | yes | 2026-05-10 | NORMAL mode implemented; COMPARE mode implemented; eval corpus + harness present; review-002 found critical bugs in nlp-mcp; Stage 01–03 task checkboxes all unchecked (plan files not updated) |
| `infra/sql-metadata` | Kotlin / Ktor | SQL-layer metadata (table/column schema) | partial | no | yes | yes | 2026-04-24 | No K8s |
| `infra/sql-security` | Kotlin / Ktor | Row-level security predicate service (OPA) | implemented | no | yes | yes | 2026-05-03 | `SecurityServiceImpl`, `PolicyRegistry`, `PolicyToExpression` present |
| `infra/sql-validator` | Kotlin / Ktor | SQL validation (integrates with security, LLM fixer) | implemented | no | yes | yes | 2026-04-22 | `LlmGatewayClient`, `Guardian`, `Gatekeeper` present |
| `infra/starters` | Kotlin / Ktor | Startup/init service | scaffolded | no | yes | yes | 2026-04-10 | |
| `infra/whois` | Kotlin / Ktor | User identity resolution (Keycloak + ERP) | partial | no | yes | no | 2026-04-13 | Flyway migrations present (V1–V5); no tests, no K8s |

### workers/ (not in CLAUDE.md layer table but in settings.gradle.kts)

| Module path | Stack | Role | Status | K8s | Tests | Last commit | Notes |
|---|---|---|---|---|---|---|---|
| `workers/mssql` | Kotlin / Ktor | MS SQL query worker (Arrow IPC output) | implemented | no | yes | 2026-05-01 | `ExecutePipelineSpec`, `ConnectionPoolManagerSpec`, `WorkerServiceImplSpec` |
| `workers/polars` | Python / FastAPI | Polars in-memory DataFrame worker (v1.5) | scaffolded | no | yes | 2026-05-03 | `workspace.py`, `grpc_service.py` present; scoping notes in docs; feature-gated to Phase 2.2 |

---

## 2. Capability Deep-Dives

### tools/query-mcp

- **`compile` MCP tool**: present at `tools/querymcp/tools/CompileTool.kt`. Returns `pipelineWarnings` in structured content even on rejection.
- **`query` MCP tool**: present at `tools/querymcp/tools/QueryTool.kt`. Source-language docstring lists SQL, TransDSL, and DataFrame DSL. Session_id sticky-routing parameter documented in schema: `"Session id for sticky routing on stateful workers."`.
- **TransDSL stack composition**: the tool description states support for TransDSL; the underlying translator service handles Filter/Project/Sort. No TransDSL-specific field-level parsing occurs inside query-mcp itself (pass-through to translator).
- **`pipeline_warnings`**: implemented in `tools/querymcp/mcp/PipelineWarnings.kt`. Both `QueryTool` and `CompileTool` emit `pipelineWarnings` array in `structuredContent`. Tests in `PipelineWarningsSpec.kt` and both tool specs confirm G7 behaviour including the `sticky_session_match` code.
- **Sticky-routing infrastructure**: present in `services/dispatcher/sticky/StickyRegistry.kt` with tests in `StickyRegistrySpec.kt`. Session_id flows from query-mcp through query-runner proto to dispatcher.

### tools/meta-mcp

- **`list_queries(kind="named")`**: the `Tools.kt` file exposes a query-catalog tool; full signature not deeply inspected, but the metadata client is invoked. Whether `kind` filtering with localised labels is exposed requires deeper inspection of `MetadataClient`.
- **`get_entity(<entityId>)`**: present — `get_entity_details` tool at line 221 of `Tools.kt`.
- **`cnc.role` schema (G2)**: no evidence found in `tools/meta-mcp/src`. The metadata proto (`infra/metadata`) and TTR parser would need to support `cnc.role` mappings first.
- **`value_labels` on Model attributes (G4)**: `value_labels` field exists in generated `AttributeDetailKt.kt` (proto field 6) but meta-mcp tool schemas expose raw physical columns (`allowedValues` array), not the localised `LocalizedString` value-labels structure.
- **`display_label` on Model attributes (G5)**: `display_label` field exists in generated `AttributeDetailKt.kt` (field 7) and `EntityDetailKt.kt` (field 5), confirmed by proto test `LocalizedStringSpec.kt`. Surface-level exposure through meta-mcp not confirmed.

### tools/fuzzy-mcp + services/fuzzy-matcher

- **Czech tokenization / NFD diacritic-fold pipeline**: **not present**. `DistanceCache` uses `token.lowercase()` only. `TokenIndex` uses `token.lowercase()`. No `java.text.Normalizer.NFD` call found anywhere in `services/fuzzy-matcher/src`.
- **Inflection trimming**: not present. No stemmer, suffix stripper, or Czech morphological reducer in source.
- **Levenshtein matching**: present in `Algorithms.kt` (`LevenshteinAlgorithm`) and `TokenBasedMatcher` (uses `Levenshtein()` from `info.debatty.java.stringsimilarity`). The default algorithm is `TATRMAN` (token-based with Levenshtein at the token level).
- **Namespace API matching `fuzzyMatcherNamespace` per EntityTypeSpec**: `fuzzy-mcp` passes `category` to fuzzy-matcher; the resolver's `callFuzzyService` is wired to use `span.entityTypeCandidates` for namespace selection (per tasks-review-003 A4, marked done).

### infra/llm-gateway

- **Stack**: Kotlin / Spring Boot (`alias(libs.plugins.spring.boot)` in `build.gradle.kts`).
- **Modality × tier routing**: `ModelService.kt` has a provider-to-client map; `ModelRepository.kt` has `modelType: String? = null // chat, embedding`. Anthropic provider is commented out; only `AiProvider.AZURE` is wired.
- **Embeddings endpoint**: field `modelType = "embedding"` in `ModelRepository.kt`; not confirmed as a live endpoint.
- **Pricing API or `cached: bool`**: not found in source.
- **Redis cache**: `NatsConfig.kt` is present (NATS async job queue); no Redis client or config found in `infra/llm-gateway/src`.
- **Model vendors configured**: Azure OpenAI via Spring AI (`AzureOpenAiChatOptions`). Anthropic (`AnthropicChatOptions`) is commented out.
- **Flyway migrations**: V1 migration at `src/main/resources/db/migration/V1__initial_schema.sql`.

### services/sql-formatter (data-formatter)

- **Location**: the formatter *service* is `services/sql-formatter`. The shared *library* is `shared/libs/kotlin/data-formatter`. The library is the canonical implementation; the service is a thin HTTP adapter.
- **`hide_columns_matching` (G3)**: implemented in `shared/libs/kotlin/data-formatter`. `FormatOptions.hideColumnsMatching: List<Regex>` is present, tested in `HideColumnsSpec.kt`. The service adapter (`LibraryFormatterAdapter.kt`) currently calls `DataFormatter.fromJsonRows(bytes, outputFormat, FormatOptions())` — `FormatOptions()` with defaults, meaning `hideColumnsMatching` is empty and the feature is not exposed by the HTTP service endpoint. The library is ready; the service endpoint does not pass the parameter through.
- **Markdown/CSV/TSV/JSON output modes**: `OutputFormat` enum is confirmed in the library; `DataFormatterFacadeSpec.kt` tests Markdown and JSON modes.
- **Localised column headers + value labels**: `Phase2_2LocalisationSpec.kt` exists, suggesting localisation is in the library. `value_labels` and `display_label` fields are in the proto and library test infra, but end-to-end wiring (Arrow IPC schema metadata → formatter → display) is not confirmed from source inspection alone.

### infra/sql-security + infra/sql-validator

- **sql-security implemented**: yes. `SecurityServiceImpl.kt`, `PolicyRegistry.kt`, `PolicyToExpression.kt` are present. Tests in `SecurityServiceImplSpec.kt` and `OpaClientTest.kt`.
- **sql-validator implemented**: yes. `Gatekeeper.kt`, `Guardian.kt`, `TopNEnforcer.kt`, `SqlSyntaxChecker.kt`, `SqlFixer.kt` present. `LlmGatewayClient` in sql-validator (for SQL fix loop). Tests present.
- **Both invoked from query-mcp pipeline**: sql-security and sql-validator are downstream of query-runner / translator per the architecture; query-mcp calls query-runner which calls these via gRPC. Direct verification of call graph would require running the stack.

### services/dispatcher + services/query-runner

- **MSSQL worker**: present at `workers/mssql`; last commit 2026-05-01; tests present.
- **Polars worker**: present at `workers/polars` (Python); scaffolded with `workspace.py`, `grpc_service.py`, tests. Feature-gated to Phase 2.2; scoping notes in `docs/`.
- **Sticky-routing on dispatcher**: implemented in `services/dispatcher/sticky/StickyRegistry.kt`. Tests in `StickyRegistrySpec.kt` verify record/find/evict semantics.
- **Session-scoped DataFrame management**: deferred to Polars Worker Phase 2.2 per architecture docs. The `workspace.py` in `workers/polars` begins the session-DF concept.

### infra/nlp + tools/nlp-mcp

- **FastAPI + engine plugin system (NlpEngine protocol)**: present in `infra/nlp/src/nlp_service/engines/base.py`. Engines: `stanza_engine.py`, `spacy_engine.py`, `nametag_engine.py`, `langid_engine.py`, `morphodita_engine.py`.
- **Engines wired**: Stanza, spaCy, NameTag, langid — all present. MorphoDiTa via UFAL HTTP API — `morphodita_engine.py` present (Stage 03 deliverable).
- **NORMAL mode operational**: yes, orchestrator confirms `mode="NORMAL"` path is the primary path in `orchestrator.py`.
- **COMPARE mode (Stage 03)**: implemented — `_analyze_compare()` in `orchestrator.py` fans out to all engines supporting `(language, op)` in parallel.
- **nlp-mcp Kotlin/Ktor thin wrapper**: present at `tools/nlp-mcp`. Has `analyze` and `parse` tools (`Tools.kt`). K8s manifests at `tools/nlp-mcp/k8s/`. **Critical bug from review-002**: `ops` argument was read as `jsonObject` instead of `jsonArray`, making the tool non-functional. Bug was filed in `tasks-review-002.md` (17 unchecked). Whether this was fixed in HEAD is not confirmed from source inspection (the `tasks-review-002.md` shows 0/17 checked, all unchecked).

### agents/resolver

- **Directory exists**: yes, at `agents/resolver/`.
- **Status**: partial — 9 nodes implemented as plain Kotlin coroutines; tests committed; K8s manifests staged for deletion; LLM and fuzzy HTTP calls are real (not mocked in production path); A2/A3 (NLP caching / HITL NLP skip) were partially reverted per progress notes but re-implemented in subsequent sessions per tasks-review-003.
- **Koog dependency**: `koog = "0.8.0"` is declared in `gradle/libs.versions.toml` with five library entries (`koog-agents`, `koog-core`, `koog-tools`, `koog-ktor`, `koog-prompt`). However, **none of these are referenced in `agents/resolver/build.gradle.kts`**. The resolver was implemented as plain Kotlin coroutines without Koog, due to Ktor 2.x/3.x transitive conflict. This is a documented deviation.
- **Nodes implemented** (per `ResolverGraph.kt` grep):
  - `branchOnInput` (line 65)
  - `detectLangAndParse` (line 80)
  - `extractUniversal` (line 95)
  - `proposeDomainSpans` (line 131)
  - `filterRelevantSpans` (line 162)
  - `fuzzyMatchSpans` (line 190)
  - `jointInference` (line 224)
  - `decideHitlOrEmit` (line 251)
  - `decodeTokenAndApplyChoice` (line 316)
  - Note: `assembleResp` does not appear as a named function — assembly logic appears inline in `decideHitlOrEmit` / calling code. The design doc describes it as a separate node.
- **Proto package `cz.dfpartner.resolver.v1`**: present at `shared/proto/src/main/proto/cz/dfpartner/resolver/v1/resolver.proto`.

### agents/erp-agent, erp-agent-2, office-agent

- `agents/erp-agent-2`: tracked in git (last commit 2026-04-20, commit `2966309 fuzzy`), but **all files are deleted in the working tree** (not staged). This is the "Golem" analytical agent — Python, LangGraph, `graph.py`, `nodes.py`, `entity_resolution.py`.
- `agents/golem`: untracked directory in working tree. Python, FastAPI, LangGraph. Contains `graph.py`, `nodes.py`, `dynamic_chip_resolver_heuristic.py`, `pattern_catalog.py`. This appears to be the current operational agent (erp-agent-2 renamed/replaced).
- `agents/office-agent`: does not exist in the repository.
- The "current Golem" in the V1 design doc context maps to `agents/erp-agent-2` (tracked, deleted) / `agents/golem` (untracked). Likely the same codebase at a later state.

### Persistence + transport

- **Postgres**: declared in `deployment/local/postgres/` and `deployment/local/data-postgres/` via Helm charts. Flyway migrations in `infra/llm-gateway` and `infra/whois`.
- **Redis**: referenced in Pythia architecture (`§6.1`) and llm-gateway design as needed for LLM cache, but no Redis deployment manifest found in `deployment/local/` and no Redis client library usage confirmed in `infra/llm-gateway/src`.
- **NATS JetStream**: `NatsConfig.kt` present in `infra/llm-gateway`. No standalone NATS deployment manifest found in `deployment/local/`.
- **SeaweedFS**: referenced in Pythia architecture doc as Arrow-IPC blob storage via Charon. Not found in deployment or source.
- **Client libraries**: gRPC stubs for all proto-defined services; Kotlin gRPC clients present in tool services.

### infra/whois + Keycloak

- **whois**: `infra/whois` is implemented (Application.kt, UserRepositoryDb, KeycloakClient, ErpClient, Flyway V1–V5 migrations). No tests, no K8s manifests in service directory. Last commit 2026-04-13.
- **Keycloak deployment**: not found in `deployment/local/`. No Keycloak `StatefulSet` or `Deployment` manifest located. `infra/whois` references `KeycloakClient` in code.

---

## 3. Gap Status (G1–G7)

| Gap | Description | Status | File / Evidence | Missing |
|---|---|---|---|---|
| **G1** | Czech-aware fuzzy matching: NFD diacritic-fold + inflection trim + Levenshtein | **open** | `services/fuzzy-matcher/src/main/kotlin/cz/dfpartner/fuzzy/core/` — only `lowercase()`, no `Normalizer.normalize()`, no stem/inflection logic | NFD normalisation pass; Czech inflection trimmer (suffix stripping); expose via `fuzzy_match_czech` tool |
| **G2** | `cnc.role` schema in metadata (fact/dimension/structural roles on `er.entity`) | **open** | No `cnc` directory in `shared/proto`; no `er2cncRole` mapping in TTR parser; no `cnc.role` query in `tools/meta-mcp` | Proto schema, TTR DSL sugar, metadata service support, meta-mcp query surface |
| **G3** | `hide_columns_matching` in data-formatter | **partial** | `shared/libs/kotlin/data-formatter/src/main/kotlin/shared/formatter/core/Projection.kt`; `HideColumnsSpec.kt` passes. But `services/sql-formatter/src/main/kotlin/.../LibraryFormatterAdapter.kt` calls `FormatOptions()` (no patterns). `tools/query-mcp` `hide_columns_matching` tool input field not found. | Expose parameter through sql-formatter HTTP endpoint and query-mcp tool schema |
| **G4** | `value_labels` on Model attributes | **partial** | Proto field exists: `generated/sources/proto/main/kotlin/cz/dfpartner/metadata/v1/AttributeDetailKt.kt` field 6. Test: `LocalizedStringSpec.kt` "AttributeDetail.value_labels and display_label round-trip". End-to-end wiring through data-formatter Arrow metadata not confirmed. | Arrow IPC schema metadata carry-through; formatter consuming the labels |
| **G5** | `display_label` on Model attributes and entities | **partial** | Proto field exists: `AttributeDetailKt.kt` field 7, `EntityDetailKt.kt` field 5. Same round-trip test as G4. Formatter consuming `display_label` not confirmed. | Same as G4 — formatter needs to read Arrow field metadata for column header replacement |
| **G6** | Dynamic suggestions: resolved agent-side (no platform change required) | **obsolete-or-renamed** | Resolved in Golem design doc §8 Q2: "Option A — agent computes dynamic chips client-side from named-query catalog." No platform work item. | None |
| **G7** | `pipeline_warnings` in query-mcp structuredContent | **closed** | `tools/querymcp/mcp/PipelineWarnings.kt`; `PipelineWarningsSpec.kt`; `QueryTool.kt` line `put("pipelineWarnings", pipelineWarnings)`; `CompileTool.kt` does the same. Tests in `QueryToolSpec.kt` confirm array always present. | — |

---

## 4. Resolver Progress

### Stage task file checkbox counts (plan files only — actual code state may differ)

| Stage | File | Checked | Total | % |
|---|---|---|---|---|
| 01 | `tasks-resolver-stage-01-infra-nlp.md` | 0 | 48 | 0% |
| 02 | `tasks-resolver-stage-02-nlp-mcp.md` | 0 | 34 | 0% |
| 03 | `tasks-resolver-stage-03-eval-compare.md` | 0 | 25 | 0% |
| 04 (original) | `tasks-resolver-stage-04-resolver-agent.md` | 0 | 36 | 0% |
| 04 (developer) | `tasks-stage-04-resolver-agent.md` | 0 | 40 | 0% |
| 05 | `tasks-resolver-stage-05-parallel-deployment.md` | 0 | 22 | 0% |
| 06 | `tasks-resolver-stage-06-consumer-migration.md` | 0 | 15 | 0% |
| review-002 | `tasks-review-002.md` | 0 | 17 | 0% |
| review-003 | `tasks-review-003.md` | 24 | 25 | 96% |

**Important note**: task files are plan/tracking documents; checkboxes were not updated as code was committed. The `tasks-review-003.md` reflects the most recent review session (2026-05-11) and shows 24/25 tasks marked done.

### Actual state per stage based on code inspection

**Stage 01 (`infra/nlp`)**: Code delivered. `infra/nlp/` exists with all 5 engines, orchestrator, FastAPI routes, Dockerfile, K8s manifests, eval corpus (50 entries), eval harness. Review-002 identified critical bugs; `tasks-review-002.md` shows all 17 items unchecked (plan file not updated). Effective completion: ~70% of committed functionality, pending review-002 bug fixes.

**Stage 02 (`tools/nlp-mcp`)**: Code delivered. K8s manifests present (`tools/nlp-mcp/k8s/`). Review-002 identified a critical bug in `analyze` tool (ops read as `jsonObject` instead of `jsonArray`). Bug status in current HEAD not confirmed resolved.

**Stage 03 (Eval corpus + COMPARE mode)**: COMPARE mode in orchestrator implemented. MorphoDiTa engine present. Eval corpus at `infra/nlp/eval/corpus/seed.jsonl` (50 entries per commit content). Eval harness at `infra/nlp/eval/run_eval.py`. No comparison report committed.

**Stage 04 (`agents/resolver`)**: Committed as "stage 04 complete" (2026-05-12). 9 routing nodes implemented (no separate `assembleResp`). 21 unit tests + 6 integration tests. Eval harness + corpus. README. Prompt templates. K8s manifests exist in HEAD but are **staged for deletion**. Build blocked state described in `progress-stage-04.md` (unstaged modification) refers to a pre-commit session; the commit may or may not resolve it. A2/A3 cache wiring and NLP-skip-on-resume are described as done in tasks-review-003.

**Stages 05–06**: Not started. No code or configuration exists.

### Active branches

- `feat/resolver` (current) — primary resolver work
- `feat/v2-1a-data-formatter`, `feat/v2-1b-query-mcp`, `feat/v2-1c-sql-formatter-migration`, `feat/v2-2-model-expressiveness`, `feat/v2-4-worker-polars` — parallel Phase 2 tracks
- `feat/feature-model-search`, `feat/feature-yaml-import` — other feature work
- `fleet-local-history`, `health-service` — other branches

---

## 5. Build / CI / Maven Publishing Readiness

### Checklist

| Item | Status | Notes |
|---|---|---|
| `shared/proto/build.gradle.kts` has maven-publish | **no** | No `maven-publish` plugin, no `publishing {}` block |
| Maven repo URL configured | **no** | `dependencyResolutionManagement` in `settings.gradle.kts` uses only `mavenCentral()` |
| CI publishes on release tags | **no** | `ci.yml` runs init → lint-check → test-all; no publish step; `release-service.yml` exists but not inspected for publish |
| Convention plugins `my.kotlin-ktor` and `my.kotlin-spring` defined | **no (as named)** | Root `build.gradle.kts` has no `buildSrc` or `includeBuild`. Services use `alias(libs.plugins.kotlin.spring)` and `alias(libs.plugins.ktor)` from `libs.versions.toml`. The CLAUDE.md and AGENTS.md say `id("my.kotlin-spring")` / `id("my.kotlin-ktor")` — these names are **not found** in any `.gradle.kts` file. The docs appear to describe planned convention plugin names that have not been implemented; services use direct plugin aliases instead. |
| `just proto-all` generates KT + PY + JS | **partial** | `just proto` runs `./gradlew :shared:proto:assemble`. `just proto-py` runs `preparePythonPackage`. `just proto-js` runs `prepareJsPackage`. There is no single `just proto-all` recipe — CLAUDE.md documents `proto-all` but the justfile has only `proto`, `proto-py`, `proto-js`. |
| `just sync-py` recipe | **no** | CLAUDE.md documents `just sync-py`; justfile has `just py-sync-all` (different name). |
| `just build-kt <service>` | yes | Present. |
| `just deploy-kt <service>` | yes | Present (Jib). |
| `just deploy-py <service>` | yes | Present. |
| `just test-all` | yes | Present. |
| `just lint-all` | yes | Present. |
| `just debug-tunnel` | **no** | CLAUDE.md documents this command; it does not appear in the justfile. Only a `port-forward` invocation appears inside the `eval-nlp` recipe. |
| CI auto-detects Jib vs Docker | yes | `ci.yml` runs `just test-all` which discovers services via `lang-kotlin` and `lang-python` helpers. |
| Shared libs in `settings.gradle.kts` | yes | `otel-config`, `logging-config`, `data-formatter`, `ttr-parser`, etc. all included. |

---

## 6. Cross-Cutting Infrastructure

### OTEL configuration

`shared/libs/kotlin/otel-config` provides `createOpenTelemetrySdk()` (last commit 2026-04-11). Consumers confirmed in source: `infra/sql-metadata`, `infra/health-check-service`, `infra/starters`, `infra/sql-validator`, `infra/whois`, `infra/metadata`, `infra/sql-security`, `tools/nlp-mcp` (via `NlpMcpTelemetry`), `tools/fuzzy-mcp` (via `FuzzyMcpTelemetry`), `agents/resolver` (`ResolverOtel.kt`), `services/erp-sql-dispatcher`, `services/fuzzy-matcher`, `services/sql-entity-service`. `shared/libs/python/otel-config` provides `setup_otel`, `middleware`, `client_interceptor` for Python services.

### Alloy → Tempo / Prometheus / Loki

No Alloy, Tempo, or Grafana manifests found in `deployment/local/`. The deployment directory contains only `apps/`, `local/` (postgres, data-postgres, wiremock, config, init). The observability stack is documented in CLAUDE.md and AGENTS.md as running on K3s, but no manifests for these components are present in the repository.

### Backstage catalog-info.yaml files

15 files found across the repository:
- `docs/catalog-info.yaml` (root)
- `infra/backstage/catalog-info.yaml`
- Services with catalog entries: `services/sql-pattern-service`, `services/sql-formatter`, `services/erp-sql-dispatcher`, `services/fuzzy-matcher`, `services/sql-entity-service`, `services/sql-named-service`, `services/erp-sql`, `services/sql-free-service`
- Infra with catalog entries: `infra/sql-metadata`, `infra/starters`, `infra/sql-validator`
- Tools with catalog entries: `tools/fuzzy-mcp`
- Other: `infra/llm-gateway`

Services **without** catalog entries: `tools/query-mcp`, `tools/meta-mcp`, `tools/nlp-mcp`, `infra/nlp`, `infra/metadata`, `infra/sql-security`, `infra/whois`, `agents/resolver`, `agents/golem`, `services/translator`, `services/validator`, `services/dispatcher`, `services/query-runner`, `workers/mssql`, `workers/polars`.

### ArgoCD app-of-apps

No ArgoCD `Application` manifest found in `deployment/`. The deployment directory uses Kustomize build patterns (`kustomization.yaml`). ArgoCD app-of-apps pattern is described in CLAUDE.md but not found in repository manifests.

### `just debug-tunnel`

Not present in the justfile. The command is documented in CLAUDE.md as port-forwarding K3s services (DB, Wiremock) to localhost, but no recipe with this name exists. The `eval-nlp` recipe has an inline `kubectl port-forward` call.

### Flyway migrations

Services with Flyway SQL migrations in `src/main/resources/db/migration/`:
- `infra/llm-gateway`: V1 (initial schema)
- `infra/whois`: V1–V5 (users, identities, user_roles, role_hierarchy, roles)

No other Kotlin services have Flyway migrations found (services that use Postgres would be expected to have them; `infra/metadata` has no migration directory visible).

---

## 7. Doc-vs-Code Drift

### Drift 1 — CLAUDE.md `just proto-all` and `just sync-py` commands don't exist

**Documented** (CLAUDE.md): `just proto-all`, `just sync-py`, `just debug-tunnel`  
**Reality** (justfile): `just proto` (not `proto-all`), `just py-sync-all` (not `sync-py`). `just debug-tunnel` does not exist at all.  
**Impact**: developers following CLAUDE.md will get `just: recipe 'proto-all' not found` and `just: recipe 'sync-py' not found` errors.

### Drift 2 — resolver-design.md names Koog as the agent framework; resolver uses plain Kotlin coroutines

**Documented** (`resolver-design.md` line 28): `agents/resolver/ ─ Kotlin · Koog`; §Resolver agent — Koog graph describes the architecture using Koog graph semantics.  
**Reality** (`agents/resolver/build.gradle.kts`): no `koog-*` dependency. The graph is implemented as a `ResolverGraph` class with a `run()` coroutine loop and `NodeResult` sealed class — plain Kotlin, no Koog library. Also, `tasks-resolver-stage-04-resolver-agent.md` (the original plan) references Koog in task 3 ("Define the agent graph per design doc §Resolver agent — Koog graph") and task 2 ("Add Koog dependency to `gradle/libs.versions.toml`").  
**Reason documented in brief**: Ktor 2.x/3.x transitive conflict. The `koog-*` entries in `libs.versions.toml` are declared but unused. This deviation is intentional but not documented in the resolver's README.

### Drift 3 — CLAUDE.md describes `id("my.kotlin-spring")` / `id("my.kotlin-ktor")` convention plugins; these IDs do not exist in the codebase

**Documented** (CLAUDE.md): "Apply `id("my.kotlin-spring")` or `id("my.kotlin-ktor")` — these convention plugins are in `gradle-build/`"  
**Reality**: no `gradle-build/` directory exists. No `my.kotlin-spring.gradle.kts` or `my.kotlin-ktor.gradle.kts` found anywhere. Services use `alias(libs.plugins.kotlin.spring)`, `alias(libs.plugins.spring.boot)`, `alias(libs.plugins.ktor)` etc. directly from the version catalog. The named convention plugins either do not exist yet or were removed.

### Drift 4 — Pythia §6.1 dependency table lists `LLM Gateway` as needing "embeddings endpoint; pricing API; caching layer (Redis-backed)"

**Documented** (`Pythia-v1-Design.md` §6.1): `LLM Gateway — Changes needed: Tier-based routing API; embeddings endpoint; pricing API; caching layer (Redis-backed)`.  
**Reality** (`infra/llm-gateway`): single Azure OpenAI provider wired; `modelType = "embedding"` field exists in `ModelRepository.kt` but no live endpoint confirmed; no pricing API found; no Redis client or config found. The gateway is Spring Boot (not Ktor), which differs from the Kantheon architecture doc's expectation of `shared/libs/kotlin/*` Ktor-pattern services publishing to Maven.

### Drift 5 — Stage task files have 0/N checkboxes despite code being delivered

All six stage task files (`tasks-resolver-stage-01` through `tasks-resolver-stage-06`) have 0 boxes checked. The code for Stages 01–04 exists. This is a workflow discipline issue: task files were never updated to reflect implementation progress.

---

## 8. Risks and Unknowns

1. **Build state of agents/resolver is not confirmed compilable.** The latest commit message is "stage 04 complete" but `progress-stage-04.md` (unstaged) describes a BLOCKED state with `encodeToString` unresolved. The `kotlinx.ser.json` dependency was added, but Gradle cache corruption was the hypothesised root cause. Whether `just build-kt resolver` succeeds on a clean machine is unknown without running the build.

2. **K8s manifests for agents/resolver are staged for deletion** (`agents/resolver/k8s/base/deployment.yaml`, `kustomization.yaml`, `overlays/local/kustomization.yaml`). These three files exist in HEAD but are in the staging area as deletes. The current working-tree state has no k8s/ directory for the resolver.

3. **agents/erp-agent-2 working-tree deletion is uncommitted.** 163+ files for `agents/erp-agent-2` are deleted in the working tree but not staged for deletion. Whether `agents/golem` is a rename of `agents/erp-agent-2` or a separate evolution is unclear from git history alone (golem is untracked, erp-agent-2 is tracked but missing from disk).

4. **nlp-mcp `analyze` tool bug status.** Review-002 (`review-002.md`) identified a critical bug where `ops` argument is read as `jsonObject` instead of `jsonArray`. `tasks-review-002.md` shows 0/17 items checked. Whether this was fixed in `tools/nlp-mcp/src/main/kotlin/tools/nlp/mcp/Tools.kt` between Stage 02 commit and current HEAD cannot be confirmed without inspecting the current file content.

5. **Maven publishing for ai-platform shared libs is not configured.** The Kantheon architecture explicitly requires ai-platform to publish `shared/proto` and `shared/libs/kotlin/*` as Maven artifacts for consumption by Kantheon services. No `maven-publish` plugin or target repository URL exists anywhere in the codebase.

6. **The observability stack (Alloy, Tempo, Prometheus, Grafana) is not deployed** from this repository's manifests. Whether it runs in a separate ops cluster/configuration is not knowable from the codebase alone.

7. **Keycloak deployment is absent from deployment manifests.** `infra/whois` references a `KeycloakClient`, and the architecture requires Keycloak for auth. No Keycloak deployment manifest found in `deployment/local/`.

8. **NATS JetStream and SeaweedFS** are referenced in Pythia design and llm-gateway source but have no deployment manifests in this repo.

9. **Resolver REST endpoint `POST /v1/resolve`** status: tasks-review-003 marks D1 as done. The Main.kt imports suggest `handleRestResolve` exists, but it was not inspected. MCP tool is the primary interface.

---

## 9. Recommended Next Steps for Bora

1. **Verify agents/resolver builds.** Run `just build-kt resolver` on a clean machine (or `./gradlew :agents:resolver:build --no-build-cache`). If the Gradle cache corruption was the root cause of the build errors described in `progress-stage-04.md`, a clean build may resolve it. If `encodeToString` remains unresolved for proto-generated list types (`List<Token>`, etc.), a serializer annotation or `@Serializable` on the proto wrapper types is needed.

2. **Decide the fate of K8s manifests for agents/resolver.** The manifests are staged for deletion. Either un-stage them (keep them, fix the namespace/imagePullPolicy for local K3s), or commit the deletion and document that K8s deployment is deferred. The `fwd-stage-04.md` file lists creating K8s manifests as a remaining Priority: MEDIUM item.

3. **Fix the nlp-mcp `analyze` tool `ops` array bug.** Review-002 identified this as making the `analyze` MCP tool completely non-functional (`ops` read as `jsonObject` instead of `jsonArray`). 17 review items are unchecked in `tasks-review-002.md`. This must be fixed before Stage 04's resolver can call nlp-mcp in integration tests.

4. **Configure maven-publish for `shared/proto` and `shared/libs/kotlin/*`.** The Kantheon architecture (§10, locked 2026-05-11) requires ai-platform to publish these as Maven artifacts. Without this, Kantheon can never be bootstrapped. Add `maven-publish` plugin and a target repository (GitHub Packages, Azure Artifacts, or local file-based) to `shared/proto/build.gradle.kts` and the shared lib `build.gradle.kts` files.

5. **Add NFD diacritic-fold + inflection trimming to fuzzy-matcher** (G1). This is a prerequisite for the analytical agent (Phase A) and for Resolver's `fuzzyMatchSpans` node to work correctly on Czech inputs. The change is in `services/fuzzy-matcher/src/main/kotlin/cz/dfpartner/fuzzy/core/` — add `java.text.Normalizer.normalize(query, NFD)` before tokenisation, and add a suffix-stripping step.

6. **Align the justfile command names with CLAUDE.md documentation.** Either add aliases (`proto-all`, `sync-py`, `debug-tunnel`) or update CLAUDE.md to reflect the actual command names (`proto`, `py-sync-all`, and a new `debug-tunnel` recipe). The discrepancy causes confusion for any developer following CLAUDE.md.

7. **Commit or revert the agents/erp-agent-2 working-tree deletions.** The 163+ deleted files are a pending change that obscures the repository's state. If `agents/golem` replaces `agents/erp-agent-2`, stage the deletions and add `agents/golem` in a single "rename erp-agent-2 to golem" commit; or revert if erp-agent-2 should persist alongside golem.

---

## Appendix A — Commands Run

```bash
git rev-parse HEAD
git branch --show-current
git branch -a
git log --oneline HEAD~5..HEAD
git show --name-only HEAD
git ls-files agents/resolver/k8s/
git ls-files agents/resolver/src/test/
git ls-files --error-unmatch agents/erp-agent-2/pyproject.toml
git log -1 --format="%cd %s" --date=short -- agents/golem
git log -1 --format="%cd %s" --date=short -- agents/erp-agent-2
git log -1 --format="%cd" --date=short -- <service> (for each service)
git diff HEAD -- progress-stage-04.md
git show HEAD:agents/resolver/k8s/base/deployment.yaml
git status
find / -name "*.kt" (multiple targeted searches)
grep -r "..." (multiple targeted greps)
wc -l / head / tail (on various files)
ls (on all service directories)
cat (justfile, build.gradle.kts, settings.gradle.kts, task files)
```

---

## Appendix B — Files Read

| File | Purpose |
|---|---|
| `/Users/bora/Dev/ai-platform/CLAUDE.md` | Project conventions and commands |
| `/Users/bora/Dev/ai-platform/AGENTS.md` | Agent development guidelines |
| `/Users/bora/Dev/ai-platform/resolver-design.md` | Resolver design doc |
| `/Users/bora/Dev/ai-platform/progress-stage-04.md` | Stage 04 progress (working tree, pre-commit state) |
| `/Users/bora/Dev/ai-platform/fwd-stage-04.md` | Stage 04 forward plan |
| `/Users/bora/Dev/ai-platform/tasks-resolver-stage-01-infra-nlp.md` | Stage 01 tasks |
| `/Users/bora/Dev/ai-platform/tasks-resolver-stage-02-nlp-mcp.md` | Stage 02 tasks |
| `/Users/bora/Dev/ai-platform/tasks-resolver-stage-03-eval-compare.md` | Stage 03 tasks |
| `/Users/bora/Dev/ai-platform/tasks-resolver-stage-04-resolver-agent.md` | Stage 04 tasks (original plan) |
| `/Users/bora/Dev/ai-platform/tasks-stage-04-resolver-agent.md` | Stage 04 tasks (developer tracking) |
| `/Users/bora/Dev/ai-platform/tasks-resolver-stage-05-parallel-deployment.md` | Stage 05 tasks |
| `/Users/bora/Dev/ai-platform/tasks-resolver-stage-06-consumer-migration.md` | Stage 06 tasks |
| `/Users/bora/Dev/ai-platform/tasks-review-002.md` | Review 002 tasks (stages 01–03) |
| `/Users/bora/Dev/ai-platform/tasks-review-003.md` | Review 003 tasks (stage 04) |
| `/Users/bora/Dev/ai-platform/review-002.md` | Review 002 findings |
| `/Users/bora/Dev/ai-platform/review-003.md` | Review 003 findings |
| `/Users/bora/Dev/ai-platform/settings.gradle.kts` | Gradle project includes |
| `/Users/bora/Dev/ai-platform/justfile` | Build/deploy recipes |
| `/Users/bora/Dev/ai-platform/agents/resolver/build.gradle.kts` | Resolver Gradle config |
| `/Users/bora/Dev/ai-platform/agents/resolver/src/main/kotlin/agents/resolver/Main.kt` (partial) | Resolver entrypoint imports |
| `/Users/bora/Dev/ai-platform/agents/resolver/src/main/kotlin/agents/resolver/koog/ResolverGraph.kt` (grep) | Resolver graph nodes |
| `/Users/bora/Dev/ai-platform/services/fuzzy-matcher/src/main/kotlin/cz/dfpartner/fuzzy/core/Algorithms.kt` | Fuzzy matching algorithms |
| `/Users/bora/Dev/ai-platform/services/fuzzy-matcher/src/main/kotlin/cz/dfpartner/fuzzy/core/TokenBasedMatcher.kt` | Token-based matcher |
| `/Users/bora/Dev/ai-platform/tools/meta-mcp/src/main/kotlin/tools/meta/mcp/Tools.kt` (partial) | Meta MCP tools |
| `/Users/bora/Dev/ai-platform/infra/llm-gateway/build.gradle.kts` | LLM gateway build config |
| `/Users/bora/Dev/ai-platform/infra/nlp/src/nlp_service/pipeline/orchestrator.py` (grep) | NLP orchestrator |
| `/Users/bora/Dev/ai-platform/shared/libs/kotlin/data-formatter/src/...` (grep/file list) | Data formatter library |
| `/Users/bora/Dev/ai-platform/shared/proto/build.gradle.kts` | Shared proto build |
| `/Users/bora/Dev/kantheon/docs/v1/kantheon-architecture.md` | Kantheon architecture (§10) |
| `/Users/bora/Dev/kantheon/docs/v1/pythia/Pythia-v1-Design.md` (§6.1) | Pythia dependency table |
| `/Users/bora/Dev/golem/docs/aip-v1/Analytical Agent on V1.md` (§4, §5) | G1–G7 gap definitions |
