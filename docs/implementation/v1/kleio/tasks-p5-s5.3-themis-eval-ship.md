# Stage 5.3 — Themis routing + eval + ship

> **Phase 5, Stage 5.3.** Branch `feat/docwh-p5-s5.3-themis-eval-ship`. **The final stage of the arc.**
>
> **Reads with.** [`tasks-p5-overview.md`](./tasks-p5-overview.md), [`plan.md`](./plan.md) §7 Stage 5.3, [`../../../architecture/kleio/architecture.md`](../../../architecture/kleio/architecture.md) §13 (observability + testing), [`../../../architecture/kleio/contracts.md`](../../../architecture/kleio/contracts.md) §9 (the `KNOWLEDGE` intent), [`../../../architecture/themis/`](../../../architecture/themis/) (the routing layers Kleio plugs into).

## Goal

Themis routes `KNOWLEDGE`-intent questions to Kleio (counter-examples keep doc questions off Golem/Pythia); the eval corpus gates grounded-citation faithfulness + NO_GROUNDING honesty + mart-scope leakage; observability + Grafana + trace nesting; docs + cross-refs. DONE = tag **`kleio/v0.1.0`** — the DocWH is a live constellation citizen.

## Tasks (5)

- [ ] **T1 — Themis `KNOWLEDGE` intent in `classifyIntentKind` + route-to-Kleio (cross-arc).**

  In the Themis module: add `KNOWLEDGE` to `classifyIntentKind` and the route-to-Kleio path (cross-arc — coordinate with the Themis arc). Add **counter-examples** that keep document/notebook questions off Golem/Pythia and analytical questions off Kleio (the classifier boundary). Pairs with the additive enum from S5.2 T4.

  Acceptance: `KNOWLEDGE`-intent questions route to Kleio; counter-examples hold (Themis classifier test green).

- [ ] **T2 — Eval corpus (`eval/`) + eval gate.**

  Build `agents/kleio/eval/`: grounded-citation **faithfulness** (every claim traces to a retrieved source), **NO_GROUNDING honesty** (out-of-mart questions refuse, don't fabricate), **mart-scope leakage negatives** (a question never surfaces another mart's content). Wire the eval as a CI gate (the Golem/Pythia eval-gate precedent).

  Acceptance: eval corpus runs; the gate passes; faithfulness + honesty + no-leakage thresholds met.

- [ ] **T3 — E2E smoke Iris→Themis→Kleio (mock where the Spine isn't live).**

  An E2E smoke from Iris through Themis routing into Kleio and back (cited envelope rendered in Iris). Mock the parts of the Spine that aren't live locally (planning-conventions §4 — the real in-K3s e2e is the integration suite).

  Acceptance: the Iris→Themis→Kleio path produces a cited answer (mocked where needed).

- [ ] **T4 — Observability (architecture §13) + Grafana + trace nesting.**

  Emit the Kleio metrics (`kleio_turns_total{status}`, `kleio_grounded_citations{count}`, `kleio_artifact_total{kind}`) + the retrieval metrics already present; wire Grafana panels; ensure trace nesting (Iris turn → Themis → Kleio → `library.*` → Prometheus is one trace). 

  Acceptance: metrics emit; traces nest end-to-end; Grafana panels render (deploy-time check).

- [ ] **T5 — Docs + kantheon-architecture cross-ref + master-plan status; tags.**

  Update `docs/architecture/kantheon-architecture.md` (Kleio is now a live citizen — the `KNOWLEDGE` plane), the master-plan status (Stream B, Librarian/DocWH arc → done), and the Kleio README. Tag **`kleio/v0.1.0`**; bump catalog.

  Acceptance: docs + cross-refs updated; tag pushed. PR `[docwh-p5-s5.3] themis routing + eval + ship`.

## DONE — Stage 5.3

- [ ] All five tasks checked.
- [ ] Themis routes `KNOWLEDGE` to Kleio; counter-examples keep the boundary clean.
- [ ] Eval gate green (faithfulness + NO_GROUNDING honesty + mart-scope leakage negatives).
- [ ] E2E smoke Iris→Themis→Kleio produces a cited answer (mocked where the Spine isn't live).
- [ ] Observability + Grafana + nested traces.
- [ ] kantheon-architecture + master-plan + README updated.
- [ ] Tag `kleio/v0.1.0` pushed. **Phase 5 DONE — the DocWH is a live constellation citizen. Kleio arc complete.**
- [ ] PR merged.

## Library / pattern references

- **contracts.md §9** — the `KNOWLEDGE` intent + Kleio `AgentCapability`. **themis arch/contracts** — the routing layers + `classifyIntentKind`.
- **architecture.md §13** — the Kleio metric set + the testing split.
- Golem/Pythia `eval/` — the eval-gate precedent.

## Out of scope for Stage 5.3

- The Ariadne bridge / write-back loop / user-facing mart editing (all v1.x — plan §10).
- Real in-K3s Iris→Themis→Kleio e2e (integration suite).
