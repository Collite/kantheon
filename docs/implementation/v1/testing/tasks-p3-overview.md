# Phase 3 — Coverage rollout + hardening

> **Reads with.** [`plan.md`](./plan.md) (Phase 3), [`../../../architecture/testing/architecture.md`](../../../architecture/testing/architecture.md) §4.1/§5/§8, olymp [`docs/test-harness.md`](../../../../../collite-gh/olymp/docs/test-harness.md) (Phase B), [`../../planning-conventions.md`](../../planning-conventions.md).
>
> **Phase deliverable.** Integration coverage across the cluster-worthy chains (golem-erp, themis-routing, pythia-rca), and a nightly that is cost-bounded and self-cleaning enough to run indefinitely (warm image supply, namespace reaper, release-tag gating, flake policy).
>
> **Dependency note.** Each new context needs its target chain to (a) build and (b) have a D3′ Helm chart. `pythia-rca` in particular gates on the Charon + Metis arcs reaching the cluster. Add contexts incrementally as those arcs land; this phase is **not** all-at-once.

## Stages

| Stage | Goal — testable boundary | Task list |
|---|---|---|
| **3.1** — Additional contexts | nightly runs ≥3 contexts green, isolated by namespace-per-run | [`tasks-p3-s3.1-contexts.md`](./tasks-p3-s3.1-contexts.md) |
| **3.2** — Image supply + cluster hygiene | a cold nightly completes within budget; leaked namespaces are swept; release tags gate on integration | [`tasks-p3-s3.2-hardening.md`](./tasks-p3-s3.2-hardening.md) |
| **3.3** — Iris deploy + session-smoke | Iris (FE+BFF) live on bp-dsk; session path smokes green; Iris Phase 2 closes (M3) | [`tasks-p3-s3.3-iris-deploy-smoke.md`](./tasks-p3-s3.3-iris-deploy-smoke.md) |
| **3.4** — Sysifos deploy + workbench-smoke | Sysifos (FE+BFF) live on bp-dsk; layered data-entry smoke green; Sysifos Phase 2 closes | [`tasks-p3-s3.4-sysifos-deploy-smoke.md`](./tasks-p3-s3.4-sysifos-deploy-smoke.md) |

## Sequencing

```
Stage 3.1 ──► Stage 3.2          Stage 3.3 (Iris)      Stage 3.4 (Sysifos)
  more contexts    hardening/budget    deploy + smoke        deploy + smoke
                                       (harness-independent — only needs the arc chart + bp-dsk)
```

3.1 stages can land per-context as each target arc reaches a chart (golem-erp first — Golem already has a Helm chart per olymp Phase 5; themis-routing next; pythia-rca last, gated on Charon/Metis on-cluster). **Stages 3.3/3.4 are deploy+smoke stages, independent of the 3.1/3.2 nightly harness** — each lands when its arc's chart/image assets + a live bp-dsk are ready (3.4 also needs a live Midas-core + Excel loader).

## Pre-flight for the phase

- [ ] Phase 2 closed (one green nightly for `theseus-runquery`).
- [ ] olymp test-harness **Phase B** scheduled (olymp Stages 7.6–7.7: more context manifests, reaper, warm registry).
- [ ] Each target chain builds + has a D3′ chart (verify per context before adding it).

## Aggregate progress

- [ ] **Stage 3.1** — golem-erp / themis-routing / pythia-rca contexts + specs + fixtures; run-set isolation.
- [ ] **Stage 3.2** — warm image supply, namespace reaper, timing budget + alert, release-tag gating, flake/quarantine policy, main-branch policy doc.
- [ ] **Stage 3.3** — Iris deploy + session-smoke on bp-dsk; tags; Iris Phase 2 closes (M3).
- [ ] **Stage 3.4** — Sysifos deploy + workbench-smoke on bp-dsk; tags; Sysifos Phase 2 closes.

When 3.1/3.2 are checked, the nightly integration harness is DONE: the constellation has nightly integration coverage on a self-maintaining harness. 3.3/3.4 are the harness-independent live deploy+smoke stages for the user-facing (Iris) + back-office (Sysifos) stacks.
