# Deploy-descriptor index (feeds WS-D3 bp-dsk app wiring)

> **What this is.** The consolidated per-module deploy descriptor (contracts §2.5) for
> every module that got a Helm chart in **WS-D2** (the 22 newly-charted deployables),
> compiled from each module's `<module>/k8s/README.md`. D3 reads this to author the
> olymp `clusters/bp-dsk/apps/<name>/{config.json,values.yaml}` + platform deps.
>
> **Chart contract.** Every row is a thin chart on `shared/charts/kantheon-service`
> (`chartPath: <module>/k8s`), image `ghcr.io/boraperusic/<name>`, rendered green by
> `just validate-charts`. Image types: **jib** (Kotlin, `just publish-image`),
> **build-py** (Python, `just build-py` + push), **fe-nginx** (`just publish-fe-image`),
> **node** (backstage — custom build).
>
> *Created WS-D2, 2026-07-05. Owner: Bora.*

## The 22 new charts

| Chart | Path | Image type | Ports | pg-database | Wave | Externally-exposed |
|---|---|---|---|---|---|---|
| `charon` | services/charon | jib | http 7250 / grpc 7251 | (named conns via `charon-db-credentials`) | 1 | — |
| `ariadne-mcp` | tools/ariadne-mcp | jib | http 7262 | — | 1 | — |
| `charon-mcp` | tools/charon-mcp | jib | http 7252 | — | 2 | — |
| `echo-mcp` | tools/echo-mcp | jib | http 7267 | — | 2 | — |
| `kadmos-mcp` | tools/kadmos-mcp | jib | http 7272 | — | 2 | — |
| `kallimachos-mcp` | tools/kallimachos-mcp | jib | http 7262 | — | 5 | — |
| `metis-mcp` | tools/metis-mcp | jib | http 7262 | — | 2 | — |
| `metis` | services/metis | build-py | http 7260 / grpc 7261 | — | 2 | — |
| `steropes` | workers/steropes | build-py | http 7300 / grpc 7301 | — | 2 | — |
| `pythia` | agents/pythia | jib | http 7090 | pythia (`envFrom pythia-db-credentials`) | 3 | — |
| `midas-core` | agents/midas/core | jib | http 7310 / grpc 7311 (mcp) | midas (`midas-db-secret`) | 4 | — |
| `midas-excel-loader` | agents/midas/loaders/excel | jib | http 7315 | — | 4 | — |
| `sysifos-bff` | agents/sysifos-bff | jib | http 7601 | — | 4 | — |
| `kallimachos` | services/kallimachos | jib | http 7260¹ / grpc 7261¹ | kallimachos | 5 | — |
| `pinakes` | services/pinakes | jib | http 7280 / grpc 7281 | — | 5 | — |
| `report-renderer` | services/report-renderer | jib | http 7320 | — | 5 | — |
| `hebe` | agents/hebe | jib | http 8765 | hebe | 5 | — |
| `kleio` | agents/kleio | jib | http 7270 | kleio | 5 | — |
| `sysifos` | frontends/sysifos | fe-nginx | http 7602 | — | 4 | `sysifos.<cluster>` |
| `landing` | frontends/landing | fe-nginx | http 80 | — | 6 | `landing.<cluster>` |
| `kallimachos-browse` | frontends/kallimachos-browse | fe-nginx | http 8080² | — | 5 | `kallimachos.<cluster>` |
| `backstage` | infra/backstage | node | http 7007 | backstage | 6 | `backstage.<cluster>` |

¹ **kallimachos port names:** the base names the ports `probe`(7260)/`http`(7261) and targets probes at 7260. The library uses `http`/`grpc` names; mapped `ports.http=7260` (probe target) / `ports.grpc=7261` (the HTTP API). Numbers, probe targeting, and env var names (`KALLIMACHOS_PROBE_PORT`/`KALLIMACHOS_HTTP_PORT`) are exact; only the rendered port *names* differ cosmetically. Callers use 7261.
² **kallimachos-browse:** port 8080 + `/healthz` probe are **placeholders** — the module has no Dockerfile/nginx.conf yet, and it proxies `/library` (not `/bff`), so its FE config is an imperfect fit. **Best-effort** (§7-D3); revisit when the FE build lands.

## Cross-cutting deploy needs (for the olymp platform layer)

- **PG databases to add** (`platform/data/postgres/base/databases.yaml` + per-cluster `ExternalSecret`): `pythia`, `midas`, `hebe`, `kleio`, `backstage`. (`iris`/`golem`/`whois`/`keycloak`/`llm-gateway` already exist.) charon uses **named connections** (not a dedicated agent DB) via the `charon-db-credentials` secret.
- **Seaweed buckets:** charon (S3 gateway, no fixed bucket), pinakes (`docwh-stage`).
- **Keycloak clients:** SPA clients for `sysifos`, `landing`, `kallimachos-browse`; service/confidential client for `backstage`. (`iris` exists.)
- **Downstream wiring** (in each chart's `extraEnv` defaults — stable in-cluster names): the `*-mcp` wrappers → their backing service; `midas-core` → capabilities-mcp; `kleio` → kallimachos-mcp + prometheus; `sysifos` FE → `sysifos-bff:7601`; etc. See each README's `downstream:`.
- **Volumes (configMap/secret created by the deploying env, mounted by name):** charon `connections` (`connections.configMapName`, default `charon-connections`), hebe instance secrets, midas-excel-loader blob scratch (emptyDir).

## Normalization applied to every new chart (vs the kustomize base)

- **Env-agnostic OTel:** `telemetry.enabled=false` default; the base's hardcoded `OTEL_ENABLED_*=true` + OTLP endpoint become values (endpoint gated on `enabled && endpoint`). `OTEL_SERVICE_NAME` + `OTEL_ENABLED_<NAME>` added to charts whose base carried no OTel (charon-mcp, kallimachos-mcp, metis-mcp, hebe, kleio) for constellation parity — additive, off by default.
- **`resources` + a liveness probe** added where the base omitted them (the library always renders both) — MCP-wrapper defaults `256Mi/100m→512Mi/500m`; liveness on the base's readiness path where no `/health` existed.
- **`strategy: Recreate`** dropped where present (midas-core, sysifos-bff) → library default RollingUpdate.

## The existing 18 (D1-migrated) charts

Already thin charts on the library; **4 already run on bp-dsk** (`capabilities-mcp`, `golem`, `iris`, `iris-bff`). Their deploy needs are read directly from their `values.yaml` (ports/extraEnv) — a descriptor README exists for iris/golem/capabilities-mcp; the remaining backend services (theseus/proteus/argos/kyklop/ariadne/echo/kadmos/prometheus/arges/brontes/theseus-mcp/themis/health/whois) follow the query-path/registry waves (contracts §2.4) and need the platform deps already provisioned (per fork Phases 1–5). D3 wires all 40.
