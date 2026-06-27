# iris — Helm chart (the Vue SPA, nginx)

Env-agnostic chart for the Iris frontend. Mirrors `agents/iris-bff/k8s`; per-environment
values live in Olymp (`clusters/<cluster>/apps/iris/values.yaml`), wired via ArgoCD
multi-source. Stage 2.4.

## Shape

- **nginx static server** on container port **7012** (`Dockerfile` = `nginx:alpine` + `COPY dist`).
- **Runtime config** (`config.*` → a ConfigMap, `envFrom`'d into the pod). At container start
  `scripts/generate-env.sh` writes the `VITE_*` values into `/env.js` (`window.APP_CONFIG`,
  read by `src/config/index.ts`) and `scripts/nginx-entrypoint.sh` substitutes the
  `BFF_UPSTREAM_*` vars into the `/bff` proxy.
- **Same-origin BFF.** `config.bffBaseUrl: /bff` → the app calls `/bff/v1/...`; nginx proxies
  `/bff/` → `config.bffUpstream` (in-cluster `iris-bff:7410`). So **iris-bff stays ClusterIP**
  (no external exposure, no CORS) — only the FE is routed in. The proxy is SSE-tuned for
  `/v1/chat/stream`.
- **Exposure.** `httpRoute.enabled` attaches a Gateway API `HTTPRoute` to the shared Gateway
  (`eg`/ns `gateway`) routing `httpRoute.hostname` → the FE Service. The FE is the first
  externally-routed Iris app.
- **Probes** hit `/healthz` (a cheap nginx `return 200`).

## Image

Not Jib (it's an nginx image). Build + push with:

```
GHCR_USER=BoraPerusic GHCR_TOKEN=ghp_… just publish-fe-image iris v0.1.0
```

(`just build-fe iris` runs first — `dist/` is gitignored, and the Dockerfile `COPY dist`.)
bp-dsk is amd64, so the recipe builds `--platform linux/amd64`.

## Deploy (bp-dsk, GitOps)

Olymp app dir `clusters/bp-dsk/apps/iris/` (`config.json` → `{chartPath: frontends/iris/k8s,
chartRevision}`, `values.yaml` → image repo/tag, `imagePullSecrets: [ghcr-pull]`,
`config.bffUpstream.host: iris-bff.iris-bff.svc.cluster.local`, `httpRoute.hostname`,
Keycloak vars). The ns `iris` needs a `ghcr-pull` secret (private image). See
`docs/implementation/v1/iris/tasks-p2-s2.4-deploy-cutover.md` Group B.
