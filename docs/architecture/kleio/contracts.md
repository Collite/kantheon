# The Librarian (DocWH) — Wire Contracts (Pinakes · Kallimachos · Kleio)

> **Companions.** [`architecture.md`](./architecture.md), [`../../implementation/v1/kleio/plan.md`](../../implementation/v1/kleio/plan.md).
>
> **Authority.** Source of truth for `org.tatrman.pinakes.v1` (pipelines/catalogue/lineage), `org.tatrman.kallimachos.v1` (corpus/wiki/retrieval), `org.tatrman.kantheon.kleio.v1` (the agent); the wiki node+edge model; the stage library; the `library.*` MCP surface; the mart/RLS model; the `concept_ref` Ariadne seam; and the additive `capabilities/v1` enums + Prometheus `EmbedText`.
>
> **Conventions inherited.** Rule 6 (`repeated org.tatrman.kantheon.common.v1.ResponseMessage messages = 99;` on every response). Rule 7 (function-call args ride as `string argsJson`, camelCase). Protobuf is the source of truth even where the wire is REST (CI tests REST against the proto).

## 1. Proto package `org.tatrman.kallimachos.v1` (the warehouse)

File: `shared/proto/src/main/proto/org/tatrman/kallimachos/v1/kallimachos.proto`. Platform-service root (Kotlin source root `org.tatrman.kallimachos.*`). The store speaks REST+JSON at its HTTP surface (ported from doc-store); these messages are the proto truth those JSON shapes are tested against. A **read** surface (search/browse/retrieval) is public via `kallimachos-mcp`; a **`LoadApi`** write surface is internal, Pinakes-only.

