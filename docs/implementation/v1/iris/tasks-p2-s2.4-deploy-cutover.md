# Iris Phase 2 Stage 2.4 — deploy + cutover

> **Goal (plan §4 Stage 2.4).** `frontends/iris` served from the olymp **bp-dsk** cluster (nginx), all traffic via the BFF; daily-usable. Phase 2 closes here — tags `iris/v0.1.0` + `iris-bff/v0.2.0`.
>
> **Reworked 2026-06-23 — no ai-platform** ([[no-ai-platform-olymp-clusters]]). The plan's tasks 3–4 (agents-fe side-by-side / mark agents-fe frozen in ai-platform) are **void** — there is no agents-fe to run beside, and ai-platform is not integrated. Deploy is GitOps to **bp-dsk** (kube-context `dsk`), chart in kantheon + app/values in olymp, mirroring the Stage 1.4 iris-bff deploy.
>
> **This stage = the deploy ASSETS only (Group A).** The live deploy + smoke + Phase-2 tags (the former "Group B") were **moved to the Testing arc, Stage 3.3** ([`../testing/tasks-p3-s3.3-iris-deploy-smoke.md`](../testing/tasks-p3-s3.3-iris-deploy-smoke.md)) on 2026-06-23 (Bora) — cluster-driven verification belongs with testing.
>
> **Companions.** [`plan.md`](./plan.md) §4 · `agents/iris-bff/k8s` (the chart pattern) · Stage 1.4 [`tasks-p1-s1.4-deploy-smoke.md`](./tasks-p1-s1.4-deploy-smoke.md) (the GitOps deploy precedent) · olymp `clusters/bp-dsk/apps/iris-bff/`.

## Scoping findings (2026-06-23)

The FE already carries a **complete ai-platform-era container stack** (subtree-imported): `Dockerfile` (nginx:alpine, `COPY dist`, EXPOSE 7012), `nginx.conf.template`, `scripts/{generate-env.sh,nginx-entrypoint.sh}` (the `window.APP_CONFIG`→`/env.js` runtime-env mechanism, wired via `index.html`). What's missing / stale:

- **Same-origin BFF wiring.** Stage 2.2 re-pointed the app to a single backend (`config.bff.baseUrl`, default `http://localhost:7410`) and added an **SSE-tuned vite `/bff` proxy** — i.e. the intended prod shape is **same-origin**: `VITE_BFF_BASE_URL=/bff`, nginx proxies `/bff/`→ in-cluster `iris-bff:7410`. This keeps iris-bff ClusterIP (no external exposure, no CORS); only the FE is exposed. The prod `nginx.conf.template` still has the **stale** `/llm/api/`, `/golem/`, `/erp/mcp/`, `/fuzzy/mcp/` proxies and **no `/bff` block** — must be reworked.
- **`generate-env.sh` omits `VITE_BFF_BASE_URL`** (the live backend the app reads) — must be added.
- **No Helm chart** under `frontends/iris/k8s/`, **no `.dockerignore`**, **no FE image-publish path** (`just publish-image` is jib-only; the nginx image needs `docker build`/`push` after `just build-fe`).
- **olymp has no FE precedent** — iris-bff/golem/capabilities-mcp are all ClusterIP backends with no ingress. The FE is the **first externally-exposed** app: needs a **Gateway API HTTPRoute** (Gateway `eg`/ns `gateway`, host `iris.<...>.nip.io`) + a per-namespace **`ghcr-pull`** secret (private image). No DB / ESS (the FE is stateless).

## Group A — codeable (in kantheon; validated with `helm lint`/`helm template`, no cluster)

