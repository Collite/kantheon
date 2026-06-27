# Landing Page

> **forked-from:** `ai-platform@2575b923dca521fea0e3156257e4b779f02a6ed4` (`frontends/landing/`), tag `kantheon-fork-point`, forked 2026-06-24 (fork Phase 5 Stage 5.4).
> Maintained independently since the fork; do not assume parity with the ai-platform original. The fork is a **rebrand** — wire/build shape unchanged; strings, the service catalog, and link/realm defaults move to Kantheon.

A multilingual landing page for the **Kantheon** cluster dashboard and service dispatcher — a
technical-wave (no-persona) frontend in `frontends/`.

## Architecture

- **Framework**: Vue 3 + TypeScript
- **Build Tool**: Vite
- **Styling**: TailwindCSS
- **Internationalization**: vue-i18n (`en/de/cs/sk/hu`)
- **Containerization**: Nginx (Alpine)

## What it does

- **Cluster Dashboard** — reads the forked `infra/health` roll-up (`GET /health/all/detailed`,
  base URL from `HEALTH_URL`) and renders a status tile per service. The tile-to-service map lives
  in `public/services.json`; its `tech` keys MUST match the health service's `technologies.*`
  config keys (Stage 5.2 re-point — kantheon constellation + fabric-infra, no legacy ai-platform).
- **Service dispatcher** — links to the dev portal (forked `infra/backstage`), Grafana, ArgoCD,
  Traefik, Keycloak. Hosts come from `VITE_LINK_*` / the runtime `env.js` (`generate-env.sh`).

## Rebrand surface (Stage 5.4)

- `src/i18n/locales/*.json` `header.title` → **Kantheon** (all five locales).
- `public/services.json` → the kantheon estate (agents, platform services, workers, MCP tools,
  technical wave) + retained fabric-infra; legacy ai-platform / erp-sql entries dropped.
- `.env` / `.env.example` Keycloak realm → kantheon; link vars point at kantheon ingress hosts.

## Build / run

```
just build-fe landing          # npm ci + vite build (type-check + bundle)
cd frontends/landing && npm run test:unit   # vitest rebrand + dispatcher specs
```

The Nginx image is built from the `Dockerfile` and tagged `kantheon/landing` at deploy time;
`scripts/generate-env.sh` writes `env.js` from the `VITE_*` env at container start.
