# The Librarian (DocWH) — Phased Implementation Plan

> **Scope.** From "the DocWH exists only as architecture + contracts" to "a wiki-structured document warehouse live in local K3s: assets stage to Seaweed, Pinakes pipelines compile them into an interlinked wiki on one Postgres, Kallimachos serves graph-primary cited retrieval to Golem/Pythia, and Kleio holds grounded NotebookLM conversations over marts." **Five phases, ~15 stages, ~95 tasks.**
>
> **Companions.** [`../../../architecture/kleio/architecture.md`](../../../architecture/kleio/architecture.md), [`../../../architecture/kleio/contracts.md`](../../../architecture/kleio/contracts.md), [`../../planning-conventions.md`](../../planning-conventions.md); seed `~/Dev/doc-store`; Karpathy *LLM Wiki*.
>
> **Testing.** Mocked unit/component only inside stages ([`planning-conventions.md`](../../planning-conventions.md) §4): MockK, in-memory `Port` fakes, Wiremock for Prometheus, mocked MCP/HTTP/gRPC clients. The integration suite (real pgvector/AGE, live Prometheus, OBO/Argos RLS, in-K3s e2e) is separate and does not gate stage DONE.
>
> **Arc position — Stream B (the Body).** **Stream-B remaining-work order (master-plan, set 2026-06-24): Fork P5 → Hebe → Kleio** — Kleio is the **3rd** (last) of the three remaining Body pushes. Independent of the Spine through Phase 4. **Phase 5 (Kleio agent)** takes two soft Spine dependencies: Themis P3 (the `KNOWLEDGE` intent — **met**, `themis/v0.2.0`) and an Iris notebook surface. **One hard external pre-flight:** Prometheus `EmbedText` (Phase 2). RAG payoff (`getContext`) is internally usable at Phase 2 and GA at Phase 4 — before any Spine dependency bites. Master-plan placement: §2 arc inventory (Stream B) + §5 timeline + §7.

## 1. Phase summary

| Phase | Goal — what deploys at the end | Stages | Est. |
|---|---|---|---|
| **P1 — warehouse core + stage** | `services/kallimachos` (Ktor+Exposed) on one Postgres: sources/parts/marts; one-tx mechanical ingestion (parsers ported); keyword `query` + `getById`. `services/pinakes` skeleton: assets stage to Seaweed + asset catalogue. | 1.1 / 1.2 / 1.3 | ~2 wk |
| **P2 — retrieval planes + graph-primary getContext** | pgvector + Prometheus embeddings; AGE plane (sources/parts + `CONTAINS`); `getContext` graph-led fusion over source parts. Internally usable RAG. | 2.1 / 2.2 / 2.3 | ~2 wk |
| **P3 — Pinakes pipelines + LLM wiki-compile** | Stage library + per-source pipeline DAGs + run/lineage; the compile stage (entity/concept pages, synthesis, links, global resolution, contradiction flags); corpus becomes a wiki; getContext leads with pages. `concept_ref` seam. | 3.1 / 3.2 / 3.3 | ~2.5 wk |
| **P4 — serving: MCP + identity + browse** | `tools/kallimachos-mcp` (`library.*`) + capability registration; OBO + Argos mart RLS; Golem/Pythia consume getContext (RAG GA); minimal wiki browse FE. | 4.1 / 4.2 | ~1.5 wk |
| **P5 — Kleio agent (NotebookLM)** | `agents/kleio` Koog graph: mart-scoped graph-primary grounded turns with citations + artifacts; `KNOWLEDGE` intent + Themis routing + Iris picker. | 5.1 / 5.2 / 5.3 | ~2.5 wk |

Critical path P1 → P2 → P3 → P4 → P5 (strictly sequential at phase granularity).

## 2. Pre-flight — before Phase 1

| Item | Status (2026-06-20) | Owner |
|---|---|---|
| `pinakes/kallimachos/kleio` package conventions | locked, contracts §1/§2/§8 | — |
| Kantheon PG carries `vector` + `age` extensions | **confirmed available** (Bora 2026-06-20) | infra |
| SeaweedFS S3 gateway reachable (`data-seaweedfs:8333`) | deployed (fabric-infra; Charon uses it) | infra |
| Prometheus `EmbedText` RPC | **open — P2 pre-flight** (additive, contracts §10); fallback `remote_http` | Prometheus owner |
| doc-store source to port (adapters/ingestion/parsers) | present | — |
| Exposed / pgvector-jdbc / AGE-jdbc / parsers / Koog versions pinned | Stage 1.1 T1 | — |

