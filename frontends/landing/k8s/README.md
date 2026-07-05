# landing — Helm chart (the landing page / service dispatcher, nginx)

Env-agnostic chart for the Kantheon landing FE. Mirrors `frontends/iris/k8s` (the canonical
FE-nginx thin chart); per-environment values live in Olymp. Authored in deploy-test WS-D
Stage 2 (no pre-existing kustomize base).

## Deploy descriptor (contracts §2.5)

```
module: landing
image: ghcr.io/boraperusic/landing     # fe-nginx
ports: { http: 80 }
needs:
  keycloak: { client: landing }        # SPA login; client-id key VITE_KEYCLOAK_LANDING_CLIENT_ID
  downstream: []                        # no BFF — serves static + dispatches by deep-link/hostname
wave: 6                                 # infra
externally-exposed: { hostname: <landing.…> }   # Olymp sets httpRoute.hostname (default off)
```

## Shape & notes

- **nginx static server on container port 80** — the module's `nginx.conf` (`listen 80;`) and
  `Dockerfile` (`EXPOSE 80`) do NOT follow the FE-family high ports (iris 7012); the chart uses
  80 to match the actual listen port.
- **No BFF.** Landing dispatches to the estate by deep-links (`VITE_LINK_*`, `VITE_HEALTH_URL`,
  `VITE_GRAFANA_DASHBOARD_URL`) and hostname; it has no `/bff` proxy. The library `fe-configmap`
  always emits `VITE_BFF_BASE_URL` / `BFF_UPSTREAM_*`, but landing's entrypoint never reads them
  (`config.bffBaseUrl`/`config.bffUpstream` are inert here).
- **Runtime config** (`config.extra` → ConfigMap → `envFrom`) is consumed by
  `scripts/generate-env.sh`, which writes `window.APP_CONFIG` into `/env.js`. Landing's
  client-id key is `VITE_KEYCLOAK_LANDING_CLIENT_ID` (not `VITE_KEYCLOAK_CLIENT_ID`), supplied
  via `config.extra`.
- **Probes** hit `/healthz`. Landing's nginx has no explicit `/healthz` location, but the SPA
  `try_files` catch-all returns `index.html` (200) for any path, so the probe passes.
- **Exposure.** `httpRoute.enabled` (default false — Olymp sets it) attaches a Gateway API
  HTTPRoute (`eg`/ns `gateway`) routing `httpRoute.hostname` → the FE Service.
- **strategy** (Recreate/Rolling) intentionally omitted (D2 convention) — the library default applies.

## Image

fe-nginx (not Jib). `just build-fe landing` then `just publish-fe-image landing v0.1.0`.
