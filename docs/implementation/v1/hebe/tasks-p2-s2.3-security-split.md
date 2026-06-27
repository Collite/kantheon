# Stage 2.3 — Security: console-auth vs platform-identity split

> **Phase 2, Stage 2.3.**
>
> **Reads with.** [`tasks-p2-overview.md`](./tasks-p2-overview.md), [`plan.md`](./plan.md) §"Stage 2.3", [`../../../architecture/hebe/architecture.md`](../../../architecture/hebe/architecture.md) §6 (two independent auth concerns; three layers), [`../../../architecture/hebe/contracts.md`](../../../architecture/hebe/contracts.md) §5.2 (`[security]` block) + §3.2 (machine-client OBO).

## Goal

The two auth concerns are split onto their own axes and resolved **independently**:

- `console_auth` (`password` | `oidc`) — how the human logs into Hebe's own admin console.
- `platform_identity` (`none` | `keycloak`) — how Hebe authenticates **outbound** to iris-bff via on-behalf-of.

**Keycloak/OBO is required by any `platform.reach != none` profile (`personal`/`server`/`k8s`), not k8s alone.** `local` stays password + keychain. Both axes are real code paths (no compile-out). The OBO token service mints for the bound user via **both** grant paths: device-code + cached refresh (`personal`/`server`) and client-credentials → OBO (`k8s`).

## Pre-flight

- [x] **Stage 2.1 DONE** (axes), **Stage 2.2 DONE** (the `secret:` resolver this stage generalises).
- [x] **Branch**: `feat/hebe-p2-s2.3-security-split`.
- [x] `deployment/local` Keycloak realm `kantheon` reachable for the integration suite; unit work uses a Wiremock'd Keycloak token endpoint.

## Tasks

- [x] **T1 — Tests first: OIDC console login + OBO mint (both grants) + channel rejection.**

  Create specs in `:agents:hebe:modules:security`:

  - `OidcConsoleLoginSpec` — against a Wiremock'd Keycloak, the `oidc` console path completes an auth-code/OIDC login and establishes a console session; the `password` path is unaffected.
  - `OboTokenServiceSpec` — for the bound user, mint an OBO token via **both** paths: (a) device-code + refresh-token exchange (`personal`/`server`), (b) client-credentials → token-exchange (`k8s`). Assert caching (second call within TTL does not re-hit Keycloak) and refresh-on-expiry.
  - `ChannelIdentityRejectionSpec` — a Telegram message from a `chat_id` **not** in `chat_user_map` is rejected before the agent loop, whenever `platform_identity = keycloak`.

  Acceptance: specs written and failing. Commit `[hebe-p2-s2.3] failing security specs`.

- [x] **T2 — `SecretsStore` seam: three impls behind the `secrets_backend` axis.**

  Generalise secret resolution into a `SecretsStore` interface with three implementations selected by `security.secrets_backend`:

  - `keychain` — the existing OS-keychain impl (`local`/`personal`).
  - `file` — encrypted/permissioned file (`server` option).
  - `k8s` — K8s Secret mounted as env/files (`k8s`).

  Every secret-ref scheme (`keychain:`, `secret:`, `file:`, `env:`) resolves through this seam. Unit-test each impl with a fake backing store.

  Acceptance: three impls green; the Stage 2.2 `secret:` resolver now delegates to `SecretsStore`.

- [x] **T3 — Console auth driven by `console_auth` axis (both paths real).**

  Wire the console (`:agents:hebe:modules:channels` web console / `:agents:hebe:modules:gateway`) to select login by the `console_auth` axis: OIDC when `oidc`, password+keychain when `password`. **No compile-out** — both are live, tested code (architecture §6, principle "no dead code paths"). Use `ktor-server-auth` (`libs.ktor.server.auth`) for the OIDC provider.

  Acceptance: T1 `OidcConsoleLoginSpec` passes; password path regression-green.

- [x] **T4 — Bound-user model + OBO token service.**

  Implement the instance↔Keycloak-user binding (`bound_user` from config) and the `OboTokenService` (cached, auto-refreshed) used for outbound iris-bff calls. The **same** bound-user model serves all three platform-reaching profiles; only the grant path differs (T1). The service exposes `currentBearer(): String` for the iris-bff client (Phase 4 Stage 4.1 consumes it). Receipts record the **acting identity** (the bound user) on every platform-reaching action.

  Acceptance: T1 `OboTokenServiceSpec` passes (both grant paths, caching, refresh); a receipt carries the acting identity.

- [x] **T5 — Telegram `chat_user_map` enforcement.**

  Whenever `platform_identity = keycloak`, enforce the `chat_user_map` (contracts §5.2): inbound Telegram messages map `chat_id → keycloak user`; unmapped chats are rejected pre-loop. Validate at boot that `chat_user_map` includes `bound_user`. `local` keeps today's allowlist behaviour.

  Acceptance: T1 `ChannelIdentityRejectionSpec` passes; boot fails fast if the map omits `bound_user` on a keycloak profile.

- [x] **T6 — `doctor`: Keycloak reachability + token mint + secret resolution.**

  Register checks into the Stage 2.1 matrix: Keycloak reachable, OBO mint succeeds for `bound_user`, and each configured secret-ref resolves. Required when `availability = always`; probed (DEGRADED) when `intermittent`. Stub Keycloak in the unit test.

  Acceptance: `doctor` reports Keycloak/secret health per axis. PR `[hebe-p2-s2.3] console-auth vs platform-identity split`.

## DONE — Stage 2.3

- [x] All six tasks checked.
- [x] Console logs in via Keycloak on `oidc` profiles (Wiremock'd in CI; live local-infra Keycloak in the integration suite).
- [x] OBO mint works from a simulated `personal` host (device-code path) **and** the `k8s` path; tokens cached + refreshed.
- [x] Receipts record the acting identity.
- [x] `chat_user_map` enforced on keycloak profiles; `SecretsStore` three impls green.
- [x] PR merged.

## Library / pattern references

- **architecture.md §6** — the two-concern split + three layers (platform identity / channel identity / agent-level security).
- **contracts.md §3.2** — machine-client OBO: iris-bff sees a normal user bearer, no service-account path. **§5.2** — `[security]` block + `chat_user_map`.
- **ktor-server-auth** (`libs.ktor.server.auth`) — OIDC console provider.
- **EXAMPLES.md §9** — Wiremock stub pattern for the Keycloak token endpoint.

## Out of scope for Stage 2.3

- The actual iris-bff call using the OBO bearer (Phase 4 Stage 4.1 — this stage produces `currentBearer()`; Phase 4 consumes it).
- PD-8 authorization filtering (constellation-side; Hebe inherits it via the OBO token, no Hebe change).
- Outbox/breaker (Stage 2.5).
