# Stage 4.1 — kallimachos-mcp + registration

> **Phase 4, Stage 4.1.** Branch `feat/docwh-p4-s4.1-mcp-registration`.
>
> **Reads with.** [`tasks-p4-overview.md`](./tasks-p4-overview.md), [`plan.md`](./plan.md) §6 Stage 4.1, [`../../../architecture/kleio/contracts.md`](../../../architecture/kleio/contracts.md) §4 (the `library.*` tool surface) + §11 (config/ports), [`../../../architecture/kleio/architecture.md`](../../../architecture/kleio/architecture.md) §4 (`tools/kallimachos-mcp`), [`../../../../EXAMPLES.md`](../../../../EXAMPLES.md) §2 (MCP server) + §4 (capabilities heartbeat). Template: the existing `tools/charon-mcp` / `tools/metis-mcp` wrappers.

## Goal

`tools/kallimachos-mcp` — a **thin** (zero-logic) MCP wrapper over the Kallimachos read/RAG/browse surface, streamable-HTTP at `POST /mcp`, JSON-mirroring contracts §1. The `library.*` tools register as `ToolCapability` manifests in capabilities-mcp with `cost_hints` from the S2.3 benchmark. DONE = `library.*` registered and callable.

## Tasks (6)

- [ ] **T1 — Tests first: `McpToolsSpec` — JSON↔HTTP fidelity per `library.*` (contracts §4).**

  Spec each `library.*` tool's JSON request/response against the Kallimachos HTTP surface (Wiremock'd store): `getContext`, `search`, `findSimilar`, `getPage`, `traverse`, `getSource`, `listNotebooks`, `createNotebook`/`addToNotebook`. Assert JSON↔proto fidelity (Rule 7 `argsJson`) and `messages = 99` pass-through (Rule 6), plus error propagation.

  Acceptance: specs written and failing. Commit `[docwh-p4-s4.1] failing mcp tools spec`.

- [ ] **T2 — `tools/kallimachos-mcp` (Kotlin MCP SDK; zero logic).**

  Create the module (architecture §4) following the `charon-mcp`/`metis-mcp` template (EXAMPLES.md §2): Kotlin MCP SDK streamable-HTTP, `App.kt` + `McpTools.kt`, **zero business logic** — each tool forwards to a Kallimachos HTTP endpoint. Config (contracts §11): `kallimachos-mcp.{port=7262, kallimachos-http.{host,port}, capabilities-mcp.{host,port}}`. `include(":tools:kallimachos-mcp")`.

  Acceptance: module compiles; `POST /mcp` answers; pod starts.

- [ ] **T3 — The `library.*` tools incl. `getPage`/`traverse`/`getContext`.**

  Implement all `library.*` tools (contracts §4 table) as thin forwards. Each carries the caller OBO bearer through (RLS enforcement is S4.2 — here the bearer is forwarded, the predicate lands next stage). `getContext`/`search`/`findSimilar` (RAG/search), `getPage`/`traverse`/`getSource` (browse).

  Acceptance: T1 `McpToolsSpec` green; every `library.*` tool forwards correctly.

- [ ] **T4 — `library.*:v1` `ToolCapability` manifests + heartbeat.**

  Author the `library.*:v1` `ToolCapability` manifests; register + heartbeat into capabilities-mcp via `capabilities-client` (EXAMPLES.md §4). Visible in capabilities-mcp `list()`.

  Acceptance: `library.*` appears in capabilities-mcp `list`/`search`; heartbeat ticks.

- [ ] **T5 — `cost_hints` from the S2.3 benchmark; `search_tags`.**

  Populate `cost_hints` on the `library.*` manifests from the S2.3 retrieval benchmark (latency + candidate counts per plane); add `search_tags` so Themis/Pythia can discover the RAG surface.

  Acceptance: manifests carry `cost_hints` + `search_tags`; reflected in capabilities-mcp `get`.

- [ ] **T6 — Deploy + smoke.**

  Deploy `kallimachos-mcp` to local K3s; smoke a `library.getContext` + `library.getPage` call end-to-end (MCP → Kallimachos → store). Deployment smoke, not an automated e2e gate.

  Acceptance: `library.*` callable over MCP in-cluster. PR `[docwh-p4-s4.1] kallimachos-mcp + registration`.

## DONE — Stage 4.1

- [ ] All six tasks checked.
- [ ] `tools/kallimachos-mcp` (zero logic) serves all `library.*` tools over streamable-HTTP with JSON↔proto fidelity.
- [ ] `library.*:v1` `ToolCapability` manifests registered + heartbeating; visible in capabilities-mcp.
- [ ] `cost_hints` (from the S2.3 benchmark) + `search_tags` on the manifests.
- [ ] `library.*` callable in-cluster.
- [ ] PR merged.

## Library / pattern references

- **contracts.md §4** — the `library.*` tool ↔ store-endpoint table (the authority).
- **EXAMPLES.md §2** — MCP server (tool registration + `structuredContent`). **§4** — capabilities heartbeat + read-mostly cache.
- `tools/charon-mcp` / `tools/metis-mcp` — the thin-wrapper template.
- Kotlin MCP SDK (`~/Dev/view-only/kotlin-mcp-sdk`) — streamable-HTTP.

## Out of scope for Stage 4.1

- OBO/Argos RLS enforcement + the RLS predicate (Stage 4.2 — bearer is forwarded here, enforced there).
- RAG-consumer proof + the browse FE (Stage 4.2).