## 3. Phase 1 — warehouse core + stage

### Stage 1.1 — protos + Kallimachos skeleton + ported domain
**Tasks (6).** (1) Pin versions in `libs.versions.toml`. (2) Write `kallimachos.proto` + `pinakes.proto` per contracts §1/§2; `just proto`; bindings green (verify the second proto roots like charon). (3) `services/kallimachos` skeleton (Ktor + probes) following the charon `services/` template; CI wiring. (4) Port `ingestion/` from doc-store (`DocNode`, `{Text,Md,Html,Pdf}Handler`, `ParagraphSplitter`); strip Spring. (5) Tests first: per-format `*HandlerSpec` + `ParagraphSplitterSpec` (ported corpus). (6) Port `MetadataValue` + serializer; `MetadataSpec`.
**DONE.** Module compiles; parser suites green; pod starts (routes stubbed).

### Stage 1.2 — relational + full-text planes + one-tx ingestion + marts
**Tasks (7).** (1) Flyway `V1__sources_parts_notebooks.sql` + `V2__fulltext_tsv.sql` (GIN) per contracts §3. (2) Tests first: `ExposedRelationalAdapterSpec` (id allocation, source+part insert, global-unique ids). (3) Port `RelationalPort` + `PostgresFullTextAdapter` onto single-PG. (4) Tests first: `IngestionServiceSpec` — one DocNode fans out to relational + fulltext `Port` fakes in one transaction; rollback leaves nothing. (5) `IngestionService` (relational + fulltext); `POST /documents` (requires `notebook_id`). (6) `NotebookService` + routes + m:n `notebook_members`; owner from a fixture principal (real bearer P4). (7) `getById` + `POST /query` (keyword/metadata, mart-scoped) + `DocumentQueryServiceSpec`.
**DONE.** Ingest → keyword query round-trips on fakes; one-tx rollback proven.

### Stage 1.3 — Pinakes stage + asset catalogue + deploy
**Tasks (6).** (1) `services/pinakes` skeleton; `PinakesService` RPCs `RegisterAsset`/`ListAssets` (contracts §2). (2) `SeaweedAssetStore` (S3 SDK → `data-seaweedfs`); `AssetCatalog` persistence. (3) Tests first: `SeaweedAssetStoreSpec` (mocked S3 — put + key scheme) + `AssetCatalogSpec`. (4) A mechanical `RunPipeline` that does extract→chunk→`LoadApi` only (no embed/compile yet) — proves the stage→warehouse path; `RunnerSpec` over fakes. (5) `application.conf` + readiness (DB + extensions + Seaweed); k8s `base/`+`overlays/local/`; provision `kallimachos` DB. (6) Deploy; live smoke (stage a `~/Dev/doc-store/samples/*` asset → run → keyword query); tag.
**DONE.** Tags `kallimachos/v0.1.0`, `pinakes/v0.1.0`. **P1 DONE — staged ingestion + keyword warehouse live.**

## 4. Phase 2 — retrieval planes + graph-primary getContext

**Pre-flight.** Prometheus `EmbedText` (or `remote_http` fallback); AGE extension confirmed in `kallimachos` DB.

### Stage 2.1 — vector plane + Prometheus embeddings
**Tasks (6).** (1) Flyway `V3__doc_vectors_pgvector.sql` (`vector(N)` + ivfflat/hnsw; N = pipeline `EmbedConfig.dimensions`). (2) Tests first: `PrometheusEmbeddingsClientSpec` (Wiremock) — batch, dim assertion, error→pending. (3) `PrometheusEmbeddingsClient` (`EmbeddingsPort`, contracts §10); wire into the `EMBED` stage; `embedding_status` + backfill hook. (4) Tests first: `PgVectorAdapterSpec` (fake) — upsert keyed by `(part, model_id, model_version)`, KNN order, metadata filter. (5) Port `VectorPort` + `PgVectorAdapter`; `VectorRecall`. (6) `backfillEmbeddings` for PENDING parts; spec.
**DONE.** EMBED stage embeds via Prometheus; vector recall green.