```proto
syntax = "proto3";
package org.tatrman.kallimachos.v1;
import "org/tatrman/kantheon/common/v1/response_message.proto";

// ── Corpus nodes ────────────────────────────────────────────────────────
message Source {                         // an ingested document (faithful, citeable)
  int64 id           = 1;
  string asset_ref   = 2;                // Seaweed stage key (provenance to the raw asset)
  string mime_type   = 3;
  string title       = 4;
  map<string, MetadataValue> metadata = 5;
  string created_at  = 6;
  EmbeddingStatus embedding_status = 7;
}
message Part {                           // a chunk of a Source
  int64 id           = 1;
  int64 source_id    = 2;
  int32 idx          = 3;
  string kind        = 4;                // "paragraph" (v1) | "page" | ...
  string content_text = 5;
  map<string, MetadataValue> metadata = 6;
}
message Page {                           // an LLM-authored wiki page (Pinakes compile)
  int64 id           = 1;
  PageKind kind      = 2;                // ENTITY | CONCEPT | SUMMARY | OVERVIEW
  string title       = 3;
  string content_md  = 4;                // markdown body
  optional ConceptRef concept_ref = 5;  // §6 — Ariadne bridge seam (null in v1)
  repeated int64 derived_from_parts = 6;// provenance: source parts this page compiled from
  string updated_at  = 7;
}
enum PageKind { PAGE_KIND_UNSPECIFIED = 0; ENTITY = 1; CONCEPT = 2; SUMMARY = 3; OVERVIEW = 4; }
enum EmbeddingStatus { EMBEDDING_STATUS_UNSPECIFIED = 0; OK = 1; PENDING = 2; }

// Bridge currency (§6). Shaped to align with common/v1.EntityBinding; able to
// hold an Ariadne qname later. v1: type+label local to the wiki; ariadne_qname empty.
message ConceptRef {
  string entity_type   = 1;             // "customer", "concept", ...
  string entity_id     = 2;             // wiki-local id at v1
  string display_label = 3;
  string ariadne_qname = 4;             // FILLED by the bridge (v1.x); empty in v1
}

// ── Wiki edges (AGE) ────────────────────────────────────────────────────
enum EdgeKind {
  EDGE_KIND_UNSPECIFIED = 0;
  CONTAINS      = 1;   // Source → Part (structural)
  DERIVED_FROM  = 2;   // Page → Source/Part (provenance)
  MENTIONS      = 3;   // Page → Entity/Page
  ABOUT         = 4;   // Page → Concept
  RELATED       = 5;   // Page ↔ Page
  SAME_AS       = 6;   // Page ↔ Page (dedupe / cross-graph bridge anchor)
  CONTRADICTS   = 7;   // Page ↔ Page (compile-flagged)
}

message MetadataValue { oneof kind { string single = 1; StringList list = 2; } }
message StringList { repeated string values = 1; }

// ── Notebooks (marts) ───────────────────────────────────────────────────
message Notebook {
  string id            = 1;
  string display_name  = 2;
  string owner_user_id = 3;
  repeated string visibility_roles = 4; // empty == owner-only (PD-8; security §3)
  int64 member_count   = 5;             // corpus nodes in this mart
  string created_at    = 6;
}

// ── Retrieval ───────────────────────────────────────────────────────────
message QuerySpec {                      // keyword/metadata search
  string notebook_id = 1;               // MANDATORY scope (RLS); "*" admin-only
  optional string text = 2;
  repeated string keywords = 3;
  map<string, MetadataValue> metadata_filter = 4;
  int32 limit = 5;                       // default 10
}
message Hit { int64 id = 1; string kind = 2; double score = 3; string snippet = 4;
              map<string, MetadataValue> metadata = 5; }

// getContext — the graph-primary, citation-bearing RAG primitive.
message ContextRequest {
  string notebook_id = 1;
  string query       = 2;
  int32 k            = 3;                // default 8
  int32 graph_hops   = 4;               // wiki-graph walk depth (default 2)
  bool vector_boost  = 5;               // recall booster (default true)
}
message ContextChunk {
  int64 part_id      = 1;
  int64 source_id    = 2;
  optional int64 page_id = 3;           // set when the chunk came via a wiki page
  string text        = 4;
  double score       = 5;
  RetrievalLead lead = 6;               // how it surfaced (graph-led vs boosted)
  Citation citation  = 7;
}
enum RetrievalLead { RETRIEVAL_LEAD_UNSPECIFIED = 0; GRAPH = 1; VECTOR = 2; KEYWORD = 3; }
message Citation {                       // maps 1:1 onto envelope BlockProvenance+Drilldown (§5)
  int64 source_id = 1; int64 part_id = 2; optional int64 page_id = 3;
  string title = 4; string locator = 5;          // "¶12" / "p.3"
  string source_ref = 6;                          // "kallimachos://{notebook}/{source}/{part}"
}

// ── Browse (wiki frontend + agents) ─────────────────────────────────────
message GetPageRequest { int64 page_id = 1; string notebook_id = 2; }
message TraverseRequest { int64 from_node_id = 1; repeated EdgeKind edges = 2; int32 hops = 3; string notebook_id = 4; }
message TraverseResult { repeated GraphNode nodes = 1; repeated GraphEdge edges = 2;
                         repeated org.tatrman.kantheon.common.v1.ResponseMessage messages = 99; }
message GraphNode { int64 id = 1; string kind = 2; string title = 3; }
message GraphEdge { int64 from = 1; int64 to = 2; EdgeKind kind = 3; double weight = 4; }

// ── Internal write surface — Pinakes-only (cluster-internal, no MCP) ─────
message LoadSourceRequest { Source source = 1; repeated Part parts = 2; }
message LoadPagesRequest  { repeated Page pages = 1; repeated GraphEdge links = 2; }
message LoadVectorsRequest{ repeated PartVector vectors = 1; }
message PartVector { int64 part_id = 1; repeated float vector = 2; string model_id = 3; string model_version = 4; }
```

## 2. Proto package `org.tatrman.pinakes.v1` (pipelines + catalogue + lineage)

File: `shared/proto/src/main/proto/org/tatrman/pinakes/v1/pinakes.proto`. The **write path** — stage raw assets, run pipelines, track lineage. Operator/loader-driven at v1 (no MCP).

