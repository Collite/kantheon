# Stage 3.3 — Instance provisioning + K8s deploy

> **Phase 3, Stage 3.3.**
>
> **Reads with.** [`tasks-p3-overview.md`](./tasks-p3-overview.md), [`plan.md`](./plan.md) §"Stage 3.3", [`../../../architecture/hebe/contracts.md`](../../../architecture/hebe/contracts.md) §4.4 (provisioning runbook) + §4 (schema-per-instance), [`../../../architecture/hebe/architecture.md`](../../../architecture/hebe/architecture.md) §9 (build/test/deploy flow), [`../../../../EXAMPLES.md`](../../../../EXAMPLES.md) §10 (K8s Kustomize base + local overlay).

## Goal

A runnable Hebe **pod per instance** on local K3s: a provisioning script (schema + role + Flyway + Secret skeleton), a Jib image for `cli-app` (server mode entrypoint, shadowJar path retained for local), Kustomize `base` + `overlays/local` parameterised by instance id, health/readiness probes, and `just` recipes. DONE = a documented instance bring-up reproducible from clean K3s.

## Pre-flight

- [ ] **Stage 3.2 DONE** — PG memory + workspace + receipts impls exist.
- [ ] **Branch**: `feat/hebe-p3-s3.3-instance-deploy`.
- [ ] Local K3s up; Kantheon PG reachable in-cluster; `deployment/local` Keycloak realm available.

## Tasks

- [ ] **T1 — Provisioning runbook script (contracts §4.4).**

  Author `agents/hebe/deploy/provision.sh` (or a `just` recipe + script) implementing the §4.4 runbook for a given `<id>`:

  1. `CREATE SCHEMA hebe_<id>;` + a dedicated role with usage limited to that schema (**no UPDATE/DELETE on `receipts`** — the append-only grant from Stage 3.2; **no cross-schema access**).
  2. `flyway -schemas=hebe_<id> migrate` (the shared `db/migration-pg/` set).
  3. Create the K8s Secret `hebe-<id>` skeleton: PG creds, Keycloak client creds + bound user, llm-gateway key, Telegram bot token, **receipts signing key**.
  4. (T3 applies the Kustomize overlay.)
  5. (T-S3.4 registers the manifest.)

  Acceptance: running the script against the local PG creates `hebe_<id>` with migrations applied and the role correctly grant-limited (verify `receipts` has no UPDATE/DELETE for the app role).

- [ ] **T2 — Jib image for `cli-app` (server mode entrypoint).**

  Add Jib to `:agents:hebe:modules:cli-app` (kantheon CI auto-detects Jib modules, architecture §4.2/§9). Entrypoint = `hebe run` in **server mode** (web console + channels + scheduler loop, no TTY). The shadowJar path for the local binary is **retained** (both artifacts build). `imagePullPolicy: Never` is set in the local overlay (T3).

  Acceptance: `just deploy hebe` (or `just build hebe` + Jib) produces an image loadable into K3s; shadowJar still builds.

- [ ] **T3 — Kustomize `k8s/{base,overlays/local}` parameterised by instance id.**

  Create `agents/hebe/k8s/base/` (Deployment, Service, ConfigMap referencing the instance config) + `overlays/local/` (`imagePullPolicy: Never`, local PG/Keycloak/gateway URLs), following EXAMPLES.md §10. Parameterise by `<id>` (the Deployment/Service/Secret names + `instance_id` config + `hebe_<id>` schema). One pod per instance.

  Acceptance: `kustomize build agents/hebe/k8s/overlays/local` renders valid manifests for a given `<id>`.

- [ ] **T4 — Probes: `/healthz` + `/ready`.**

  Implement `/healthz` (liveness) and `/ready` (readiness) on the server-mode HTTP surface. **Ready = config resolved + PG reachable + migrations at head + channels up** (architecture §2.2 doctor parity). Wire the probes into the Deployment. Unit-test the readiness gate logic (each precondition toggles readiness).

  Acceptance: readiness returns 503 until all preconditions hold, then 200; probes wired in the Deployment.

- [ ] **T5 — `just` recipes: deploy + provision.**

  Add `just deploy hebe` (Jib → K3s apply the overlay) and `just hebe-provision <id>` (run T1's script) to the root `justfile`, mirroring the kantheon `deploy-kt` recipe shape.

  Acceptance: both recipes resolve and run against local K3s.

- [ ] **T6 — Deploy smoke on K3s (deployment confirmation, not a CI gate).**

  Provision `dev`, deploy, then: converse via the web console, confirm a routine fires, and `--verify` the receipts chain. Per planning-conventions §4 this is a **smoke/demo** of the K3s bring-up capability, **not** an automated e2e test gate — the automated in-cluster round-trip is the integration suite. Capture the steps as the reproducible bring-up runbook in `agents/hebe/docs/`.

  Acceptance: the bring-up is reproducible from clean K3s and documented. PR `[hebe-p3-s3.3] instance provisioning + k8s deploy`.

## DONE — Stage 3.3

- [ ] All six tasks checked.
- [ ] Provisioning script creates a grant-limited schema-per-instance with migrations applied.
- [ ] Jib image (server mode) + retained shadowJar; Kustomize base + local overlay parameterised by `<id>`.
- [ ] `/healthz` + `/ready` (ready = config + PG + migrations + channels) wired.
- [ ] `just deploy hebe` + `just hebe-provision <id>` work.
- [ ] Documented instance bring-up reproducible from clean K3s.
- [ ] PR merged.

## Library / pattern references

- **contracts.md §4.4** — the provisioning runbook (the authority for T1).
- **EXAMPLES.md §10** — K8s Kustomize base + local overlay (`imagePullPolicy: Never`).
- **architecture.md §9** — build/test/deploy flow; Jib auto-detection; shadowJar + Jib coexist.

## Out of scope for Stage 3.3

- capabilities-mcp registration (Stage 3.4 — runbook step 5).
- Automated in-cluster e2e (integration suite).
- The iris-bff constellation client (Phase 4).
