# Ariadne → ttr-metadata adoption arc

> **Status:** planned 2026-07-05; execution = Phase M4 of the tatrman-side plan. Design + contracts live in **Collite/tatrman**: `docs/ttr-metadata/architecture/{architecture,contracts}.md`, plan `docs/ttr-metadata/implementation/v1/plan.md` (decisions MD1–MD8). This doc is the kantheon-side pointer + checklist.

## What happens

Ariadne's platform-agnostic core (typed model, sources, reconciler, resolver, model graph, search, registry, refresher mechanism, export pipeline) moves to the tatrman monorepo and comes back as published artifacts:

- `org.tatrman:ttr-metadata` (core)
- `org.tatrman:ttr-metadata-git` (GitArchiveStorage behind the core `ModelStorage` SPI)

Ariadne becomes a thin service wrapper — the same consume-published-artifacts pattern we already use for `org.tatrman:ttr-parser/writer/semantics`, and the same shape as the Proteus→ttr-translator arc. Package rename on the move: `org.tatrman.kantheon.ariadne.*` → `org.tatrman.ttr.metadata.*`.

**Frozen through the swap (MD7):** the `org.tatrman.ariadne.v1` proto and all 14 RPC behaviors · ports 7260/7261/7262 · `METADATA_GIT_*` env contract · ariadne-mcp · all downstream consumers (Golem, Shem assembly). The swap must be invisible outside the service.

## What stays in Ariadne

`grpc/` (MetadataServiceImpl as conversion + delegation to the library's `MetadataQuery`, PageTokenCodec) · proto conversions · `parse/` (QueryParseWorker — its query-translator dependency belongs to the ttr-translator arc, not this one) · `refresh/RefreshScheduler` (scheduling policy; the refresher *mechanism* is library-side) · `Application.kt`, Ktor/OTel/logging, k8s, `MetadataExportRoutes`.

## Pre-arc baseline (added 2026-07-05 — BLOCKS tatrman M1 start)

The 0.8.4 re-point (`1eaaac8`) shipped without a compile/test gate. Status: compile fixed 2026-07-05 (`Source.kt` → `modelDirective.modelCode`/`.schema`, grammar-4.0 rename), **suite still red**. Review-and-fix checklist — the DONE bar is `just test-kt ariadne` fully green, and that green tree is the frozen baseline tatrman M1 copies from:

- [x] Migrate the 12 pre-4.0 test fixtures (`src/test/resources/{fixture-model, fixture-packages, fixture-packages-noimport, v2-1-samples, fixture-fuzzy}/…`): file directive `schema <code> [namespace]` → `model <code> [schema <id>]`. Done 2026-07-05 (`9328e98`).
- [x] Sweep the specs embedding inline TTR snippets (8 specs) — same directive migration inside string literals + rendered-output assertions. Includes fixing a stale pre-3.0 `schema binding namespace map` → `model binding` in `ListObjectsFuzzyAttributeMappingSpec`.
- [x] Re-run `:services:ariadne:test`: **56 failing → 2 failing** (all 54 fixture-syntax failures cleared).
- [x] **2 remaining — PRE-EXISTING 0.8.4/qname-redesign behavioral regressions (failed before the migration too; NOT fixture syntax). Per this checklist they are tatrman issues, fixed during the M1 port, not patched in Ariadne core.** RESOLVED 2026-07-05 (review-025): both specs are ported into `org.tatrman:ttr-metadata` (not `@Ignore`d) and pass green in `:packages:kotlin:ttr-metadata:test`; the fixes live in the library (`source/BuiltinStockSource.kt`, `resolve/…`), not in Ariadne.
  - `StockRoleResolutionSpec > bare stock-role … auto-import`: `BuiltinStockSource` keys the stock-role internalId on the pre-D15 doubled `cnc.cnc.role.<name>` while 0.8.4 semantics load it as `cnc.role.<name>` (the spec's *final* assertion already expects `cnc.role`), so the `cnc.*` auto-import can't match `fact`. → Fix in the ported `BuiltinStockSource`/`ReferenceResolutionPass`/`PublishedResolverAdapter` (this spec is in the M1.1 port roster and goes green in tatrman; it does not re-run in Ariadne after M4).
  - `ResolutionIntegrationSpec > same-package ref … non-default namespace (sales)`: exercises a pre-4.0 er-`namespace` resolution concept the 4.0 qname redesign changed (er has no schema/namespace slot; `modelHasSchema` is db-only). → Needs a 4.0-intent decision on whether Ariadne's per-file `namespace` still drives same-package er resolution; `resolve/` ports in M1.2, so decide/fix there.
- [x] On green: record the commit hash here as the **frozen baseline** for tatrman M1.1's copy step: `baseline = 9328e98` ("green except the 2 documented behavioral regressions above", both now resolved in the tatrman library port — see the row above).

## Core freeze (effective at tatrman Phase M1 start)

No new features in the to-be-moved packages while the extraction runs. Bugfixes land in **both** trees with a `// dual-landed: <tatrman commit>` note. Anything bigger waits for the artifact.

## Execution checklist (Stage M4.1)

> **Status (2026-07-05, review-025):** M4.1 code landed (`e033253`). The swap/delete/delegation
> items below are done; the **open gate is publishing** — the pin is still `0.0.1-LOCAL` via
> Maven Local, not `kotlin-metadata/v0.1.0` on GitHub Packages, so the green build is not yet
> CI-reproducible (all five artifacts must be `publishToMavenLocal`'d first). K3s image + mcp
> smoke remain to be run. review-025 also fixed a Search paging regression (page_size was dropped
> at the facade) and removed orphaned `stop-words-*.txt` duplicates that shadowed the library copies.

- [ ] `kotlin-metadata/v0.1.0` available on GitHub Packages (tatrman M2 published); consumer PAT configured — **OPEN: still pinned to `0.0.1-LOCAL` (Maven Local); the real publish is deferred (tatrman M2.2 gate)**
- [x] Pin `tatrman-ttr-metadata` in `gradle/libs.versions.toml`; add `-git` dep — done as a **temporary `0.0.1-LOCAL`** pin (flip to `0.1.x` once published)
- [x] Rewrite imports; delete moved packages (`model/ source/ reconcile/ resolve/ graph/ search/ registry/ export/`(minus routes)`, refresh/MetadataRefresher`) and their moved specs (24 — enumerated in tatrman `tasks-m4-s4.1-kantheon-swap.md` T4.1.3; they now run in tatrman)
- [x] `MetadataServiceImpl` bodies → proto conversion + `MetadataQuery`/`WorldResolver` delegation; grpc-layer specs green unchanged (Search `page.pageSize` mapping fixed in review-025)
- [x] `RefreshScheduler` drives `MetadataRefresher` from the library
- [ ] `just build-kt ariadne && just test-kt ariadne` green; Jib image runs on local K3s — **build green against Maven Local only; K3s image run not yet verified**
- [ ] Smoke via ariadne-mcp: ListObjects / Search / GetModel / ResolveArea / Refresh
- [x] Drift guard: `git grep` proves no copy of moved core classes remains under `services/ariadne` (orphaned `stop-words-*.txt` resources removed in review-025 — the guard now holds for data as well as classes)
- [ ] README + `docs/architecture/fork/contracts.md` updated to record the library boundary

## Drift rule (steady state)

Core behavior gaps found later are fixed by a **ttr-metadata release**, never by re-implementing core logic inside Ariadne. Review flag: any PR adding model/graph/search/resolution logic under `services/ariadne` gets bounced to the library.