### Stage 2.2 — Apache AGE plane (spike-gated)
**Tasks (6).** (1) **AGE spike (gate):** openCypher over JDBC (`ag_catalog`, `search_path`, `cypher()`); verdict in README — AGE adapter or adjacency-table fallback behind `GraphPort`. (2) Tests first: `AgeGraphAdapterSpec` (fake) — `CONTAINS` on ingest, node upsert, neighbour fetch; reuse doc-store `CypherGraphPort` DSL. (3) `AgeGraphAdapter` (new driver; ported DSL) per the verdict. (4) Wire `CONTAINS` (source→part) into the `LOAD` stage; fan-out now relational+fulltext+vector+graph in one tx. (5) Tests first: `GraphWalkSpec` — traverse from seed parts/sources along `CONTAINS`/links to depth `graph_hops`. (6) `GraphWalk` retrieval primitive.
**DONE.** Four-plane ingest atomic; graph walk green.

### Stage 2.3 — graph-primary getContext + fusion
**Tasks (6).** (1) Tests first: `HybridFusionSpec` — graph-led ranking with vector/keyword recall boost; cross-plane score normalisation; `lead` labelling. (2) `HybridFusion` + `retrieval/` assembly; `POST /getContext` returning `ContextChunk[]` with `Citation` (contracts §1/§5). (3) Tests first: `CitationMappingSpec` — `Citation` → Drilldown/provenance (contracts §5). (4) `min-score` NO_GROUNDING semantics + empty result. (5) `POST /findSimilar` (vector + graph boost) route. (6) Retrieval benchmark harness (`bench/`) — latency + candidate counts per plane on a reference mart; feeds P4 `cost_hints`.
**DONE.** Tag `kallimachos/v0.2.0`. **P2 DONE — graph-primary cited retrieval over source parts.**

## 5. Phase 3 — Pinakes pipelines + LLM wiki-compile

> The DocWH differentiator. The compile stage is LLM-driven (Prometheus) and batch/offline (never on the query path).

### Stage 3.1 — stage library + pipeline runner + per-source binding
**Tasks (6).** (1) Tests first: `StageLibrarySpec` + `RunnerSpec` — a DAG of stage fakes executes in order; per-stage `StageRecord`; PARTIAL/FAILED status; resumable. (2) `Pipeline`/`Stage`/`StageLibrary`/`Runner` (contracts §2); pipeline defs from YAML; per-source-feed binding. (3) `EmbedConfig` conformance check — registering a pipeline whose embed model disagrees with the corpus is a config error. (4) `RunPipeline`/`GetRun`/`GetLineage` RPCs + `Lineage` persistence. (5) Tests first: `LineageSpec` — asset→run→source/page ids. (6) Wire the existing extract/chunk/embed/load stages into the library (compile/link/resolve land in 3.2/3.3).
**DONE.** Named per-source pipelines run mechanical stages with lineage; embed conformance enforced.

### Stage 3.2 — the LLM compile + linking
**Tasks (7).** (1) Tests first: `WikiCompilerSpec` (Wiremock Prometheus) — source parts → ENTITY/CONCEPT/SUMMARY page drafts; prompt shaping; output parse + fallback. (2) `WikiCompiler` (`COMPILE` stage; prompts in `prompts/`); `Page` creation via `LoadPagesRequest`. (3) Tests first: `LinkStageSpec` — page→source `DERIVED_FROM`, page↔page `MENTIONS`/`ABOUT`/`RELATED`/`CONTRADICTS` edges. (4) `LINK` stage → AGE wiki edges. (5) Tests first: `EntityResolverSpec` — GLOBAL resolution against the corpus graph; new vs merged; `concept_ref` populated (wiki-local; `ariadne_qname` empty — §6 seam). (6) `RESOLVE` stage (conformed, global); `pinakes_entities_resolved_total`. (7) `getContext` seeding now prefers ENTITY/CONCEPT pages (graph truly leads); `HybridFusion` reweight + spec update.
**DONE.** A pipeline run compiles sources into linked wiki pages with globally-resolved entities.

### Stage 3.3 — compile hardening + deploy
**Tasks (5).** (1) Contradiction-flag pass (`CONTRADICTS` edges) + spec. (2) Compile token-budget + cost metrics + degrade-to-mechanical-links on compile failure (corpus still queryable). (3) Re-ingest/update semantics: a new source updates existing pages/links rather than duplicating (compounding); spec. (4) Deploy Pinakes; live smoke (stage → run full pipeline → wiki pages + links queryable via getContext). (5) Tag.
**DONE.** Tags `pinakes/v0.2.0`, `kallimachos/v0.3.0`. **P3 DONE — the corpus is a compiled wiki.**

