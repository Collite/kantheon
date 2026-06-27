# Golem Phase 3 · Stage 3.2 — clarification + resume + selection

> **Arc.** Golem Phase 3 (conversational surface). **Branch.** `feat/golem-p3-s3.2-clarification-resume`.
> **Companions.** [`plan.md`](./plan.md) §5 Stage 3.2 (+ §10 Δ2/Δ3/Δ4), [`../../../architecture/golem/architecture.md`](../../../architecture/golem/architecture.md) §4 (graph + resume), [`../../../architecture/golem/contracts.md`](../../../architecture/golem/contracts.md) §3 (resume/action routes) / §4 (golem_turns), [`../../../architecture/themis/contracts.md`](../../../architecture/themis/contracts.md) (RESUME mode — pin-by-id), [`../../../architecture/iris/architecture.md`](../../../architecture/iris/architecture.md) (AgentDispatcher / GolemClient). The S2.4 `resolve_selection` node + selection state.
> **Goal.** The full conversational loop runs via the BFF in dev — entity/intent/missing-arg/**param_fill** clarifications resume, **pin-by-id** entity resume (with splice fallback), and **row-detail selection** binds.
>
> **⚠ Re-scope baseline (2026-06-24, plan.md §10 Δ2/Δ3/Δ4).** The snapshot's "typed actions (sort/filter/paginate/select_row)" **largely no longer exist** in current Golem: paging is **client-side in the FE**, sort/filter were never built, `typed_action_handler.py` was **removed**. Only **`select_row`** survives — as the **row-detail selection** path (`resolve_selection` + `selection_context`, wired in S2.4), not a typed `/v1/action` re-issue. Two new resume features: **`param_fill`** (4th `PendingClarification.kind`) and **pin-by-id** entity resume (a Themis/Resolver RESUME contract). This stage drops the sort/filter/paginate `/v1/action` work and adds those two.

## Pre-flight

- Stage 3.1 closed — the format/SSE surface streams envelopes + clarification envelopes.
- Stage 2.4 closed — `resolve_selection` node + `selection`/`selected_rows`/`selection_context` state + the `bootstrap→execute` param-fill shortcut edge exist.
- **Themis RESUME surface confirmed** (plan.md §2 pre-flight): RESUME mode returns a `resolver_resume_token` + per-option `resolved_id`/`entity_type_ref`. If absent, T3 lands the text-splice fallback only and pin-by-id is deferred (raise as a Themis cross-arc item).
- iris-bff `AgentDispatcher` reachable for the native `GolemClient` (T5) — small Iris-side PR, flag-gated.

## Tasks

- [ ] **T1 — resume codec specs (tests first) — all 4 clarification kinds.** Round-trip specs for the resume token covering **`entity_choice | intent_choice | missing_arg | param_fill`** (the `PendingClarification.kind` is now a 4-value enum). HMAC integrity; tamper → reject; expiry. *(Reference: contracts §3 resume; themis resume codec pattern.)*
- [ ] **T2 — HMAC resume tokens + `emitClarification` node + `/v1/resume` + `param_fill`.** Implement `resume/` (HMAC sign/verify), the `emitClarification` node, and `POST /v1/resume`. **`param_fill` (Δ2):** an unbound required param emits `awaiting_clarification` `kind="param_fill"` (`error_code="PARAM_FILL_CLARIFICATION"`, option `id == param name`); resume re-enters via `resume_param_fill=true` + `bindParamFill(plan, paramName, answer)`, **skipping the cascade** (the `bootstrap→execute` shortcut edge from S2.4). Spec the cascade-skip.
- [ ] **T3 — pin-by-id entity resume (Δ3 — Themis RESUME contract).** Resume **pins the chosen entity by `resolved_id`** via a Themis/Resolver RESUME call (`resume_pinned`) instead of text-splice + re-resolve. The resume token carries `resolver_resume_token`; `ClarificationOption` carries `entity_type_ref` + `resolved_id`. Keep text-splice (`_splice_entity_choice`) as a **fallback** when the RESUME surface is unavailable. Tests on both paths. *(Reference: themis/contracts RESUME mode.)*
- [ ] **T4 — selection (`select_row`) end-to-end wiring.** Verify the row-detail path from S2.4 round-trips: a `select_row` reference → `resolve_selection` → `selection_context` → `_bind_selection_args` → a bound drill plan → answer. **Explicitly drop** sort/filter/paginate (FE owns paging; note in the stage README that they are kantheon-net-new beyond v2 parity if ever wanted). Tests on the selection fixture turns.
- [ ] **T5 — iris-bff native `GolemClient` (small Iris PR, flag-gated).** Add the native `GolemClient` to `AgentDispatcher` (alongside the `/v2` adapter), behind a per-session flag. SSE multiplex back to the FE unchanged. *(Reference: iris architecture AgentDispatcher; contracts §3 SSE.)*
- [ ] **T6 — component: BFF → Golem round-trips.** All four clarification kinds (entity/intent/missing_arg/param_fill) + the pin-by-id path (+ splice fallback) + the selection round-trip, exercised BFF→Golem in dev. Assert captured args + the second (resume) call, not just call counts.

> Order note: T1 (codec spec) gates T2/T3 (the two resume paths); T2 (`param_fill`) is self-contained within Golem; T3 (pin-by-id) carries the only cross-arc dependency (Themis RESUME); T4 wires the S2.4 selection path; T5 (Iris PR) precedes T6 (BFF round-trips). Stage runs to 6 tasks.

## Status (2026-06-24) — golem-side code-complete

- [x] **T1** — `ResumeCodecSpec`: HMAC-SHA256 round-trip across all 4 kinds (`entity_choice|intent_choice|missing_arg|param_fill`), tamper/wrong-secret/expiry/malformed all rejected.
- [x] **T2** — `ResumeCodec` + `ParamFill` (clarification envelope + `bindParamFill`) + `POST /v1/resume`. **param_fill (Δ2):** the rail's `MissingRequired` now surfaces a `ParamFillNeed` (not a hard fail); `AnswerService` emits an `awaiting_clarification` `kind="param_fill"` (`error_code="PARAM_FILL_CLARIFICATION"`, option id = param name) + resume token; resume binds the answer and re-enters at execute via the `nodeStart → execute` cascade-skip (`resumeParamFill`). **Validator relaxed:** a missing *required* param is no longer a validation failure (it's a param_fill concern); only *unknown* params are rejected.
- [x] **T3 (splice fallback) / structured pin-by-id** — `ClarificationOption` proto gained `entity_type_ref`+`resolved_id`; the resume token carries `resolver_resume_token`; `resumeBySplice` + `spliceText` implement the text-splice fallback (`_splice_entity_choice`). **The Resolver RESUME call itself is deferred** (golem has no Resolver client; §2 pre-flight allows the splice-only fallback) — a Themis cross-arc follow-up.
- [x] **T4** — selection (`select_row`) round-trip verified end-to-end in `AnswerServiceSpec` (selection → `selection_context` → `bindSelectionArgs` → bound drill plan → answer). Sort/filter/paginate explicitly dropped (FE-owned).
- [ ] **T5 — iris-bff native `GolemClient` (Iris-arc follow-up).** The golem surfaces (`/v1/answer` SSE, `/v1/resume`) are contract-complete and ready to wire. The native client + per-session flag land in the **iris-bff** module (currently code-complete/awaiting-deploy); kept out of this Golem branch to avoid destabilising that arc. Tracked as an Iris-side PR against the `AgentClient` seam.
- [ ] **T6 — component BFF→Golem** — gated on T5; the golem-side resume/answer/selection round-trips are covered by `AnswerServiceSpec`.

## DONE

Golem-side conversational loop complete: HMAC resume codec, `param_fill` clarification + cascade-skip resume, entity-choice splice fallback (+ pin-by-id proto fields), selection binding, `/v1/resume`. The iris-bff native `GolemClient` (T5) + its BFF→Golem component test (T6) are the remaining Iris-arc wiring against the now-ready golem surfaces.
