# Stage 4.1 — iris-bff headless client

> **Phase 4, Stage 4.1.**
>
> **Reads with.** [`tasks-p4-overview.md`](./tasks-p4-overview.md), [`plan.md`](./plan.md) §"Stage 4.1", [`../../../architecture/hebe/contracts.md`](../../../architecture/hebe/contracts.md) §1.2 (`hebe.v1` proto) + §3 (headless-client contract: §3.1 TurnOrigin, §3.2 OBO, §3.3 session naming, §3.4 pause), [`../../../architecture/hebe/architecture.md`](../../../architecture/hebe/architecture.md) §7 (the flow), [`../../../../CLAUDE.md`](../../../../CLAUDE.md) §4 (proto packaging).

## Goal

Hebe drives a chat turn through **iris-bff** with OBO identity, consuming the same SSE stream the Vue FE would. The `hebe.v1` proto lands. An `IrisBffClient` (a `tools/builtin` "kantheon" tool family) posts a turn and maps the stream to `RoutineRun` statuses, including `AWAITING_AGENT` on agent pause. DONE = a manual routine run produces a turn visible in Iris session history.

## Pre-flight

- [ ] **Phase 4 pre-flight** met (Phase 3 done; iris-bff reachable in K3s; OBO `currentBearer()` available; outbox seam present).
- [ ] **Branch**: `feat/hebe-p4-s4.1-iris-bff-client`.
- [ ] Confirm `iris/contracts.md` §1.2 — `ChatTurnRequest.origin`/`origin_ref` + the session-create + turn-POST + SSE endpoints Hebe will call. Build the client against the proto, not hand-rolled JSON (wire policy).

## Tasks

- [ ] **T1 — Tests first: Wiremock'd iris-bff (session + turn + SSE).**

  Create `IrisBffClientSpec` stubbing iris-bff with Wiremock (`ktor-client`, EXAMPLES.md §9 + §7 for the in-repo-MCP-client shape). Cover:

  - **Session create** — `POST` session returns a `session_ref`.
  - **Turn POST** — `POST` a `ChatTurnRequest` with `origin = SCHEDULED`, `origin_ref = <routine_id>`, OBO bearer header.
  - **SSE consumption** — consume `step` / `envelope` / `done` / `error` events; assemble the terminal envelope.
  - **Reconnect** — a dropped stream reconnects/resumes (Hebe is a patient client — no human waiting, architecture §7).
  - **Timeout** — a stalled stream times out into a `FAILED`-mapped outcome (never hangs forever).

  Acceptance: specs written and failing. Commit `[hebe-p4-s4.1] failing iris-bff client specs`.

- [ ] **T2 — `hebe/v1` proto + codegen wiring.**

  Author `shared/proto/src/main/proto/org/tatrman/kantheon/hebe/v1/hebe.proto` exactly as contracts §1.2: `Routine`, `RoutineBody` (oneof `skill`/`tool`/`kantheon`), `SkillBody`, `ToolBody`, `KantheonQuestionBody`, `RoutineRun`, `RunStatus` (incl. `AWAITING_AGENT = 2`), `DeliveryRecord`. Rule 6 (`messages = 99`) + Rule 7 (`args_json`, camelCase wire keys) inherited. Package root `org.tatrman.kantheon.hebe.v1` (constellation root — Hebe is an agent, CLAUDE.md §4). Run `just proto`; verify Kotlin bindings.

  **Proto round-trip spec** (mirrors the Themis `CapabilitiesProtoSpec` pattern): `RoutineRun` with `AWAITING_AGENT` + a `DeliveryRecord` round-trips through bytes; `KantheonQuestionBody` carries `question`/`session_ref`/`delivery_channels`/`routing_hint`.

  Acceptance: proto compiles; round-trip spec green via `just test-kt shared:proto`.

