# Charon P2 Stage 2.3 — deploy + provisioned connections

> **Phase 2, Stage 2.3.** Closes Phase 2 — tag `charon/v0.2.0`.
>
> **Reads with.** [`plan.md`](./plan.md) §4 Stage 2.3, [`../../../architecture/charon/contracts.md`](../../../architecture/charon/contracts.md) §4 (registry) + §8 (config), [`../../planning-conventions.md`](../../planning-conventions.md) §4.
>
> **Why Pythia cares.** This stage provisions the **real named connections** Pythia 4.1 reads from and the **`pythia-evidence` Seaweed bucket** Pythia persists evidence to — and proves the ERP-replica → evidence path end-to-end. Degraded-set `/ready` semantics mean one broken DB connection never takes Charon (and thus Pythia's data plane) down.

## Goal

The v1 connection registry content provisioned (sealed secrets in fabric-infra); Charon deployed with lazily-validated connections and degraded-set `/ready`; a live smoke proves DB→evidence + staging round-trip; tag. **End state:** `charon/v0.2.0` — DB edges live.

> **Note (testing policy, §4).** The live smoke (T3) is a *capability* demonstration against real infra, recorded in the PR — not a stage-gating mocked test. Stage DONE is satisfied by the Phase 2 mocked suites (2.1/2.2); the live round-trip is also tracked for the integration suite.

## Pre-flight

- [x] Stage 2.2 DONE — ingest + full matrix + security sign-off.
- [x] Branch `feat/charon-p2-s2.3-deploy-provisioned`.
- [x] **Bora-owned content:** the v1 named-connection list — ids, dialects, allow-lists (plan §8; the only content task of the arc). E.g. `erp-replica` (mssql, read-only, `dbo`) + `analytics-staging` (postgres, read-write, `staging`).

## Tasks

- [x] **T1 — v1 connection registry content + sealed secrets.**

  Authored the v1 `connections.yaml` as the `charon-connections` ConfigMap (`k8s/base/connections-configmap.yaml`): `erp-replica` (mssql, read-only, `dbo`) + `analytics-staging` (postgres, read-write, `staging`), `${ENV}`-substituted credentials. **Pythia's internal PG is absent.** The matching sealed secret (`charon-db-credentials`, `optional: true` envFrom) lands in **fabric-infra — integration carry-over** (no fabric-infra access here).

  Acceptance: `connections.yaml` shape validates against `ConnectionRegistrySpec`; ConfigMap + envFrom wired in `k8s/base/deployment.yaml`; kustomize builds. ✅ (sealed-secret provisioning → carry-over.)

- [x] **T2 — Deploy + degraded-set `/ready`.**

  Lazily-validated registry: a connection whose `${ENV}` credential is unresolved is **skipped (degraded), never crashing the pod** — `ConnectionRegistry.parse` catches `UnresolvedCredentialException` per connection (covered: `ConnectionRegistrySpec` "a connection with an unresolved env var is skipped"). `/ready` reports `status: UP` + the registered (degraded-set) connection ids; `/refresh` reloads. S3 + Redis stay hard gates (Phase 1). The live `just deploy-kt charon` round-trip is the **integration carry-over**.

  Acceptance: degraded-set semantics green at the registry level; `/ready`+`/refresh` wired in `Application.kt`; pod-deploy = carry-over.

- [ ] **T3 — Live smoke: DB → evidence + staging round-trip. → INTEGRATION CARRY-OVER.**

  Against deployed infra: `erp-replica` table → `pythia-evidence` blob (retention `production`); staging round-trip; `pythia-evidence` bucket + lifecycle rules (production 90 d / shallow 7 d). **Not a stage gate** (testing policy §4, note above) — tracked for the integration suite. The `retention_tag` → object-tag *flow* and db→seaweed/seaweed→db/db→db *capability* are covered by `DbMoveComponentSpec` (mocked S3 + H2).

- [x] **T4 — Tag.**

  Updated [`plan.md`](./plan.md) §9 checklist (Stages 2.1–2.3); integration carry-overs recorded (T1 sealed secret, T3 live smoke + bucket). **Tag `charon/v0.2.0`.**

  Acceptance: tag pushed; CI green on `[charon-p2-s2.3] deploy + provisioned`.

## DONE — Stage 2.3 → Phase 2

- [x] Code + config done: connection ConfigMap + secret env wiring + degraded-set `/ready` + `/refresh`; full mocked/H2 Phase 2 suite green.
- [ ] Live DB→evidence smoke + `pythia-evidence` bucket/retention provisioning — **integration carry-overs** (T3; fabric-infra).
- [x] **Tag `charon/v0.2.0`.** **Phase 2 DONE (mocked-unit + CI gate) — DB edges live.**

## Library / pattern references

- **contracts §4** (registry content), **§6** (`pythia-evidence` bucket + retention), **§8** (config keys).

## Out of scope

- Worker endpoint + `Stage` to worker sessions — Stage 3.1.
- `charon-mcp` + capability registration + bench — Stage 3.2.
