# Stage 5.2 — Iris integration

> **Phase 5, Stage 5.2.**
>
> **Reads with.** [`tasks-p5-overview.md`](./tasks-p5-overview.md), [`plan.md`](./plan.md) §7 Stage 5.2, [`../../../architecture/pythia/architecture.md`](../../../architecture/pythia/architecture.md) §6 (Iris rendering at v1), [`../../../architecture/pythia/contracts.md`](../../../architecture/pythia/contracts.md) §2 (REST + SSE) + §3 (lifecycle subject), [`../../../architecture/iris/contracts.md`](../../../architecture/iris/contracts.md) (IrisStreamEvent, envelope interactions, PD-9 Block.provenance), [`../../planning-conventions.md`](../../planning-conventions.md) §4.

## Goal

iris-bff drives Pythia: a `PythiaClient` submits + consumes the SSE bridge, mapping events to `IrisStreamEvent`; approval/clarification/budget pauses render as envelope interactions; synthesizer blocks render as envelopes (one per bubble, `agent_id: pythia`, executor refs in `Block.provenance` per PD-9); a joint component test with Iris Phase 4 (inbox + lifecycle subject + hypothesis tree) passes. **End state:** a chat-submitted SHALLOW investigation renders through the Iris envelope path; joint mocked test green.

## Pre-flight

- [x] Stage 5.1 DONE (or in parallel — 5.2 is BFF-side).
- [x] **iris-bff** reachable + **Iris Phase 4 Stage 4.1** (inbox + lifecycle subscriber + hypothesis tree) available — the joint test exercises it.
- [x] Branch `feat/pythia-p5-s5.2-iris-integration`.
- [x] Read `iris/contracts.md` — `IrisStreamEvent` (`step` with `detail_json`, `envelope`), the envelope interaction types (`PendingClarification`, `PromptChips`, `RoutingPickChip`/`InvestigateChip`), `Block.provenance` (PD-9).

## Tasks (TDD-shaped)

- [x] **T1 — iris-bff `PythiaClient`.**

  Implement `PythiaClient` in **iris-bff** (this task lands in the iris-bff module, coordinated with the Iris arc): submit (`POST /v1/investigations`) + consume the **SSE bridge** (`GET …/events?from_seq`), mapping Pythia `InvestigationEvent`s → `IrisStreamEvent` per iris/contracts: lifecycle/hypothesis/batch events → `IrisStreamEvent.step` (with `detail_json`); synthesizer blocks → `envelope` events. iris-bff consumes the SSE bridge, **not NATS directly** (divergence 4).

  Test (Wiremock Pythia SSE): the BFF maps a fixture event stream to the expected `IrisStreamEvent` sequence.

  Acceptance: `PythiaClientSpec` (iris-bff) green.

- [x] **T2 — Approval / clarification / budget prompts as envelope interactions.**

  Render the AWAITING_* pauses as envelope interactions: AWAITING_RESOLUTION_INPUT / AWAITING_USER_INPUT → `PendingClarification` + `PromptChips`; AWAITING_PLAN_APPROVAL / AWAITING_PLAN_REVISION_APPROVAL → a chip pair (approve / reject-with-comment); **AWAITING_BUDGET_DECISION → the budget-decision flow** (CONTINUE / HALT_GRACEFULLY / ABANDON chips). The chip actions call back the Pythia control endpoints (contracts §2).

  Test: each AWAITING_* renders the right interaction; a chip action issues the right control call.

  Acceptance: `AwaitingInteractionSpec` green.

- [x] **T3 — Synthesizer blocks → envelopes (PD-9 provenance).**

  Map synthesizer blocks to envelopes: block-per-bubble, `agent_id: "pythia"`; add executor refs to `Block.provenance` (step / hypothesis / model id — PD-9, "how was this computed"). 

  Test: a synthesised conclusion renders as N envelope bubbles each carrying provenance refs.

  Acceptance: `SynthEnvelopeSpec` green.

- [x] **T4 — Joint component test (mocked Pythia + iris-bff testApplication).**

  A chat-submitted **SHALLOW** investigation renders end-to-end through the Iris envelope path; **joint with Iris Stage 4.1** — the inbox lists it (via `GET /v1/investigations` + the `pythia.lifecycle.{user_id}` subject), the lifecycle subject drives the badge, and the hypothesis tree renders from the events. All mocked (live in-cluster Pythia→Iris e2e → integration suite, plan §7 Stage 5.2 T4).

  Test: submit → inbox shows it → lifecycle transitions update the badge → conclusion renders; assert each leg.

  Acceptance: joint component spec green.

- [x] **T5 — Close.**

  `just test-kt pythia` + the iris-bff suite green; update [`tasks-p5-overview.md`](./tasks-p5-overview.md); record integration carry-overs (live Pythia→iris-bff SSE under load, real lifecycle-subject fan-out). 

  > **Scope note (PD-2, 2026-06-12):** inbox + hypothesis tree are **Iris Phase 4 Stage 4.1 (v1)** — not deferred. Only the **plan-DAG pane** stays deferred (`kantheon-v1.1.md` §2). Do not re-defer the inbox/tree.

  Acceptance: CI green on `[pythia-p5-s5.2] iris integration`.

## DONE — Stage 5.2

- [x] All tasks checked; joint mocked test green.
- [x] A chat investigation renders through Iris with approvals/budget/clarification + inbox + hypothesis tree.
- [x] Integration carry-overs recorded (live Pythia→iris-bff SSE/`/events?from_seq` reattach, real lifecycle-subject NATS fan-out).
- [x] CI green on `[pythia-p5-s5.2] iris integration`.

## Library / pattern references

- **architecture §6** (Iris rendering at v1 — events→step, blocks→envelope), **iris/contracts.md** (`IrisStreamEvent`, interaction types, `Block.provenance` PD-9), **contracts §2/§3** (control endpoints + lifecycle subject).

## Out of scope

- The dedicated investigation UI (plan-DAG pane) — v1.1 (`kantheon-v1.1.md` §2). Inbox + hypothesis tree are **in** (Iris P4 S4.1).
- Eval gate + hardening + ship — Stage 5.3.
