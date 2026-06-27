# Stage 2.3 — Cross-repo bring-up + nightly workflow

> **Phase 2, Stage 2.3.** Closes the loop: olymp's scripted `infra-up/down` + kantheon's nightly workflow run the `theseus-runquery` context end-to-end, with no ArgoCD. **Cross-repo — depends on olymp test-harness Phase A.**
>
> **Reads with.** [`tasks-p2-overview.md`](./tasks-p2-overview.md), [`../../../architecture/testing/architecture.md`](../../../architecture/testing/architecture.md) §5/§7/§8, [`../../../architecture/testing/contracts.md`](../../../architecture/testing/contracts.md) §5 (CI orchestration) + §6 (handshake), olymp [`docs/test-harness.md`](../../../../../collite-gh/olymp/docs/test-harness.md) (D23 + Phase 7).
>
> **Status (2026-06-20): code-complete; awaiting the first live nightly run.** Olymp Phase A is live (Phase 7.1–7.5: `infra-up/down`, the `theseus-runquery` context, the WireMock component, `bp-olymp01` as test cluster, the namespace handshake). The kantheon side landed: the readiness gate now **derives** readiness from the namespace (no annotation — olymp writes only the two ns labels), `tools/theseus-mcp/k8s` is Helm-ified + added to the context, and `integration-nightly.yml` exists. **Two contract-seam reconciliations were needed** vs. this task's original assumptions — see the per-task notes. T1/T2/T4 are done at code level; **T3 (olymp CI secret), T5 (first green run + timing), T6 (failure-issue verified live) need the runner + cluster** and are Bora's to land. Olymp-side prep (the `theseus-mcp` service entry + `theseus-mcp.values.yaml`) is staged in the olymp working tree for Bora's PR.

## Goal

A scheduled `integration-nightly.yml` checks out kantheon + olymp, runs `infra-up theseus-runquery` → `:integrationTest -Pcontext=theseus-runquery` → `infra-down` (always, via trap), green end-to-end on the olymp test cluster, with namespace-per-run isolation and a tracked issue on red (the ratified failure policy).

## Pre-flight

- [x] Stages 2.1 + 2.2 closed.
- [x] **olymp test-harness Phase A live** (olymp Stages 7.1–7.5): `infra-up/down` recipes, `test-contexts/theseus-runquery/`, the `platform/testing/wiremock` component, the `bp-olymp01` test cluster, the namespace handshake + labels. *(Verified in olymp `cfc6610`; theseus-runquery LIVE.)*
- [ ] `Collite/olymp` read credential as a CI secret (`OLYMP_GITHUB_TOKEN`) — distinct from the `Collite/modeler` packages PAT (CLAUDE.md §7.3). **(T3 — Bora.)**
- [ ] Branch `feat/p2-s2.3-nightly-integration`.

## Contract-seam reconciliations (this was the T1 work)

Olymp's live implementation differed from this task's original assumptions in two ways that would have **failed the suite**; both are now fixed:

1. **Readiness handshake.** Olymp writes only the two ns labels (`olymp.collite/context`, `/run`) + `managed-by` — **no `olymp.collite/readiness` annotation**. The kantheon gate originally *required* that annotation and threw without it. **Fix (kantheon-side, Bora-ratified "derive in kantheon"):** `Fabric8ClusterReader` now lists the namespace's Deployments/StatefulSets/Jobs and asserts each Ready — no new cross-repo surface; contract stays at the two labels. (`contracts.md` §4.2 updated.)
2. **`theseus-mcp` was not in the context.** The context Helm-installed `services/theseus` but not the MCP edge the suite drives. **Fix (cross-repo):** Helm-ified `tools/theseus-mcp/k8s` (was kustomize) + added `theseus-mcp` to the olymp `context.yaml` `services[]`/`readiness[]` with `theseus-mcp.values.yaml` (staged olymp-side). Validated via `just infra-up … --dry-run` against this checkout.

## Tasks

- [x] **T1 — Verify olymp's `infra-up/down` + namespace handshake (integration point).** *(Done — the two seam fixes above. The real olymp CLI is `infra-up <context> <run-id> <kube> [--kantheon <path>] [--ghcr-from <ns>/<secret>]` — `kube` is a **required positional**, `--kantheon` is required (charts come from the kantheon checkout), `--ghcr-from` supplies the private-image pull secret. Validated via `--dry-run` against this checkout: it helm-installs all six services incl. theseus-mcp and prints the handshake.)*

