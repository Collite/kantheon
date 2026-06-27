# Stage 1.2 — `bff-base` + Sysifos-BFF skeleton

> **Phase 1, Stage 1.2.**
>
> **Reads with.** [`tasks-p1-overview.md`](./tasks-p1-overview.md), [`plan.md`](./plan.md) §3 (Stage 1.2), [`../../../architecture/sysifos/architecture.md`](../../../architecture/sysifos/architecture.md) §3.1 + §11 (`bff-base`), [`../../../architecture/sysifos/contracts.md`](../../../architecture/sysifos/contracts.md) §3 (BFF API) + §5 (dictionaries).

## Audit decision (T1) — **EXTRACT** `shared/libs/kotlin/bff-base`

**Measured shared surface (vs the current iris-bff): 347 LOC**, all BFF-agnostic — above the ~200 LOC threshold (architecture §11), so the lib is extracted rather than folded.

| Concern | Source (iris-bff) | bff-base file | LOC |
|---|---|---|---|
| RS256 / JWKS verification | `api/Jwks.kt` | `auth/Jwks.kt` | 176 |
| Bearer extraction + `requireCaller` | `api/Auth.kt` | `auth/BearerAuthenticator.kt` | 109 |
| Liveness/readiness routes | `api/HealthRoutes.kt` | `health/HealthRoutes.kt` | 36 |
| Tenant-header forwarding | (new — both BFFs need it) | `tenant/TenantHeaderForwarder.kt` | 26 |

**Package root** `org.tatrman.kantheon.bffbase` (matches envelope-render / capabilities-client). Generalised off iris-specific naming (logger strings, config keys); consumers wire their own config + logger names. Health routes emit a literal JSON string via `respondText` so the lib makes no ContentNegotiation assumption.

**iris-bff migration deferred (recorded).** To avoid disturbing the active Iris Stream-A frontier (iris is mid-arc; M3 gates on it), iris-bff keeps its inline copy for now and migrates onto `bff-base` in a follow-up. The architecture sanctions this ("extracted from iris-bff *or co-developed*; no behavioural change" — §3.2). The duplication is temporary, tracked debt.

## Goal

`sysifos-bff` authenticates Keycloak JWTs, forwards `X-Tenant-Id`, serves `/sessions/current`, `/dictionaries/*`, a heartbeat `/stream`, `/health`, `/ready`, and reaches Midas-core. `bff-base` extracted or folded per audit.

## Pre-flight

- [x] **Stage 1.1 DONE.**
- [~] Keycloak service account `sysifos-bff` exists; JWKS URL known. _(Config item, provisioned at deploy time; the BFF reads it via `SYSIFOS_AUTH_*` env — decode mode locally, JWKS signature mode in-cluster.)_
- [x] Iris-BFF skeleton available for the extraction audit; `tools/capabilities-mcp` running.
- [x] **Branch**: `feat/sysifos-p1-p2-workbench` (whole-arc branch).

## Tasks

- [x] **T1 — `bff-base` extraction audit.** Compare the auth/tenant/envelope/capabilities surface Sysifos needs against Iris-BFF's. Measure genuinely-shared LOC. Decision rule (architecture §11): **extract `shared/libs/kotlin/bff-base`** if shared > ~200 LOC, else **fold** helpers into both BFFs and defer the lib. Record the decision + LOC count at the top of this file.

  Acceptance: decision recorded; if "extract", module stub created and added to `settings.gradle.kts`.

- [x] **T2 — `bff-base` (or folded helpers) + tests first.** Implement (in `bff-base` or inline):
  - `auth/KeycloakJwtVerifier.kt` — verify RS256 against JWKS; extract `sub` (user_id) + `tenant_id` claim.
  - `tenant/TenantHeaderForwarder.kt` — produce `X-Tenant-Id` for downstream calls; reject if JWT tenant ≠ requested path/body tenant.
  - capabilities-client wrapper (read-only), envelope-render re-export.

  Tests first: fixture JWTs (valid, expired, wrong-tenant); assert verify + extract + mismatch-reject. EXAMPLES.md §9 (Kotest + Wiremock for the JWKS endpoint).

  Acceptance: `just test-kt bff-base` (or `sysifos-bff`) green.

