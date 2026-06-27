# Iris Phase 2 Stage 2.2 — envelope-ts adoption + BFF re-point

> **Goal (plan §4 Stage 2.2).** The FE consumes the generated `@kantheon/envelope-ts` bindings (envelope/v1) instead of its hand-rolled v2 `src/types/envelope.ts`, and talks **only to the iris-bff** (single `VITE_BFF_BASE_URL`) — no direct platform calls, no `/golem` proxy. Daily-usable in dev against the BFF.
>
> **Companions.** [`plan.md`](./plan.md) §4 · [`../../../architecture/iris/contracts.md`](../../../architecture/iris/contracts.md) §2 (BFF endpoints) · [`tasks-p1-s1.3-dispatch-sse.md`](./tasks-p1-s1.3-dispatch-sse.md) (the BFF SSE/event shape) · `@kantheon/envelope-ts` (`shared/libs/ts/envelope-ts`).

## Scoping findings (2026-06-21)

- **This is the bulk of Phase 2** — the largest, most coupled change in the arc. The FE's `src/types/envelope.ts` is the **flat v2 snake_case `FormatEnvelope`** (228 lines), consumed across `src/catalog/`, `src/components/chat/`, `chatStore`. `@kantheon/envelope-ts` is the **envelope/v1 camelCase proto** shape (`FormatEnvelope` + `Block[]` + `provenance`). **The shapes genuinely differ** — adoption is a renderer migration, not a type re-export. iris-bff already normalizes v2→envelope/v1 on its SSE wire (Stage 1.3 `IrisStreamMux`), so once the FE re-points to the BFF it receives envelope/v1 and renders that.
- **envelope-ts is consumed as TS source** (`main`/`exports` → `./src/index.ts`, no dist; dependency-free generated code). The FE must alias `@kantheon/envelope-ts` → that source in **vite + vitest (merges vite) + tsconfig paths**; mind vue-tsc rootDir (include the source or use a project reference).
- **BFF endpoints to target** (contracts §2 / Stage 1.3): `POST /v1/session`, `GET /v1/sessions`, `GET /v1/session/{id}`, `POST /v1/chat/stream` (SSE: `step`/`envelope`/`error`/`done`), `POST /v1/chat/turn`, `POST /v1/chat/resume`, `POST /v1/action`. Bearer (OBO) on every call.
- **Best validated against the live BFF** — iris-bff is running on bp-dsk (PG-backed). The re-point (T3–T6) should be smoke-checked against it, not just unit mocks.

## Tasks

- [x] **T0 — Wire `@kantheon/envelope-ts` into the FE build** *(foundational; no renderer change yet)*. `file:` dep + vite/vitest/tsconfig aliases; a binding smoke test (`FormatEnvelope.fromJSON` on a golden sample) green. Keeps the 132-test baseline.
- [x] **T1 — Adopt envelope-ts types; retire `src/types/envelope.ts`.** **Atomic (not sliceable):** `src/types/envelope.ts` is imported by **15 files** and is the linchpin — re-pointing it to `@kantheon/envelope-ts` ripples through all consumers at once. **Exact deltas pinned (2026-06-21):**
  1. **`FormatKind` becomes a numeric enum** (`FormatKind.PLAINTEXT=1, MARKDOWN=2, TABLE=3, CHART=4`, `UNSPECIFIED=0`, `UNRECOGNIZED=-1`) — NOT the v2 `'plaintext'|…` string union. Rewrite `formatCatalog` (keyed by enum, not string), `resolveRenderer`, and every `kind === 'table'`/`'chart'`/`'markdown'`/`'plaintext'` comparison (in `ChatBubble.vue`, `chatStore.formatKindFor`, renderers) to the enum. Defaults `?? 'plaintext'` → `?? FormatKind.PLAINTEXT`. `displayState.viewKind` retyped to the enum.
  2. **snake_case → camelCase**: `bubble_id→bubbleId`, `turn_id→turnId`, `thread_id→threadId`, `pending_clarification→pendingClarification`, `entity_context→entityContext`, `current_view→currentView`, `update_tab_id→updateTabId`, `plan_source→planSource`, `created_at→createdAt`, etc. (touches chatStore, ChatBubble, ClarificationCard, DrilldownChips, layout/chip/tabs stores, agentService, slashCommands).
  3. **`content` (parsed value) → `contentJson` (JSON string)** — renderers that read rows (TableRenderer, ChartRenderer, chartAutoIntent, compileVegaLite) must `JSON.parse(contentJson)` at one shared helper (e.g. `envelopeContent(env)`).
  4. **`format` is `{kind, table?, chart?, markdown?}`** — v1 has **no `details` union**; drop the `?? fmt.details` fallback in `ChatBubble.effectiveDetails`.
  5. Keep FE-local `DisplayState`; `src/types/envelope.ts` → thin re-export of the canonical types from `@kantheon/envelope-ts` + `DisplayState` (+ any FE-only view helpers).
  - **Drive with the existing component tests** (below) + `npx vitest run` + `npm run type-check` after each consumer; the type-checker enumerates the work.
