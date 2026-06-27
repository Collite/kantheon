# Golem Phase 4 · Stage 4.1 — `domain → area` rename + `AREA_QA` (proto + code + roles)

> **Arc.** Golem Phase 4 (Golem-ucetnictvi assembled Shem + cutover). **Branch.** `feat/p4-s4.1-area-rename`.
> **Companions.** [`plan.md`](./plan.md) §6 Stage 4.1, [`../../../architecture/golem/contracts.md`](../../../architecture/golem/contracts.md) §6 (assembled Shem; "area" not "domain"), [`../../../architecture/themis/contracts.md`](../../../architecture/themis/contracts.md) §3.2 (capabilities/v1 + routing), [`../../../architecture/kantheon-security.md`](../../../architecture/kantheon-security.md) §3.1 (role convention).
> **Goal.** The `domain → area` vocabulary lands as a **typed contract change** before the Shem-assembly refactor (Stage 4.3) builds on it. Per the 2026-06-25 vocabulary canon: "domain" is now a TTR value concept; a Golem's subject area is an **area** (`AREA_QA`, `area_*`, `kantheon-area-<area>`).
>
> **Re-scope note.** The pre-2026-06-25 P4 task files (`shem-content`/`soak`/`cutover`) were deleted when the plan §6 was re-scoped to 4.1 rename → 4.2 Ariadne `ResolveArea` → 4.3 assembly → 4.4 deploy → 4.5 soak → 4.6 cutover; this file is the new Stage 4.1.

## Pre-flight

- Stages 3.1–3.3 closed (P2+P3 code-complete 2026-06-24); the template serves conversational turns at parity.
- This is a **pure rename** stage: field names + the enum value + the role convention. **No field drops** (`style_addendum`/`preferred_queries`/`locale_defaults` stay — they leave the *authored* surface only in the Stage 4.3 assembly refactor), **no value migration** (`golem-erp` agent id, `"ERP"` area value stay until the deploy/assembly stages). Keep proto field numbers (20/21/22, enum value 2).
- Serialization is protobuf-util `JsonFormat` (camelCase) — `domain_entities` → `area_entities` becomes JSON key `areaEntities`, which Themis's `RouteToAgentNode` reads.

## Rename surface (mapped 2026-06-25)

**Proto** (`shared/proto/.../capabilities/v1/capabilities.proto`): `AgentKind.DOMAIN_QA`→`AREA_QA` (value 2 kept); `domain_name=20`→`area_name`, `domain_entities=21`→`area_entities`, `domain_terminology=22`→`area_terminology`; comments (`agent_kind == DOMAIN_QA`, `per-domain realm roles "kantheon-domain-<shem>"`).

**Generated accessors** (consumed): `setDomainName/areaName`, `domainName/areaName`, `addAllDomainEntities/areaEntities`, `domainEntitiesList/areaEntitiesList`, `addAllDomainTerminology/areaTerminology`, `domainTerminologyList/areaTerminologyList`, `AgentKind.DOMAIN_QA/AREA_QA`.

**Kotlin consumers:** `golem/shem/{ShemYaml,ShemContext,ShemLoader}.kt`; `themis/koog/nodes/RouteToAgentNode.kt` (data-class field + `"DOMAIN_QA"` string + JSON key `areaEntities`); `capabilities-mcp/{loader/ManifestYamlLoader,registry/RegistryQueryService}.kt`.

**Roles:** `kantheon-domain-<shem>` → `kantheon-area-<area>`; `GolemErpIntegrationSpec` `erp_user` → `kantheon-area-accounting`.

**Specs/fixtures:** `shared/proto/.../CapabilitiesProtoSpec`; golem `ShemTestFixtures`/`ShemLoaderSpec`/`ShemRegistrationSpec`/`ShemAdmissionSpec`; capabilities-mcp `InMemoryRegistrySpec`/`ManifestYamlLoaderSpec`/`CapabilitiesRestSpec` (+ test manifest `golem-erp-test.yaml`); themis `RouteToAgentSpec`/`Phase3RoutingComponentSpec`/`Phase3ProfileRefusalComponentSpec`; main manifest `golem-erp.yaml`.

**Docs:** `kantheon-security.md` §3.1, `themis/contracts.md` §3.2 (10 refs), CLAUDE.md vocabulary canon (area row already landed).

## Tasks

- [x] **T1 — proto rename + regen.** Renamed the three `domain_*` fields + `DOMAIN_QA`→`AREA_QA` in `capabilities.proto` (numbers 20/21/22 + enum value 2 unchanged) + comments; `CapabilitiesProtoSpec` asserts `area_*`/`AREA_QA`; `just proto` regen green.
- [x] **T2 — golem `shem/` sweep.** `ShemYaml` (`area_name`/`area_entities`/`area_terminology` keys), `ShemContext` (getters + manifest accessors), `ShemLoader` (`AREA_QA` lint + setters + lint messages). `ShemTestFixtures`/`ShemLoaderSpec` → `area_*` + `kantheon-area-erp`. `:agents:golem:test` green.
- [x] **T3 — Themis routing sweep.** `RouteToAgentNode` data-class field `domainEntities`→`areaEntities`, JSON key `obj.strList("areaEntities")`, `agentKind == "AREA_QA"` (scoring + `spansMultipleDomains`). Specs (`RouteToAgentSpec`, `Phase3RoutingComponentSpec`, `Phase3ProfileRefusalComponentSpec`). `:agents:themis:test` green.
- [x] **T4 — capabilities-mcp sweep.** `ManifestYamlLoader` (node fields + builder setters), `RegistryQueryService.areaEntitiesList`; manifests `golem-erp.yaml` + `golem-erp-test.yaml` (`area_*`); specs (`InMemoryRegistrySpec`, `ManifestYamlLoaderSpec`, `CapabilitiesRestSpec` + the `agentCapability` DSL). `:tools:capabilities-mcp:test` green.
- [x] **T5 — roles + admission + integration spec.** `kantheon-domain-erp`→`kantheon-area-erp` across `ShemAdmissionSpec`/`ShemRegistrationSpec`/fixtures; `GolemErpIntegrationSpec` `erp_user`→`kantheon-area-accounting`. `kantheon-security.md` §3.1 already carried the convention (lines 53/55/134).
- [x] **T6 — doc sweep + full `test`/`ktlintCheck`.** `themis/contracts.md` §1.1/§3.2 (proto block + example manifest), `kantheon-architecture.md` (Keycloak roles + Pythia planner-context field), `pythia/architecture.md` master-of-Golems field; fork docs already clean; CLAUDE.md canon already carried the area row. Full `./gradlew test ktlintCheck` green (47 modules).

## DONE — 2026-06-25

No `domain_name`/`domain_entities`/`domain_terminology` Shem field or `DOMAIN_QA` reference remains **in code** (the surviving "domain" senses are TTR value / cross-area-routing concepts only); full suite + ktlint green. Stage 4.3 assembly can build on `area_*`/`AREA_QA`.

**Scoped doc follow-up (not Stage 4.1):** frozen `design/` records (`golem-template-design.md` — its rewrite is Stage 4.6 T4; `pythia/Pythia-v1-Design.md`), completed-stage task lists (themis `tasks-p1-s1.2`/`-s1.4`/`-p3-s3.3`, golem `tasks-p2-s2.2`), and other arcs' docs (`midas/contracts.md`, `hebe/*`, `kleio/*`, `kantheon-v1.1.md`, `product-design-issues.md`) still show `DOMAIN_QA`/`domain_*`/`kantheon-domain-<shem>`. Left to their own arcs — none is compiled; no code breakage.
