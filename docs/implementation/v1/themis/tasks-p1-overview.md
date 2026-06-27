# Phase 1 — Kantheon foundation + `tools/capabilities-mcp`

> **Reads with.** [`plan.md`](./plan.md) §3 (Phase 1 description), [`../../../architecture/themis/architecture.md`](../../../architecture/themis/architecture.md), [`../../../architecture/themis/contracts.md`](../../../architecture/themis/contracts.md), [`../../planning-conventions.md`](../../planning-conventions.md).
>
> **Phase deliverable.** `tools/capabilities-mcp` running in local K3s with seed `AgentManifest` / `ShemManifest` fixtures and ai-platform `query-mcp` registering via heartbeat as proof-of-concept. Tag `capabilities-mcp/v0.1.0`.

## Stages

| Stage | Goal — testable boundary | Task list |
|---|---|---|
| **1.1** — Repo bootstrap | `just init && just build-kt _smoke-test && just test-all && just lint-all` all green on a clean checkout; CI passes on PR merge | [`tasks-p1-s1.1-repo-bootstrap.md`](./tasks-p1-s1.1-repo-bootstrap.md) |
| **1.2** — Capabilities proto + service skeleton | `just test-kt capabilities-mcp` green; `/health` 200, `/ready` 503 (no loader yet) | [`tasks-p1-s1.2-capabilities-proto.md`](./tasks-p1-s1.2-capabilities-proto.md) |
| **1.3** — MCP + REST surface + heartbeat client | Six MCP tools + REST mirror answered; pruner + version-resolver + `capabilities-client` lib all component-test green | [`tasks-p1-s1.3-mcp-surface.md`](./tasks-p1-s1.3-mcp-surface.md) |
| **1.4** — Deployment + ai-platform PoC | Pod Ready in local K3s with fixtures; ai-platform `query-mcp` heartbeating; cross-repo OTel trace verified | [`tasks-p1-s1.4-deploy-poc.md`](./tasks-p1-s1.4-deploy-poc.md) |

## Sequencing

Strictly sequential. Each stage closes before the next starts.

```
Stage 1.1 ──► Stage 1.2 ──► Stage 1.3 ──► Stage 1.4
  bootstrap    proto+skeleton  surface+client  deploy + PoC
```

## Pre-flight for the phase

- [ ] ai-platform Maven publishing live on GitHub Packages (verified — closed via `gap-v1` PR #48).
- [ ] Bora's GitHub PAT with `read:packages` scope in `~/.gradle/gradle.properties` (`gpr.user` + `gpr.token`).
- [ ] Bora confirms the GitHub `<org>` name to embed in `kantheon/settings.gradle.kts` Maven URL.

## Aggregate progress

Mark each stage when DONE:

- [x] **Stage 1.1** — Repo bootstrap (see task list for sub-tasks). Two carry-overs to 1.2: ai-platform `shared/v0.0.1` tag predated publish.yml so cz.dfpartner Maven artifacts aren't actually published yet, and `mcp-server-base` doesn't exist in ai-platform.
- [x] **Stage 1.2** — Capabilities proto + service skeleton (capabilities.proto + InMemoryRegistry + /health + /ready, 15 tests green).
- [x] **Stage 1.3** — MCP + REST surface + heartbeat client (6 MCP tools, REST mirror, TTL pruner, VersionResolver, capabilities-client lib with Wiremock-driven tests).
- [ ] **Stage 1.4** — Deployment + ai-platform PoC. _T1–T3, T6 closed in code; T4 (live K3s deploy) and T5 (ai-platform PoC PR) deferred to a follow-up live-cluster session. Tag `capabilities-mcp/v0.1.0` pending live validation._

When all four boxes above are checked, push tag `capabilities-mcp/v0.1.0` and move to Phase 2.

## Up / across

- Up: [`./README.md`](./README.md) — Themis implementation index.
- Phase neighbours: [`tasks-p2-overview.md`](./tasks-p2-overview.md), [`tasks-p3-overview.md`](./tasks-p3-overview.md).
