# Iris — Vue SPA (frontend)

The user-facing chat UI of the Kantheon constellation. Vue 3 + TypeScript + Vite, with
dockview / PrimeVue / Vega-Lite. Talks to the Iris dispatch BFF (`agents/iris-bff`); renders
`envelope/v1` blocks.

## Provenance

This SPA was brought into kantheon by a **one-time, read-only source extraction** from
ai-platform's `frontends/agents-fe` (Iris Phase 2 Stage 2.1, 2026-06-21) via `git subtree`,
**history preserved** — source commit `54ba4d73` (`fix(agents-fe): attach Keycloak bearer to
ALL golem calls, not just /v2/chat`).

This is a **copy, not an integration**: per the no-ai-platform policy (2026-06-21) kantheon
does not read from or call ai-platform at build- or run-time. The extraction was the single,
final touch of that repo; there is no remote, submodule, or runtime tie. Deployment targets the
olymp clusters (`bp-dsk` / `bp-olymp01`), never ai-platform.

> **Stage 2.1 is the source landing + build wiring only.** The package was renamed
> (`golem-frontend` → `iris`) and the build wired into kantheon, but the import surface is
> otherwise unchanged so the green baseline is meaningful. Envelope-ts adoption (replace
> `src/types/envelope.ts` with the generated `shared/libs/ts/envelope-ts` bindings) and the
> re-point onto the BFF endpoints land in **Stage 2.2**; session UX in 2.3; deploy/cutover in
> 2.4 (→ `iris/v0.1.0`, which crosses M3). The many `golem` / `VITE_GOLEM_*` identifiers are
> the transitional `/v2` wiring reworked in those later stages.

## Routing UX (Phase 3)

Every turn resolves through Themis on the BFF; the FE renders the routing
surface and re-issues typed actions:

- **`services/typedAction.ts`** — the `POST /v1/action` client. Per-kind builders
  (`sort`/`filter`/`paginate`/`selectRow`/`chipInvocation`/`reaskAgent`/
  `investigate`) over the shared `irisStream.action` SSE consumer. Shaping kinds
  stream a *replacing* envelope (same `bubble_id`); `select_row` opens a new bubble.
- **`components/chat/ChipStrip.vue`** — discriminates the envelope/v1 `Chip` oneof:
  `RoutingPickChip.vue` (needs_user_pick — click pins the question to an agent via
  `routingHintAgentId`), `InvestigateChip.vue` (PD-1 escalation to Pythia), and the
  prompt arm (re-submits as a normal turn).
- **`components/chat/AgentBadge.vue`** — "Answered by {agent}" + a re-ask picker
  (PD-14) pre-sorted by the original `RoutingDecision.alternates`; a pick re-routes
  the turn (`reask_agent` → the BFF records `corrected_agent_id`).
- **`TableRenderer`** sort/filter/paginate + row drilldown dispatch the real typed
  actions; the per-block "Investigate this" affordance escalates table/chart answers.

The re-issue plumbing lives in `composables/useAgentSession.ts`
(`pickRoutingAgent` / `submitChip` / `reaskAgent` / `investigateTurn` /
`sortTable` / `filterTable` / `paginateTable` / `drillRow`).

## Build / test / lint

From the repo root (via `just`):

```bash
just build-fe iris    # type-check (vue-tsc) + vite build
just test-fe iris     # vitest run (non-watch)
just lint-fe iris     # oxlint + eslint (non-mutating check)
```

Or directly in this directory (npm; node 22+):

```bash
npm ci          # install (lockfile authoritative; falls back to npm install)
npm run dev     # vite dev server
npm run test:unit
npm run build
```

CI runs the `frontend-iris` job in `.github/workflows/ci.yml` (install → lint → test → build).

## Golden-sample fixtures

Envelope rendering is gated by golden samples in `shared/libs/ts/envelope-ts` (the shared TS
bindings). Re-record those fixtures when new-golem envelope shapes change — the CI golden job
owns that discipline (see the Iris plan §6 cross-cutting and the envelope-ts README).