- [x] **A1 — nginx same-origin `/bff` proxy.** Rework `nginx.conf.template`: drop the four stale backend proxies; add one **SSE-tuned** `location /bff/ { proxy_pass http://${BFF_UPSTREAM_HOST}:${BFF_UPSTREAM_PORT}/; ... }` (buffering off, `proxy_read_timeout 3600s`, chunked, `X-Accel-Buffering no`, strip `/bff` via the trailing slash) + a cheap `location = /healthz { return 200; }` for k8s probes. Trim `nginx-entrypoint.sh`'s `VARS` to just the BFF-upstream vars (+ sane in-cluster defaults: `iris-bff` / `7410`).
- [x] **A2 — runtime env + `.dockerignore`.** Add `VITE_BFF_BASE_URL` to `generate-env.sh` (default `/bff`); add `.dockerignore` (`node_modules`, `.git`, `tests`, `*.md`, keep `dist`). Confirm `index.html`/`config` already consume it (Stage 2.2).
- [x] **A3 — Helm chart `frontends/iris/k8s/`** (mirror `agents/iris-bff/k8s`, FE-shaped). `Chart.yaml` (`name: iris`), `values.yaml` (image `iris`, service/containerPort 7012, `imagePullSecrets`, a `config:` block → ConfigMap of the `VITE_*` runtime vars incl. `VITE_BFF_BASE_URL=/bff` + `bffUpstream.{host,port}`, an `httpRoute` toggle with `gateway`/`hostname`, probes on `/healthz`), `templates/{_helpers.tpl,deployment.yaml (envFrom the ConfigMap),service.yaml,configmap.yaml,httproute.yaml}`.
- [x] **A4 — image-publish + just recipes.** A `just publish-fe-image iris <tag>` recipe: `just build-fe iris` → `docker build` → tag/push `ghcr.io/boraperusic/iris:<tag>` (multi-arch note: bp-dsk is amd64, build `--platform linux/amd64`). Optional `deploy-fe` local recipe. Wire an image-build step (or document the manual push) — CI `frontend-iris` stays lint/test/build only for now.
- [x] **A5 — chart validation + docs.** `helm lint frontends/iris/k8s` + `helm template` render green (HTTPRoute + ConfigMap + Deployment/Service). Short `frontends/iris/k8s/README.md` (deploy notes, same-origin `/bff`, the olymp app dir). Update this task list's Group B with the exact olymp files.

## Group B — MOVED to the Testing arc (Stage 3.3 — Iris deploy + session-smoke)

> **Relocated 2026-06-23 (Bora).** The cluster-driven live deploy + smoke (image push, olymp app sync, ghcr-pull, Keycloak `iris` client, ArgoCD sync, live session-smoke, tags) is **no longer an Iris-arc task** — it lives in the **Testing arc** as **Stage 3.3**: [`../testing/tasks-p3-s3.3-iris-deploy-smoke.md`](../testing/tasks-p3-s3.3-iris-deploy-smoke.md) (plan: [`../testing/plan.md`](../testing/plan.md) §Phase 3). The Iris arc owns the **deploy assets** (Group A); the Testing arc owns the **live verification + the Phase-2-closing tags**.
>
> The olymp manifests are **prepared** (uncommitted in `~/Dev/collite-gh/olymp`): `clusters/bp-dsk/apps/iris/{config.json,values.yaml}` + the `iris` ns on the `clusterexternalsecret-ghcr-pull` selector. Realm `kantheon`. They feed Testing Stage 3.3 T2/T3.

## DONE (Iris-arc scope)
**Group A DONE** — `helm lint`/`template` green; FE image recipe in place; chart + olymp manifests ready. **Iris Phase 2 closes when Testing Stage 3.3 lands the live smoke + tags** (`iris/v0.1.0`, `iris-bff/v0.2.0`) — **crosses M3** (Iris usable). Live turn-leg smoke deferred to a `/v2` golem behind the BFF (Golem arc).

## Out of scope (→ later / void)
- agents-fe side-by-side + "mark agents-fe frozen" (plan tasks 3–4) — **void** (no ai-platform).
- iris-bff external exposure — stays ClusterIP; the FE reaches it same-origin via the nginx `/bff` proxy.
- Live deploy + smoke + tags — **Testing arc Stage 3.3** (no longer this arc).
- Live turn validation — needs a `/v2` golem behind the BFF (Golem arc).