```proto
syntax = "proto3";
package org.tatrman.pinakes.v1;
import "org/tatrman/kantheon/common/v1/response_message.proto";

service PinakesService {
  // Stage
  rpc RegisterAsset (RegisterAssetRequest) returns (Asset);   // upload/record a raw asset in Seaweed
  rpc ListAssets    (ListAssetsRequest)    returns (ListAssetsResponse);
  // Pipelines
  rpc ListPipelines (ListPipelinesRequest) returns (ListPipelinesResponse);
  rpc GetPipeline   (GetPipelineRequest)   returns (Pipeline);
  rpc RunPipeline   (RunPipelineRequest)   returns (PipelineRun);   // ingest asset(s) through a pipeline
  rpc GetRun        (GetRunRequest)        returns (PipelineRun);
  // Lineage / catalogue
  rpc GetLineage    (GetLineageRequest)    returns (Lineage);       // asset → run → corpus entries
}

message Asset {
  string id          = 1;
  string asset_ref   = 2;               // Seaweed key (data-seaweedfs)
  string source_feed = 3;               // logical feed the asset belongs to (binds the pipeline)
  string mime_type   = 4;
  string original_name = 5;
  string staged_at   = 6;
}

message Pipeline {
  string id          = 1;
  string display_name = 2;
  string source_feed = 3;               // per-source binding (architecture §7)
  repeated Stage stages = 4;            // ordered DAG; head varies, tail conformed
  EmbedConfig embed  = 5;               // CONFORMED across pipelines feeding one corpus (§ below)
}
message Stage {
  string id   = 1;
  StageKind kind = 2;
  string config_json = 3;               // Rule 7 — stage-specific params (chunker size, prompt ref, …)
}
enum StageKind {
  STAGE_KIND_UNSPECIFIED = 0;
  EXTRACT  = 1;   // raw asset → text (parsers)            [head]
  CLASSIFY = 2;   // detect type/branch                    [head]
  CHUNK    = 3;   // parts (per-type strategy)             [head]
  EMBED    = 4;   // one model, conformed                  [tail]
  COMPILE  = 5;   // LLM: entity/concept pages + synthesis [tail]
  LINK     = 6;   // cross-references → wiki edges          [tail]
  RESOLVE  = 7;   // GLOBAL entity resolution               [tail]
  LOAD     = 8;   // Kallimachos LoadApi                    [tail]
}
// The embedding model is a CONFORMED CORPUS DIMENSION: declared per pipeline,
// but all pipelines feeding one corpus MUST agree (architecture §11). Disagreement
// is a config error at registration, not two coexisting spaces.
message EmbedConfig { string model_id = 1; int32 dimensions = 2; string model_version = 3; }

message PipelineRun {
  string id          = 1;
  string pipeline_id = 2;
  repeated string asset_ids = 3;
  RunStatus status   = 4;
  repeated StageRecord stage_records = 5;
  string started_at  = 6; string finished_at = 7;
  repeated org.tatrman.kantheon.common.v1.ResponseMessage messages = 99;
}
enum RunStatus { RUN_STATUS_UNSPECIFIED = 0; RUNNING = 1; SUCCEEDED = 2; FAILED = 3; PARTIAL = 4; }
message StageRecord { string stage_id = 1; StageKind kind = 2; string status = 3;
                      int64 items_in = 4; int64 items_out = 5; int64 latency_ms = 6; double cost_usd = 7; optional string error = 8; }

message Lineage {                        // catalogue: what came from where, via which run
  string asset_id = 1;
  repeated string run_ids = 2;
  repeated int64 source_ids = 3;        // Kallimachos Sources produced
  repeated int64 page_ids = 4;          // wiki Pages touched
}
```

## 3. The wiki/corpus model (single Postgres, four planes)

Two databases on the one Kantheon PG (topology §7.1). Flyway migrations under each module.

