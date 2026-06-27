# Stage 1.1 — protos + Kallimachos skeleton + ported domain

> **Phase 1, Stage 1.1.** Branch `feat/docwh-p1-s1.1-skeleton`.
>
> **Reads with.** [`tasks-p1-overview.md`](./tasks-p1-overview.md), [`plan.md`](./plan.md) §3 Stage 1.1, [`../../../architecture/kleio/contracts.md`](../../../architecture/kleio/contracts.md) §1 (`kallimachos.proto`) + §2 (`pinakes.proto`), [`../../../architecture/kleio/architecture.md`](../../../architecture/kleio/architecture.md) §4 (module map) + §6 (corpus model), [`../charon/tasks-p1-s1.1-skeleton.md`](../charon/tasks-p1-s1.1-skeleton.md) (the `services/` skeleton precedent — second non-`kantheon.*` proto root, exactly like ariadne/charon), [`../../../../EXAMPLES.md`](../../../../EXAMPLES.md) §1 (Ktor bootstrap).

## Goal

Both service protos compile; `services/kallimachos` exists as a Ktor module with probes (routes stubbed); the doc-store **ingestion domain is ported** (parsers + splitter + metadata) framework-agnostically and its parser suites are green against the doc-store corpus. Conventions for the DocWH `services/` modules settle here.

## Tasks (6)

- [ ] **T1 — Pin versions in `libs.versions.toml`.**

  Add (or confirm) pins: Exposed, Flyway, `pgvector` JDBC helper, AGE/openCypher JDBC driver (the pin lands now; the adapter is P2 — record it deferred-but-pinned), the parsers **jsoup** / **flexmark** (md) / **PDFBox** (pdf), Kotlin MCP SDK (P4), Koog (P5 — already pinned for Golem). Catalog drift is a planning bug (AGENTS.md §5) — pin the AGE driver here even though P2 wires it. Note `vector`/`age` are PG *extensions* (infra), not JVM deps.

  Acceptance: `./gradlew help` clean; no module changes yet.

- [ ] **T2 — Write `kallimachos.proto` + `pinakes.proto` (contracts §1/§2); `just proto`.**

  Author `shared/proto/src/main/proto/org/tatrman/kallimachos/v1/kallimachos.proto` and `.../org/tatrman/pinakes/v1/pinakes.proto` **byte-for-byte** from contracts §1/§2 (Source/Part/Page/ConceptRef, EdgeKind, Notebook, QuerySpec/Hit, ContextRequest/ContextChunk/Citation, browse messages, the internal `Load*Request` write surface; PinakesService RPCs, Asset/Pipeline/Stage/StageKind/EmbedConfig/PipelineRun/StageRecord/Lineage). Rule 6 (`messages = 99`) + Rule 7 (`config_json`/`args_json`) inherited. Import `org/tatrman/kantheon/common/v1/response_message.proto`.

  These are the **third + fourth** non-`kantheon.*` proto roots after ariadne/charon — verify they codegen at the right tree depth like those precedents. Run `just proto`.

  **Tests first:** `KallimachosProtoSpec` + `PinakesProtoSpec` — round-trip a `Page` (with `concept_ref`), a `ContextChunk` (with `Citation` + `RetrievalLead`), a `PipelineRun` (with `StageRecord`), an `EmbedConfig`.

  Acceptance: both protos compile; round-trip specs green via `just test-kt shared:proto`.

- [ ] **T3 — `services/kallimachos` skeleton (Ktor + probes), CI wiring.**

  Create the module following the charon `services/` template (architecture §4): `App.kt` (≤45 lines, EXAMPLES.md §1b — OTel init + probes), `application.conf` (HOCON keys from contracts §11: `kallimachos.http.port=7261`, `probe.port=7260`, `db.url`, `storage.{relational,fulltext,vector,graph}`, `retrieval.{graph-hops,k,graph-weight,min-score}`), `logback.xml`, `k8s/{base,overlays/local}` (`imagePullPolicy: Never`). Kotlin source root `org.tatrman.kallimachos.*`. HTTP route handlers (`SearchRoutes`/`BrowseRoutes`/`NotebookRoutes`/`LoadRoutes`) stubbed `NOT_IMPLEMENTED`. `include(":services:kallimachos")` in root `settings.gradle.kts`.

  Acceptance: module compiles; pod starts; `/health` 200, `/ready` 503 (no DB yet); stubbed routes return `NOT_IMPLEMENTED`.

- [ ] **T4 — Port `ingestion/` from doc-store; strip Spring.**

  Port `DocNode`, the per-format handlers (`TextHandler`, `MdHandler`, `HtmlHandler`, `PdfHandler`), and `ParagraphSplitter` into `services/kallimachos/src/main/kotlin/org/tatrman/kallimachos/ingestion/` (architecture §4). The code is framework-agnostic — **strip Spring annotations/DI**, wire as plain Kotlin constructors. Keep parser libraries: jsoup (html), flexmark (md), PDFBox (pdf), plain (txt).

  Acceptance: ported sources compile with no Spring on the classpath.

- [ ] **T5 — Tests first: per-format handler + splitter specs (ported corpus).**

  Port the doc-store parser tests as `TextHandlerSpec`, `MdHandlerSpec`, `HtmlHandlerSpec`, `PdfHandlerSpec`, `ParagraphSplitterSpec`, using the **doc-store test corpus** as the parity oracle (risks note: "Spring→Ktor rewrite drops a doc-store behaviour" — the corpus is the guard). Each format → `DocNode` → expected parts.

  Acceptance: all parser suites green; behaviour matches the doc-store baseline.

- [ ] **T6 — Port `MetadataValue` + serializer; `MetadataSpec`.**

  Port the `MetadataValue` model (the `oneof single | list` from contracts §1) + its JSON serializer (jsonb-bound at P1.2). `MetadataSpec` round-trips single + list values.

  Acceptance: `MetadataSpec` green. PR `[docwh-p1-s1.1] protos + kallimachos skeleton + ported domain`.

## DONE — Stage 1.1

- [ ] All six tasks checked.
- [ ] `kallimachos.proto` + `pinakes.proto` compile; round-trip specs green.
- [ ] `services/kallimachos` compiles; pod starts (routes stubbed `NOT_IMPLEMENTED`).
- [ ] Parser suites (txt/md/html/pdf) + splitter + metadata green against the doc-store corpus.
- [ ] PR merged.

## Library / pattern references

- **contracts.md §1/§2** — the proto definitions (the authority).
- **charon `services/` skeleton** (`services/charon/`, `docs/.../charon/tasks-p1-s1.1-skeleton.md`) — the Ktor module + second-proto-root precedent.
- **EXAMPLES.md §1** — Ktor bootstrap (≤45-line `App.kt`). **§3** — `messages = 99` + `argsJson`.
- doc-store `com.docstore.ingestion` — the source being ported (jsoup/flexmark/PDFBox).

## Out of scope for Stage 1.1

- The relational/fulltext planes + `IngestionService` (Stage 1.2).
- Pinakes module (Stage 1.3).
- vector/AGE planes (Phase 2); compile (Phase 3); MCP (Phase 4); the agent (Phase 5).