## 6. Phase 4 — serving: MCP + identity + browse

### Stage 4.1 — kallimachos-mcp + registration
**Tasks (6).** (1) Tests first: `McpToolsSpec` — JSON↔HTTP fidelity per `library.*` (contracts §4), error + `messages` pass-through. (2) `tools/kallimachos-mcp` (Kotlin MCP SDK; zero logic). (3) The `library.*` tools incl. `getPage`/`traverse`/`getContext`. (4) `library.*:v1` `ToolCapability` manifests + heartbeat; visible in capabilities-mcp `list()`. (5) `cost_hints` from the S2.3 benchmark; `search_tags`. (6) Deploy + smoke.
**DONE.** `library.*` registered and callable.

### Stage 4.2 — OBO + Argos mart RLS + RAG consumers + browse
**Tasks (7).** (1) Tests first: `MartRlsSpec` — visibility predicate over fixture bearers; `PERMISSION_DENIED` before store touch; `kallimachos_mart_rls_denied_total`. (2) OBO bearer forwarding at the MCP edge; Argos `bearer` role source; store defence-in-depth scope. (3) `createNotebook`/`addToNotebook` ops-gated; mart membership writes; audit rows (security §4). (4) RAG-consumer proof: a fixture `getContext` from a mock Golem/Pythia client returns cited chunks under the caller bearer; cross-arc note in golem/pythia plans (no code there). (5) Minimal `frontends/kallimachos-browse` (Vue) — page view + link/graph traverse over `library.*`. (6) Security-note sign-off (cross-mart leakage). (7) Deploy; tag.
**DONE.** Tags `kallimachos-mcp/v0.1.0`, `kallimachos/v0.4.0`. **P4 DONE — RAG GA under RLS; wiki browsable.**

## 7. Phase 5 — Kleio agent (NotebookLM)

**Pre-flight.** `kallimachos-mcp` live (P4); Themis P3 routing (or land Kleio callable directly, Themis wiring as the final task); Prometheus chat reachable.

### Stage 5.1 — kleio proto + Koog graph + grounded turn
**Tasks (6).** (1) Write `kleio.proto` (contracts §8); `just proto`. (2) `agents/kleio` (Ktor + Koog; `KallimachosMcpClient` + `PrometheusClient`); mirror Golem bootstrap. (3) Tests first: `KleioStrategySpec` (Koog mock executor + mocked `getContext`) — `Scope→Retrieve→GroundedAnswer→Render` yields a `GroundedResponse` citing only retrieved nodes. (4) `GroundedAnswerNode` prompt (in `prompts/`) constrains synthesis to retrieved chunk ids; `RenderNode` drops uncited claims (contracts §5). (5) `NO_GROUNDING` → CALLOUT refusal. (6) `kleio_turns` persistence; `kleio` DB migration + provisioning.
**DONE.** A grounded mart turn renders cited `envelope/v1` blocks against mocks.

### Stage 5.2 — artifacts + capabilities registration
**Tasks (6).** (1) Tests first: `ArtifactNodeSpec` — SUMMARY/FAQ/TIMELINE/BRIEFING map-reduce over a mart → cited MARKDOWN/TABLE envelope. (2) `ArtifactNode` + request/response; `notebook_artifacts` persistence. (3) `AgentCapability` registration (`KNOWLEDGE_QA`, `[KNOWLEDGE]`, router copy + few-shots, contracts §9); heartbeat. (4) **Additive enum landing:** add `KNOWLEDGE_QA`/`KNOWLEDGE` to `capabilities/v1`; regen; confirm existing manifests unaffected. (5) Iris surface: notebook picker → `listNotebooks` (visible set) → bind `notebook_id` into context/handoff. (6) Deploy; smoke (direct turn + artifact).
**DONE.** Artifacts generate; Kleio registered + discoverable.