**`kallimachos` DB:**
```
notebooks(id pk, display_name, owner_user_id, visibility_roles text[], created_at)
sources(id bigserial pk, asset_ref, mime_type, title, metadata jsonb, embedding_status, created_at)
parts(id bigserial pk, source_id fk, idx int, kind, content_text,
      content_tsv tsvector,                      -- FULL-TEXT plane (GIN)
      metadata jsonb)
pages(id bigserial pk, kind, title, content_md, concept_ref jsonb null, updated_at)  -- LLM wiki pages
doc_vectors(part_id fk, embedding vector(N), model_id, model_version)                -- VECTOR plane (pgvector)
notebook_members(notebook_id fk, node_kind, node_id)   -- m:n mart membership (sources/pages in a mart)
-- GRAPH plane: Apache AGE graph 'kallimachos_graph' in the SAME DB:
--   (:Source{id})-[:CONTAINS{idx}]->(:Part{id})
--   (:Page{id})-[:DERIVED_FROM]->(:Source|:Part)
--   (:Page)-[:MENTIONS|:ABOUT|:RELATED|:SAME_AS|:CONTRADICTS]->(:Page|:Entity)
request_log(id, node_kind, node_id, action, actor_user_id, notebook_id, at)          -- audit (security §4)
```
IDs are DB-generated, globally unique across sources + parts + pages. Ingestion fan-out (relational + tsvector + pgvector + AGE) is **one transaction** — the only non-atomic edge is the embedding call (`embedding_status = PENDING` + backfill). The `concept_ref jsonb` column is the §6 seam — present, null at v1.

**`kleio` DB:**
```
kleio_turns(turn_id pk, session_id, notebook_id, question, status, envelopes jsonb,
            sources_used jsonb, resource_usage jsonb, created_at)
notebook_artifacts(artifact_id pk, notebook_id, kind, envelope jsonb, sources_used jsonb,
                   created_by_user_id, created_at)
```
Conversation memory is Iris's job (Golem rule); Kleio persists one turn row + generated artifacts.

**Pinakes state** (its own small schema or the `kallimachos` DB — TBD plan §8): `assets`, `pipelines`, `pipeline_runs`, `lineage`.

## 4. `kallimachos-mcp` tool surface (read/RAG/browse — Phase 4)

Streamable-HTTP at `POST /mcp`; JSON mirrors of §1; **every call carries the caller OBO bearer** (forwarded for RLS, §7). Zero logic.

| Tool | Store endpoint | Purpose | Primary caller |
|---|---|---|---|
| `library.getContext` | `POST /getContext` | graph-primary, cited chunks for RAG | **Golem, Pythia, Kleio** |
| `library.search` | `POST /query` | keyword/metadata hits | Kleio, ops |
| `library.findSimilar` | `POST /findSimilar` | vector recall | Kleio |
| `library.getPage` | `GET /pages/{id}` | a wiki page (markdown + concept_ref) | browse, Kleio |
| `library.traverse` | `POST /traverse` | walk wiki links from a node | browse, Kleio |
| `library.getSource` | `GET /sources/{id}` | a source/part (Drilldown) | Kleio |
| `library.listNotebooks` | `GET /notebooks` | marts visible to the caller | Iris picker |
| `library.createNotebook` / `addToNotebook` | `POST /notebooks…` | mart curation (write — v1: ops/admin) | Iris/ops |

Owns the `library.*:v1` `ToolCapability` manifests + capabilities-mcp heartbeat. `cost_hints` from the P4 retrieval benchmark. (Pinakes's pipeline surface is **not** MCP at v1 — it's operator gRPC/HTTP.)

## 5. Citation ↔ envelope mapping (the grounding contract)

`ContextChunk.citation` maps deterministically onto `envelope/v1` when Kleio renders a cited `Block`:

| `Citation` field | envelope/v1 target |
|---|---|
| `source_ref` (`kallimachos://…`) | `Block.provenance.source_tables[]` (PD-9) |
| `source_id`/`part_id`/`page_id` | `Drilldown.arg_mapping {sourceId, partId, pageId}`; `Drilldown.scope="point"`, `source="citation"` |
| `title` + `locator` | `Drilldown.display` ("Title — ¶12") |
| producing agent | `Block.provenance.producing_agent_id = "kleio"`; `computed_at` at render |

`RenderNode` drops any model-emitted citation whose `part_id`/`page_id` is **not** in the turn's retrieved set — provenance points only at what was retrieved. Absence renders "provenance unavailable", never an error (PD-9).