- [ ] **T3 — `IrisBffClient` module (the "kantheon" builtin tool family) using OBO.**

  Implement `IrisBffClient` in `:agents:hebe:modules:tools:builtin` (the "kantheon" tool family). It uses the OBO bearer from Stage 2.3 (`OboTokenService.currentBearer()`) on every call; base URL from `[kantheon].iris_bff_url` (remote = public ingress, in_cluster = svc URL, none = empty). On `personal` the H→I call goes through the Phase 2 **outbox** (Stage 2.5) — the client exposes an enqueue path the outbox drains, not only a synchronous call.

  Acceptance: T1 session-create + turn-POST specs pass; OBO header present; the outbox path is wired (re-uses the Stage 2.5 contract).

- [ ] **T4 — Per-routine session management.**

  First run of a routine creates a session titled `"⏰ <routine name>"` (contracts §3.3) via the ordinary iris-bff session API and stores `session_ref` on the routine; subsequent runs append turns to the same session (the user gets investigation history for free, architecture §7). Use the `routines.session_ref` column added in Phase 3 (contracts §4.1).

  Acceptance: a second run of the same routine appends to the stored `session_ref` (spec against Wiremock).

- [ ] **T5 — Stream-state → `RoutineRun` status mapping (incl. `AWAITING_AGENT`).**

  Map the SSE lifecycle to `RunStatus`: `RUNNING` while streaming; `DELIVERED` on terminal envelope + successful delivery (delivery itself is Stage 4.2); `FAILED` on stream error/timeout; **`AWAITING_AGENT`** when the stream reports an agent pause (`AWAITING_*`). On pause, Hebe does **not** attempt to answer clarifications itself in v1 (contracts §3.4) — it records `AWAITING_AGENT` and (Stage 4.2) emits a deep-link channel message.

  Acceptance: T1 SSE + pause specs pass; the status mapping is exhaustive over the event types.

- [ ] **T6 — Trace propagation: one trace cron-tick → iris-bff → agent → delivery.**

  Use the W3C trace-context propagation wired in Phase 2 Stage 2.4 T4 so a single trace spans the cron tick → iris-bff turn → agent → (Stage 4.2) delivery (architecture §8). Assert with an in-memory exporter + a Wiremock server capturing `traceparent` that the iris-bff call carries the routine-fire trace.

  Acceptance: trace continuity proven at the unit level; **manual** run produces a turn visible in a real Iris session history (deployment confirmation). PR `[hebe-p4-s4.1] iris-bff headless client`.

## DONE — Stage 4.1

- [ ] All six tasks checked.
- [ ] `hebe/v1` proto landed (`org.tatrman.kantheon.hebe.v1`); round-trip spec green.
- [ ] `IrisBffClient` posts a turn with `origin=SCHEDULED` + OBO bearer; SSE consumed; reconnect + timeout handled.
- [ ] Per-routine session create/append works; `RoutineRun` status mapping covers `AWAITING_AGENT`.
- [ ] Trace spans cron-tick → iris-bff.
- [ ] Manual routine run produces a turn visible in Iris session history.
- [ ] PR merged.

## Library / pattern references

- **contracts.md §1.2** — the `hebe.proto` definition (byte-for-byte). **§3** — the headless-client contract (session naming, OBO, pause).
- **EXAMPLES.md §7** — Kotlin MCP/HTTP client calling an in-repo service. **§9** — Wiremock SSE stubbing. **§3** — `argsJson` + `messages = 99` wiring.
- **CLAUDE.md §4** — Hebe proto takes the constellation root `org.tatrman.kantheon.hebe.v1`.
- **iris/contracts.md §1.2** — `TurnOrigin` fields (already landed) Hebe sets.

## Out of scope for Stage 4.1

- The `kantheon_question` routine type + console CRUD + envelope→channel rendering + delivery records (Stage 4.2).
- Answering `AWAITING_*` clarifications (v1 rule: human resumes in Iris).
- Live E2E gating (integration suite).
