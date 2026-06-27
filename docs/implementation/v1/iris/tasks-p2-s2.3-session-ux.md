# Iris Phase 2 Stage 2.3 — session UX on the BFF

> **Goal (plan §4 Stage 2.3).** Multi-session daily-driver UX over the BFF: list/create/switch sessions, reload restores the conversation from `iris_turns`, edit-and-resend re-enters the turn flow through the real `edit_resend` typed action (BFF snapshot + discard), `/reset` clears with snapshot-undo, and the slash-command surface is reconciled against the v1 BFF.
>
> **Companions.** [`plan.md`](./plan.md) §4 · [`../../../architecture/iris/contracts.md`](../../../architecture/iris/contracts.md) §2.1–2.4 (session/chat/action surface, `edit_resend` payload, snapshot/undo) · [`tasks-p2-s2.2-envelope-ts-bff.md`](./tasks-p2-s2.2-envelope-ts-bff.md) (the re-point this builds on).

## Scoping findings (2026-06-23)

Grounded against the post-2.2 codebase. The two sides are at different readiness:

**BFF is mostly ready — the store machinery already exists, the routes do not.**
- `GET /v1/session/{id}` returns `SessionDto` **with turn pointers** (`SessionRoutes.kt:75`, `store.getSessionWithTurns`); per-turn envelopes hydrate on demand via `GET /v1/session/{id}/turn/{turnId}` → `TurnEnvelopeDto` (`:93`). Both contract-blessed (contracts §2.1: "envelopes hydrated from `iris_turns` on demand").
- `store.discardTurnsAfter(sessionId, fromTurnId)` exists in **both** stores (InMemory `:127`, Exposed `:206`) — snapshots with `reason="edit_resend"`, discards `seq > from.seq`, returns the discarded turns. **Nothing calls it** — there is no `/v1/action` route. This is the T3 gap.
- `store.reset(id)` exists + is routed (`POST /v1/session/{id}/reset`, `:85`) — snapshots `reason="reset"`, discards all, clears context.
- **No snapshot read-back / restore path** anywhere — `snapshots()` is write-only; no `undo` store method or route. This is the T4 gap (contracts §3: "Discarded turns are status-flipped, never deleted (undo restores from snapshot)").

**FE has the optimistic machinery but no session surface.**
- `chatStore` has `discardAfter`/`removeDiscarded`/`restoreDiscarded`/`clearPendingDiscard` (well-tested) — the optimistic side of edit-resend already works.
- `irisStream` has `listSessions`/`getSession`/`resetSession` fully implemented **but unused** — nothing calls them. `createSession` is the only wired one (`useAgentSession.init`).
- **No session-list/switch UI, no hydration path** (`chatStore` is in-memory only; reload re-mints a session), `/reset` slash command absent, `services/typedAction.ts` is a throwing Phase-3 stub, `ChatBubble.saveEdit` degrades edit to a plain `irisStream.turn` (`ChatBubble.vue:236`).
- `ChatMessage` carries no `turnId` today → must be threaded through hydration for `edit_resend` (`fromTurnId`) to work.

**Decisions baked in (contract-faithful):**
- **Hydration stays on-demand** (contracts §2.1): `switchSession`/reload fetch turn pointers via `getSession`, then fetch each visible turn's envelope in parallel. No new bulk endpoint (revisit only if N+1 hurts in Phase 3).
- **`edit_resend` rides the contracted `/v1/action`** (contracts §2.4), not an ad-hoc `fromTurnId` on `/v1/chat`. The route handles `kind=edit_resend` now; `sort`/`filter`/`paginate`/`select_row`/`chip_invocation`/`reask_agent` return a not-yet-implemented error envelope (Phase 3 Stage 3.2 fills them).
- **Undo restores the latest snapshot** (covers both `reset` and `edit_resend`): a new `restoreLatestSnapshot` store method un-discards the snapshot's `turnIds` and restores `entityContext`; routed at `POST /v1/session/{id}/undo`.

## Tasks

