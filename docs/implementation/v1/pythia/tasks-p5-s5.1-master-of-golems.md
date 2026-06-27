# Stage 5.1 — Master-of-Golems + manifest

> **Phase 5, Stage 5.1.**
>
> **Reads with.** [`tasks-p5-overview.md`](./tasks-p5-overview.md), [`plan.md`](./plan.md) §7 Stage 5.1, [`../../../architecture/pythia/architecture.md`](../../../architecture/pythia/architecture.md) §5 ("Master-of-Golems"), [`../../../architecture/pythia/contracts.md`](../../../architecture/pythia/contracts.md), [`../../../architecture/golem/contracts.md`](../../../architecture/golem/contracts.md) §6 (ShemManifest / area semantics), [`../../../architecture/capabilities-mcp/`](../../../architecture/capabilities-mcp/), [`../../planning-conventions.md`](../../planning-conventions.md) §4.

## Goal

The planner reads relevant Golems' Shem context for cross-domain plans; a cross-domain fixture investigation runs; the `pythia.yaml` AgentManifest is filled and Themis routes investigation buckets to Pythia; heartbeat registration replaces the fixture manifest. **End state:** Shem-read planner spec green; cross-domain fixture green; Themis routing eval passes; Pythia heartbeats into capabilities-mcp.

## Pre-flight

- [x] Phase 4 DONE (or Phases 1–3 for SQL-only cross-domain — but the eval needs the full set).
- [x] **capabilities-mcp** live with ShemManifest content for at least two areas (golem-ucetnictvi + one synthetic second area for cross-domain — Golem arc Phase 4).
- [x] Branch `feat/pythia-p5-s5.1-master-of-golems`.
- [x] **Vocabulary note (2026-06-25):** a Golem's subject is an **area**, not "domain" — fields `area_name`/`area_entities`/`area_terminology`, discriminator `AREA_QA` (CLAUDE.md §9). Use `area_*` throughout.

## Tasks (TDD-shaped)

- [x] **T1 — Shem-read planner context.**

  Implement `CapabilitiesReadClient` Shem reads in the planner (Stage 2.1): for the resolved entities, find relevant Golems (**relevance = `area_entities` ∩ resolved entities**), pull their `preferred_queries` + `area_terminology` + `preferred_capabilities` (ShemManifest, golem/contracts.md §6), and inject them as **structured context** into the planner prompt. No agent-to-agent delegation (R4 — Pythia reads manifests, doesn't call Golems).

  Test (Wiremock capabilities-mcp): a question spanning two areas pulls both Shems' context; an in-area question pulls one; assert the injected context shape.

  Acceptance: `ShemReadPlannerSpec` green.

- [x] **T2 — Cross-domain fixture investigation.**

  Build a cross-domain fixture (ERP/účetnictví + a second synthetic Shem) as a component investigation: assert the plan draws capabilities/terminology from both areas and the conclusion synthesises across them.

  Acceptance: `CrossDomainFixtureSpec` green.

- [x] **T3 — `pythia.yaml` AgentManifest content.**

  Fill `pythia.yaml` (the AgentManifest, registered into capabilities-mcp): **Bora-owned** — `description_for_router`, example questions, counter-example questions (the same pattern as the golem-erp fill, plan §10). **Claude-owned** — endpoints, latency, cost from measurements. Discriminator: Pythia is an agent capability (`kind: AGENT`), routable for RCA/FORECAST/SIMULATION/cross-domain.

  Acceptance: `pythia.yaml` validates against the AgentManifest schema (capabilities/v1); loads into capabilities-mcp.

- [x] **T4 — Themis routing eval.**

  Joint with the Themis corpus: RCA / FORECAST / SIMULATION / cross-domain question buckets route to Pythia (INVESTIGATION_DEEP). Add the Pythia buckets to the Themis routing eval; assert routing accuracy meets the Themis gate threshold.

  Test: the routing eval (scripted) routes each bucket's questions to Pythia; misroutes are reported.

  Acceptance: routing-eval bucket green (coordinate the threshold with the Themis arc).

- [x] **T5 — Heartbeat registration replaces fixture.**

  Replace the seed/fixture manifest with live heartbeat registration (the capabilities-mcp heartbeat client — warn-and-continue if the registry is unreachable, architecture §7). Pythia registers its `AgentCapability` on boot + heartbeat.

  Test: on boot the heartbeat posts the manifest; registry-unreachable degrades to warn (no boot failure).

  Acceptance: `HeartbeatRegistrationSpec` green; `just test-kt pythia` green.

## DONE — Stage 5.1

- [x] All tasks checked; suite green.
- [x] Pythia is discoverable + routable in the constellation; cross-domain planning reads Shems.
- [x] Integration carry-overs recorded (live capabilities-mcp heartbeat, live Themis routing accuracy on the joint corpus — the nightly themis-routing gate) (live capabilities-mcp heartbeat, live Themis routing).
- [x] CI green on `[pythia-p5-s5.1] master-of-golems`.

## Library / pattern references

- **architecture §5** (master-of-Golems — read manifests, no delegation), **golem/contracts.md §6** (ShemManifest / `area_*` semantics), **capabilities/v1** (AgentManifest schema).
- **plan §10** — Bora-owned manifest content (examples/counter-examples) follows the golem-erp pattern.

## Out of scope

- iris-bff integration / investigation UX — Stage 5.2.
- Eval CI gate + hardening — Stage 5.3.
- Plan-node-level Golem delegation — v1.5 backlog (R4: no agent-to-agent calls at v1).