## 6. Concept identity & the Ariadne bridge seam

- Every Kallimachos **`Page` of kind ENTITY/CONCEPT may carry `concept_ref`** (§1 `ConceptRef`), shaped to align with `common/v1.EntityBinding` and to hold an Ariadne `qname` later. **v1: wiki-local (`entity_type`+`entity_id`+label); `ariadne_qname` empty.**
- The **global `RESOLVE` stage** (Pinakes, §7 architecture) populates `concept_ref` within the wiki at v1. A **future additive grounding source** (flagged like Argos `bearer | whois`) also calls `Ariadne.Search`/`GetObject` to fill `ariadne_qname` and write a cross-graph `SAME_AS` edge — no Ariadne dependency in v1.
- When Kleio later emits resolved entities, it does so as `common/v1.EntityBinding` (the constellation currency) so Golem/Pythia act on them via the existing `HandoffContext` — the docs→data drilldown. **The merge of the two graphs is out of scope (architecture §12).**

## 7. Mart RLS model

- **Scope mandatory** on every retrieval (`QuerySpec.notebook_id` / `ContextRequest.notebook_id`); no un-scoped search at v1.
- **Visibility:** read mart `N` iff `N.owner_user_id == caller.user_id` **OR** `N.visibility_roles ∩ caller_roles ≠ ∅`; roles from the forwarded **OBO bearer** (Argos `bearer`, security §3.6), never service identity.
- **Enforcement** at the `kallimachos-mcp` edge before the store is touched; store filters by the scoped mart (defence in depth). `kallimachos_mart_rls_denied_total`; audit row per ingest/retrieval (security §4).
- **v1 read-only except pipelines:** `createNotebook`/`addToNotebook` are ops/admin at v1; user-facing mart editing is v1.x.

## 8. Proto package `org.tatrman.kantheon.kleio.v1` (the agent)

Mirrors `golem/v1` — trusts Themis upstream, emits `envelope/v1`.

```proto
syntax = "proto3";
package org.tatrman.kantheon.kleio.v1;
import "org/tatrman/kantheon/envelope/v1/envelope.proto";
import "org/tatrman/kantheon/themis/v1/themis.proto";
import "org/tatrman/kantheon/common/v1/handoff.proto";
import "org/tatrman/kantheon/common/v1/response_message.proto";

message KleioRequest {
  string id        = 1;                                          // == Iris turn_id
  string question  = 2;
  string notebook_id = 3;                                        // bound mart
  org.tatrman.kantheon.themis.v1.Resolution resolved_intent = 4; // from Themis
  KleioContext context = 5;
  Caller caller    = 6;
}
message KleioContext {
  repeated org.tatrman.kantheon.envelope.v1.EntityContextSnapshot entity_context = 1;
  repeated ConversationTurn conversation_excerpt = 2;
  string locale = 3;
  optional org.tatrman.kantheon.common.v1.HandoffContext handoff = 4;   // PD-1 assembly
}
message ConversationTurn { string turn_id = 1; string question = 2; optional string answer_summary = 3; }
message Caller { string user_id = 1; string tenant_id = 2; string correlation_id = 3; }

message GroundedResponse {
  string id = 1; string request_id = 2;
  repeated org.tatrman.kantheon.envelope.v1.FormatEnvelope envelopes = 3;  // each cited block carries BlockProvenance
  repeated SourceUse sources_used = 4;
  Status status = 5;
  ResourceUsage resource_usage = 6;
  string finalised_at = 7;
  repeated org.tatrman.kantheon.common.v1.ResponseMessage messages = 99;
}
enum Status { STATUS_UNSPECIFIED = 0; STATUS_DONE = 1; STATUS_FAILED = 2; STATUS_CLARIFICATION = 3; STATUS_NO_GROUNDING = 4; }
message SourceUse { int64 source_id = 1; int64 part_id = 2; optional int64 page_id = 3; string title = 4; double score = 5; }
message ResourceUsage { double total_usd = 1; int64 tokens_in = 2; int64 tokens_out = 3; int32 retrieval_count = 4; int64 total_latency_ms = 5; }

message ArtifactRequest { string notebook_id = 1; ArtifactKind kind = 2; optional string focus = 3; Caller caller = 4; }
enum ArtifactKind { ARTIFACT_KIND_UNSPECIFIED = 0; SUMMARY = 1; FAQ = 2; TIMELINE = 3; BRIEFING = 4; }
message ArtifactResponse { string artifact_id = 1; org.tatrman.kantheon.envelope.v1.FormatEnvelope envelope = 2;
                          repeated SourceUse sources_used = 3;
                          repeated org.tatrman.kantheon.common.v1.ResponseMessage messages = 99; }
```
`STATUS_NO_GROUNDING` = honest refusal: nothing in-mart above threshold → a CALLOUT block, no fabricated citations.

