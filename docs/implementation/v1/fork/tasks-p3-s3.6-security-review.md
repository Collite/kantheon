# Fork — Stage 3.6: Security review + acceptance

> Branch: `feat/fork-p3-s3.6-security-review`. Pre-flight: Stage 3.5 (full query path on K3s). Plan: [`plan.md`](./plan.md) Stage 3.6. Tracker: [`tasks.md`](./tasks.md).
>
> The RLS/identity enforcement edge now lives in-repo — the handover called this THE most sensitive move; this stage is its dedicated review. The security-review intent stands; per the testing policy (planning-conventions.md §4) the acceptance checks below are written as **mocked component tests**, with true e2e acceptance against the live stack deferred to the separate integration-test suite.

- [x] **T1 — `kantheon-security.md` rewrite.**
  Execute the §1/§3.4 owner swap the fork notice promises: "ai-platform resolves identity at the query-mcp edge" → "Theseus resolves identity at the theseus-mcp edge (IdentityResolver, forked)"; "row-level security … the ai-platform Validator's job" → "Argos's job (validator + policy engine, in-repo)"; the §2 mermaid relabels (query-mcp → theseus-mcp, Validator → Argos); delete the fork notice block (now executed); principle text stays verbatim. Cross-check every claim in the rewritten sections against the mocked component tests of T2–T4 (deployed-behaviour confirmation against the live stack deferred to the separate integration-test suite).
  *Done 2026-06-15: §1 reworded — "no *new* authorization engine; the platform's enforcement code is forked intact"; identity resolves at the **theseus-mcp** edge (IdentityResolver forked unchanged), **Argos** (validator + in-process policy engine) applies RLS. §2 mermaid relabelled (`query-mcp`→`theseus-mcp`, `Validator (ai-platform owned)`→`Argos (validator + policy engine)`, + a Theseus orchestrator node); load-bearing rule now "agents call theseus-mcp". §3.4 retitled "(in-repo: Argos)" + points at the `argos.policies` store. The "Fork notice (2026-06-12)" block replaced by a "Fork executed (Stage 3.6)" record; scope line RLS-content owner → model (Ariadne) + Argos store. Each rewritten claim matches a T2–T4 component test. Stragglers swept (only the historical before→after record names the old owners).*

- [x] **T2 — RLS acceptance matrix (mocked component test).**
  Two test users modelled as mocked bearers with different `kantheon-domain-*` / data-scope roles (and one admin). Mocked component acceptance run (commit it under `services/argos/src/test/acceptance/` or a `just fork-acceptance` recipe), Argos exercised against policy fixtures with a mocked worker/data source: same `run_query` per user → row sets differ per policy fixtures; admin with `apply_security` bypass → unfiltered (DF-V02); admin flag without admin role → refused. Record the matrix output in this file's appendix section when run. (Live-Keycloak acceptance deferred to the separate integration-test suite.)
  *Done 2026-06-15: `services/argos/.../acceptance/RlsAcceptanceMatrixSpec` (package `…argos.acceptance`) — real `ArgosServiceImpl` + in-process `PolicyEngine` (no gRPC/whois), a `tenant_isolation` policy, and a 4-row mock `customers` dataset spanning two tenants; the injected security predicate is applied to the dataset so row sets are concrete. Matrix (see appendix): tenant-7 analyst → {Acme,Beta}; tenant-9 analyst → {Cobalt,Delta} (disjoint); admin + `apply_security=false` → bare scan, all 4 rows (DF-V02); non-admin + `apply_security=false` → `security_bypass_denied` Rule-6 + forced back to tenant-scoped. 4 tests green.*

