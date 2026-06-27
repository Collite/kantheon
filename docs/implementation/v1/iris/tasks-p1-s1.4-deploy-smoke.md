# Iris Phase 1 Stage 1.4 — deploy + live smoke (+ review-deferred hardening)

> **Goal (plan §3 Stage 1.4).** `iris-bff` live in local K3s; agents-fe (in ai-platform, env-flipped) completes a real conversation through it. **DONE → tag `iris-bff/v0.1.0`; Phase 1 DONE.**
>
> **Companions.** [`plan.md`](./plan.md) Stage 1.4 · [`review-p1.md`](./review-p1.md) ("Deferred — Stage 1.4 / integration") · [`../../../architecture/iris/architecture.md`](../../../architecture/iris/architecture.md) §10 (observability) · [`../../../architecture/iris/contracts.md`](../../../architecture/iris/contracts.md) §2 (auth) / §3.1 (audit) / §6 (config).
>
> **Two groups.** **A — codeable hardening** (the review items deferred *to* 1.4; TDD, verifiable against the mocked gate, no cluster). **B — operational** (deploy + live smoke; needs the **olymp `bp-dsk` cluster**). Land A first so that when B deploys, the real audit writer + key loading + JWKS path are the ones exercised, not the dev fallbacks.
>
> **No ai-platform (Bora, 2026-06-21).** The original Stage 1.4 plan smoke-tested iris-bff via ai-platform's agents-fe (env-flipped). **That is void** — kantheon no longer integrates with ai-platform. Deploy + smoke now target the **olymp-defined `bp-dsk`** cluster (kube-context `dsk`; GitOps via ArgoCD; chart in kantheon, app/values in olymp). Phase-1 smoke is a **direct REST/SSE smoke** (no FE — the FE arrives in Iris Phase 2). See [`../../../../collite-gh/olymp/`](../../../) and the `no-ai-platform-olymp-clusters` memory.

## Group A — codeable hardening (review-deferred → 1.4)

### A1 — Exposed `iris_audit` writer
- [x] **T-A1.1 (test first).** `ExposedAuditStoreSpec` — append assigns monotone `seq`, chains `prev_hash`/`self_hash` (canonical payload), signs; `all()` returns rows ordered by `seq`; `verifyChain(store.all(), signer)` true; tampering one payload → false. Backed by the in-memory DB fake (real-PG fidelity → integration, per planning-conventions §4).
- [x] **T-A1.2.** Map `iris_audit` in `Tables.kt` (`IrisAudit`); `seq` is DB-`GENERATED ALWAYS AS IDENTITY` → read-after-insert, never written.
- [x] **T-A1.3.** `ExposedAuditStore : AuditStore` mirroring `ExposedSessionStore` — `append` takes a serialize lock (session-style `FOR UPDATE` on the chain tail / advisory lock) so `prev_hash` linkage is race-free under REPEATABLE READ; canonicalises payload via `canonicalizePayload`; signs via injected `Ed25519Signer`.
- [x] **T-A1.4.** Wire in `buildComponents`: DB path → `ExposedAuditStore`; in-memory path unchanged (`InMemoryAuditStore`).

### A2 — Secret-loaded Ed25519 signing key
- [x] **T-A2.1 (test first).** `Ed25519KeyLoaderSpec` — load a PKCS#8 private key (PEM / base64-DER) from a configured ref; derive the public key; round-trip sign→verify; absent/malformed ref → explicit error (no silent ephemeral key when a ref is set).
- [x] **T-A2.2.** `iris.audit.signing-key-ref` config block (file path or inline base64); `Ed25519Signer.fromKeyRef(...)`; ephemeral-with-warning retained only when no ref is configured (dev/local).
- [x] **T-A2.3.** Wire the loaded signer into `buildComponents`; document the K8s Secret + the verify runbook stub in contracts §3.1.

