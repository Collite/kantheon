# Kantheon — Product Design Issues

> **Status: COMPLETE — all 15 issues resolved 2026-06-12.** This document is now the *record* of the Kantheon v1 product-design convergence: every issue carries its full Resolution with pointers to where the decision landed (contracts, `kantheon-security.md`, the Hebe arc, `kantheon-v1.1.md` for the deferred parts). Retained as the design rationale; do not reopen issues here — new product concerns get new documents.
>
> Originally: living working document, opened 2026-06-12 as the output of the whole-constellation product design review.
>
> **Purpose.** This is the working list for converging on the final Kantheon v1 product design. Each issue gets discussed, decided, and its resolution folded back into the relevant architecture / contracts / plan docs. The issue then flips to **Resolved** here with a pointer to where the decision landed.
>
> **How to read.** Issues are grouped into (A) missing relations between components and (B) product gaps vis-à-vis planned usage. Each carries: what's wrong, why it matters, which docs/arcs it touches, and a proposed direction (a starting point for discussion, not a decision).
>
> **Review basis.** `kantheon-architecture.md` + the full design/architecture/contracts/plan sets for Iris, Themis, Pythia, Golem, Charon, Metis, Midas, capabilities-mcp (state as of 2026-06-12, the day the Iris/Golem/Pythia arcs were planned).

---

## Issue index

| ID | Title | Group | Severity | Status |
|------|--------------------------------------------------------|-------|-------------------|--------|
| PD-1 | Golem → Pythia escalation handoff undefined | A | Critical | **Resolved 2026-06-12** |
| PD-2 | Iris ↔ Pythia lifecycle beyond streaming (investigation inbox) | A | Critical | **Resolved 2026-06-12** |
| PD-3 | No feedback loop into Themis / Golem / Pythia | A | High | **Resolved 2026-06-12** |
| PD-4 | EntityContext is a convention, not a contract | A | High | **Resolved 2026-06-12 (via PD-1)** |
| PD-5 | Pythia ↔ Metis/Charon session-lifecycle mismatch | A | High | **Resolved 2026-06-12** |
| PD-6 | Dashboards/saved artifacts buried in Midas arc | A | High | **Resolved 2026-06-12** |
| PD-7 | capabilities-mcp has no human-facing surface ("what can I ask?") | A | Medium | **Resolved 2026-06-12** |
| PD-8 | Authorization + audit trail absent | B | Critical (launch blocker) | **Resolved 2026-06-12** |
| PD-9 | Provenance / explainability not first-class on Block | B | High | **Resolved 2026-06-12** |
| PD-10 | Everything is ephemeral — no saved/parameterized/scheduled work | B | Medium | **Resolved 2026-06-12 (via PD-6 + Hebe arc)** |
| PD-11 | Pythia budget controls incomplete; no cost attribution | B | Medium | **Resolved 2026-06-12 (enforcement → v1.1)** |
| PD-12 | Shem authoring has no toolchain | B | High | **Resolved 2026-06-12 (direction locked; build → v1.1)** |
| PD-13 | Multi-question decomposition loses comparative intent | B | Medium | **Resolved 2026-06-12** |
| PD-14 | Misroute recovery UX (confident misroutes) | B | Medium | **Resolved 2026-06-12** |
| PD-15 | Sharing / collaboration / session search absent | B | Low (v2 candidate) | **Resolved 2026-06-12 (explicit v2 deferral)** |

---

## Group A — Missing relations between components

### PD-1 — Golem → Pythia escalation handoff undefined

**Severity:** Critical · **Status:** Open · **Touches:** `themis/contracts.md`, `golem/contracts.md`, `pythia/contracts.md`, `iris/contracts.md`, envelope chips

**Problem.** The canonical product session is: user asks Golem-ERP "Q4 sales?", gets an answer, then asks "why did they drop?". Per-turn Themis routing *should* send turn 2 to Pythia, but the handoff is undefined:

