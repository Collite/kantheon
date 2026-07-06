# Proteus → ttr-translator extraction arc

> **Status:** planned 2026-07-06; execution = Phase B of the tatrman-side plan. Design + contracts live in **Collite/tatrman**: `docs/ttr-translator/architecture/{architecture,contracts}.md`, plan `docs/ttr-translator/implementation/v1/plan.md` (decisions TR-1…TR-8). This doc is the kantheon-side pointer + summary; the kantheon task lists live in [`../../implementation/v1/ttr-translator/`](../../implementation/v1/ttr-translator/).

## What happens

The translation core `shared/libs/kotlin/query-translator` (73 main files: orchestrator, framework/ModelHandle SPI, schema adapters, joiner, SQL/TransDSL/DFDSL codecs, PlanNode wire layer, dialects) moves to the tatrman monorepo and comes back as published artifacts — the same consume-published-artifacts pattern as `org.tatrman:ttr-parser/writer/semantics` and the ttr-metadata/Ariadne arc:

- `org.tatrman:ttr-translator` — the whole lib (TR-1), package rename `org.tatrman.query.shared.translator.*` → `org.tatrman.translator.*` (TR-2)
- `org.tatrman:ttr-plan-proto` (+ `ttr-plan-proto` PyPI wheel) — **ownership transfer of the wire formats** (TR-3, the S25 final call): `plan.v1` (plan/context/parameters) + `transdsl.v1` + `dfdsl.v1` become canonically tatrman-owned; proto packages and `java_package` are **unchanged**, so every generated FQCN (`org.tatrman.plan.v1.PlanNode`, …) stays identical — consumers switch classpath source, not imports.

Extraction baseline: this repo @ `f2e2efb02fe9a2d6c243d467ed5725cb50521eec` (green tree). No behavioral change rides the move; the moved Kotest suite (35 files) is the proof.

## What changes here (Phase B, three stages)

1. **B1 · Proto adoption** — the 5 transferred `.proto` files are deleted from `shared/proto`; `api(org.tatrman:ttr-plan-proto)` provides both the generated classes and the `.proto` files for the protoc include path (service protos — proteus/theseus/argos/kyklop/worker/ariadne/security — keep their `import "org/tatrman/plan/v1/…"` lines untouched). Python: workers take the wheel.
2. **B2 · Translator switch** — Proteus **and Ariadne** (`QueryParseWorker`) re-point from `project(":shared:libs:kotlin:query-translator")` to `org.tatrman:ttr-translator`; mechanical import rewrite; the in-repo lib is **deleted**.
3. **B3 · Docs, guards, tags** — CLAUDE.md §3/§7.3, this doc's status, tracker, service tags.

## What stays

`services/proteus` in full (gRPC wrapper `TranslatorServiceImpl`, `SnapshotModelHandle`, Ktor/OTel/k8s) · `org.tatrman.proteus.v1` service proto + RPC behaviors + ports 7275/7276 · Ariadne's `QueryParseWorker` (as a consumer) · every other plan.v1 consumer (Theseus, Argos, Kyklop, workers) — unchanged imports, artifact-sourced classes.

## Frozen through the swap

Proteus RPC surface + behaviors · all plan.v1/transdsl.v1/dfdsl.v1 **FQCNs and wire bytes** · Calcite version (1.41.0, mirrored in tatrman's catalog) · the golden/Calcite test expectations. The swap must be invisible outside the build files and import statements.

## Proto governance after the transfer (TR-3 guardrail)

plan.v1 evolution stays kantheon-authored in practice: changes driven by runtime needs (e.g. new `PipelineContext` fields for Argos) go as PRs to Collite/tatrman (`packages/kotlin/ttr-plan-proto`); tatrman cuts a prompt lockstep `kotlin-translator/v*` release. Wire rules: field numbers append-only within v1; breaking changes = `v2` package (tatrman `docs/ttr-translator/architecture/contracts.md` §2 is normative).

## Why (upstream context)

TTR-P (tatrman's processing language) Phase 3 compiles relational islands offline via this core (decision E-a α′: "the Proteus translation core moves to modeler as a published library; kantheon's Proteus consumes it as a thin wrapper — exactly the metadata/Ariadne pattern"). The tatrman Phase 3 gate opens when the artifact is **published** (their Phase A); our Phase B proceeds on our own cadence.