### A3 — JWKS signature verification
- [x] **T-A3.1 (test first).** `JwksVerifierSpec` — a JWT signed by a local RSA keypair verifies against an injected JWKS provider (no network); wrong `kid` → fail; bad signature → fail; `exp`/`iss`/`aud` enforced when configured; provider failure → fail-closed (401, never open).
- [x] **T-A3.2.** `JwksProvider` (issuer `/.well-known/openid-configuration` → `jwks_uri`, or direct certs URL) with `kid`-keyed cache + bounded refresh; injectable for tests.
- [x] **T-A3.3.** `BearerAuthenticator` honours `verify-signature = true`: signature + `iss`/`aud` checks before claim extraction; `verify-signature = false` keeps today's decode-only path. Extend `AuthSpec`.
- [x] **T-A3.4.** Wire issuer/audience/JWKS config through `buildComponents`.

### A-gate
- [x] **T-A.gate.** `just test-kt iris-bff` + `ktlintCheck` green; no regression in the 46-test suite. Component test (`ChatRoutesSpec`) still finalises audit through the (now-pluggable) store.

## Group B — operational (olymp `bp-dsk` cluster; GitOps via ArgoCD)

- [x] **T-B0.** **Helm chart** `agents/iris-bff/k8s/` (mirrors golem's env-agnostic chart: image/db/golemV2BaseUrl/auth/audit/telemetry as values); kustomize base/overlays removed. `helm lint` + `helm template` green. *(Done 2026-06-21.)*
- [x] **T-B1.** Build + push multi-arch image `ghcr.io/boraperusic/iris-bff:0.1.0` (CI-path jib; arm64+amd64). *(Done 2026-06-21.)*
- [x] **T-B2.** olymp app `clusters/bp-dsk/apps/iris-bff/{config.json,values.yaml}` (chart from kantheon `feat/p1-s1.4-deploy-smoke` ref via multi-source; db.enabled=false) + `iris-bff` ns added to the `ghcr-pull` ClusterExternalSecret selector. Merged to olymp master (PR Collite/olymp#3); ArgoCD ApplicationSet generated the `iris-bff` Application → **Synced + Healthy**. *(Done 2026-06-21.)*
- [x] **T-B3.** Live smoke on bp-dsk (direct REST/SSE — no FE): `/ready` 200, `/health` 200, `POST /v1/session` → 201 (real session), `GET /v1/sessions` lists it, `GET /v1/sessions` w/o bearer → 401. Dispatch-to-golem + recorded `/v2` fixtures deferred until an in-cluster `/v2` backend exists (Golem arc). *(Done 2026-06-21.)*
- [x] **T-B4.** Wire `iris-bff` to central-PG (Stage 1.4 T-B4). The `iris` DB/role + data-ns `pg-iris-cred` already existed in the data tier; added the `pg-iris` ClusterExternalSecret (materializes `pg-iris-cred` into the iris-bff ns) + flipped `db.enabled=true` (olymp PR Collite/olymp#4, merged). iris-bff connected to **PostgreSQL 18.3**, Flyway migrated v1 (all 7 tables: sessions/turns/audit/feedback/snapshots/artifacts/v2_threads). **Re-smoke green:** `POST /v1/session` row verified in `iris_sessions` via psql, and the session **survived a pod restart** (proves `ExposedSessionStore` PG persistence, not memory). `ExposedAuditStore` is wired + its table created, but the live audit *write* fires at turn finalization → proven once golem `/v2` dispatch lands. *(Done 2026-06-21.)*
- [ ] **T-B5.** OTel (when kantheon#27 otel gap resolved); fix-forward; bump `gradle/libs.versions.toml`; **tag `iris-bff/v0.1.0`**. **Phase 1 DONE.**

## DONE (Stage 1.4)
- Group A green on the mocked gate (above).
- Group B: BFF serving a real conversation in-cluster; recorded fixtures captured; tag cut.
- Plan §9 checkbox `Stage 1.4` ticked; master-plan status board + M3 note updated.

## Notes / deferred onward
- **Real-PG fidelity** for `ExposedSessionStore` **and** `ExposedAuditStore` stays in the separate integration-test suite (planning-conventions §4) — not a 1.4 gate.
- Recorded-fixture refresh discipline owned by the CI golden job (plan §6 cross-cutting).
