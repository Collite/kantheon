# v1 Cohesion Review — Findings & Proposed Amendments

> **Status: EXECUTED 2026-06-12** — Bora confirmed all recommendations (D1–D8 as recommended); all §3 amendments applied the same day (contracts, kantheon-architecture incl. §7.1 persistence topology, all five plans, iris/midas architecture refreshes, v1.1 ledger, hygiene deletions, CLAUDE.md/AGENTS.md sync). Retained as the review record. **Per-stage task-list writing deliberately NOT started** — Bora queued one more architectural task first (ai-platform → kantheon migration).
>
> **Basis:** the full read prescribed by [`handover-2026-06-12-architecture-review.md`](./handover-2026-06-12-architecture-review.md) §3 — PD issues record, `kantheon-architecture.md`, `kantheon-security.md`, all seven per-agent `contracts.md`, all eight plans, `kantheon-v1.1.md`, plus the actual proto files in `shared/proto`.
>
> **Verdict in one line:** the *contracts* are coherent post-PD; the *plans* and `kantheon-architecture.md` lag them (as the handover predicted), and the review found **five contract-level inconsistencies the handover did not know about** (§2) — two of them load-bearing (Rule-6 message identity; AgentId layering).

---

## 1. Handover §4 items — verdicts

| # | Item | Verdict | Fix → |
|---|---|---|---|
| 1 | Iris plan missing stages for PD additions | **Confirmed.** Plan has only §7 amendment notes; no stages for inbox, hypothesis tree, artifacts, `/v1/discover`, `reask_agent`, feedback, `iris_audit`, HandoffContext assembly, InvestigateChip. | §3.1 |
| 2 | iris-bff NATS dep + signing key absent from `iris/architecture.md` | **Confirmed.** No NATS anywhere in architecture.md (§2 stack, §4 diagram, §8 topology); no Ed25519 key in config/deps; contracts §6 config keys also lack `iris.nats.*`, `iris.audit.*`, inbox/artifact/feedback keys. Also stale: §3.3 still says dashboards are "Midas arc Phase 3 concern"; §6.3 still defers all investigation UI to "Pythia-arc follow-up". | §3.2 |
| 3 | capabilities-mcp v0.2.0 homeless; Themis Phase 3 absorption | **Confirmed, with nuance.** Hebe Stage 3.4 T3/T4 already own the `non_routable` routing-view exclusion + the Hebe fixture — but nobody owns `visibility_roles` storage/serving (a registry schema change to a tagged v0.1.0 service). Themis plan Phase 3: **zero** mentions of `prior_context`, `decomposition`, `NO_ENTITLED_AGENT`, `visibility_roles`, or `non_routable` (grep-verified); Stage 3.4 T3's gap-kind fixture list stops at the original four. | §3.3, §3.4 |
| 4 | Golem plan vs echo/emission/admission rules | **Confirmed.** Stage 3.1 T1 covers `current_view` emission only — no `applied_context` echo, no InvestigateChip emission, no admission re-check (bearer + `visibility_roles`), no OBO forwarding in `QueryClient` (Stage 2.4 T2). envelope-render Phase 1 (Stages 1.1/1.2) has **no provenance-stamping task** (PD-9 says the lib stamps `Block.provenance` uniformly). | §3.5 |
| 5 | Pythia plan vs new surface | **Confirmed.** Plan still says "all 11 statuses" (Phase 1 summary) and "all four AWAITING_*" (Phase 3 summary) — both stale (12 / five per PD-11). No task for `GET /v1/investigations`, none for `pythia.lifecycle.{user_id}`, none for §3a resume probes (recipe + fingerprint checkpointing; `Describe`/`GetStatus`; lazy re-materialization), none for `handoff` seeding (Stage 2.1 T1's "prior-context threading" is the continuation token, not the HandoffContext). Stage 5.2 T5 still files the hypothesis tree as "v1.5 Iris work" — contradicts PD-2 (in v1, Iris arc). | §3.6 |
| 6 | PG decision only in Hebe docs | **Confirmed + a real conflict.** `hebe/architecture.md` §5.1 declares: one Kantheon PG, one DB per agent (`iris, pythia, golem, midas, hebe`), `hebe` schema-split. But `midas/architecture.md` §10 provisions a **separate `midas-postgres` CloudNativePG instance**, and `golem/contracts.md` §4 says "per-pod database/schema" — ambiguous vs "one `golem` database". Three docs, three answers. Also: `deployment/local/` is **empty** — no plan owns the PG/pgvector/NATS/Keycloak local-infra work that Hebe P3 and Iris Phase 4 pre-flights assume. | §3.7 |
| 7 | envelope→common import ripple | **Mostly fine, one real ripple.** Golden-gate rule ("additions allowed; renames not", iris contracts §1.1) already treats `provenance` + `InvestigateChip` as compatible. But see finding **2.2**: envelope/v1 imports **themis/v1** (for `AgentId`), and themis/v1 imports ai-platform `nlp`/`metadata` protos — so `envelope-ts` codegen transitively drags ai-platform proto trees into the FE bundle. Fixable now (Themis Stage 3.1 not started). | §2.2 |
| 8 | Cross-repo asks parked | **Partially tracked.** llm-gateway headers are in `kantheon-v1.1.md` §5 ✓. `PipelineContext.used_objects` copying is in the BlockProvenance comment ✓ (no plan task — acceptable, lands with provenance tasks). **`TurnOrigin` + `origin_ref` is tracked nowhere actionable**: it's a Hebe Stage 4.1 *pre-flight*, but no Iris stage owns it, it's absent from `iris/contracts.md` `ChatTurnRequest`, **and `iris_turns` has no `origin` column even though inbox `InboxItem` (§2.5) claims to join `origin` from `iris_turns`**. | §3.1 (Stage 4.1) |
| 9 | Hygiene | **Confirmed.** iris contracts §2 ordering is 2.4 → 2.5 → 2.4a → 2.4b → 2.5a → 2.6; **§3.2 is an empty duplicate header** (feedback DDL actually in §3.4). `docs/v1/` and `docs/_orphans/` still exist; `agents/hebe/docs/plan/` still has the redirect README. Plus stale-name sweep needed: `mcp-server-base` still asserted as a published artifact in `kantheon-architecture.md` §1/§6/§10 and `midas/plan.md` pre-flight — contradicts the 2026-06-12 correction (CLAUDE.md §7.1). `hebe/contracts.md` §6 says "Themis Stage 4.5 follow-up" — Resolver-era numbering; it's Themis Phase 3 Stage 3.3 now. | §3.9 |
| 10a | Inbox vs Hebe `AWAITING_AGENT` | **Sane.** A scheduled *Golem* turn that pauses never enters the inbox (investigations-only) — but Hebe Stage 4.2 T5 delivers a Telegram message with an Iris deep link, and the session itself shows the pending clarification. Covered; the widening trigger already sits in `kantheon-v1.1.md` §2. No change needed. | — |
| 10b | InvestigateChip inline HandoffContext size | **Sane with one guard.** `EntityBinding` is small; the heavy field is `ViewProvenance.sql` (bounded — one statement). Suggest one normative line in iris contracts: cap `handoff.entities` (e.g. ≤ 50) and emitters truncate `suggested_focus`; nothing structural. | §3.8 |

## 2. New findings (not in the handover)

### 2.1 Rule-6 `ResponseMessage` — three different identities in play (**load-bearing**)

- The contract docs (themis §1.2, golem §1, pythia §1, iris §1.1/1.2) all import **`cz/dfpartner/common/v1/response_message.proto`** — *which does not exist*: ai-platform's `ResponseMessage` lives in `cz.dfpartner.metadata.v1` (carries a metadata-domain `ObjectRef`, not portable).
- The actual checked-in `themis.proto` uses **`cz.dfpartner.metadata.v1.ResponseMessage`**.
- Kantheon has its own stand-in **`org.tatrman.kantheon.common.v1.ResponseMessage`** (`shared/proto/.../common/v1/response_message.proto`, with an explicit TODO to swap when ai-platform extracts a domain-free one) — and `hebe/contracts.md` is the *only* contract doc that correctly uses it.

**Proposed fix (consolidation):** one decision line in `kantheon-architecture.md` §4 — *all kantheon protos use `org.tatrman.kantheon.common.v1.ResponseMessage` at field 99 until ai-platform publishes a domain-free `cz.dfpartner.common.v1` version* — then a mechanical sweep of the four contract docs + `themis.proto` (themis is mid-Stage 2.4; the swap is cheapest now, before golem/pythia/iris protos are written at all). Track the ai-platform extraction as a cross-repo ask in `kantheon-v1.1.md` §5.

### 2.2 `AgentId` placement — layering bug about to calcify (**load-bearing**)

`AgentId` is defined in **themis/v1** (themis contracts line ~366), but `kantheon-architecture.md` §4 lists it under **capabilities/v1**, and `envelope/v1` imports **themis/v1** solely for `RoutingPickChip.agent_id`. Consequences: (a) the §4 table's "envelope imports common/v1 only" claim is false; (b) golem/v1 and pythia/v1 import envelope/v1 *and* themis/v1, transitively coupling every agent proto to `cz.dfpartner.nlp.v1`; (c) `envelope-ts` TS codegen drags ai-platform proto trees into the FE bundle (finding 1.7).

**Proposed fix:** move `AgentId` to **common/v1** (it is a one-field wrapper; common is the declared bottom layer). envelope/v1 then imports only common/v1 — exactly what the architecture table already claims. Zero code cost: `AgentId` is a Themis **Phase 3 Stage 3.1** type, not yet generated. Touches: themis contracts §1.2, iris contracts §1.1 import, kantheon-architecture §4 row.

### 2.3 `themis_prior_context` naming collision

PD-1 says `ResolveRequest.prior_context` (HandoffContext) "IS themis_prior_context" — yet `ResolveContext.themis_prior_context = 3` (typed `ResolutionContinuation`, the HMAC profile-handoff token) still exists, and Pythia's `InvestigationContext` carries *both* under similar names. Two unrelated mechanisms share one name.

**Proposed fix:** rename `ResolveContext.themis_prior_context` → `continuation` (and the matching Pythia context field comment); reserve "prior context" exclusively for the HandoffContext. Doc-only today (field not generated yet — Stage 3.1).

### 2.4 Caller-roles transport to Themis is unspecified

`kantheon-security.md` §2 shows BFF → Themis "roles in request ctx", but `ResolveRequest`/`ResolveContext` carry no roles field and no rule says Themis validates a forwarded bearer. Themis cannot filter the routing view without knowing the caller's roles.

**Proposed fix (pick one, document in themis contracts + security doc):** (a) BFF forwards the user's bearer on the Themis hop; Themis validates and reads `realm_access.roles` itself — consistent with the OBO-everywhere rule, no proto change (recommended); or (b) `repeated string caller_roles` on `ResolveRequest` with a trust-upstream note. 

### 2.5 `GolemRequest` has no HandoffContext slot

Iris contracts §1.2 assembly rule: the BFF sends the HandoffContext "(b) to the routed agent's request". Pythia has `InvestigationContext.handoff`; **GolemRequest/GolemContext has nothing** — only `prior_view` (typed as envelope `CurrentView`, not `common.v1.ViewProvenance`) + entity snapshots.

**Proposed fix:** add `optional org.tatrman.kantheon.common.v1.HandoffContext handoff = 5` to `GolemContext`, and note that `prior_view` (v2-compat `CurrentView`) is derived from `handoff.view` during the transition. (Alternative: declare Golem's existing context fields the handoff-equivalent and exempt Golem from rule (b) — but that forks the contract; not recommended.)

### 2.6 Long-running-agent token lifetime — gap in the PD-8 design

THE security rule (agents use the user's OBO token, never service identity) is silent on expiry: a NORMAL/DEEP Pythia investigation runs longer than a typical access-token TTL, and resumes after `AWAITING_*` happen days later. Resume-triggered calls get a fresh bearer from the resuming request — fine — but **mid-flight steps in a multi-hour run will outlive the original token**.

**Proposed fix:** add a §2.1 to `kantheon-security.md`: v1 mechanism = Keycloak token-exchange/refresh held by the *agent* for the duration of an active run (bounded by run lifetime), expiry during a pause is expected and resolved by the resuming request's fresh token; scheduled paths already covered by Hebe's OBO service (Stage 2.3 T4). If Bora prefers, defer the mechanism to v1.1 with a documented v1 stance ("runs longer than token TTL fail closed with a Rule-6 error") — but the doc must say *something*; today it implies multi-hour OBO works by magic.

### 2.7 `kantheon-architecture.md` has decayed well beyond the handover's list

Beyond the PG promotion (1.6) and `mcp-server-base` (1.9): **Hebe appears nowhere** (grep: zero hits — §2 table, §3 layout, §6 dependency graph all missing it, while CLAUDE.md has it); §3 layout still shows the retired `docs/v1/` doc set; §4 claims "all Kantheon-owned protos live under `org.tatrman.kantheon`" (false since charon/metis) and omits `hebe/v1`; §7 `TurnPointer` lacks the PD-1 snapshot fields; §8 `MultiQuestionDetected` lacks SPLIT/KEEP_TOGETHER; §9 says "four `AWAITING_*`"; §6 graph lacks charon/metis/NATS; §12 open items are mostly resolved (iris-bff design, golem-template plan, pythia revision — all planned arcs now); the security doc isn't in the companions list; the footer still says the doc lives in `docs/v1/`. One coherent refresh pass needed, not spot fixes — see §3.10.

### 2.8 Pythia status-name drift

Iris §2.5 maps `CONCLUDED → Done`; Pythia's eval-corpus example asserts `"terminal_status": "DONE"`. One enum, two names across docs. Verify against design §3.4 and align (cheap now; annoying after the inbox mapping is coded).

### 2.9 `iris_audit` event-kind list vs PD-6

Both `kantheon-security.md` §4.2 and iris contracts §3.1 enumerate `turn | typed_action | export | resume | escalation`, but §2.5a audits artifact refreshes as `event_kind: artifact_refresh`. Add it to both lists (and decide whether `reask_agent` is a distinct kind or a `typed_action`).

### 2.10 Proto-sketch import omissions (mechanical)

In the contract docs, every proto sketch that *uses* `org.tatrman.kantheon.common.v1.*` is missing the corresponding `import` line: envelope.proto (InvestigateChip, Block.provenance), iris.proto (TurnPointer), themis.proto (prior_context), golem.proto (echo fields). Pure doc fixes, but the wire policy says task lists must match contracts exactly — fix before Stage 1.1 task lists are written.

## 3. Proposed amendments (the work, per doc)

### 3.1 `iris/plan.md` — new Phase 4 + insertions into existing stages

Insertions into existing stages (small, no re-sequencing):

- **Stage 1.1**: T2 explicitly includes the common/v1 import + `provenance`/`InvestigateChip` fields; T4/T5 note the golden gate treats them as additive; envelope-ts codegen includes common/v1 types.
- **Stage 1.2**: add `iris_audit` + `iris_feedback` migrations (tables from day one; write paths later) — *Bora call*: alternatively both land in Stage 4.3, but audit-of-turns from Phase 1 is nearly free and PD-8 is the launch blocker.
- **Stage 1.3**: add audit write at turn finalization (chain + signing key from config).
- **Stage 3.1**: add HandoffContext assembly on every dispatch (build from previous TurnPointer + EntityContext; send to Themis as `prior_context` + to routed agent) and the PD-4 `applied_context` comparison + scope indicator/mismatch warning.
- **Stage 3.2**: add InvestigateChip rendering + the BFF-side always-on "Investigate this" drilldown action; add `reask_agent` typed action (badge menu + `routing_hint` re-issue + `corrected_agent_id` upsert); add `TurnOrigin`/`origin_ref` on `ChatTurnRequest` + `iris_turns.origin/origin_ref` columns (the Hebe Stage 4.1 co-design, landed here).

New **Phase 4 — inbox, artifacts, discovery, feedback** (post-3.3; pre-flight: Pythia `ListInvestigations` + lifecycle subject for 4.1 only — 4.2/4.3 have no Pythia dependency and can run earlier if Pythia slips):

- **Stage 4.1 — investigation inbox + lifecycle stream (6):** 1. Tests first: inbox aggregation spec (Pythia list ⋈ `iris_turns`, 12→5 mapping, origin badges). 2. NATS subscriber (`pythia.lifecycle.{user_id}`) + degrade-to-polling. 3. `GET /v1/inbox` + `/v1/inbox/stream` SSE fan-out. 4. FE header badge + dockview panel + reattach via `/events?from_seq`. 5. Needs-input rows → existing clarification UI via BFF proxy to Pythia control endpoints. 6. Hypothesis-tree pane (debug-grade) from artifact + stream events.
- **Stage 4.2 — artifacts: pins + dashboards (6):** 1. Tests first: capture/refresh specs (Golem typed-action path, Pythia replay/reproduce, error→stale state). 2. `iris_artifacts` migration + repository. 3. `/v1/artifacts` CRUD + pin capture assembly. 4. `/v1/artifacts/{id}/refresh` + `/v1/dashboards/{id}/open` SSE. 5. FE pin tiles (provenance ⓘ, scope indicator, refreshed-at) + dashboard layout + template support. 6. Audit `artifact_refresh` events + metrics.
- **Stage 4.3 — discovery, feedback, audit completion (6):** 1. `GET /v1/discover` (role-filtered DomainCards) + first-run panel + empty-input chips. 2. `POST /v1/turns/{id}/feedback` + FE 👍/👎 + reason picker. 3. `just feedback-export` adapters → `eval/candidates/`. 4. Audit verify endpoint + retention/segment job + signing-key ops (Secret). 5. Metrics: `feedback_total`, inbox/artifact counters; Grafana panel updates. 6. Contracts §2 renumber + docs; tag `iris-bff/v0.4.0`, `iris/v0.3.0`.

### 3.2 `iris/architecture.md` — refresh pass

Add NATS (dependency, topology, config) + Ed25519 signing key (config + Secret); module map gains `inbox/`, `artifacts/`, `audit/`, `feedback/`; delete the stale §3.3 dashboard line and §6.3 deferral sentence (point at PD-2/PD-6 scope); extend §10.1 metrics; contracts §6 config keys gain `iris.nats.*`, `iris.audit.{retention-months, signing-key-ref}`, `iris.inbox.*`. Local-infra note: NATS reachable by the BFF (with §3.7's deployment/local work).

### 3.3 capabilities-mcp v0.2.0 — give it a home in the Hebe arc

Extend **Hebe Stage 3.4** (it already owns T3 fixture + T4 routing-view exclusion) with: T7 `visibility_roles` stored/served on `AgentCapability` (schema + search/list surfaces) and the seed fixtures updated (golem-hr example). Rename the stage "capabilities-mcp v0.2.0 + Hebe registration"; tag `capabilities-mcp/v0.2.0` in its DONE. (Alternative: a one-stage mini-arc under the Themis plan — fine too, but the Hebe stage already touches the same code.)

### 3.4 `themis/plan.md` — Phase 3 absorptions (no new stages needed)

- **Stage 3.1** (proto extensions): + `prior_context` (HandoffContext), `Decomposition` enum + fields, `GapKind.NO_ENTITLED_AGENT`, `visibility_roles` mirror; the 2.1–2.3 renames (`AgentId`→common/v1, ResponseMessage swap, `continuation` rename) execute here.
- **Stage 3.2**: + decomposition verdict (relation-cue rules + joint-inference output field) + fixtures for SPLIT vs KEEP_TOGETHER; + coreference against `prior_context.entities`.
- **Stage 3.3**: + routing-view derivation task (drop `non_routable` → role-filter → layers) + roles-transport decision (finding 2.4) implemented; regression: Hebe never in a RoutingDecision (pairs with Hebe Stage 3.4 T5).
- **Stage 3.4**: + `NO_ENTITLED_AGENT` fixtures incl. reveal-existence-deny-access on explicit naming.
- **Stage 3.5**: eval corpus gains an entitlement bucket + KEEP_TOGETHER bucket.

### 3.5 `golem/plan.md` + `golem/contracts.md`

Plan: **Stage 1.1/1.2** + provenance stamping in envelope-render (BlockProvenance at format time; tests on the gotcha fixtures). **Stage 2.2** + admission re-check (bearer validation + `visibility_roles`, 403 + Rule-6). **Stage 2.4** + OBO forwarding in `QueryClient` (user token, never service identity). **Stage 3.1** + `applied_context` echo + InvestigateChip emission (confidence-gate failure × analytical intent) with tests. Contracts: add the admission re-check note (security §6 promised it "when next touched" — it was touched 2026-06-12 without it); add `GolemContext.handoff` (finding 2.5); add `golem.auth.*` / OBO config keys.

### 3.6 `pythia/plan.md`

- Phase 1 summary: "all **12** statuses"; Phase 3 summary: "all **five** `AWAITING_*`".
- **Stage 1.3**: + `pythia.lifecycle.{user_id}` publisher (EventEmitter task) + `GET /v1/investigations` list endpoint + `/budget-decision` in the control-surface task.
- **Stage 1.2** (checkpointer): + recipe + Arrow-fingerprint recording per handle (the §3a checkpoint shape).
- **Stage 2.1**: + investigation seeding from `context.handoff` (anchor view/entities; Conclusion links `source_turn_ref`).
- **Stage 2.3**: + `AWAITING_BUDGET_DECISION` parking at the ASK gate; OBO forwarding in `QueryMcpClient`.
- **Stage 4.1**: + resume liveness probes (Charon `Describe`) + lazy re-materialization + fingerprint-drift warning/LooseEnd; **Stage 4.2**: + Metis `GetStatus` probe path.
- **Stage 5.2** T5: drop the stale "hypothesis tree = v1.5" note → point at Iris Phase 4 Stage 4.1; Stage 5.2 gains the lifecycle-subject + inbox joint test with Iris.
- §8/§10 sweep: "Cross-repo (ai-platform side): metis…" in contracts §10 is stale (Metis is kantheon-side per §7) — fix the sentence.

### 3.7 PG decision promotion + deployment/local

- `kantheon-architecture.md`: new §"Persistence topology" — one internal Kantheon PG; one database per agent (`iris`, `pythia`, `golem`, `midas`, `hebe`); `hebe` schema-per-instance; **disambiguate Golem**: one `golem` database, schema per Shem pod (recommended — matches "DB per agent" with Golem-the-template as the agent; alternative: DB per Shem) — *Bora call*.
- `midas/architecture.md` §10: reconcile — `midas-postgres` becomes the `midas` database on the Kantheon PG (CloudNativePG operates the *instance*, not a per-arc DB), or record an explicit exception with rationale.
- **deployment/local has no owner and is empty**: add a small infra stage (suggest: Iris arc Phase 1 pre-flight extension, or a standalone `deployment/local` task list) covering PG (+pgvector) with per-agent DB provisioning, NATS reachability for the BFF, Keycloak dev realm + `kantheon-domain-*` roles. Hebe P3 and Iris P4 both pre-flight on it.

### 3.8 Contract micro-fixes (one sweep)

ResponseMessage standardization (2.1); `AgentId` → common/v1 (2.2); `continuation` rename (2.3); roles transport (2.4); `GolemContext.handoff` (2.5); missing import lines (2.10); `artifact_refresh` event kind (2.9); status-name alignment CONCLUDED/DONE (2.8); InvestigateChip size guard line (1.10b); `TurnOrigin` + `origin` column into iris contracts (1.8); iris contracts §2 renumber + delete empty §3.2 (1.9).

### 3.9 Hygiene sweep (mechanical, one commit)

`mcp-server-base` removal from `kantheon-architecture.md` (×3) + `midas/plan.md` pre-flight; `hebe/contracts.md` §6 "Themis Stage 4.5" → "Themis Phase 3 Stage 3.3"; delete `agents/hebe/docs/plan/`, `docs/v1/`, `docs/_orphans/` (pending Bora confirmation on the latter two); footer/path fixes in kantheon-architecture.

### 3.10 `kantheon-architecture.md` — full refresh (finding 2.7)

One pass aligning it with CLAUDE.md + the post-PD state: Hebe row + layout + dep graph (incl. charon/metis/NATS); §4 preface (charon/metis pkg roots) + `hebe/v1` row + ResponseMessage decision line; §7 TurnPointer snapshot fields; §8 decomposition rule; §9 five AWAITING_* / 12 statuses; §11 waypoints (Hebe arc, Iris→Golem→Pythia order); §12 open-items prune; companions + security-doc link; PG topology section (3.7).

### 3.11 `kantheon-v1.1.md` additions

- Cross-repo: ai-platform extraction of a domain-free `cz.dfpartner.common.v1.ResponseMessage` (trigger: ai-platform proto release; kantheon swaps imports and deletes the stand-in).
- If 2.6 is resolved as "defer": long-running OBO token exchange (trigger: first investigation failing on token expiry).

## 4. Suggested execution order (after sign-off)

1. **Contract micro-fix sweep** (§3.8) — everything else cites these.
2. **kantheon-architecture refresh + PG promotion** (§3.10, §3.7) — the cross-cutting truth.
3. **Plan amendments** (§3.1, §3.4, §3.5, §3.6, §3.3) — Iris first (first arc in the locked order; its Stage 1.1 task list is blocked on §3.8 items 2.1/2.2/2.10).
4. **Architecture refreshes** (§3.2) + **hygiene** (§3.9) + **v1.1 ledger** (§3.11).
5. Then per-stage task-list writing starts with Iris Stage 1.1, per the handover's exit criterion.

## 5. Decisions needed from Bora

| # | Decision | Recommendation |
|---|---|---|
| D1 | ResponseMessage: standardize on the kantheon common/v1 stand-in? | Yes — matches CLAUDE.md §4 and the existing TODO |
| D2 | `AgentId` home: common/v1 vs capabilities/v1? | common/v1 (envelope must not import capabilities either) |
| D3 | Roles transport to Themis: forwarded bearer vs explicit field? | Forwarded bearer (one identity mechanism everywhere) |
| D4 | Golem persistence: one `golem` DB with schema-per-Shem, or DB-per-Shem? | One DB, schema per Shem |
| D5 | Midas PG: fold into the Kantheon PG, or documented exception? | Fold in (the decision says "one instance") |
| D6 | `iris_audit` from Phase 1 (cheap, early coverage) or Phase 4 (with the rest)? | Phase 1 tables + finalization writes; verify/retention in 4.3 |
| D7 | Token lifetime (2.6): v1 mechanism (agent-held refresh) or documented fail-closed + v1.1? | Documented fail-closed for v1; mechanism at first real incident |
| D8 | `docs/v1/` + `docs/_orphans/` deletion now? | Yes (git history retains) |

---

*Review by the 2026-06-12 cohesion session (handover §5 method: every item → amendment or ledger). Nothing in this report has been applied yet.*