- `themis_prior_context` exists in the proto surface with no documented semantics.
- There is no contract for carrying Golem's conversational context — the table the user is looking at, active entity bindings, the query that produced the displayed result — into a Pythia `Investigation` kickoff.
- Golem has no way to *signal* that a question exceeds its capability mid-conversation (it either answers or refuses; there's no "this needs investigation" outcome).

Without this, the platform's flagship moment — Q&A escalating into investigation — silently starts from a cold context.

**Why now.** Golem and Pythia arcs are planned but pre-execution; the handoff contract is cheap to add now and expensive to retrofit.

**Proposed direction.** Define a single `HandoffContext` message (probably in `envelope/v1` or `capabilities/v1`) used in both directions: Themis → chosen agent (as the now-specified `themis_prior_context`) and Golem → Pythia escalation. Add an `EscalationSuggested` outcome to Golem's `ConversationalResponse` that Iris renders as an "Investigate this" chip; chip click kicks off a Pythia investigation with the `HandoffContext` populated (displayed envelope ref, entity bindings, originating query, Shem id).

**Resolution (2026-06-12, Bora-approved).** Landed as `org.tatrman.kantheon.common.v1` — `shared/proto/.../common/v1/handoff.proto` (`HandoffContext` + `EntityBinding` + `ViewProvenance`). The design:

1. **Routing was never the gap** — Iris re-routes every turn through Themis; the fix is *typed context*, not new routing.
2. **`HandoffContext`, assembled by Iris-BFF on every dispatch** from the previous `TurnPointer` + `EntityContext` + the agent-echoed view provenance. Sent to Themis in `ResolveRequest` (this *is* the definition of `themis_prior_context` — coreference resolves against `entities`) and to the routed agent. Pythia seeds investigations from it (anchor query, entities, view); conclusions link back to `source_turn_ref`.
3. **`ViewProvenance` = "what the user is looking at"**, field-for-field from new-golem v2's `CurrentView` (`pattern_id, args_json, sql, bubble_id, total_rows`). Contract rule: **agents echo `current_view` on every response; Iris snapshots it into the `TurnPointer`**. Consistent with the locked ownership split (`current_view` agent-owned).
4. **Escalation = `InvestigateChip { handoff, proposed_question }`, never a direct Golem→Pythia call.** Chip click re-issues a turn with `routing_hint = pythia` + the handoff (Layer 0 honors it). Two emitters: Golem (confidence-gate failure with analytical smell — and optionally alongside partial answers; it's metadata, not control flow) and Iris itself (always-on "Investigate this" drilldown action on any table/chart block — pure BFF-side). Stays in the session, audit trail, PD-2 inbox.
5. **Inline payload in v1** (no peer artifact-read API); `source_turn_ref`/`envelope_ref` enable a pull API later without re-contracting.

Cross-arc insertion points: Iris contracts (assembly rule, TurnPointer snapshot field, InvestigateChip rendering, drilldown action), Golem contracts (current_view echo on `ConversationalResponse`, applied-context echo, chip emission), Pythia contracts (`Investigation.context.handoff` + seeding semantics), Themis contracts (`ResolveRequest.prior_context` typed as `HandoffContext`). Also resolves PD-4 (see below) and pre-shapes PD-9 (`ViewProvenance` is the shared provenance type).

---

### PD-2 — Iris ↔ Pythia lifecycle beyond streaming (investigation inbox)

**Severity:** Critical · **Status:** Open · **Touches:** `iris/architecture.md`, `iris/contracts.md`, `pythia/contracts.md`

**Problem.** Pythia has 11 investigation statuses and four `AWAITING_*` pause states; Iris defines only synchronous SSE streaming with 15s heartbeats. Nobody owns:

- a list of running / paused / completed investigations per user,
- notification when a long investigation completes or pauses on a question,
- resume after browser close / SSE disconnect,
- cancel.

Pythia's pause states currently have **no UI owner**. The Iris plan defers "investigation-specific UI" (hypothesis tree, DAG panes) — legitimately — but the *lifecycle* surface is not the same thing and is absent, not deferred.

**Proposed direction.** An "investigation inbox" at the Iris level: BFF endpoint listing the user's investigations with status (reading Pythia's persisted `Investigation` records), an FE pane/badge, reconnect-to-stream on click, and a resume affordance for `AWAITING_*` states. No investigation-internals rendering required — status + last envelope is enough for v1.

**2026-06-12 note.** Hebe (moved into `agents/hebe`) brings out-of-band notification channels (Telegram, web console) — a candidate delivery path for "your investigation finished / paused on a question" pushes, complementing the in-Iris inbox. To be decided in the Hebe integration arc.

**Resolution (2026-06-12, Bora-approved).** The inbox is a *view over Pythia's persisted state*, no second store. Pythia adds: `GET /v1/investigations?user_id=…` (`InvestigationSummary` list), the coarse lifecycle subject `pythia.lifecycle.{user_id}` on NATS (status transitions only), and locked halt semantics = **cancel-with-partials** (drain → synthesis over findings-so-far → partial `Conclusion`). The resumable per-investigation stream (`/events?from_seq=N`, PG replay + NATS tail) already existed in Pythia's contracts and is the reattach mechanism. Iris adds (`iris/contracts.md` §2.5): `GET /v1/inbox` (aggregation ⋈ session/turn/origin), `GET /v1/inbox/stream` (NATS-fed SSE fan-out; polling fallback), the 11→5 status mapping (Running / Needs your input / Done / Failed / Cancelled), header badge + dockview panel with cost-so-far (PD-11 visibility), reattach-on-click, and — **scope amended by Bora: included from the start for debugging** — a debug-grade **hypothesis-tree pane** rendered live from `InvestigationArtifact.hypotheses` + stream events. Plan-DAG pane stays deferred. Scope: investigations only in v1; Hebe pushes for *interactive* runs via the lifecycle subject at v1.x (zero Pythia change). Iris plan §7 amended accordingly.

**Resolution.** —

---

### PD-3 — No feedback loop into Themis / Golem / Pythia

**Severity:** High · **Status:** Open · **Touches:** `iris/contracts.md` (turn persistence), all agents' `eval/` corpora

**Problem.** Misroutes (Themis), wrong answers (Golem), wrong conclusions (Pythia) — no thumbs up/down, no misroute flag, no production→eval pipeline, despite every agent carrying an `eval/` directory. `RoutingPickChip` covers only *low-confidence* routing; a confident misroute leaves edit-and-resend as the user's only recourse. For a product whose value is trusted answers, answer-quality signal is core telemetry, not a nicety.

**Proposed direction.** Cheap v1: per-turn feedback (👍/👎 + optional "wrong agent" flag) persisted in Iris's Postgres alongside the `TurnPointer`, attributed to the producing `agent_id` and the `RoutingDecision`. Periodic export into each agent's `eval/` corpus format. No ML, no re-weighting in v1 — just close the data loop so the corpora grow from production.

**Resolution (2026-06-12, Bora-approved).** Principle: *capture is cheap and synchronous; learning is curated and offline* — no agent sees feedback at runtime in v1. Landed in `iris/contracts.md` §2.6 + §3.2: 👍/👎 per answer bubble with one-tap reason on 👎 (`wrong_data | wrong_agent | wrong_format | too_slow | other`); `POST /v1/turns/{id}/feedback` upserting `iris_feedback` (plain table — telemetry, not audit; `corrected_agent_id` column ready for PD-14's re-ask action, the strongest misroute label). Export: `just feedback-export` materializes per-agent **`eval/candidates/`** entries via schema adapters (Themis gets labeled routing examples from wrong-agent + correction pairs); **human curation before promotion** into gate corpora. Visibility: `feedback_total{agent_id, verdict, reason}` metric → Grafana. (Feedback DDL is `iris/contracts.md` §3.4 after the PD-6 artifact schema took §3.3.) Deferred to v1.1: implicit signals (edit-resend-after-answer, abandoned clarifications), scheduled exporter job, any runtime routing re-weighting — see `kantheon-v1.1.md`.

---

### PD-4 — EntityContext is a convention, not a contract

**Severity:** High · **Status:** Open · **Touches:** `iris/contracts.md`, `golem/contracts.md`, `pythia/contracts.md`

**Problem.** Iris passes `EntityContext` inside `conversation_excerpt`; no contract obliges agents to consume it. An agent that ignores it returns stale-scoped results with no warning — the user thinks they're still looking at Kaufland, the agent answered for all customers. This is a silent-wrong-answer class of bug, the worst kind for a data product.

**Proposed direction.** Make context consumption contractual: agents must echo back the entity bindings they actually applied (e.g. an `applied_context` field on `ConversationalResponse` / `InvestigationArtifact`). Iris compares against what it sent and renders a visible scope indicator (and a warning on mismatch). Cheap to implement, converts silent failure into visible state.

**Resolution (2026-06-12, via PD-1).** As proposed, using PD-1's shared type: agents echo `repeated common.v1.EntityBinding applied_context` (plus `ViewProvenance current_view`) on every response; Iris compares against what it dispatched, renders a scope indicator, and warns on mismatch. Lands in Golem/Pythia contracts alongside the PD-1 echo rule — one mechanism, both issues.

---

### PD-5 — Pythia ↔ Metis/Charon session-lifecycle mismatch

**Severity:** High · **Status:** Open · **Touches:** `metis/contracts.md`, `charon/contracts.md`, `pythia/architecture.md`

**Problem.** Metis workspaces (series + fitted models) and Charon-staged data carry TTLs; Pythia investigations checkpoint and can pause (`AWAITING_*`) for longer than those TTLs. A resumed investigation may find its fitted models and staged Arrow data gone, with undefined behavior.

**Proposed direction.** Pick one explicit rule: (a) Pythia re-materializes session state from its own checkpoint on resume (Metis/Charon stay TTL-simple; Pythia's checkpoint must capture enough to re-fit/re-stage), or (b) session TTL is pinned to investigation lifetime (Pythia extends/releases leases). Option (a) keeps the services stateless-ish and is the consolidation-friendly choice; cost is re-computation on resume.

**Resolution (2026-06-12, Bora-approved).** Option (a): **re-materialize from checkpoint; Pythia never holds leases.** Checkpoint records per-handle *recipes* (Charon move spec / Metis fit spec) + the Arrow **data fingerprint** at materialization. Resume probes liveness (Charon `Describe`, Metis `GetStatus`), lazily re-materializes only dead handles the plan still needs; fingerprint drift → Rule-6 warning + `LooseEnd`, never hard-fail, never silent epoch-mixing. Landed: `pythia/contracts.md` §3a + notes in `charon/contracts.md` (DescribeResult) and `metis/contracts.md` (workspace TTL config).

---

### PD-6 — Dashboards/saved artifacts buried in the Midas arc

**Severity:** High · **Status:** Open · **Touches:** `midas/architecture.md` (Phase 3), `iris/architecture.md`, `envelope/v1`

**Problem.** "Saved, refreshable visual artifact" is a constellation-level concept — any Golem or Pythia output should be pinnable — but it's currently planned as Midas Phase 3 (Iris's dashboard system appears there, in a domain arc). Risk: the first implementation gets baked in brokerage-domain-specific and has to be unwound.

**Proposed direction.** Lift a generic `artifact` concept into Iris/envelope: saved envelope + parameter bindings + refresh policy (manual / on-open / scheduled). Midas dashboards become the first consumer rather than the owner. Sequencing note: this must be decided **before Midas Phase 3 starts**, not necessarily built before.

**Resolution (2026-06-12, Bora-approved incl. the Midas reframe).** **Pins + dashboards** in `iris/contracts.md` §2.5a + §3.3 (`iris_artifacts`): a pin = envelope snapshot + `ViewProvenance` + `applied_context` + BFF display state; a dashboard = named pin collection + layout + optional domain-supplied template. **Refresh = deterministic re-execution, never an LLM call** — Golem pins via the typed-action surface, Pythia pins via existing `replay` (moving params) / `reproduce` (frozen); failure → explicit stale/error state. `refresh_mode: manual | on_open` in v1; `scheduled` → v1.1 as Hebe routine kind `artifact_refresh` (Hebe owns scheduling + bound-user OBO). Per-pin editable params = PD-10 layer 1. PD-1/PD-4/PD-9 resolutions compose into the tile trust surface (provenance ⓘ, scope indicator, refreshed-at). **Midas reframed to consumer:** `midas/architecture.md` §11 + `contracts.md` §7 superseded (notes added; `agent_call_spec` → `ViewProvenance`); Midas plan Stage 3.5 rewritten — templates (`investment-overview:v1`), report-preview pane kind, content, E2E; system gated on the Iris-arc artifact stage (sibling of the PD-2 inbox stage). Deferred to v1.1: scheduled refresh, dashboard-shared parameters.

---

### PD-7 — capabilities-mcp has no human-facing surface

**Severity:** Medium · **Status:** Open · **Touches:** `capabilities-mcp` design, `iris/architecture.md`

**Problem.** The registry is machine-readable only. Nothing answers the user's first question: *"what can I ask?"* No first-run experience, no per-domain suggested questions, no empty-state. For a chat product over invisible data, discoverability is the difference between adoption and a blank-box bounce.

**Proposed direction.** Iris-BFF already reads `list_agents()` for display names; extend to surface `ShemManifest.preferred_queries` / domain descriptions as: (a) first-run "here's what I can answer" panel per domain, (b) suggested-question chips on empty state. Nearly free — the data already exists in the manifests.

**Resolution (2026-06-12, Bora-approved).** `GET /v1/discover` on iris-bff (`iris/contracts.md` §2.4b): per-domain `DomainCard`s from the capabilities cache, **role-filtered per PD-8** (you only discover what you may ask; `non_routable` excluded); `example_questions` as the v1 chip source (`preferred_queries` display text → v1.1). Surfaces: first-run/empty-session panel + suggested-question chips on empty input. capabilities-mcp untouched.

---

## Group B — Product gaps vis-à-vis planned usage

### PD-8 — Authorization + audit trail absent

**Severity:** Critical — launch blocker · **Status:** Open · **Touches:** `iris/architecture.md` §8, `themis/architecture.md`, all agents, new cross-cutting doc needed

**Problem.** Keycloak gives identity; nothing gives per-domain authorization. The moment Golem-HR exists, "valid token = can ask about payroll" is untenable. There is also no audit trail of who asked what and saw which data — required for ERP/HR/finance customers. Row-level security is implicitly delegated to agents but undocumented.

**Proposed direction.** Three-point design, one cross-cutting doc:

1. **Themis filters the registry by the caller's roles** — you cannot be routed to an agent you're not entitled to see (single enforcement point, elegant; agents invisible rather than forbidden).
2. **Agents enforce on their side too** (defense in depth; role claim forwarded in the request context).
3. **Iris owns the audit log** — it already structurally holds turns + envelopes per user; an append-only audit projection of that is cheap.

Row-level security within a domain (user X sees only their region's data) is a per-Shem concern → field in `ShemManifest`.

**Resolution (2026-06-12, Bora-approved).** Full design in [`/docs/architecture/kantheon-security.md`](../architecture/kantheon-security.md). Summary: Kantheon builds no authz engine — it adds (1) **domain entitlements**: `visibility_roles = 17` on `AgentCapability` (convention roles `kantheon-domain-<shem>`, mapped to customer groups in Keycloak); Themis filters its routing view before Layer 1 (invisible-not-forbidden; `GapKind.NO_ENTITLED_AGENT = 5`; explicit naming → reveal existence, deny access); agents re-check at admission; (2) **identity propagation**: agents call query-mcp with the user's OBO token, never service identity — ai-platform's Validator RLS then works per-user end-to-end (incl. Hebe's scheduled turns); Pythia cross-domain = constrain-and-disclose (plans against the role-filtered registry; exclusions disclosed in the Conclusion); (3) **audit**: hash-chained append-only `iris_audit` reusing Hebe's receipts shape (one chained-log format constellation-wide), monthly segments, configurable retention (`iris.audit.retention_months`, default unlimited), covering turns / typed actions / exports / resumes / escalations with PD-1's `ViewProvenance` as the "what data they saw" record. Rejected: ACL service, per-session ACLs (→ PD-15), full invisibility, refusing partially-entitled investigations. Contract patches landed in capabilities.proto, themis/contracts.md, iris/contracts.md §3.1.

---

### PD-9 — Provenance / explainability not first-class on Block

**Severity:** High · **Status:** Open · **Touches:** `envelope/v1`, `golem/contracts.md`, `pythia/contracts.md`

**Problem.** Pythia produces conclusions; Golem produces answers; provenance (which queries, which source tables, which models, what confidence) is not a first-class property of the rendered blocks. Drilldown exists for data navigation, but lineage from conclusion → step → query → table is not a product surface. "Show me how you got this" should be one click on any block — it's the trust mechanism for the whole platform, and it becomes more important as Charon (data movement) and Metis (models) enter the pipeline.

**Proposed direction.** Optional `provenance` field on `Block` (query id / SQL hash / source tables / model ref / producing step id). One field powers three things: user-facing explainability, the PD-8 audit trail, and cache-invalidation reasoning. Agents populate what they know; Iris renders a uniform "ⓘ how was this computed" affordance.

**2026-06-12 note (from PD-1).** The provenance shape exists now: `common.v1.ViewProvenance` (field-for-field from new-golem v2 `CurrentView`). PD-9's remaining decision is only *placement and rendering* — attach it per-`Block` (possibly extended with model ref / step id for Pythia) and design the "how was this computed" affordance. Do not introduce a second provenance type.

**Resolution (2026-06-12, Bora-approved).** `common.v1.BlockProvenance` (wraps `ViewProvenance` + producing_agent_id + Pythia step_id/hypothesis_id + Metis model_ref + source_tables from `PipelineContext.used_objects` + model_version + computed_at) attached as `optional Block.provenance = 7` in envelope/v1 — adds the deliberate envelope→common import (bottom-layering; §4 tables updated). Population is copying, not computing: **envelope-render stamps it uniformly at format time** (consolidation — every agent using the lib gets it free); Pythia adds executor refs; agents fill what they know, absence = "provenance unavailable", never an error. Rendering: uniform ⓘ popover on table/chart blocks and PD-6 pin tiles — agent, query name, args, rows, source tables, model ref, computed-at; **raw SQL behind a collapsed expander, visible to all in v1** (no role gating; PD-8 roles can gate later if a customer demands). Free wins: `iris_audit` payload and the ⓘ popover are the same object; `hypothesis_id` gives conclusion→PD-2-tree navigation.

**Resolution.** —

---

### PD-10 — Everything is ephemeral

**Severity:** Medium · **Status:** Open · **Touches:** `iris/architecture.md`, `pythia/contracts.md`, overlaps PD-6

**Problem.** No saved or parameterized queries ("same but for Q3"), no re-run of a prior turn with changed bindings, no scheduled investigations ("run this RCA every Monday morning"). `/export` is client-side and ephemeral. Each analysis is one-off; the platform accumulates no reusable work product.

**Proposed direction.** Layered: (1) re-run-with-edited-bindings as a typed action (cheap — the function-call binding already exists in `Resolution`); (2) saved artifacts per PD-6; (3) scheduled investigations as a Pythia v1.5 feature (needs PD-2's inbox and PD-11's budgets first). Don't build (3) before (1)/(2) prove demand.

**2026-06-12 note.** Layer (3) has an owner: **Hebe** (moved into `agents/hebe` from `~/Dev/hebe`). Hebe's scheduler module (cron parser, `RoutinesEngine`, job repo/runner, heartbeat, maintenance jobs) plus its channels (Telegram/web/CLI) is the designated mechanism for "run this RCA every Monday at 3AM and message me the conclusion" — Hebe schedules and triggers, Pythia investigates, Hebe delivers. The Hebe↔Kantheon integration contract (how a Hebe routine kicks an Investigation, auth, result delivery) is a new arc to plan; Hebe currently ships as a self-contained Gradle build pending integration.

**Resolution (2026-06-12, via PD-6 + the Hebe arc).** All three layers have homes: (1) re-run with edited bindings = per-pin editable `params_json` on PD-6 artifacts (+ Pythia's `replay` for moving params); (2) saved artifacts = PD-6 pins/dashboards (`iris_artifacts`); (3) scheduled work = Hebe arc P4 (`kantheon_question` routines via iris-bff; planned in `implementation/v1/hebe/plan.md`), with scheduled *artifact refresh* as the v1.1 follow-on (Hebe routine kind `artifact_refresh`). Nothing remains open in this issue that isn't tracked in `kantheon-v1.1.md`.

**Resolution.** —

---

### PD-11 — Pythia budget controls incomplete; no cost attribution

**Severity:** Medium · **Status:** Open · **Touches:** `pythia/architecture.md`, `pythia/contracts.md`, llm-gateway

**Problem.** The 90%-budget ASK gate appears in Pythia's design but is missing from the 11 investigation statuses — the state machine can't represent "paused awaiting budget approval". There is no per-user/org budget concept and no cost attribution seam anywhere in the constellation (also flagged in the Midas review). Autonomous investigation without cost ceilings is a real operational risk.

**Proposed direction.** Add `AWAITING_BUDGET_APPROVAL` to the status enum (closes the state-machine hole); attribute LLM spend per investigation/turn via llm-gateway tags; per-user/org ceilings as v1.5. Cost display in the investigation inbox (PD-2) makes spend visible before it's enforceable.

**Resolution (2026-06-12, Bora-approved).** The state-machine hole was confirmed real (four `AWAITING_*` states, but `/budget-decision` had no parking status — the `on_budget_threshold: ASK` gate was unrepresentable) and **fixed: `AWAITING_BUDGET_DECISION` added** as the fifth `AWAITING_*` (12 status values; maps to `/budget-decision`; renders as Needs-your-input in the PD-2 inbox). Already in place from earlier resolutions: per-investigation `Constraints` budgets (Pythia design), cost-so-far visibility in the inbox (PD-2), cost-attribution headers from Hebe (PD-11 ledger item). **Deferred to v1.1: per-user/org ceilings + enforcement** (needs gateway-native attribution) — see `kantheon-v1.1.md` §5.

---

### PD-12 — Shem authoring has no toolchain

**Severity:** High · **Status:** Open · **Touches:** `golem/` arc, `capabilities-mcp`, metadata-mcp (ai-platform)

**Problem.** Golem's own docs call Shem content authoring "the single biggest task," yet the workflow is bare hand-written YAML. No schema validator, no bootstrap-from-warehouse, no per-Shem eval gate. Failure mode: silent drift between manifest and warehouse reality. Domain onboarding is the scaling lever of the entire platform — "new domain = new YAML" is only a selling point if producing correct YAML is tractable.

**Proposed direction.** Three tools, in order of leverage: (1) manifest schema validation in CI (trivial); (2) bootstrap generator that drafts a ShemManifest skeleton from metadata-mcp (tables, columns, terminology candidates); (3) per-Shem eval corpus + CI gate (the Golem template runs the corpus against a candidate manifest before deploy). (3) also consumes PD-3's production feedback over time.

**Resolution (2026-06-12, Bora-approved — direction locked, build deferred).** The toolchain direction is locked as proposed, **plus** the PD-8 onboarding step (create the `kantheon-domain-<shem>` realm role per new Shem). Four pieces, leverage order: (1) manifest schema validation in CI; (2) bootstrap-from-metadata-mcp skeleton generator; (3) per-Shem eval corpus + CI gate (fed over time by PD-3's `eval/candidates/`); (4) role-creation onboarding step. **Build deferred to v1.1 — after Golem-ERP proves the template** (hand-authoring the first Shem is the requirements-gathering exercise for the toolchain). See `kantheon-v1.1.md` §6.

---

### PD-13 — Multi-question decomposition loses comparative intent

**Severity:** Medium · **Status:** Open · **Touches:** `themis/` routing design (`MultiQuestionDetected`), `iris` decomposition UX

**Problem.** `MultiQuestionDetected` → Iris decomposes UI-side into N independent follow-up turns. "Compare ERP revenue growth with HR headcount growth" becomes two unrelated answers with nobody owning the comparison — which is exactly the cross-domain case Pythia exists for.

**Proposed direction.** Refine the rule: clauses with *disjoint* intents → split into N turns (current behavior); clauses with a *relating* intent (compare/correlate/explain-by) → route the whole turn to Pythia as a cross-domain question. This is a Themis classification refinement, not a new mechanism.

**Resolution (2026-06-12, Bora-approved).** `MultiQuestionDetected` gains `decomposition: SPLIT | KEEP_TOGETHER` + rationale (`themis/contracts.md`): relation-cue patterns in the existing cheap rule node, ambiguous cases decided by the existing joint-inference LLM call (one more output field, no new call). Iris decomposes only on `SPLIT` (`iris/contracts.md` §2.4a); `KEEP_TOGETHER` turns route whole as cross-domain (→ Pythia via Layer 1).

---

### PD-14 — Misroute recovery UX

**Severity:** Medium · **Status:** Open · **Touches:** `iris` FE/BFF, `themis` Layer 0, overlaps PD-3

**Problem.** `RoutingPickChip` handles low-confidence routing, but when Themis *confidently* misroutes, the user's only recourse is editing their message. There's no per-turn affordance to say "wrong agent, re-ask elsewhere".

**Proposed direction.** Persistent agent badge on every answer bubble (which agent answered — also good for trust) with a "re-ask with different agent" action that re-issues the turn with `routing_hint` (Layer 0 already honors it — the mechanism exists; only the UI affordance is missing). The action doubles as a misroute signal for PD-3.

**Resolution (2026-06-12, Bora-approved).** As proposed, landed as typed action `reask_agent` (`iris/contracts.md` §2.4): agent badge on every bubble (BFF capabilities cache); badge menu → picker of role-filtered routable agents **pre-sorted by the original `RoutingDecision.alternates` with their `why` strings** (Themis already computed the runner-ups); pick → re-issue with `routing_hint` (Layer 0) + upsert `iris_feedback.corrected_agent_id` (PD-3's strongest label). New surface = one FE menu + one BFF re-issue path; everything else existed.

---

### PD-15 — Sharing / collaboration / session search absent

**Severity:** Low — v2 candidate · **Status:** Open · **Touches:** `iris/` arc

**Problem.** Sessions are single-user; no sharing a finding with a colleague, no comments, no cross-session search of past questions/answers. For B2B analytics, sharing a finding is the natural distribution loop inside a customer org. Listed for completeness — plausibly out of v1 scope, but the decision should be explicit, and PD-6's artifact concept should at least not *preclude* shareability (an artifact ref that another authorized user can open).

**Proposed direction.** Explicitly defer to v2, with one constraint on v1: artifact and session identifiers are stable, unguessable refs so that read-only sharing can be added without remodeling.

**Resolution (2026-06-12, Bora-approved).** **Explicitly deferred to v2.** The one v1 constraint is already honored everywhere it matters: `iris_artifacts` refs and session ids are stable UUIDs (PD-6 resolution noted it explicitly), so read-only sharing adds without remodeling. Tracked in `kantheon-v1.1.md` §7 with related items (Hebe O-4 MCP exposure, dashboard sharing).

---

## Suggested resolution order

Ordered by product risk × cost-of-retrofit:

1. **PD-1** (Golem→Pythia handoff) — makes the core loop real; cheap now, expensive after Golem/Pythia arcs execute.
2. **PD-8** (authz + audit) — gates any real customer; touches contracts under active arcs.
3. **PD-2** (investigation inbox) — required by Pythia's own pause states.
4. **PD-3** (feedback loop) — tiny build, compounds forever; additive, can land mid-arc.
5. **PD-6** (lift artifacts out of Midas) — decide before Midas Phase 3.
6. **PD-4, PD-9** (context contract, provenance) — both are small contract additions best made while envelope/golem/pythia contracts are still soft.
7. **PD-5, PD-11, PD-13, PD-14** — bounded, per-component fixes.
8. **PD-12** (Shem toolchain) — after Golem-ERP proves the template.
9. **PD-7** (discoverability) — quick win, any time after Iris Phase 3.
10. **PD-10, PD-15** — explicit v1.5/v2 decisions.

**Cross-cutting trade-off.** PD-1, PD-8 and PD-6 touch contracts under arcs that were locked 2026-06-12 (Iris/Golem/Pythia). Deferring them means re-opening locked plans later; PD-2/PD-3/PD-14 are additive and can land mid-arc without re-planning.

---

*Working doc owner: Bora. Issues flip to Resolved with a pointer to the doc where the decision landed. Remove this file (or archive under `docs/_orphans/`) once all issues are resolved and folded into the v1 design.*
