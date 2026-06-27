# Fork — Stage 5.3: Argos optional `whois` role source (additive)

> Branch: `feat/fork-p5-s5.3-argos-role-source`. Pre-flight: Stage 5.1 (whois deployed); Phase 3 Stage 3.1 (Argos exists, **bearer-default** RLS). Plan: [`plan.md`](./plan.md) Stage 5.3. Tracker: [`tasks.md`](./tasks.md).
>
> **Additive only.** Phase 3 already ships Argos with bearer-only role resolution. This stage adds an *opt-in* `whois` role source for deployments needing the ERP role hierarchy the Keycloak token doesn't carry. Identity stays bearer-only at the theseus-mcp edge — whois enriches, never authenticates (architecture §6, contracts §3). Default config is unchanged, so the Phase-3 security posture is untouched unless a deployment flips the switch.

- [x] **T1 — Test first: the strategy + the default.**
  `RoleSource` interface + `BearerRoleSource` (extracts the Phase-3 behaviour: roles = `context.auth_roles`) + `WhoisRoleSource`. `RoleSourceSpec` covers: bearer returns exactly the forwarded roles; whois unions the bearer floor with enrichment (distinct); per-user caching (lookup ≤ once/TTL); blank user_id keeps the floor (no lookup); empty enrichment leaves the floor; unavailable lookup propagates `RoleSourceUnavailableException`.

- [x] **T2 — Config + client.**
  `argos.roleSource = bearer | whois` + `argos.whois.{baseUrl,cacheTtlSeconds}` (contracts §3) in `application.conf`; `Application.kt` builds the selected `RoleSource`. `WhoisRoleSource` = bearer floor + a `WhoisRoleLookup`; default `KtorWhoisRoleLookup` is a thin CIO client over `GET /whois?user_id=&user_id_type=KEYCLOAK` decoding `whois-common` `UserRecord`; caffeine TTL cache so the hot path hits whois at most once per user per TTL. Deps: `whois-common` + `caffeine` added to argos.

- [x] **T3 — Bearer path unchanged (regression).**
  The `service()` test helper defaults to `BearerRoleSource`, so the **entire existing ArgosServiceImpl suite runs unchanged and green** (115 argos tests, 0 failures) — `effectiveRoles == incomingContext.authRolesList` in bearer mode, so the context is not even rebuilt. Proof the stage reopens nothing.

- [x] **T4 — Whois path: hierarchy roles reach RLS.**
  `ArgosServiceImplSpec` cases: with `roleSource = WhoisRoleSource(lookup granting "query-platform-admin")`, a bearer holding only `analyst` + `apply_security=false` has the bypass **honoured** (the whois-only admin role reached the admin gate, which reads the same `auth_roles` the SecurityApplier does); the **same request in bearer mode is denied** (`security_bypass_denied`). Enrichment is written back into `PipelineContext.auth_roles`, so it reaches the RLS predicates too.

- [x] **T5 — Security envelope.**
  `KtorWhoisRoleLookupSpec` (WireMock): a record whose KEYCLOAK identity **is** the requested id contributes its role union (incl. ERP-hierarchy); a record for a **different** user_id is ignored (empty enrichment); a non-2xx whois throws `RoleSourceUnavailableException`. `ArgosServiceImplSpec` then asserts that in `whois` mode an unavailable source **fails closed** — a Rule-6 `role_source_unavailable` ERROR and **no plan**, never a wider/fallback role set.

- [x] **T6 — Docs + ship.**
  `kantheon-security.md` §3.6 (already authored at design time) marked **implemented**; §6 contract row marked landed. `:services:argos:test` (115) + `:services:argos:ktlintCheck` green. **Argos minor-version bump on merge** (`argos/v0.2.0`). Stage 5.3 checked in [`tasks.md`](./tasks.md).

**DONE means:** Argos resolves roles from the bearer by default and from whois when configured, with **no** change to the default security posture. **✅ Met 2026-06-24.**
