# Stage 3.1 — D3′ Helm charts for the context-blocking services

> **Prerequisite for Stage 3.1 contexts** (see [`tasks-p3-s3.1-contexts.md`](./tasks-p3-s3.1-contexts.md) Status). The `golem-erp` and `themis-routing` integration contexts cannot stand up live because five services have only Kustomize (`k8s/base` + `overlays`) and **no D3′ Helm chart**. This list Helm-ifies them, mirroring the converted `services/theseus/k8s` chart (the convention: env-agnostic chart, no cluster knowledge; per-env values live in the deploying repo / olymp test-context values).
>
> **Created 2026-06-24 (Bora).** Branch: `feat/p3-s3.1-contexts` (same as the spec landing).

## Convention (mirror `services/theseus/k8s`)

`Chart.yaml` (v2, `version` = packaging, `appVersion` = default image tag) + `values.yaml` (env-agnostic) + `templates/{_helpers.tpl,deployment.yaml,service.yaml}`. Extensions over the theseus template, shared by these charts:
- **Optional `ports.grpc`** — rendered only when set (Kadmos + Themis are http-only).
- **Optional `secretEnv`** list (`{name, secretName, secretKey}`) → `env[].valueFrom.secretKeyRef` (Ariadne PAT, Prometheus LLM keys, Themis HMAC).
- Probe paths are values (`/ready`+`/health` default; Kadmos `/readyz`+`/healthz`; Prometheus `/actuator/health/{readiness,liveness}`).
- **No hardcoded `namespace`** (Helm installs into the release namespace) — unlike the Kustomize bases.

## Tasks

> **All charts landed + verified 2026-06-24** on `feat/p3-s3.1-contexts`. `helm lint` + `helm template` green for all five; no namespace leakage; conditional grpc/secretEnv/telemetry render correctly. Olymp `golem-erp` context updated (ariadne + prometheus wired in) and `themis-routing` context authored; `ContextNameRegistrySpec` green. **Kept** the Kustomize `base/overlays` per service (local-dev `kubectl apply` path) alongside the new Helm chart, mirroring how the theseus conversion left them.
>
> **Per-service olymp values files drafted 2026-06-24** (olymp `test-contexts/{golem-erp,themis-routing}/*.values.yaml`): every `services[]` entry now has a `:testing`-tagged, telemetry-off, fixture/in-memory-shaped values file, validated with `helm template -f` against its chart. golem-erp reuses the theseus-chain values (copied from theseus-runquery) + new golem/ariadne/prometheus; golem runs in-memory (`db.enabled:false`) and points at Ariadne + Prometheus; Prometheus retargets its LLM upstream at `wiremock:8080`; Ariadne serves its fallback model (blank remote, no PAT). **Remaining for live bring-up (NOT chart/values work), flagged as TODO in the values files:** a `golem-erp` Shem manifest + a seed-aligned Ariadne model (with `dbo.sample_orders`), capabilities-mcp routable-manifest seeding (themis-routing), a Prometheus datastore decision (PG member or no-db profile), the themis-routing T2 spec, and a running cluster.

- [x] **C1 — Ariadne chart** (`services/ariadne/k8s`). http 7260 / grpc 7261; env `ARIADNE_HTTP_PORT`/`ARIADNE_GRPC_PORT`, `METADATA_GIT_REMOTE_URI` + `METADATA_GIT_TOKEN`/`PROMPTS_GIT_TOKEN` (secret, via `secretEnv`); probes `/ready`+`/health` on http. Model source (git remote vs fixture) is a values concern for the deploying env. **Unblocks golem-erp.**
- [x] **C2 — Prometheus chart** (`services/prometheus/k8s`). Spring Boot: http 7280 / grpc 9090; probes `/actuator/health/readiness`+`/actuator/health/liveness`; env `PROMETHEUS_SERVER_PORT`, `POSTGRESQL_HOST`/`PORT` (values), LLM-provider keys via `secretEnv` (default `prometheus-secrets`); the LLM **upstream base URL** is an `extraEnv` override so a test context can retarget it at WireMock. **Unblocks golem-erp + themis-routing (shared LLM gateway).**
- [x] **C3 — Echo chart** (`services/echo/k8s`). http 7265 / grpc 7266; env `ECHO_HTTP_PORT`/`ECHO_GRPC_PORT`; probes `/ready`+`/health`. **Unblocks themis-routing.**
- [x] **C4 — Kadmos chart** (`services/kadmos/k8s`, Python). http 7270 only (no grpc); env `UVICORN_PORT`/`KADMOS_SERVICE_PORT` + OTEL host/port/protocol; probes `/readyz`+`/healthz`. **Unblocks themis-routing.**
- [x] **C5 — Themis chart** (`agents/themis/k8s`, workload `themis-mcp`). http 7901 only; downstream wiring env (capabilities-mcp / kadmos / echo / prometheus) as `extraEnv` (env-agnostic service names) + `HMAC_SECRET_KEY` via `secretEnv`; probe `/ready` on http. **Unblocks themis-routing.**
- [x] **C6 — Verify.** `helm lint` + `helm template` render green for all five (default values + a representative override). No `namespace:` leakage; selector labels stable.
- [x] **C7 — Wire the olymp contexts.** Uncomment `ariadne` + add `prometheus` to `test-contexts/golem-erp/context.yaml` (services + readiness); author `test-contexts/themis-routing/context.yaml` (registers the T2 name). **Live bring-up + flipping `liveContext`/seed-aligned Ariadne model is a follow-on** (needs a running cluster + a model with `dbo.sample_orders`) — out of scope here.

## DONE

- [x] All five services carry a `k8s/Chart.yaml` Helm chart mirroring theseus; `helm lint`/`template` green.
- [x] The golem-erp olymp context references the ariadne + prometheus charts; themis-routing context name is registered (drift guard green).
- [x] Recorded: the Kustomize `base/overlays` are kept (local-dev) or superseded — note the decision per service.
