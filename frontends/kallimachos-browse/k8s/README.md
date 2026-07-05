# kallimachos-browse — Helm chart (the wiki-browse SPA, nginx)

Env-agnostic chart for the Kallimachos wiki-browse FE. Mirrors `frontends/iris/k8s` (the
canonical FE-nginx thin chart); per-environment values live in Olymp. Authored in deploy-test
WS-D Stage 2 (no pre-existing kustomize base). **BEST-EFFORT** — see caveats below.

## Deploy descriptor (contracts §2.5)

```
module: kallimachos-browse
image: ghcr.io/boraperusic/kallimachos-browse   # fe-nginx
ports: { http: 8080 }                            # PLACEHOLDER — no Dockerfile/nginx.conf yet
needs:
  keycloak: { client: kallimachos-browse? }      # api.ts sends the user's OBO bearer to the MCP
  downstream: [ kallimachos-mcp ]                 # /library/mcp → kallimachos-mcp:7262 (library.* tools)
wave: 5                                           # librarian
externally-exposed: { hostname: <kallimachos.…> }   # Olymp sets httpRoute.hostname (default off)
```

## Shape & notes

- **fe-nginx thin chart** mirroring iris. Renders a Deployment/Service/ConfigMap + optional HTTPRoute.
- **Backend = kallimachos-mcp.** `src/api.ts` POSTs MCP `tools/call` to `/library/mcp` with the
  caller's OBO bearer; `vite.config.ts` proxies `/library` → `kallimachos-mcp` (dev default
  `:7262`). The chart points `config.bffUpstream` at `kallimachos-mcp:7262`.
- **Probes** hit `/healthz`.
- **Exposure.** `httpRoute.enabled` (default false — Olymp sets it) attaches a Gateway API HTTPRoute.
- **strategy** intentionally omitted (D2 convention).

## Uncertainty (best-effort)

- **No Dockerfile / nginx.conf in the module.** The container **port 8080** and the `/healthz`
  probe are nginx-family placeholders — pin them once the module gains a real build. The image
  build recipe (`just build-fe`/`publish-fe-image`) assumes a `dist` + nginx image like sibling FEs.
- **Proxy path mismatch.** The app calls `/library/**`, but the library `fe-configmap` models a
  single `/bff` upstream (`config.bffBaseUrl: /library`, `bffUpstream: kallimachos-mcp:7262`). A
  real deploy needs an nginx `location /library/ { proxy_pass … }` block, which does not exist
  in the module yet.

## Image

fe-nginx (not Jib) — recipe assumed; module build config is not yet present.
