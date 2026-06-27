# Stage 4.2 — OBO + Argos mart RLS + RAG consumers + browse

> **Phase 4, Stage 4.2.** Branch `feat/docwh-p4-s4.2-rls-consumers-browse`.
>
> **Reads with.** [`tasks-p4-overview.md`](./tasks-p4-overview.md), [`plan.md`](./plan.md) §6 Stage 4.2, [`../../../architecture/kleio/contracts.md`](../../../architecture/kleio/contracts.md) §7 (mart RLS model) + §4 (write ops), [`../../../architecture/kleio/architecture.md`](../../../architecture/kleio/architecture.md) §9 (entitlements) + §14 (cross-mart leakage), [`../../../architecture/kantheon-security.md`](../../../architecture/kantheon-security.md) (OBO + Argos `bearer` + audit §4).

## Goal

Mart RLS enforced at the MCP edge (OBO bearer → Argos `bearer` roles → visibility predicate, **before** the store is touched; store filters as defence-in-depth); ops-gated mart writes with audit; a RAG-consumer proof (a mock Golem/Pythia `getContext` under a caller bearer returns cited chunks — **RAG GA**); a minimal wiki browse FE. DONE = RAG GA under RLS; wiki browsable; tags **`kallimachos-mcp/v0.1.0`** + **`kallimachos/v0.4.0`**.

## Tasks (7)

- [ ] **T1 — Tests first: `MartRlsSpec`.**

  Spec the RLS predicate (contracts §7): read mart `N` iff `N.owner_user_id == caller.user_id` **OR** `N.visibility_roles ∩ caller_roles ≠ ∅` (roles from the forwarded OBO bearer). A caller without rights gets `PERMISSION_DENIED` **before the store is touched**; `kallimachos_mart_rls_denied_total` increments. Cover: owner access, role-overlap access, no-overlap denial, the `"*"` admin scope.

  Acceptance: spec written and failing. Commit `[docwh-p4-s4.2] failing mart rls spec`.

- [ ] **T2 — OBO bearer forwarding at the MCP edge; Argos `bearer` role source; store defence-in-depth.**

  Implement RLS enforcement at the `kallimachos-mcp` edge: extract roles from the forwarded **OBO bearer** via Argos `bearer` (security §3.6 — never service identity); evaluate the visibility predicate before forwarding to the store. The store **also** filters by the scoped mart (defence in depth — architecture §9).

  Acceptance: T1 `MartRlsSpec` green; denial happens at the edge, store-side filter is the backstop.

- [ ] **T3 — `createNotebook`/`addToNotebook` ops-gated; mart membership writes; audit rows.**

  Implement the write ops (contracts §4) as **ops/admin-gated** at v1 (user-facing mart editing is v1.x — architecture §9). `addToNotebook` (the share action) is itself permission-checked. Write an audit `request_log` row per ingest/retrieval (security §4).

  Acceptance: write ops gated; audit rows written; spec green.

- [ ] **T4 — RAG-consumer proof (RAG GA).**

  Prove the RAG use case: a fixture `getContext` call from a **mock Golem/Pythia client** (under a caller bearer) returns cited chunks scoped to the caller's visible marts. Add the cross-arc note to the golem/pythia plans (the optional "RAG via `library.getContext`" note — **no code in those arcs**). This is the **MK — Knowledge plane** mergepoint (plan §8).

  Acceptance: mock-consumer `getContext` returns cited chunks under RLS; cross-arc notes added.

- [ ] **T5 — Minimal `frontends/kallimachos-browse` (Vue) — page view + traverse.**

  Build the minimal wiki browse FE (architecture §4): a page view (markdown + `concept_ref`) + link/graph traverse over `library.getPage`/`library.traverse`. Vue, following the kantheon FE conventions (EXAMPLES.md §11).

  Acceptance: the browse FE renders a page + walks links via `library.*`.

- [ ] **T6 — Security-note sign-off (cross-mart leakage).**

  Write the security note signing off the cross-mart-leakage mitigation (architecture §14, security §): callers forward the user bearer (never service identity); RLS at the edge + store defence-in-depth; the negative tests (no-overlap denial) are the evidence. Bora sign-off.

  Acceptance: security note committed + signed off.

- [ ] **T7 — Deploy; tag.**

  Deploy the MCP + browse FE; smoke RLS (an unauthorised mart access denied; an authorised one served). Tag **`kallimachos-mcp/v0.1.0`** + **`kallimachos/v0.4.0`**; bump catalog.

  Acceptance: RLS smoke passes; both tags pushed. PR `[docwh-p4-s4.2] obo + argos rls + rag consumers + browse`.

## DONE — Stage 4.2

- [ ] All seven tasks checked.
- [ ] Mart RLS at the MCP edge (OBO → Argos `bearer` → predicate) with store defence-in-depth; `PERMISSION_DENIED` before store touch; `kallimachos_mart_rls_denied_total` emitted.
- [ ] Write ops ops-gated + audit rows; cross-mart-leakage security note signed off.
- [ ] RAG-consumer proof (mock Golem/Pythia) — **RAG GA**; cross-arc notes in golem/pythia plans.
- [ ] Minimal wiki browse FE over `library.*`.
- [ ] Tags `kallimachos-mcp/v0.1.0` + `kallimachos/v0.4.0` pushed. **Phase 4 DONE — RAG GA under RLS; wiki browsable.**
- [ ] PR merged.

## Library / pattern references

- **contracts.md §7** — the mart RLS model (the authority). **§4** — write ops.
- **architecture.md §9/§14** — entitlements + cross-mart leakage. **kantheon-security.md** — OBO + Argos `bearer` + audit §4.
- **EXAMPLES.md §11** — Vue FE consuming the envelope/store.

## Out of scope for Stage 4.2

- User-facing mart editing (v1.x — ops/admin only at v1).
- The Kleio agent (Phase 5).
- Real OBO/Argos-against-cluster + in-K3s e2e (integration suite).