- [x] **T3 — OBO discipline (mocked component test).**
  Against theseus-mcp with mocked downstream clients: user bearer → 200; no token → fail-closed Rule-6; service-identity token → rejected. Verify Argos never sees a request without roles (negative-path log/trace check via OTel — span attributes carry user_id, never credentials). (Live theseus-mcp OBO e2e deferred to the separate integration-test suite.)
  *Done 2026-06-15: `tools/theseus-mcp/.../acceptance/OboDisciplineComponentSpec` composes the real `IdentityGate` decision (the same one `McpTransport` switches on) with the real `run_query` chain (QueryTool → in-process Theseus → mocked Argos) — the Argos stub records the roles it is asked to validate. Valid bearer → allowed, rows return, Argos sees `analyst`; no token → `missing_user_identity`, **Argos never called** (seenRoles empty); service-identity token (no user claim) → `missing_user_identity`, **Argos never called**. Credential hygiene: the reject message never echoes the bearer token. The "never reaches Argos on reject" is the negative-path guarantee (the gate short-circuits before the chain). 3 tests green. (The pure gate decisions are also unit-pinned by Stage 3.5's `IdentityGateSpec`; full OTel span-attribute assertion needs an exporter → integration suite.)*

- [x] **T4 — Token-expiry behavior.**
  Short-TTL test token; let it expire mid-session; re-issue `run_query` → fail-closed with the kantheon-security §2.1 Rule-6 message shape ("session expired — resume to continue" semantics at this layer = clean typed error, no partial results, no retry-with-cached-identity).
  *Done 2026-06-15: `tools/theseus-mcp/.../acceptance/TokenExpiryComponentSpec`. Two facts pinned. (1) **Trust boundary:** IdentityResolver decodes claims but does NOT verify signature **or `exp`** (KDoc — that's the ingress/sidecar's job); a well-formed token with a past `exp` still resolves at the edge. Test asserts this so the edge isn't mistaken for the expiry-enforcement point; the §2.1 park/resume is the agent (Pythia) layer. (2) **Fail-closed shape at this layer:** a data call failing mid-stream (UNAUTHENTICATED after the first batch — the post-expiry shape) → clean typed error, `ok=false`, no `rowCount`, no partial rows. 2 tests green. **Found + fixed a real defect doing this:** Theseus signals an internally-caught failure as an `errorBatch` carrying an ERROR-severity Rule-6 message (not a gRPC status), but `QueryTool` only failed on a caught `StatusException` and read pipeline messages from `context.warningsList` — so a Theseus-internal failure (mid-stream dispatch failure, OR any pre-dispatch validate/parse failure) surfaced to the agent as `ok=true, isError=false` with partial/zero rows and the **error silently dropped**. Fixed `QueryTool` to detect any ERROR-severity message in the collected batches and fail closed (`buildErrorResult`, rows dropped). No regressions (full theseus-mcp + argos suites green).*

- [x] **T5 — Audit derivability paper-check.**
  Iris isn't built yet; verify on paper (and record in `docs/architecture/iris/contracts.md` §3 as a note if anything shifted): everything `iris_audit` needs per turn — question, agent, `applied_context`, ViewProvenance (pattern/SQL/args/row count) — is derivable from the theseus-mcp request/response surface as forked. Any gap → file it in `kantheon-v1.1.md` or the Iris plan pre-flight, decision recorded here.
  *Done 2026-06-15: derivability table added to `iris/contracts.md` §3.1. question / RoutingDecision / agent_id / applied_context are BFF/Themis/agent-side ✓; ViewProvenance `pattern_id`/`args_json` are agent-side (what it sent theseus-mcp) ✓. **One bounded gap:** ViewProvenance `sql` is returned by the **`compile`** tool (`compiledSql`) but the **`query`** tool does not echo the executed/translated DB SQL (only the caller's `source`), and `total_rows` is post-truncation+`truncated` flag (exact total not surfaced when row-limited). **Decision:** non-blocking for v1 — the plan-cache path compiles-then-runs (agent holds `compiledSql`) and provenance tolerates a truncation-bounded total; if Iris needs exact provenance SQL on the direct `query` path, add additive `executedSql`+`totalRows` to the query response. Filed `kantheon-v1.1.md` §11.*

- [x] **T6 — Sign-off + stage exit.**
  Append the sign-off block to this file (date, reviewer = Bora, matrix results, open findings with owners). Tag any untagged Phase-3 modules; edit `docs/implementation/v1/pythia/plan.md` Phase 4 pre-flight: add `theseus/v0.1.0` + `steropes/v0.1.0` gates (alongside charon/metis tags); same pointer edit in `golem/plan.md` pre-flights (theseus-mcp/ariadne-mcp). `just test-all && just lint-all`. Check Stage 3.6 in [`tasks.md`](./tasks.md) — **Phase 3 exit**.
  *Done 2026-06-17: sign-off block below; Pythia P4 pre-flight (§2 + §6) gained the `theseus/v0.1.0` + `theseus-mcp/v0.1.0` + `steropes/v0.1.0` (+ `kyklop/v0.1.0`) gates; Golem pre-flight pointers retargeted query-mcp→theseus-mcp, meta-mcp→ariadne-mcp (+ `QueryClient` task). Stage 3.6 ticked in `tasks.md` with the Phase-3-close note + all Phase-3 tags listed. `test-all`/`lint-all` green. Tags prepared, not pushed (applied at merge — tracker rule).*

**DONE means:** security doc matches the modelled behaviour; per-user RLS proven by mocked component tests; OBO + fail-closed proven by mocked component tests; sign-off recorded; downstream arc gates updated. (Live-stack RLS/OBO acceptance deferred to the separate integration-test suite.)

---

## Appendix A — RLS acceptance matrix (T2 output)

Source: `services/argos/src/test/kotlin/org/tatrman/kantheon/argos/acceptance/RlsAcceptanceMatrixSpec.kt` (4 tests, green 2026-06-15). Real `ArgosServiceImpl` + in-process `PolicyEngine`; policy `tenant_isolation` (`tenant_id = <caller tenant>`); mock `customers` = {Acme,Beta}@tenant-7, {Cobalt,Delta}@tenant-9; injected predicate applied to the dataset.

| Bearer (user_id / roles) | `apply_security` | Argos outcome | Rows seen |
|---|---|---|---|
| `tenant-7:alice` / `analyst` | true | `tenant_isolation` applied | Acme, Beta |
| `tenant-9:bob` / `analyst` | true | `tenant_isolation` applied | Cobalt, Delta |
| `tenant-7:root` / `query-platform-admin` | false | bypass honoured (DF-V02); bare scan | Acme, Beta, Cobalt, Delta |
| `tenant-7:alice` / `analyst` | false | bypass **refused** — `security_bypass_denied` Rule-6, forced scoped | Acme, Beta |

Disjoint per-tenant row sets; admin bypass unfiltered; non-admin bypass refused and forced back to scoped — RLS enforcement confirmed at the modelled level.

## Appendix B — component tests added (T2–T4)

| Test | Module | Proves |
|---|---|---|
| `RlsAcceptanceMatrixSpec` (4) | services/argos | per-tenant RLS divergence; DF-V02 admin bypass; bypass-refusal |
| `OboDisciplineComponentSpec` (3) | tools/theseus-mcp | valid bearer → roles reach Argos; no-token / service-token → fail-closed, Argos never called; no credential leak |
| `TokenExpiryComponentSpec` (2) | tools/theseus-mcp | trust-boundary (edge doesn't enforce `exp`); mid-stream data-call failure → clean typed error, no partial results |

**Defect found + fixed during T4:** `QueryTool` ignored Theseus's `errorBatch` contract (ERROR-severity Rule-6 message in `batch.messages`), surfacing internally-caught pipeline failures as `ok=true` with partial/zero rows and a dropped error. Fixed to fail closed on any ERROR-severity batch message.

## Sign-off (T6)

**Reviewer:** Bora **·** **Date:** 2026-06-17 **·** **Verdict: PASS — Phase 3 closed.**

The forked query path is in-repo and reviewed at the modelled level (mocked component tests; live-stack acceptance is the separate integration suite). Identity is resolved only at the **theseus-mcp** edge from the user's OBO bearer (fail-closed, never service identity); **Argos** owns RLS in-process (per-tenant scoping + role-gated DF-V02 admin bypass); pipeline failures surface as clean typed errors with no partial results. The security doc matches the modelled behaviour.

**Acceptance results.**
- **RLS (T2):** tenant-7 → {Acme,Beta}; tenant-9 → {Cobalt,Delta} (disjoint); admin+`apply_security=false` → all rows (DF-V02); non-admin+`apply_security=false` → `security_bypass_denied` + forced scoped. ✔ (Appendix A)
- **OBO (T3):** valid bearer → roles reach Argos; no-token / service-identity → fail-closed, **Argos never called**; no credential leak. ✔
- **Token expiry (T4):** trust boundary pinned (edge does not verify `exp`); mid-stream failure → clean typed error, no partial results. ✔
- **Audit derivability (T5):** all `iris_audit` fields derivable; one bounded gap recorded. ✔

**Open findings (with owners).**
| # | Finding | Severity | Status / owner |
|---|---|---|---|
| 1 | `QueryTool` swallowed Theseus `errorBatch` failures (`ok=true`, partial/zero rows, dropped error) | High (fail-closed) | **Fixed** this stage (QueryTool fails closed on ERROR-severity batch messages) |
| 2 | `query` tool echoes neither executed SQL nor exact total rows (ViewProvenance) — `compile` does | Low (non-blocking) | Deferred — `kantheon-v1.1.md` §11; owner: Iris arc (additive `executedSql`/`totalRows`) |
| 3 | theseus-mcp does not verify token signature/`exp` (by design — ingress's job) | Info (trust boundary) | Documented — `kantheon-security.md` + `TokenExpiryComponentSpec`; defense-in-depth edge check is an open option, not a v1 gap |
| 4 | Pre-existing Metis-arc ktlint debt (PR #17 not gated through `lint-all`) | Low (hygiene) | **Fixed** (formatting-only commit); consider adding the Kotlin lint lane to the Metis CI path |

**Tags applied at Phase-3 close** (coordinated at merge — not pushed from the work branches): `argos/v0.1.0`, `kyklop/v0.1.0`, `brontes/v0.1.0`, `steropes/v0.1.0`, `theseus/v0.1.0`, `theseus-mcp/v0.1.0`.

**Downstream gates updated.** Pythia P4 pre-flight (§2 + §6) now gates on `theseus/v0.1.0` + `theseus-mcp/v0.1.0` + `steropes/v0.1.0` (+ `kyklop/v0.1.0`); Golem pre-flight pointers retargeted to **theseus-mcp** (query edge) and **ariadne-mcp** (model edge). `tasks.md` Stage 3.6 ticked — **Phase 3 exit**.
