# Handover — final architecture review session

> **Written:** 2026-06-12, end of the product-design convergence session. **For:** the next session — the final whole-constellation architecture review **before** per-stage task planning begins. Read this first; it tells you what changed today, what to read, and exactly what the review must check.
>
> **STATUS: DONE 2026-06-12.** The review ran the same day — findings + execution record in [`review-2026-06-12-v1-cohesion-findings.md`](./review-2026-06-12-v1-cohesion-findings.md) (all §4 items below resolved, plus five contract-level findings the list below missed). Exit criteria met; task-list writing intentionally deferred pending the ai-platform-migration architectural task.

---

## 1. What today's session did (context in 60 seconds)

1. **Whole-constellation product review** → `docs/design/product-design-issues.md` (PD-1…PD-15).
2. **Hebe moved into the constellation** — source copied `~/Dev/hebe` → `agents/hebe` (clean, 457 files, self-contained Gradle build); full integration arc planned: `architecture/hebe/` (architecture + contracts + relocated `standalone-v1-architecture.md`) + `implementation/v1/hebe/plan.md` (P1 build-merge → P2 `local`/`k8s` profiles → P3 PG schema-per-instance → P4 iris-bff headless client). Hebe docs migrated into the three-area structure.
3. **All 15 product-design issues resolved** in one day, each folded into contracts/architecture docs. The issues doc is flipped to COMPLETE and is now the *design-rationale record*. Deferred parts live in `docs/implementation/kantheon-v1.1.md` (7 sections, each with pickup triggers).
4. **One platform decision made along the way:** one internal Kantheon PG instance; **one database per agent**; Hebe's DB schema-split per instance (`hebe_<id>`).

## 2. Contract/architecture deltas made today (the review's raw material)

