# WS-R Stage 1 — `--kube dsk` run mode + reconcile-boundary verification

> **Workstream R (bp-dsk runs), Stage 1.** Make the integration harness run on **bp-dsk** (local, on-demand) while **nightly stays on bp-olymp01**. The hard pre-task is proving ephemeral test namespaces don't collide with bp-dsk's ArgoCD reconcile (it's your *live* cluster).
>
> **Reads with.** [`tasks-overview.md`](./tasks-overview.md) §0, [`../../../architecture/deploy-test/contracts.md`](../../../architecture/deploy-test/contracts.md) §7 (bp-dsk run-mode contract), olymp `docs/test-harness.md` §8/§9, [`master-plan.md`](./master-plan.md) §7-D4.
> **Existing.** olymp `just infra-up context run-id kube *FLAGS` / `infra-down` (Phase A, live on bp-olymp01); kantheon `integrationTest` + `@RequiresContext` + `ContextHandle`. Repos: **[O]** olymp (recipes/boundary) + **[K]** kantheon (Gradle wiring).

## Goal

`just infra-up <context> <id> dsk` stands a context up in an isolated `it-*` namespace on bp-dsk, kantheon's `:integrationTest -Pcontext -Pnamespace` runs the specs against it, `infra-down` reaps it — and **ArgoCD never touches the run namespace**.

## Pre-flight

- [ ] bp-dsk reachable (`kubectl --context dsk`); ArgoCD self-managing (✓).
- [ ] At least one runnable context exists (`theseus-runquery` ✓).
- [ ] Branch `feat/r1-bp-dsk-run-mode` in **both** repos.

## Tasks

- [ ] **T1 [O] — Reconcile-boundary verification (the hard pre-task; tests first).** Prove on bp-dsk what test-harness §8 proved on bp-olymp01: create a throwaway namespace `kantheon-boundary-check` (the real run-ns prefix) + a dummy Deployment in it; confirm **neither** `appset-apps` (`clusters/bp-dsk/apps/*`) **nor** `appset-ops` (`clusters/bp-dsk/platform/*`) generates an Application for it and ArgoCD does **not** prune it. Document the invariant (run-ns name pattern `kantheon-<context>-<run-id>` + `olymp.collite/*` labels are outside every generator glob — the generators read repo paths, not namespaces). **Gate:** nothing else in WS-R proceeds until this passes.
- [ ] **T2 [O] — `infra-up --kube dsk` parameterisation.** Confirm/extend the recipe so `kube=dsk` resolves context `dsk`, creates `kantheon-<context>-<run-id>` (labelled `olymp.collite/{context,run,managed-by}` per the handshake), applies `platform[]` deps + helm-installs `services[]` from the kantheon checkout, waits on `readiness[]`, and prints `namespace=…`. Mirror the bp-olymp01 path; the only delta is the kube context + that platform deps may already exist on bp-dsk (skip-if-present).
- [ ] **T3 [O] — `infra-down --kube dsk` + trap safety.** Deletes the `kantheon-<context>-<run-id>` namespace on bp-dsk (always, even on failure). Verify no leaked namespace after a deliberately-failing run.
- [ ] **T4 [K] — Gradle wiring + `ContextHandle` on bp-dsk.** Confirm `./gradlew :integrationTest -Pcontext=<name> -Pnamespace=<ns>` resolves the namespace from the `infra-up` handshake; `@RequiresContext` readiness gate reads bp-dsk (read-only k8s); `ContextHandle` yields in-cluster URLs. Run `theseus-runquery` end-to-end on bp-dsk as the proof.
- [ ] **T5 [K] — `just it-bp-dsk <context>` convenience loop.** A recipe wrapping up→test→down against bp-dsk (the local on-demand full-run), with `trap` teardown — the TDD "run on bp-dsk" leg for every spec. Document the local→bp-dsk loop.
- [ ] **T6 [O] — k3d parity check.** Confirm the same `infra-up <context> <id> <k3d-ctx>` still works (the fork/local path) so the TDD "run locally" leg is available without bp-dsk. (Regression check — don't break bp-olymp01/k3d.)

## DONE

- [ ] Reconcile-boundary verified on bp-dsk (T1) — a manual `kantheon-*` run ns is left untouched by ArgoCD.
- [ ] `theseus-runquery` runs green via `just infra-up theseus-runquery <id> dsk` → `:integrationTest` → `infra-down` (no leaked ns).
- [ ] The same context runs on k3d locally (parity).
- [ ] `nightly.txt` / bp-olymp01 nightly unchanged (this stage adds bp-dsk, doesn't move the nightly).

## Follow-ups → next stage

- **C2** uses this mode to run the full run-set (incl. `tpcds-query`) on bp-dsk = **MP-4**.
- Mirror the recipe + boundary note into olymp `docs/test-harness.md` (§9 cluster modes).
