# Phase 4 — Serving: MCP + identity + browse

> **Reads with.** [`plan.md`](./plan.md) §6 (Phase 4), [`../../../architecture/kleio/architecture.md`](../../../architecture/kleio/architecture.md) §9 (notebooks/identity/entitlements) + §14 (cross-mart leakage risk), [`../../../architecture/kleio/contracts.md`](../../../architecture/kleio/contracts.md) §4 (`kallimachos-mcp` `library.*` surface) + §7 (mart RLS model), [`../../../architecture/kantheon-security.md`](../../../architecture/kantheon-security.md) (OBO + Argos + `visibility_roles`).

## Phase deliverable (deployable)

`tools/kallimachos-mcp` (`library.*` — getContext / search / findSimilar / getPage / traverse / getSource / listNotebooks / createNotebook·addToNotebook) + `ToolCapability` registration; **OBO bearer + Argos** notebook RLS; Golem/Pythia consume `getContext` (**RAG GA**); a minimal wiki **browse** frontend. Tags **`kallimachos-mcp/v0.1.0`** + **`kallimachos/v0.4.0`**.

> **New mergepoint "MK — Knowledge plane"** (plan §8): `getContext` for Golem/Pythia RAG goes GA at this phase's exit. Golem/Pythia plans gain an *optional* "RAG via `library.getContext`" note — **not** a hard v1 gate on them.

## Stages

| Stage | Goal — testable boundary | Task list |
|---|---|---|
| **4.1** — kallimachos-mcp + registration | `library.*` registered and callable; JSON↔HTTP fidelity per contracts §4 | [`tasks-p4-s4.1-mcp-registration.md`](./tasks-p4-s4.1-mcp-registration.md) |
| **4.2** — OBO + Argos mart RLS + RAG consumers + browse | RAG GA under RLS; wiki browsable; tags `kallimachos-mcp/v0.1.0` + `kallimachos/v0.4.0` | [`tasks-p4-s4.2-rls-consumers-browse.md`](./tasks-p4-s4.2-rls-consumers-browse.md) |

## Sequencing

```
Stage 4.1 ──► Stage 4.2
 mcp + registration   OBO/Argos RLS + RAG consumers + browse + tags
```

## Pre-flight for the phase

- [ ] **Phase 3 DONE** (`kallimachos/v0.3.0` + `pinakes/v0.2.0`) — the compiled wiki + `getContext` exist.
- [ ] capabilities-mcp reachable (the `library.*` `ToolCapability` registration target) — `capabilities-client` lib available.
- [ ] **Argos `bearer` role source** available at the MCP edge (security §3.6) — roles from the forwarded OBO bearer, never service identity.
- [ ] Mart curation governance noted (plan §9: who creates marts; default `visibility_roles` — `kantheon-domain-<x>` convention).

## Testing policy

Mocked unit/component (architecture §13): `McpToolsSpec` (JSON↔HTTP fidelity), `MartRlsSpec` (visibility predicate over fixture bearers — `PERMISSION_DENIED` before store touch), a RAG-consumer proof with a **mock** Golem/Pythia client. OBO/Argos RLS against a live cluster + the in-K3s e2e are the integration suite.

## Aggregate progress (plan §11)

- [ ] **4.1** kallimachos-mcp + registration.
- [ ] **4.2** OBO + Argos mart RLS + RAG consumers + browse. **P4 — `kallimachos-mcp/v0.1.0` + `kallimachos/v0.4.0`.**

When both are checked, push both tags and move to Phase 5.

## Up / across

- Up: [`./README.md`](./README.md). Neighbours: [`tasks-p3-overview.md`](./tasks-p3-overview.md), [`tasks-p5-overview.md`](./tasks-p5-overview.md).
- Cross-arc: **Golem/Pythia** plans gain an optional "RAG via `library.getContext`" note (no code in their arcs at v1).