### Stage 5.3 — Themis routing + eval + ship
**Tasks (5).** (1) Themis `KNOWLEDGE` intent in `classifyIntentKind` + route-to-Kleio (cross-arc; counter-examples keep doc questions off Golem/Pythia). (2) Eval corpus (`eval/`): grounded-citation faithfulness, NO_GROUNDING honesty, mart-scope leakage negatives; eval gate. (3) E2E smoke Iris→Themis→Kleio (mocked where the Spine isn't live). (4) Observability (architecture §13) + Grafana + trace nesting. (5) Docs + kantheon-architecture cross-ref + master-plan status; tags.
**DONE.** Tag `kleio/v0.1.0`. **P5 DONE — the DocWH is a live constellation citizen.**

## 8. Master-plan placement (proposed — for `master-plan.md`)

- **Stream B (the Body).** Add §2 row: `Librarian/DocWH | B | 5 | kallimachos v0.1→v0.4 · pinakes v0.1→v0.2 · kallimachos-mcp v0.1.0 · kleio v0.1.0 | Planned`.
- **Hard external dep:** Prometheus `EmbedText` (P2) — additive RPC on a live forked service (Fork P2 done), not a new build.
- **Soft (P5 only):** Themis P3 (`KNOWLEDGE` intent); an Iris notebook surface.
- **New mergepoint "MK — Knowledge plane":** `getContext` for Golem/Pythia RAG at P4 exit (`kallimachos-mcp/v0.1.0`). Golem/Pythia plans gain an optional "RAG via `library.getContext`" note; not a hard v1 gate on them.
- **Scheduling:** P1–P4 any time (parallel with Charon/Metis/Midas, zero Spine dep). P5 after Themis P3 + an Iris picker — alongside/after Golem.

## 9. Open questions / Bora-owned content

| Item | Blocking | Note |
|---|---|---|
| Prometheus `EmbedText` owner + model/dim | P2 pre-flight | pick the multilingual model; pin pgvector dim = `EmbedConfig.dimensions` |
| The handful of v1 pipelines (feeds, chunkers, prompts) | P3 | the main content task — which source feeds, per-feed chunk + compile config |
| Pinakes state: own DB vs `kallimachos` DB | P3 S3.1 | lean: own small schema in the one PG (assets/runs/lineage) |
| Compile prompt set (entity/concept/summary synthesis) | P3 S3.2 | Bora-owned content; eval'd on a fixture corpus |
| Mart curation governance (who creates marts; default `visibility_roles`) | P4 | `kantheon-domain-<x>` convention |
| AGE vs adjacency-table fallback | P2 S2.2 | spike-gate; fallback behind `GraphPort` |

## 10. Out of scope (v1.x triggers)

- **Write-back loop** — Kleio filing good answers as wiki pages (Karpathy's compounding-through-conversation). v1 compounds only through Pinakes ingestion.
- **User-facing mart editing** (add/remove sources interactively) — v1 mart curation is ops/admin.
- **Ariadne merge** — only the `concept_ref` seam + additive resolver source land here (architecture §12); the unified conceptual model is the far-future milestone.
- Polyglot backends wired (Qdrant/OpenSearch/Neo4j/Redis/Cosmos) — behind the Ports, documented, not v1.
- Multimodal (image/diagram embeddings); per-type embedding models (rejected, architecture §11).
- Binary/asset storage beyond Seaweed staging; OCR for scanned PDFs.
- Streaming token-by-token Kleio responses (v1 streams block-by-block like Golem).

## 11. Phase progression checklist

- [ ] **1.1** protos + Kallimachos skeleton + ported parsers.
- [ ] **1.2** relational+fulltext planes + one-tx ingestion + marts + keyword query.
- [ ] **1.3** Pinakes stage + asset catalogue + deploy. **P1 — `kallimachos/v0.1.0` + `pinakes/v0.1.0`.**
- [ ] **2.1** pgvector + Prometheus embeddings.
- [ ] **2.2** AGE plane (spike-gated) + graph walk.
- [ ] **2.3** graph-primary getContext + fusion. **P2 — `kallimachos/v0.2.0`.**
- [ ] **3.1** stage library + pipeline runner + per-source binding.
- [ ] **3.2** LLM compile + linking + global entity resolution (+ concept_ref seam).
- [ ] **3.3** compile hardening + deploy. **P3 — `pinakes/v0.2.0` + `kallimachos/v0.3.0`.**
- [ ] **4.1** kallimachos-mcp + registration.
- [ ] **4.2** OBO + Argos mart RLS + RAG consumers + browse. **P4 — `kallimachos-mcp/v0.1.0` + `kallimachos/v0.4.0`.**
- [ ] **5.1** kleio proto + Koog graph + grounded turn.
- [ ] **5.2** artifacts + capabilities registration.
- [ ] **5.3** Themis routing + eval + ship. **P5 — `kleio/v0.1.0`.**

---

*Plan owner: Bora. DocWH arc planned 2026-06-20. Per-stage task lists at `docs/implementation/v1/kleio/tasks-p<n>-s<n.m>-*.md` after review.*