## 9. Additive `capabilities/v1` enums (coordinate with Themis)

```proto
// enum AgentKind — add:  KNOWLEDGE_QA = 4;   // Kleio
// enum IntentKind — add: KNOWLEDGE    = 5;   // document/notebook questions → Kleio
```
Kleio's `AgentCapability`: `agent_kind = KNOWLEDGE_QA`, `agent_id = "kleio"`, `intent_kinds_supported = [KNOWLEDGE]`, `non_routable = false`, `visibility_roles` per the mart-set, `capability_refs = ["library.getContext:v1", …]`, router copy + few-shots. Both additive (proto3-safe); land with Themis P3.

## 10. Additive Prometheus RPC — `EmbedText` (Phase 2 pre-flight)

```proto
// service PrometheusService — add:
rpc EmbedText (EmbedRequest) returns (EmbedResponse);
message EmbedRequest  { string model = 1; repeated string inputs = 2; }   // batched
message EmbedResponse { repeated Embedding embeddings = 1; string model = 2; int32 dimensions = 3; }
message Embedding     { repeated float vector = 1; }
```
`kallimachos`'s `EmbeddingsPort` → `PrometheusEmbeddingsClient` over this. `dimensions` pinned to the `doc_vectors` column and to the pipeline `EmbedConfig` (conformed). Owner coordination — plan §8. Fallback: doc-store `RemoteHttpEmbeddingsClient`.

## 11. Configuration & build

```
pinakes.{grpc.port=7281, http.port=7280}
pinakes.seaweed.{endpoint=data-seaweedfs:8333, bucket=docwh-stage, access-key, secret-key}
pinakes.prometheus.{host, port}            # compile + embed
pinakes.kallimachos.{host, port}           # LoadApi target
pinakes.pipelines.path                     # pipeline definitions (YAML)
kallimachos.{http.port=7261, probe.port=7260}
kallimachos.db.url                         # jdbc:postgresql://…/kallimachos  (vector + age extensions)
kallimachos.storage.{relational=postgres, fulltext=postgres, vector=postgres, graph=age}  # single-PG profile
kallimachos.retrieval.{graph-hops=2, k=8, graph-weight, min-score}
kallimachos-mcp.{port=7262, kallimachos-http.{host,port}, capabilities-mcp.{host,port}}
kleio.{port=7270, db.url, kallimachos-mcp.{host,port}, prometheus.{host,port}}
kleio.retrieval.{k=8, min-score}           # NO_GROUNDING threshold
```

Tags: `kallimachos/v0.1.0` (P1 store), `pinakes/v0.1.0` (P1 catalogue skeleton), `kallimachos/v0.2.0` (P2 retrieval planes + getContext), `pinakes/v0.2.0` + `kallimachos/v0.3.0` (P3 pipelines + compile/wiki), `kallimachos-mcp/v0.1.0` + `kallimachos/v0.4.0` (P4 serving + RLS), `kleio/v0.1.0` (P5 agent). Branches `feat/docwh-p<n>-s<n.m>-<short>`. The `capabilities/v1` enum additions + Prometheus `EmbedText` are tracked as cross-arc contract changes (plan §6).

---

*Contracts owner: Bora. Locked structure 2026-06-20 (DocWH arc planning). Field-level changes update this doc first.*