| Where | What changed |
|---|---|
| `shared/proto/.../common/v1/handoff.proto` (**new file**) | `HandoffContext`, `EntityBinding`, `ViewProvenance` (mirrors new-golem v2 `CurrentView`), `BlockProvenance` (PD-1/4/9) |
| `shared/proto/.../capabilities/v1/capabilities.proto` | `AgentCapability.non_routable = 16` (Hebe), `visibility_roles = 17` (PD-8); `PERSONAL_ASSISTANT` comment fixed |
| `docs/architecture/kantheon-security.md` (**new doc**) | PD-8: visibility_roles + Themis pre-Layer-1 filtering (invisible-not-forbidden; reveal-existence-deny-access on explicit naming); **THE rule: agents call query-mcp with the user's OBO token, never service identity**; Pythia cross-domain = constrain-and-disclose; `iris_audit` hash-chained (Hebe receipts shape, monthly segments, configurable retention) |
| `themis/contracts.md` | `ResolveRequest.prior_context = 8` (HandoffContext); `visibility_roles` mirror; `GapKind.NO_ENTITLED_AGENT = 5`; routing-view derivation rule (drop non_routable → role-filter → layers); `MultiQuestionDetected.decomposition SPLIT\|KEEP_TOGETHER` + rationale (PD-13) |
| `iris/contracts.md` | `TurnPointer.current_view/applied_context` snapshot + BFF assembly rule (PD-1/4); `InvestigateChip` in `Chip` oneof; `Block.provenance = 7` (+ envelope→common import); §2.4 `reask_agent` typed action (PD-14); §2.4a decompose-only-on-SPLIT; §2.4b `GET /v1/discover` DomainCards (PD-7); §2.5 inbox + 12→5 status mapping + **debug-grade hypothesis-tree pane (Bora: in from the start)**; §2.5a artifacts/pins/dashboards (PD-6); §2.6 feedback endpoint; §3.1 `iris_audit`; §3.3 `iris_artifacts`; §3.4 `iris_feedback` |
| `golem/contracts.md` | `ConversationalResponse.current_view = 10` + `applied_context = 11` echo rule; InvestigateChip emission rule (never calls Pythia) |
| `pythia/contracts.md` | `InvestigationContext.handoff` seeding comment; §3a resume semantics (recipes + Arrow fingerprint; Charon `Describe`/Metis `GetStatus` probes; warn-don't-fail on drift — PD-5); `GET /v1/investigations` list (PD-2); `pythia.lifecycle.{user_id}` NATS subject; halt = cancel-with-partials; **`AWAITING_BUDGET_DECISION` added → 12 statuses / five AWAITING_*** (PD-11) |
| `charon/contracts.md` / `metis/contracts.md` | PD-5 liveness-probe notes (Describe / GetStatus + TTL stays simple) |
| `midas/architecture.md` §11 + `contracts.md` §7 + `plan.md` Stage 3.5 | **Superseded/reframed (PD-6):** dashboard *system* moved to Iris arc (generic `iris_artifacts`; `agent_call_spec` → `ViewProvenance`); Stage 3.5 rewritten as consumer (templates `investment-overview:v1`, report-preview pane, content, E2E; 5 tasks) |
| `iris/plan.md` §7 | Amendments: inbox + hypothesis tree + artifact system now in scope (post-Phase-3 stages), DAG pane still out |
| `kantheon-architecture.md` | §4 table: `common/v1` row, envelope imports common, envelope-render stamps provenance (§5) |
| `CLAUDE.md` / `docs/README.md` | All of the above reflected; `kantheon-v1.1.md` ledger registered |

## 3. Read order for the review session

1. `docs/design/product-design-issues.md` — the 15 resolutions (the *what* and *why* of every delta).
2. `docs/architecture/kantheon-architecture.md` + `kantheon-security.md` — cross-cutting.
3. Per-agent `contracts.md` (iris → themis → golem → pythia → hebe → charon/metis) — today's deltas in situ.
4. The five plans: `iris/plan.md`, `golem/plan.md`, `pythia/plan.md`, `hebe/plan.md`, `midas/plan.md` (+ Themis status: mid-Stage 2.4).
5. `docs/implementation/kantheon-v1.1.md` — what is *deliberately not* in v1.

## 4. What the review must check — known gaps & ripples

**These are the specific things I know are NOT yet reconciled.** The contracts are updated; the *plans* mostly are not.

1. **Iris plan has no stages for today's additions.** Contracts now define: inbox + hypothesis tree, artifacts/pins/dashboards, `/v1/discover`, `reask_agent`, feedback, `iris_audit`, HandoffContext assembly, InvestigateChip rendering. `iris/plan.md` only carries §7 amendment notes. The review must turn these into actual stages (likely 2–3 new post-Phase-3 stages + additions to existing persistence/dispatch stages) and re-sequence.
2. **iris-bff gained a NATS dependency** (lifecycle subscriber, PD-2) **and a signing key** (audit chain). Neither is in Iris architecture.md's dependency/config sections. Ripple: local-infra needs NATS reachable by the BFF.
3. **capabilities-mcp shipped v0.1.0 BEFORE today's registry changes.** `non_routable` exclusion from the routing view + `visibility_roles` filtering + the Hebe fixture are *contract* changes to an already-tagged service → needs a small follow-up stage (capabilities-mcp v0.2.0) that no plan currently owns. Themis-side: routing-view filter + `NO_ENTITLED_AGENT` + `decomposition` land in Phase 3 stages — verify `themis/plan.md` Stage 3.x task lists absorb them (Themis is mid-Stage 2.4; Phase 3 not started, so this is additive, not rework).
4. **Golem plan vs new echo/emission rules** — `current_view`/`applied_context` echo, InvestigateChip emission, `visibility_roles` admission re-check: verify `golem/plan.md` stages cover them (format stage + request-admission); envelope-render Phase 1 must include provenance stamping (PD-9).
5. **Pythia plan vs new surface** — `ListInvestigations`, lifecycle subject, 12th status, §3a resume semantics, handoff seeding: verify plan stages absorb (most belong to existing executor/persistence stages; lifecycle subject may need a task).
6. **The PG platform decision is only written down in the Hebe docs.** "One Kantheon PG; one DB per agent; Hebe schema-per-instance" should be promoted into `kantheon-architecture.md` (+ `deployment/local` implications: pgvector, the `hebe` DB, Keycloak dev realm). Also check it against Midas's CloudNativePG/operational-PG plans for consistency.
7. **envelope→common import ripple** — proto codegen, `envelope-ts` TS bindings, and the golden-sample CI gate (envelope derived field-for-field from FormatEnvelope v2 — `provenance` and `InvestigateChip` are *additive*; confirm the golden gate treats additions as compatible).
8. **Cross-repo asks parked today:** `TurnOrigin SCHEDULED + origin_ref` on `ChatTurnRequest` (Iris-arc co-design, needed by Hebe Stage 4.1); llm-gateway cost-attribution headers (graceful-degrade, ai-platform side); `PipelineContext.used_objects` → `source_tables` copying (agents, when they build provenance).
9. **Hygiene:** iris contracts §2 numbering grew organically (2.4a/2.4b/2.5/2.5a/2.6) — renumber during review; `agents/hebe/docs/plan/` is an empty dir with a redirect README (deletion was blocked in-session); `docs/v1/` still pending physical removal (pre-existing); `docs/_orphans/` removable (pre-existing).
10. **Two review-worthy design tensions to sanity-check, not reopen:** (a) the inbox is investigations-only while Hebe `RoutineRun` also has `AWAITING_AGENT` — confirm scheduled-run pauses surface correctly through the session/inbox path; (b) `InvestigateChip` carries a full `HandoffContext` inline — confirm envelope size stays sane for big `applied_context` lists.

## 5. Session goal & exit criteria

**Goal:** every per-agent `architecture.md` + `plan.md` is consistent with the post-PD contracts; the PG decision is promoted; the capabilities-mcp follow-up has a home. **Exit:** amended plans (stage lists updated, new stages named with ~6 task titles each per `planning-conventions.md`) — at which point per-stage task-list writing can start, beginning with the Iris arc (first in the locked order).

**Suggested method:** walk §4 above top-to-bottom; each item either (a) produces a plan/architecture amendment on the spot, or (b) lands in `kantheon-v1.1.md` with a trigger. Nothing stays in chat.

---

*Handover by the 2026-06-12 session. Memory mirror: `kantheon_arc_plans_2026_06.md` (auto-memory) — but this doc is the authoritative handover.*