- [x] **T2 — Golden-sample rendering specs.** **Largely already covered** — existing `tests/unit/*.test.ts` mount the renderers: `TableRenderer.test.ts` + `.sum` + `.showDetail`, `ChatBubble.clarification`, `ClarificationCard`, `DrilldownChips`, `ChatInput.*` (these build **v2** envelopes inline → update their inputs to v1 as part of T1; the DOM assertions are the shape-agnostic safety net). **Extend**: add specs that render the **12 shared fixtures** (`shared/libs/ts/envelope-ts/test/fixtures/01..12`, decoded via `decodeV2Envelope`) through `formatCatalog` and assert DOM parity — locks v1 renderer behaviour. **Landed (2026-06-21, post-review):** `tests/unit/envelopeFixtureParity.test.ts` (+30 tests) — catalog dispatch (every fixture → expected renderer, never the fallback) + DOM parity for table/chart/markdown/plaintext. Caught + fixed a latent gap: the "loose" chart shape (fixture 04, rows under `format.chart.rowsJson`, `content=null`) rendered empty — `ChartRenderer.rows` now falls back to `rowsJson` when top-level content is absent.
- [x] **T3 — Split `agentService.ts` → `services/irisStream.ts` + `services/typedAction.ts`** targeting the BFF endpoints (contracts §2); bearer forwarded. **Landed (2026-06-21):** `agentService.ts` deleted; `services/irisStream.ts` is the BFF client — `createSession`/`listSessions`/`getSession`/`resetSession` (POST/GET `/v1/session*`), `streamTurn` + `resumeClarification` (SSE `/v1/chat/stream` + `/v1/chat/resume`), `turn` (sync `/v1/chat/turn`, used by edit-resend + row-select), `refresh` (`/v1/refresh`), `ready` (`/ready`). `services/typedAction.ts` is the Phase-3 placeholder (`/v1/action` not on the BFF yet → throws). DTOs in `agent-responses.ts` rewritten to the BFF camelCase shapes. New `irisStream.test.ts` (BFF endpoints + SSE arms + auth).
- [x] **T4 — Strip direct platform services.** *(Decision: unwire-keep-code, Bora 2026-06-21.)* `LlmGatewayView`/`InspectorView`+`ConnectionPanel` unrouted + removed from side-nav; `QueriesPane` removed from the bottom-pane registry + workspace. The service/view files (`llmGatewayService`, `mcpClient`, `metadataService`) stay on disk (recoverable when the BFF grows those surfaces). `keycloak`/`authHeaders` stay for the bearer. The connection-status poll re-points to the BFF `/ready`.
- [x] **T5 — Single `VITE_BFF_BASE_URL` config.** Added `config.bff.baseUrl` (default `http://localhost:7410`, the iris-bff port); the single-agent fallback registry now resolves to the BFF (`{id:'iris', baseUrl: config.bff.baseUrl}`). Vite proxies: `/golem`→`/bff` (target the BFF, SSE-tuned); `/llm/api`, `/erp/mcp`, `/fuzzy/mcp` deleted. The legacy `golem`/`*Mcp`/`llmGateway` config sections stay (they feed the unwired-but-kept code).
- [x] **T6 — SSE event-name switch.** `useAgentSession` (the stream consumer; `chatStore` holds no SSE logic) consumes the iris/v1 `IrisStreamEvent` arms `step`/`envelope`/`error`/`done` — `step.phase` (started/completed) drives the node highlight, `pick_plan`/`execute` `detailJson` the plan annotation. Envelope decoded via `FormatEnvelope.fromJSON` (BFF already emits v1 — review finding #5 applied at this edge). Lifecycle specs updated.

### Degradations carried by the re-point (deferred to Phase 3/4)
The BFF surface is narrower than golem's v2, so these affordances are intentionally degraded until the named phase:
- **Typed actions / armed row-detail `selection`** — no `/v1/action` and no `selection` field on the v1 turn request → the "Show detail" selection is FE-only UI state and is not transmitted; row-select + edit-resend re-issue as plain natural-text turns via `/v1/chat/turn`. **(Phase 3, `/v1/action`.)**
- **`/sql` (dry-run)** — no `dryRun` field on the v1 turn request; the hint is reset but not sent. **(Phase 3.)**
- **Locale-aware discovery** — POST `/v1/session` takes no locale and is non-idempotent, so `changeLanguage` is UI-only (no re-bootstrap → conversation preserved); locale-aware chips return with **(Phase 4 `/v1/discover`.)**
- **Agent-graph pane (`AgentGraphView` → `/v2/agent/graph`)** — no BFF equivalent; left kept-but-degraded (fetch 404s, handled by its own error path).

### ⚠ Live-turn validation still pending
Session CRUD + discovery + refresh + resume are validatable against iris-bff on bp-dsk now. Full **turn** validation (stream/turn) still waits on a `/v2`-speaking golem reachable from the BFF on bp-dsk (deployed `golem:0.1.0` is the rewrite skeleton) — unchanged from the Stage 2.2 scoping note. The re-point is unit-tested (mocked fetch/SSE) and green; the live smoke is the one open item before ticking plan §9 Stage 2.2 fully.

## DONE
- FE imports `@kantheon/envelope-ts`; `src/types/envelope.ts` gone; renderers on envelope/v1.
- FE talks only to the BFF in dev (one `VITE_BFF_BASE_URL`); no direct platform proxies.
- vitest green; live smoke against iris-bff (bp-dsk). Plan §9 `Stage 2.2` ticked.

## ✅ BLOCKER RESOLVED (2026-06-21) — BFF grown (option **a**)

Bora chose **grow the BFF first**. Done (iris-bff, 65 tests green): `POST /v1/session` now mirrors golem's discovery (`staticChips`/`exampleQuestions`/`packages`/`agentVersion`) onto the `SessionDto` (best-effort — golem-down → thin session, creation never fails); `POST /v1/refresh` proxies golem `/v2/refresh`. So the FE can now get discovery + refresh **from the BFF**, no direct golem. **T3–T6 (the FE re-point) is unblocked.** (Live turn-validation still waits on a `/v2` golem backend reachable from the BFF on bp-dsk; session + discovery + refresh are validatable now.) Original blocker analysis kept below for context.

## ⚠ BLOCKER for T3–T6 (the BFF re-point) — found 2026-06-21 (now resolved, see above)

The FE re-point (T3–T6) is **blocked on BFF capability gaps + a validation gap**:

1. **`/v1/session` is thin.** It returns `SessionDto` (sessionId/userId/tenantId/turns/entityContext) — **none** of the discovery data golem's `/v2/session` gave the FE: `static_chips`, `example_questions`, `packages`, `agent_version`. `useAgentSession.startSession` reads **all** of these (chip strip, ghost-text example questions, package names, agent version). Re-pointing session → BFF **regresses** the suggested-chip strip + example questions + version until the BFF grows that surface (Phase 4 `/v1/discover`, or a `/v1/session` enrichment).
2. **No `/v1/refresh`, no `/v1/action` on the BFF.** `/refresh` (metadata refresh) has no BFF endpoint; typed actions (`/v1/action`) are Iris **Phase 3**. So a clean "FE talks only to BFF" cannot cover refresh/typed-actions yet.
3. **Validation gap.** bp-dsk has no `/v2`-speaking golem behind the BFF (deployed `golem:0.1.0` is the rewrite skeleton), so chat **turns** can't be validated end-to-end on bp-dsk — only session CRUD.

**Decision needed (Bora):** (a) **grow the BFF** session/discovery surface (proxy golem `/v2/session` discovery through `/v1/session`, add `/v1/refresh`) so the re-point is clean + no regression — modest iris-bff Kotlin work; or (b) **transitional hybrid** — route the core chat loop (session/stream/resume) to the BFF but keep discovery/refresh against golem temporarily (keeps a golem-direct tie); or (c) **defer T3–T6** until after Iris Phase 3 (routing + `/v1/action`) / Phase 4 (`/v1/discover`), doing the re-point once the BFF surface exists. **Recommendation: (a)** — it's the true BFF-only architecture and unblocks a clean re-point. Either way, live turn-validation waits on a `/v2` golem backend reachable from the BFF on bp-dsk.

## Notes
- **Execute in green increments** (T0 → T1/T2 → T3–T6), not one diff — the renderer migration touches many components + tests. Each slice keeps vitest green.
- Store names stay stable (Stage 2.1 left them); Phase 3 adds routing UX (RoutingPickChip, reask_agent, InvestigateChip) on top.
