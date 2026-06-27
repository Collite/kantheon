# Themis — v1 Implementation

Phased plan and per-stage task lists for the Themis-in-kantheon arc.

## Active — the kantheon-side arc

3 phases · 14 stages · ~80 tasks. Per-stage task lists are written; ready to execute.

- **[`plan.md`](./plan.md)** — the phased plan. Read first. Phase summary, per-stage goal + DONE criteria, cross-cutting work, out-of-scope, Bora-owned content gaps.

### Phase 1 — Kantheon foundation + `tools/capabilities-mcp`

Deployable at close: `capabilities-mcp/v0.1.0` in local K3s with `pythia` + `golem-erp` fixture manifests; ai-platform `query-mcp` registers via heartbeat.

- **[`tasks-p1-overview.md`](./tasks-p1-overview.md)** — phase tracker.
- [`tasks-p1-s1.1-repo-bootstrap.md`](./tasks-p1-s1.1-repo-bootstrap.md) — kantheon repo skeleton, gradle, justfile, CI, Maven-from-ai-platform.
- [`tasks-p1-s1.2-capabilities-proto.md`](./tasks-p1-s1.2-capabilities-proto.md) — capabilities.proto, in-memory registry, Ktor scaffolding.
- [`tasks-p1-s1.3-mcp-surface.md`](./tasks-p1-s1.3-mcp-surface.md) — six MCP tools + REST mirror + TTL pruner + version resolver + `capabilities-client` lib.
- [`tasks-p1-s1.4-deploy-poc.md`](./tasks-p1-s1.4-deploy-poc.md) — YAML loader, K8s deployment, ai-platform `query-mcp` heartbeat PoC, cross-repo OTel verification.

### Phase 2 — Resolver → Themis in kantheon, Koog-based

Deployable at close: `themis/v0.1.0` in local K3s; Koog-based graph; eval-gate green against the 50-question Czech corpus.

- **[`tasks-p2-overview.md`](./tasks-p2-overview.md)** — phase tracker.
- [`tasks-p2-s2.1-koog-spike.md`](./tasks-p2-s2.1-koog-spike.md) — 1–2 day spike against current Koog/Ktor; go/no-go decision.
- [`tasks-p2-s2.2-resolver-extraction.md`](./tasks-p2-s2.2-resolver-extraction.md) — `git filter-repo` from `ai-platform/agents/resolver` → `kantheon/agents/themis`; proto package rename.
- [`tasks-p2-s2.3-koog-migration.md`](./tasks-p2-s2.3-koog-migration.md) — port all nine nodes to Koog `AIAgentGraphStrategy`; delete `ResolverGraph`.
- [`tasks-p2-s2.4-deploy-eval.md`](./tasks-p2-s2.4-deploy-eval.md) — deploy `themis-mcp`; run eval corpus; diff vs ai-platform baseline; tag.

### Phase 3 — Routing layer + Iris co-design

Deployable at close: `themis/v0.2.0` with the four-layer routing cascade live; Iris BFF stub chip round-trip; CI-enforced routing eval gate.

- **[`tasks-p3-overview.md`](./tasks-p3-overview.md)** — phase tracker.
- [`tasks-p3-s3.1-proto-extensions.md`](./tasks-p3-s3.1-proto-extensions.md) — routing proto types added to themis/v1 + envelope/v1.
- [`tasks-p3-s3.2-intent-multiquestion.md`](./tasks-p3-s3.2-intent-multiquestion.md) — `classifyIntentKind` + `detectMultiQuestion` Koog nodes.
- [`tasks-p3-s3.3-route-to-agent.md`](./tasks-p3-s3.3-route-to-agent.md) — `routeToAgent` four-layer cascade; capabilities-mcp fail-fast boot.
- [`tasks-p3-s3.4-profile-refusal.md`](./tasks-p3-s3.4-profile-refusal.md) — `Profile` semantics, `RefusalWithGaps`, routing corpus skeleton.
- [`tasks-p3-s3.5-eval-ci.md`](./tasks-p3-s3.5-eval-ci.md) — extend eval harness; CI threshold gates; Layer-1 weight tuning.
- [`tasks-p3-s3.6-iris-cutover.md`](./tasks-p3-s3.6-iris-cutover.md) — Iris BFF stub + observability + design.md update + tag.

## Carry-over from ai-platform (historical / reference)

The original Resolver six-stage plan in ai-platform. Stages 01–04 are essentially complete in `ai-platform/agents/resolver/`; Stages 05–06 are reframe-pending under the kantheon split. Kept here as the source-of-truth carry-over the kantheon work builds on.

| File | Original location | Status |
|---|---|---|
| [`tasks-stage-01-infra-nlp.md`](./tasks-stage-01-infra-nlp.md) | ai-platform | Complete (5 NLP engines + COMPARE mode + 50-entry eval corpus). |
| [`tasks-stage-02-nlp-mcp.md`](./tasks-stage-02-nlp-mcp.md) | ai-platform | Complete (`ops` array bug closed via Phase 09). |
| [`tasks-stage-03-eval-compare.md`](./tasks-stage-03-eval-compare.md) | ai-platform | Complete (MorphoDiTa via UFAL HTTP + COMPARE harness). |
| [`tasks-stage-04-resolver-agent.md`](./tasks-stage-04-resolver-agent.md) | ai-platform | Complete as plain Kotlin coroutines (Koog migration is Phase 2 Stage 2.3 kantheon-side). |
| [`tasks-stage-05-parallel-deployment.md`](./tasks-stage-05-parallel-deployment.md) | ai-platform | **Reframe pending.** Originally diff-harness vs `golem` in-process logic; under the kantheon split this needs rewriting. Out of current arc. |
| [`tasks-stage-06-consumer-migration.md`](./tasks-stage-06-consumer-migration.md) | ai-platform | **Reframe pending.** Consumer list updates to Iris + Pythia + Hebe under the kantheon split. Out of current arc. |

## What's elsewhere

- **Design**: [`../../../design/themis/`](../../../design/themis/) — what Themis is, the brainstorming history.
- **Architecture + contracts**: [`../../../architecture/themis/`](../../../architecture/themis/) — implementation shape and wire contracts.
- **Cross-cutting v1 status**: [`../next-steps.md`](../_archive/next-steps.md), [`../aip-v1-status-audit.md`](../_archive/aip-v1-status-audit.md), [`../aip-v1-gap-closure-plan.md`](../_archive/aip-v1-gap-closure-plan.md).
- **Planning conventions**: [`../../planning-conventions.md`](../../planning-conventions.md).

## Up / across

- Up: [`../README.md`](../README.md) — v1 implementation entry point.
- Across: [`../../../design/themis/`](../../../design/themis/), [`../../../architecture/themis/`](../../../architecture/themis/).