- [x] **T1 — Session list + create/switch UI.** *(FE; placement: **left session rail** — Bora, 2026-06-23.)* New persistent left-column `SessionRail.vue` (sibling of `SideNavigation`, not a dockview pane) populated by `irisStream.listSessions()` (summaries: id, title/first-question, updatedAt), active session highlighted, "+ New" at top reusing the `/new` path; selecting a row calls a new `useAgentSession.switchSession(id)` (T2 hydrates it). **Tests first:** component spec — rail renders summaries, active highlight, click → `switchSession` called with the id, "+ New" → `startNewSession`.
- [x] **T2 — History hydration (reload restores conversation).** Thread `turnId` onto `ChatMessage` (user + assistant bubbles). New `hydrateSession(id)` in `useAgentSession`: `getSession(id)` → visible turn pointers → parallel `getSessionTurn(id, turnId)` → map each turn to a user-question `ChatMessage` + an assistant-envelope `ChatMessage` (envelope via `FormatEnvelope.fromJSON`), then `chatStore.setMessages(...)`. `init` adopts an existing session + hydrates when one is resumable; `switchSession` hydrates on select. **Tests first:** `hydrateSession` spec — mocked `getSession` + per-turn envelopes produce the expected ordered `ChatMessage[]` with `turnId`s; empty session → welcome only.
- [x] **T3 — `edit_resend` typed action (BFF route + FE wiring).**
  - **BFF:** `POST /v1/action` (`ActionRoutes.kt`) consuming `TypedActionRequest` (camelCase JSON per contracts §1). `TypedActionDispatcher.editResend`: owner-check, parse `{editedQuestion, fromTurnId}`, `store.discardTurnsAfter(sessionId, fromTurnId)`, then re-enter the normal turn flow via the existing `ChatDispatcher` and stream `IrisStreamEvent` SSE. Unknown/other kinds → terminal `error` event (`code=NOT_IMPLEMENTED`, recoverable=false). **Tests first:** `ActionRoutesSpec` (testApplication + Wiremock-golem + in-memory store) — `edit_resend` discards turns-after + streams a fresh turn; unknown kind → error tail; non-owner → 404; bad payload → 400.
  - **FE:** implement `typedAction.editResend({sessionId, fromTurnId, editedQuestion})` against `/v1/action` (SSE, same consumer as `streamTurn`); rewire `ChatBubble.saveEdit` to use the edited user bubble's `turnId` + the optimistic `discardAfter`/`removeDiscarded`/`restoreDiscarded` machinery (already present). **Tests first:** `typedAction.test.ts` (endpoint + auth + SSE arms) + updated `ChatBubble` edit spec.
- [x] **T4 — `/reset` + snapshot-undo.**
  - **BFF:** `restoreLatestSnapshot(sessionId)` on `SessionStore` (InMemory + Exposed) — un-discard the latest snapshot's `turnIds`, restore its `entityContext`, return the rebuilt session; `POST /v1/session/{id}/undo` route (owner-checked) → `SessionDto`. **Tests first:** `InMemorySessionStore` undo spec (reset→undo restores turns + context; edit_resend→undo restores discarded tail); `SessionRoutesSpec` undo route (owner-check, no-snapshot → 409).
  - **FE:** `/reset` slash command → `irisStream.resetSession(id)` + `chatStore.clear()` + an **Undo** affordance (toast/chip) that calls the undo route then re-hydrates. **Tests first:** `slashCommands`/`ChatInput` `/reset` spec; undo re-hydrate spec.
- [x] **T5 — Slash-command audit + reconciliation.** Register `/reset` (request kind). Reconcile the degraded set against the v1 surface: `/sql` (armed-but-never-sent — surface "not supported yet" instead of silently arming, or gate behind a Phase-3 TODO), `/format` (works on the stream path — document), `/export` (FE-only blob — document). Update `slashCommands.ts` help text to match reality. **Tests first:** `slashCommands.test.ts` registry assertions (the command set + kinds + `/reset` present, `/sql` no longer silently arms).
- [x] **T6 — Component/e2e pass.** End-to-end FE component flow (vitest+jsdom): create → switch → hydrate → edit_resend → reset → undo, asserting `chatStore` + calls. BFF component coverage for `/v1/action` (edit_resend) + `/v1/session/{id}/undo` in the testApplication suite. Green: FE vitest + vue-tsc + build + lint; iris-bff `test-kt` + ktlint.

## DONE
`just test-kt iris-bff` + FE vitest/tsc/lint green; multi-session daily-driver UX complete (list/switch/hydrate/edit-resend/reset/undo) against the BFF. Live cross-session smoke on bp-dsk deferred to the Stage 2.4 deploy (and to a `/v2`-speaking golem for the turn leg, per the standing 2.2 note). Plan §9 Stage 2.3 ticked.

## Out of scope (→ later stages)
- The full `/v1/action` surface (`sort`/`filter`/`paginate`/`select_row`/`chip_invocation`/`reask_agent`) — **Phase 3 Stage 3.2**; this stage lands only `edit_resend` + the route skeleton.
- Themis routing per turn, HandoffContext, RoutingPickChip — **Phase 3 Stage 3.1**.
- Live in-cluster cross-session + turn smoke — **Stage 2.4** (needs the FE deployed + a `/v2` golem behind the BFF).