- [x] **T2 — `integration-nightly.yml` workflow.** *(Done — `.github/workflows/integration-nightly.yml`. YAML validates; the `trap` guarantees teardown; `integrationTest` skips locally with no `-Pcontext`.)* The corrected invocation (note the required `kube` positional + flags + the right module):
  ```yaml
  jobs:
    integration:
      runs-on: [self-hosted, olymp-test]   # or ubuntu-latest + k3d fallback (T5)
      steps:
        - uses: actions/checkout@v4                       # kantheon @ SHA
        - uses: actions/checkout@v4
          with: { repository: Collite/olymp, token: ${{ secrets.OLYMP_GITHUB_TOKEN }}, path: olymp }
        - run: |
            set -euo pipefail
            CTX=theseus-runquery; RUN="${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}"
            KUBE=bp-olymp01
            cleanup(){ just -f olymp/justfile infra-down $CTX $RUN $KUBE || true; }
            trap cleanup EXIT
            NS=$(just -f olymp/justfile infra-up $CTX $RUN $KUBE \
                   --kantheon "$GITHUB_WORKSPACE" --ghcr-from argocd/ghcr-pull \
                   | sed -n 's/^namespace=//p')
            ./gradlew :tools:theseus-mcp:integrationTest -Pcontext=$CTX -Pnamespace=$NS
  ```
  **Acceptance:** workflow validates; the `trap` guarantees teardown even on test failure.

- [ ] **T3 — Olymp checkout credential.** **(Bora — needs the secret.)**

  Add the `Collite/olymp` read secret `OLYMP_GITHUB_TOKEN` to the repo/org; confirm the private-repo checkout works in the runner. Optionally set repo vars `OLYMP_KUBE_CONTEXT` / `OLYMP_GHCR_FROM` (the workflow defaults to `bp-olymp01` / `argocd/ghcr-pull`). Document the secret in CLAUDE.md §7 (a *testing* credential, not ai-platform coupling). **Acceptance:** the olymp checkout step succeeds.

- [x] **T4 — Pass the namespace handshake into Gradle.** *(Done in the workflow — captures `infra-up`'s `namespace=` line via `sed -n 's/^namespace=//p'` → `-Pnamespace`, with `-Pcontext=theseus-runquery`. The `@RequiresContext` gate resolves the ns from `-Pnamespace` and the derived readiness asserts against it. Final live confirmation rides with T5.)*

- [ ] **T5 — First green nightly (server) + k3d fallback documented.**

  Run the workflow against the persistent olymp testing server; capture wall-clock + image-pull/build timing for the Phase 3 budget. Document the ephemeral-k3d fallback path (`runs-on: ubuntu-latest` + a k3d bring-up before `infra-up`) for forks/server-down. **Acceptance:** one fully green nightly; timing recorded in the PR/issue.

- [ ] **T6 — Failure UX: red nightly opens a tracked issue (ratified policy).**

  On job failure, open/update a GitHub issue with the failing context, the run link, and the relevant logs (e.g. via `actions/github-script` or a maintained action). Per the ratified policy (architecture §8): a red nightly is an **issue**, not a retroactive block on the window's merges. Teardown still runs (trap). **Acceptance:** a deliberately broken service → red nightly → issue created → namespace still torn down (verify `kubectl get ns` shows none lingering).

## DONE criteria

- [x] One full green nightly: `infra-up → :integrationTest → infra-down`, no ArgoCD. *(Achieved after a four-round live bring-up: missing `theseus-mcp:testing` image → `just publish-image`; wrong kube-context for the test JVM → job-scoped kubeconfig; out-of-cluster DNS → fabric8 port-forward through the API server. The harness loop + fail-fast readiness gate + identity-discipline assertion are green; teardown verified clean each round.)*
- [x] A deliberately broken service turns the nightly red, opens an issue, and leaks **no** namespace. *(Observed incidentally — every failed round opened/updated issue #39 via the failure step and `infra-down` left no namespace; the diagnostics block captures pod state pre-teardown.)*
- [ ] Namespace-per-run verified (two concurrent runs don't collide — coordinate with olymp Stage 7.5).
- [ ] Timing captured for the Phase 3 image-supply budget.

> **Scoped close (Bora, 2026-06-20).** Stage 2.3's deliverable — the cross-repo harness loop (bring-up → fail-fast gate → teardown), the nightly, and end-to-end identity discipline — is **proven**. The query *result* + RLS assertions are **deferred** (disabled behind `RunQueryIntegrationSpec.modelAlignedContext = false`): the live run showed the context's Model doesn't match its seed (`detection_failed` — Proteus' fixture model lacks `dbo.sample_orders`; Argos' deployed policy is `tenant_isolation`, not the assumed column-DENY). Re-enabling is **Phase 3 Stage 3.1 T7** — give `theseus-runquery` a Model aligned with the `mssql-init` seed (Ariadne model, or fixture-model extension) + an aligned Argos policy/bearer. This pairs naturally with the Ariadne-bearing Phase 3 contexts.

## Notes for the executor

- This is the cross-repo seam: if olymp's recipe interface drifts from contracts §5/§6, fix the **contract doc** + both sides, don't paper over it in the workflow.
- Teardown reliability is non-negotiable — the `trap`/`finally` must run `infra-down` even when Gradle exits non-zero. The Phase 3 reaper is the backstop, not the primary path.
- Keep the nightly to the single `theseus-runquery` context this phase; the run-set grows in Phase 3.
