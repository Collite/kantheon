# sysifos — Helm chart (the back-office workbench SPA, nginx)

Env-agnostic chart for the Sysifos FE. Mirrors `frontends/iris/k8s` (the canonical FE-nginx
thin chart); per-environment values live in Olymp. Authored in deploy-test WS-D Stage 2 (no
pre-existing kustomize base). Pairs with testing Stage 3.4 (deploy + workbench-smoke).

## Deploy descriptor (contracts §2.5)

```
module: sysifos
image: ghcr.io/boraperusic/sysifos     # fe-nginx
ports: { http: 7602 }
needs:
  keycloak: { client: sysifos }        # SPA login (OBO); X-Tenant-Id forwarded by the BFF
  downstream: [ sysifos-bff ]          # same-origin /bff proxy → sysifos-bff:7601 (stays ClusterIP)
wave: 4                                 # domain (Sysifos/Midas)
externally-exposed: { hostname: <sysifos.…> }   # Olymp sets httpRoute.hostname (default off)
```

## Shape & notes

- **nginx static server on container port 7602** (`nginx.conf.template` `listen 7602;`, `EXPOSE 7602`).
- **Same-origin BFF.** `config.bffUpstream` (`sysifos-bff:7601`) is substituted into the `/bff`
  proxy by `scripts/nginx-entrypoint.sh` — so **sysifos-bff stays ClusterIP** (no external
  exposure, no CORS). The proxy is SSE-tuned for the draft/stream path.
- **BFF-base key variance.** The SPA reads `window.APP_CONFIG.VITE_BFF_BASE`, but the library
  `fe-configmap` emits `VITE_BFF_BASE_URL`. The chart supplies the app's real key
  `VITE_BFF_BASE: /bff` via `config.extra` (and `VITE_TENANT_CLAIM`). `config.bffBaseUrl` feeds
  the library's `VITE_BFF_BASE_URL` (harmless).
- **Probes** hit `/healthz` (a cheap nginx `return 200` in `nginx.conf.template`).
- **Exposure.** `httpRoute.enabled` (default false — Olymp sets it) attaches a Gateway API
  HTTPRoute (`eg`/ns `gateway`) routing `httpRoute.hostname` → the FE Service.
- **strategy** intentionally omitted (D2 convention).

## Image

fe-nginx (not Jib). `just build-fe sysifos` then `just publish-fe-image sysifos v0.1.0`.