- [x] **T3 — BFF skeleton tests first (Ktor TestApplication).** `src/test/kotlin/.../SessionRouteSpec.kt`, `HealthSpec.kt`:
  ```kotlin
  "GET /sessions/current without JWT -> 401"
  "GET /sessions/current with valid JWT -> SysifosSession with tenant_id from claim"
  "GET /health -> 200"
  "GET /ready -> 200 only when midas-core reachable (Wiremock up) else 503"
  ```
  Acceptance: specs written and failing.

- [x] **T4 — BFF skeleton.** Per architecture §3.1:
  - `App.kt` (EXAMPLES.md §1b, ≤45 lines) — OTel init, `installKtorServerBase`, routing.
  - `auth/` install verifying JWT via `bff-base`.
  - `api/SessionRoute.kt` — POST `/sessions`, GET `/sessions/current`.
  - `midas/MidasCoreClient.kt` — Ktor `HttpClient` (CIO) with base URL from config; forwards `Authorization` + `X-Tenant-Id`; used by `/ready` to ping Midas-core `/health`.
  - `api/HealthRoutes.kt` — `/health`, `/ready` (ready = JWKS loaded && Midas-core reachable).
  - `application.conf` — port 7601, `midas.core.base-url`, `keycloak.jwks-url`.

  Acceptance: T3 specs green; `just build-kt sysifos-bff` runnable.

- [x] **T5 — Dictionaries.** `api/DictionaryRoute.kt` + `dictionaries/DictionaryCache.kt` (TTL 10 min) serving `/dictionaries/{brokers,currencies,transaction-kinds,asset-kinds}` per contracts §5. Brokers from a loader-registry stub (static list in v1); currencies from an ISO-4217 resource; tx/asset-kinds from `midas/v1` enums + a cs/en label map. Tests assert shape + cache hit.

  Acceptance: all four dictionary endpoints answer with labels; cache tested.

- [x] **T6 — Heartbeat stream.** `stream/StreamRoute.kt` — SSE at `/stream`; emits `SessionHeartbeat` every 30s for the calling session. Test: open the channel, assert at least one heartbeat within 35s (use a virtual clock / shortened interval in test). Verify SSE survives behind a proxy (keep-alive comment frames).

  Acceptance: `/stream` opens and heartbeats; reconnect documented.

- [→] **T7 — Deploy + smoke → MOVED to Testing Stage 3.4.** The cluster-tier deploy + live smoke for the BFF edge (curl `/sessions/current` with a real Keycloak JWT → `SysifosSession`; `/ready` → 200 with Midas-core up; `/dictionaries/currencies` → list) is owned by the Testing arc — see [`../testing/tasks-p3-s3.4-sysifos-deploy-smoke.md`](../testing/tasks-p3-s3.4-sysifos-deploy-smoke.md) (T5 "BFF edge" leg). **In-arc deliverables done:** manifests authored + `kubectl kustomize overlays/local` builds clean (port 7601, `imagePullPolicy: Never`, Midas + auth env); the Keycloak `sysifos-bff` service account is wired through `SYSIFOS_AUTH_*` env (provisioned at deploy time, T4 of 3.4).

## DONE — Stage 1.2

- [x] T1–T6 done; T7 deploy+smoke **moved to Testing Stage 3.4** (no longer an in-arc task).
- [x] `just test-kt sysifos-bff` green (10 specs). _(Cluster smoke owned by Testing 3.4.)_
- [x] `bff-base` extract-or-fold decision recorded.

## Library / pattern references

- EXAMPLES.md §1b (App.kt), §8 (OTel before start), §9 (Kotest + Wiremock).
- ai-platform `infra/whois` JWT verification for the Keycloak pattern.
- Iris-BFF auth/tenant code — the extraction source.

## Out of scope

- The draft commit path + sync CRUD proxy (Stage 1.3). FE (Stage 1.3). Business screens (Phase 2).
