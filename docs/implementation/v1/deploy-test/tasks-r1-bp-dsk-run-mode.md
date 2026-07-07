# WS-R Stage 1 — `--kube dsk` run mode + reconcile-boundary verification

> **Workstream R (bp-dsk runs), Stage 1.** Make the integration harness run on **bp-dsk** (local, on-demand) while **nightly stays on bp-olymp01**. The hard pre-task is proving ephemeral test namespaces don't collide with bp-dsk's ArgoCD reconcile (it's your *live* cluster).
>
> **Reads with.** [`tasks-overview.md`](./tasks-overview.md) §0, [`../../../architecture/deploy-test/contracts.md`](../../../architecture/deploy-test/contracts.md) §7 (bp-dsk run-mode contract), olymp `docs/test-harness.md` §8/§9, [`master-plan.md`](./master-plan.md) §7-D4.
> **Existing.** olymp `just infra-up context run-id kube *FLAGS` / `infra-down` (Phase A, live on bp-olymp01); kantheon `integrationTest` + `@RequiresContext` + `ContextHandle`. Repos: **[O]** olymp (recipes/boundary) + **[K]** kantheon (Gradle wiring).

## Goal

`just infra-up <context> <id> dsk` stands a context up in an isolated `it-*` namespace on bp-dsk, kantheon's `:integrationTest -Pcontext -Pnamespace` runs the specs against it, `infra-down` reaps it — and **ArgoCD never touches the run namespace**.

## Pre-flight

- [x] bp-dsk reachable (`kubectl --context dsk`); ArgoCD self-managing (✓). Read-only `kubectl get`
  works from the coding-agent sandbox; **mutations** (ns/deploy create, `infra-up/down`) are Bora's.
- [x] At least one runnable context exists (`theseus-runquery` ✓).
- [ ] Branch `feat/r1-bp-dsk-run-mode` in **both** repos (kantheon ✓ 2026-07-07; olymp pending).

## Tasks

- [x] **T1 [O] — Reconcile-boundary verification (the hard pre-task; tests first).** ✅ **VERIFIED on bp-dsk 2026-07-07.** Bora created the throwaway `kantheon-boundary-check` ns + a `dummy` Deployment; read-only checks confirmed the invariant holds:
  - The two ApplicationSets are **git generators over `Collite/olymp` repo paths** — `bp-dsk-apps` = git-*files* `clusters/bp-dsk/apps/*/config.json`; `bp-dsk-ops` = git-*directories* `clusters/bp-dsk/platform/*`. **Neither reads cluster/namespace state.** An ephemeral `kantheon-<context>-<run-id>` ns has no matching repo folder ⇒ **no Application is generated** ⇒ ArgoCD has nothing to prune it with.
  - Evidence: **0** of the 28 live Applications reference `kantheon-boundary-check` (name or `.spec.destination.namespace`); the ns carries only `kubernetes.io/metadata.name` (no `argocd.*` tracking); the Deployment carries only `app: dummy` (no argocd tracking labels/annotations) and stayed **1/1 Running** (not pruned).
  - **Invariant documented:** run-ns name pattern + `olymp.collite/*` labels are outside every generator glob because the generators read **repo paths, not namespaces**. *(Still to mirror into olymp `docs/test-harness.md` §9 — T-followup.)*
  - **Gate cleared:** WS-R may proceed.
- [x] **T2 [O] — `infra-up --kube dsk` parameterisation.** ✅ **No olymp code needed — the harness is already `--kube`-parameterised** (recon 2026-07-07). `just infra-up context run-id kube *FLAGS` takes `kube` as a positional; `00-bootstrap/bootstrap.sh` already maps `bp-dsk → dsk`; `just-helper.py` creates `kantheon-<context>-<run-id>` with the `olymp.collite/{context,run,managed-by}` labels, applies `platform[]` (kustomize `_test` overlay, else base — no skip-if-present needed, it's namespace-scoped), helm-installs `services[]` from the `--kantheon` checkout, waits `readiness[]`, and prints `namespace=…` as its sole stdout line. The recipe's own usage example is `just infra-up theseus-runquery $RUN_ID dsk --kantheon ~/Dev/collite-gh/kantheon`. Private images pull via `--ghcr-from argocd/ghcr-pull`. **Remaining:** a live confirmation run (Bora cluster op).
- [x] **T3 [O] — `infra-down --kube dsk` + trap safety.** ✅ **Already provided.** `just infra-down context run-id dsk` = `kubectl delete namespace … --ignore-not-found --wait` (idempotent, safe-if-absent); the `just it-bp-dsk` recipe (T5) wraps it in a bash `trap … EXIT` so it fires on success **or** failure. No olymp change.
- [x] **T4 [K] — Gradle wiring + `ContextHandle` on bp-dsk.** ✅ **Code done 2026-07-07.** The `-Pcontext`/`-Pnamespace` pass-through already existed; added a **`-PkubeContext`** knob (root `build.gradle.kts` → sysprop `kubeContext` → `Fabric8ClusterReader.defaultClient()` → `Config.autoConfigure("dsk")`) so the read-only cluster reader targets bp-dsk **without** mutating the developer's current-context. `ContextHandle` port-forwards through the dsk API server unchanged. **Remaining:** the live `theseus-runquery` proof run (Bora cluster op).
- [x] **T5 [K] — `just it-bp-dsk <context>` convenience loop.** ✅ **Done 2026-07-07** (`justfile`): `infra-up dsk --ghcr-from argocd/ghcr-pull` → parse `namespace=` → `./gradlew integrationTest -Pcontext -Pnamespace -PkubeContext=dsk` → `trap` `infra-down` on EXIT. One context / one run-id / one namespace (§8). `OLYMP_DIR` overridable.
- [x] **T6 [O] — k3d parity check + docs mirror.** ✅ k3d/local path unchanged (same `--kube <k3d-ctx>` recipes — no code touched, so no regression). Docs mirror **done**: bp-dsk added as the third `docs/test-harness.md` §9 cluster mode + the R1-T1 boundary invariant recorded (olymp branch `feat/r1-bp-dsk-run-mode`, commit `c6e483c`; **Bora pushes** — prod GitOps).

## DONE

- [x] Reconcile-boundary verified on bp-dsk (T1) — a manual `kantheon-*` run ns is left untouched by ArgoCD (2026-07-07).
- [ ] `theseus-runquery` runs green via `just it-bp-dsk theseus-runquery` (infra-up dsk → `:integrationTest` → infra-down, no leaked ns) — **code-complete; awaiting the live proof run (Bora cluster op).**
- [x] The same context runs on k3d locally (parity) — recipes untouched, so the k3d/local path is unaffected by this stage.
- [x] `nightly.txt` / bp-olymp01 nightly unchanged (this stage adds bp-dsk, doesn't move the nightly).

## Follow-ups → next stage

- **C2** uses this mode to run the full run-set (incl. `tpcds-query`) on bp-dsk = **MP-4**.
- ✅ Mirrored the bp-dsk mode + boundary note into olymp `docs/test-harness.md` §9 (done, T6).
- **Per-context gate filtering (needed before >1 live integration spec — a C2 pre-req).** Today the
  `RequiresContextExtension` gate ignores `-Pcontext` for spec *selection*: with `-Pnamespace` set,
  **every** `@RequiresContext` spec binds to that one namespace, and non-matching specs pass only
  because their test leaves are currently `enabled = false` (theseus `modelAlignedContext`, golem
  `liveContext`). Once a second context's leaves go live, `just it-bp-dsk <ctx>` would run the other
  context's (now-enabled) tests against the wrong namespace. Fix in C2: have the gate **skip** specs
  whose `@RequiresContext(name)` ≠ the `-Pcontext` sysprop (or scope the Gradle run to the owning
  module). Not needed for R1's single-live-spec proof, but land it before C2's multi-context run-set.
