# Testing Phase 3 Stage 3.3 — Iris deploy + session-smoke (bp-dsk)

> **Goal.** Stand the user-facing Iris stack (FE nginx + iris-bff) up on the olymp **bp-dsk** cluster and verify the session path end-to-end through a real browser/REST smoke: load → Keycloak login → session CRUD / discovery / history hydration / **reset + undo** via the BFF. The integration-tier verification of the Iris arc's Phase 2 — **owned by the Testing arc** (moved here from Iris Stage 2.4 "Group B", 2026-06-23, Bora).
>
> **Nature.** A GitOps **deploy + live smoke**, mirroring the iris-bff Stage 1.4 deploy precedent — *not* (yet) a `@RequiresContext` nightly spec. It needs the cluster + credentials (GHCR push, Keycloak admin, ArgoCD), so it runs with Bora driving, not in CI. Graduating it to an automated `@RequiresContext("iris-session")` nightly spec is a follow-on (see "Follow-on").
>
> **Reads with.** [`plan.md`](./plan.md) (this arc) · Iris [`../iris/tasks-p2-s2.4-deploy-cutover.md`](../iris/tasks-p2-s2.4-deploy-cutover.md) Group A (the chart/image assets this consumes) · Stage 1.4 [`../iris/tasks-p1-s1.4-deploy-smoke.md`](../iris/tasks-p1-s1.4-deploy-smoke.md) (the iris-bff deploy precedent + bp-dsk gotchas) · [[no-ai-platform-olymp-clusters]].

## Inputs (ready — Iris Stage 2.4 Group A, done 2026-06-23)

- Helm chart **`frontends/iris/k8s`** (same-origin `/bff` nginx; Gateway API HTTPRoute; ConfigMap runtime-env). `helm lint`/`template` green.
- **`just publish-fe-image`** recipe (nginx image → GHCR, amd64).
- **olymp manifests PREPARED** (uncommitted in `~/Dev/collite-gh/olymp`): `clusters/bp-dsk/apps/iris/{config.json,values.yaml}` + the `iris` ns on the `clusterexternalsecret-ghcr-pull` selector. Realm `kantheon`.

## Tasks

- [ ] **T1 — Build + push the FE image.** `GHCR_USER=… GHCR_TOKEN=… just publish-fe-image iris 0.1.0` → `ghcr.io/boraperusic/iris:0.1.0` (linux/amd64; bp-dsk is amd64).
- [ ] **T2 — Land the olymp app.** Commit + PR the prepared `clusters/bp-dsk/apps/iris/{config.json,values.yaml}` (separate olymp repo). The ApplicationSet git-files generator auto-discovers the dir → Application `iris`, ns `iris`. `config.json.chartRevision` = the Phase-2 FE branch, flipped → `main` on merge (T6).
- [ ] **T3 — `ghcr-pull` in ns `iris`.** Land the `clusterexternalsecret-ghcr-pull` selector edit (adds the `iris` ns); the `auth` Application materialises the dockerconfigjson. **Gotcha (1.4):** the first pod can race the secret → ImagePullBackOff; delete it once the secret exists. New-ns ghcr-pull lands only after `auth` re-syncs.
- [ ] **T4 — Keycloak `iris` client** (realm **`kantheon`**): a public SPA client `iris` with redirect URIs + web origins for `https://iris.192-168-1-38.nip.io`. **Required for the smoke** — the BFF demands a bearer, so the SPA must log in and forward the OBO token. Config-only.
- [ ] **T5 — ArgoCD sync + live session-smoke.** App `iris` Synced + Healthy; force the ApplicationSet refresh annotation if the git-generator lags (1.4 gotcha). Then through the browser at `https://iris.192-168-1-38.nip.io`:
  - load + Keycloak login (kantheon realm);
  - session **create** (left rail "+ New") + **list/switch**;
  - **history hydration** (reload restores the conversation — needs ≥1 persisted turn; see the turn-leg caveat);
  - **`/reset` + Undo** round-trip (server snapshot restore);
  - 401 without a bearer (sanity).
  - **Turn-leg caveat (standing):** a full chat *turn* (stream/edit_resend re-run) needs a `/v2`-speaking golem behind the BFF — deferred to the Golem arc. Session/discovery/hydration/reset+undo are smoke-able now; the turn path is asserted once a `/v2` backend is in-cluster.
- [ ] **T6 — Tags + chartRevision flip.** Tag `iris/v0.1.0` + `iris-bff/v0.2.0`; flip the olymp `iris` (+ `iris-bff`) `config.json` chartRevision feature-branch → `main` on the Phase-2 PR merge. **This closes Iris Phase 2 → crosses M3** ("Iris usable").

**Pre-flight.** Iris Stage 2.4 Group A (chart + image recipe) done; iris-bff already live on bp-dsk (Stage 1.4, central-PG). **DONE:** the session-smoke (T5) is green and the tags land (T6); the Iris stack is reachable + daily-usable for the non-turn surface. **Branch:** `feat/p3-s3.3-iris-deploy-smoke` (mostly olymp + live ops; minimal kantheon code — a smoke checklist / optional script).

## Follow-on (not this stage)
- **Automated `@RequiresContext("iris-session")` nightly spec** — once olymp can stand an Iris context up (FE+BFF+PG) and a `/v2` golem (or a WireMock golem) is available, formalise the T5 smoke as a Phase-3 integration spec in the nightly run-set (joins 3.1's context roster). Until then T5 is a Bora-driven manual smoke.
- **Turn-leg assertion** — gated on a `/v2`-speaking golem behind the BFF (Golem arc).
