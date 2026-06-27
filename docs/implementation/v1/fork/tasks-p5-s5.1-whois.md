# Fork — Stage 5.1: whois service

> Branch: `feat/fork-p5-s5.1-whois`. Pre-flight: Stage 5.0 (`whois-common`, `keycloak-auth` in-repo; `infra/` accepted). Plan: [`plan.md`](./plan.md) Stage 5.1. Tracker: [`tasks.md`](./tasks.md).
>
> Source: `infra/whois` (Kotlin/Ktor; user/role directory syncing Keycloak + ERP → own Postgres; `WhoisCache`; `WhoisSyncService`; `UserRepositoryDb`; OPA `BundleHandler`). No proto — REST/JSON. Package `infra.whois.*` → `org.tatrman.whois.*`. Port 7110 (kept). Deps: in-repo `whois-common`, `keycloak-auth`, `db-common`, `otel-config`, `ktor-configurator`. Contracts §1, §3 (the `GET /whois` shape Argos will consume), §7.1 (port).

- [x] **T1 — Fork the module + package sweep.**
  `infra/whois` → kantheon `infra/whois`; included in `settings.gradle.kts` (and `infra/_smoke` retired, the tree now carrying a real module). Provenance header in README. Swept `package`/`import` `infra.whois.*` → `org.tatrman.whois.*` and `infra.erp.sql.common.auth` → `org.tatrman.keycloak.auth` (the Stage-5.0 lib); Jib `mainClass` → `org.tatrman.whois.ApplicationKt`, image → `whois:dev` (kantheon `<persona>:dev` convention, argos-mirrored), container port 7110, engine CIO. Deps re-pointed to in-repo libs (`whois-common`, `keycloak-auth`, `db-common`, `ktor-configurator`, `otel-config`) — no `erp-sql-common`, no Maven `whois-common`. New catalog alias `ktor-client-auth`.

- [x] **T2 — Forked suite green unmodified.**
  ai-platform `infra/whois` ships **no** unit tests (only the `test-whois.json` fixture, brought along). The suite is therefore what T5 adds; cache, sync, repository, deep-merge and bundle handler are all forked byte-identical (sweep aside) and exercised by the new specs.

- [x] **T3 — Postgres + migrations.**
  Flyway V1–V5 (`users`, `user_identities`, `user_roles`, `role_hierarchy`, `roles`) forked unchanged and run on boot in `db` mode; whois keeps its own Postgres (`who-is-database`, a DB in the one Kantheon PG). `json` mode (default) needs no DB — used for local boot + the component specs. Keycloak/ERP upstreams are mocked (WireMock) in CI; the live-Keycloak sync is integration-suite territory.

- [x] **T4 — k8s + deploy.**
  Helm chart `k8s/{Chart,values,templates}` (argos-mirrored, the current kantheon convention — supersedes the plan's kustomize wording). Single HTTP port 7110, `repositoryType` + DB/telemetry wiring as env-agnostic values. `helm template` renders clean. `_resolve` gains `infra/` so `just deploy-kt infra/whois` works (Jib build CI-gated on Rancher). `/health` + `/ready` are the probe targets.

- [x] **T5 — component test: serve + sync.**
  Mocked unit/component specs (planning-conventions §4): `WhoisRoutesComponentSpec` (JSON repo over the `whois.json` fixture — `/whois` by user_id+type / email / internal_id / generic id, 400s); `BundleHandlerComponentSpec` (`/bundle/{type}/roles.tar.gz` → manifest + merged data.json + .rego; bad-type 400); `KeycloakClientComponentSpec` (WireMock-mocked Keycloak admin API → GenericUser/GenericRole parse); `WhoisSyncServiceSpec` (mocked client + repo → the five-phase pipeline honours the config flags); `DeepMergeSpec`. **18 tests green.** True e2e against live Keycloak/PG deferred to the integration suite.

- [x] **T6 — Stage exit.**
  `:infra:whois:test` (18) + `:infra:whois:ktlintCheck` green. **Tag `whois/v0.1.0` on merge** (single `fork-5` branch; tracker no-merge rule). Stage 5.1 checked in [`tasks.md`](./tasks.md).

**DONE means:** whois serves the user/role directory + OPA bundles fully in-repo, with its own Postgres. **✅ Met 2026-06-24** (live-on-K3s confirmation rides the cluster deploy; engineering complete).
