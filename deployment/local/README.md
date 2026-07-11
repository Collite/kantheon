# deployment/local — owned local infra + one-command fork bring-up

> **Read spine extracted to `tatrman-server` (2026-07, SV-P0/P1).** The forked
> query path this doc brings up — Prometheus/Kadmos/Echo/Ariadne/Proteus/Kyklop/
> Argos/Theseus + the MSSQL/Polars/Postgres workers, and the technical-wave
> `whois`/`health`/`backstage` — **no longer live in kantheon**; they moved to the
> [`tatrman-server`](https://github.com/Collite/tatrman-server) repo under functional
> names (Ariadne→Veles, Theseus→ttr-query, …, whois→ttr-identity) and deploy from
> there. The `deploy-fork` / technical-wave sections below are retained as the
> **fork-era bring-up record**; from kantheon, `local-infra-up` now stands up only the
> shared infra + the surviving services/agents (Charon, Metis, Kallimachos, Pinakes,
> report-renderer + the agent constellation).

The single entry point for standing the forked kantheon constellation up on a
local K3s cluster (kantheon-architecture §7.1). Two commands.

## 1. `just local-infra-up` — the owned infrastructure

Creates the `kantheon` namespace and applies everything under `deployment/local`:

- **MSSQL** (`deployment/local/mssql/`) — Brontes's backing store: Deployment +
  Service + an init-SQL ConfigMap + an init Job, with a local-only SA secret.
- **Postgres** (`deployment/local/postgres/`) — the **shared Kantheon PG**
  (kantheon-architecture §7.1): Deployment + Service + a 10 GiB PVC + an init Job
  that provisions the `midas` database + `midas_app` role (Midas Stage 1.1).
  Local-only superuser + `midas_app` secrets (`postgres-superuser-secret`,
  `midas-db-secret`). Later PG-backed agents add their own databases here.
- **seeds** — any future seed Jobs land here.

WireMock-LLM upstreams for **Prometheus** are an **integration-track** concern
(Stage 2.5 T4): the local Prometheus overlay boots on placeholder API keys with
the in-memory `test` profile, so no LLM stub is required for local dev. When the
integration track lands the WireMock fixtures they fold in here, behind this same
command.

Idempotent — safe to re-run. Waits for MSSQL + Postgres to roll out and for the
`postgres-init` job (midas database + role) to complete.

## 2. `just deploy-fork` — the constellation

Builds images (Jib for Kotlin, Docker for the two Python modules) and applies
each module's `k8s/overlays/local`, in **dependency order**:

| Order | Module(s) | Why here |
|---|---|---|
| 1 | `capabilities-mcp` | registry up first, so MCP wrappers can heartbeat on boot |
| 2 | `prometheus`, `kadmos` (py), `echo`, `ariadne` | leaf services — no intra-fork runtime deps |
| 3 | `proteus` | translator (used by Theseus + Brontes) |
| 4 | `brontes`, `steropes` (py) | workers — Brontes needs MSSQL + Proteus |
| 5 | `argos` | validation (reads Ariadne metadata) |
| 6 | `kyklop` | dispatch (routes to the workers) |
| 7 | `theseus` | orchestrator (Proteus → Argos → Kyklop) |
| 8 | `ariadne-mcp`, `echo-mcp`, `kadmos-mcp`, `theseus-mcp` | agent-facing MCP edges |

k8s reconciles regardless of order; the ordering minimises boot-time churn and
makes the dependency graph legible. Heavy — it builds ~15 images.

```bash
# From a clean K3s context:
just local-infra-up
just deploy-fork
kubectl -n kantheon get pods -w        # watch them go Ready
```

Per-module redeploy during iteration: `just deploy-kt theseus` /
`just deploy-py workers/steropes` (both resolve services/ and workers/).

## Acceptance

- **Unit/component gate (in-repo, runs in CI):** the registry-completeness test
  (`ForkedToolRegistrySpec`, Stage 4.1 T1) and the Stage 3.6 acceptance specs
  (`RlsAcceptanceMatrixSpec`, `OboDisciplineComponentSpec`, `TokenExpiryComponentSpec`,
  `RunQueryFullChainComponentSpec`) — all green under `just test-all`.
- **On-cluster bring-up (manual / integration-track):** after the two commands
  above on a fresh K3s, every pod reaches Ready and `run_query` flows end-to-end.
  Per the testing policy (planning-conventions §4) the live cluster e2e is the
  separate integration-test suite's job, not the unit gate's.

## The technical wave (fork Phase 5 — whois / health / landing / backstage)

The four technical services forked in Phase 5 (2026-06-24) are **Helm-chart**
deployables (like Argos/Ariadne), so they bring up via the olymp/ArgoCD D3'
multi-source GitOps path — **not** `deploy-fork` (which is Kustomize, predating the
Helm convention). Their in-repo deploy artifacts:

| Service | Chart / image | Port | Notes |
|---|---|---|---|
| `infra/whois` | `infra/whois/k8s` (Helm) → `whois:dev` | 7110 | own Postgres (a DB in the one Kantheon PG); Flyway V1–V5; `json` mode boots without a DB |
| `infra/health` | `infra/health/k8s` (Helm) → `health:dev` | 7000 | stateless; checks the kantheon estate + fabric-infra; probe `/health/all?threshold=0` |
| `frontends/landing` | `frontends/landing/Dockerfile` → `kantheon/landing` (Nginx) | 80 | `generate-env.sh` writes `env.js` at start; reads the `infra/health` roll-up |
| `infra/backstage` | `infra/backstage` (Node/yarn) → `kantheon/backstage` | 7007 | own Postgres; catalog = the pantheon (`examples/kantheon-catalog.yaml`) |

`helm template infra/whois/k8s` and `infra/health/k8s` render clean; the landing
`vite build` and backstage `yarn build` are CI-gated Node-toolchain steps. On a live
cluster the technical wave is what makes ai-platform's `whois`/`health`/`landing`/
`backstage` instances unnecessary — the Phase-5 independence assertion (no kantheon
pod egresses to an ai-platform-hosted instance of these) is the integration suite's
to confirm on `bp-dsk`.
