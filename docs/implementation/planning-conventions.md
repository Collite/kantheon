# Kantheon — Planning Conventions

> **Status:** locked 2026-05-15. Applies to all planning in this repository going forward. Any deviation is documented per-stage at the point of departure.

## 1. Hierarchy — bottom-up

Three nested units. Pick the right one for the scope of work being described.

| Unit | Size | Done means | Hand-off shape |
|---|---|---|---|
| **Task** | atomic work unit, ~½ – 1 day, single concern | the change is committed and its **(mocked) unit tests** pass | written so a junior developer (or coding agent) can complete it without further clarification — explicit file paths, code shapes, acceptance criteria |
| **Stage** | ~6 tasks (5–8 acceptable) | something **testable** ships: a module compiles, an endpoint responds, a graph passes its **mocked unit/component tests** against fixtures (real-dependency verification is a separate integration-test suite — see §4 Testing policy) | one task list per stage; checkboxes track progress |
| **Phase** | a set of stages | something **deployable** ships: an actual service or capability that ships to local K3s and serves real (or fixture-driven) callers | a phase-level overview doc plus one task-list document per stage |

## 2. Naming

- Phases: `Phase 1`, `Phase 2`, `Phase 3`, … numbered within a planning arc.
- Stages: `Stage 1.1`, `Stage 1.2`, `Stage 2.1`, … numbered within their phase.
- Tasks: numbered within their stage as `1.1.T1`, `1.1.T2`, … or just `T1`, `T2`, … when the parent stage is obvious from filename. (Either form is fine; pick one per stage doc and use it consistently.)

Branch convention: `feat/<phase-id>-<stage-id>-<short-name>`, e.g. `feat/p1-s1-2-capabilities-proto`. Tag convention follows ai-platform's `<service>/v<x.y.z>` per `gradle/libs.versions.toml`.

## 3. Per-document responsibilities

For each planning arc — that is, each thread of work big enough to take a phase or more — Claude produces three artefacts **before** task lists are generated. They live in the relevant agent's docs directory, e.g. `kantheon/docs/v1/themis/`, `kantheon/docs/v1/iris/`, `kantheon/docs/v1/pythia/`.

### `architecture.md`

The shape of the solution: component diagram, module dependency graph, deployment topology, tech-stack table, build/test/deploy flow, observability. References to upstream design docs (`*-design.md`) and locally-cloned libraries (`~/Dev/view-only/koog`, `~/Dev/view-only/calcite`, `~/Dev/view-only/kotlin-mcp-sdk`) plus `ai-platform/EXAMPLES.md`.

### `contracts.md`

All wire contracts: protobuf packages with full type definitions, MCP tool input/output schemas, REST endpoint shapes, manifest YAML schemas, event protocols, persistence row shapes. **Source of truth for cross-service boundaries.** Any later task list referencing these contracts must match them exactly.

### `plan.md`

Phased implementation plan: phase summary table; per-phase deliverable; per-stage goal, ~6 task titles, pre-flight conditions, DONE criteria, dependencies. The actual full task descriptions go in the per-stage task-list files (see §4).

These three artefacts MUST exist (or be explicitly updated when planning is refactored) before any task list is written. They are the immutable reference the task lists point back to.

## 4. Task lists

### Testing policy — mocked unit tests only (locked 2026-06-14)

Implementation plans and their task lists develop against **mocked unit tests only**:

- **In scope for stages:** unit tests at the class/object boundary and component tests at the inter-class boundary, with **all external collaborators mocked** — MockK / in-memory fakes; **Wiremock** for HTTP dependencies; mocked clients/stubs for gRPC, S3 (Seaweed), Redis, DB drivers (ADBC/JDBC), the LLM gateway, and MCP servers. *(Wiremock and friends are mocks — they stay; they are how a unit/component test isolates an HTTP collaborator.)*
- **Not in scope for stages — deferred to a separate integration-test suite:** **Testcontainers**-based tests, **integration** tests, **end-to-end / e2e** tests, and **in-cluster (local K3s) round-trip** test acceptance. A stage's DONE criteria are satisfied by passing its mocked unit/component tests; any behaviour that can only be confirmed against a *real* dependency is recorded as an item for the integration-test suite, **not** as a blocker for the stage.
- **Test type ≠ system capability.** This governs *what tests run inside a stage*, not what the system does. A plan may still say "the service serves an end-to-end query" or "deploys to local K3s" as a capability — it simply does not gate that capability on an e2e/integration *test*.

The integration-test suite (Testcontainers + integration + e2e + in-cluster acceptance) is planned and built **separately**, after the mocked-unit-test implementation lands.

### Per-stage files

Task lists are written per-stage, one file per stage. Filename convention:

```
kantheon/docs/v1/<agent>/tasks-p<phase>-s<stage>-<short-name>.md
```

For example: `kantheon/docs/v1/themis/tasks-p1-s2-capabilities-proto.md`.

### Per-task-list expectations

- **Header** linking back to `architecture.md`, `contracts.md`, and `plan.md` so the executor sees the context.
- **Pre-flight checklist** — what must be true before starting (other stages closed, branch created, dependencies available).
- **6 – 8 tasks max.** If a stage has more, split into two stages.
- **Every task gets a checkbox.** Executor checks each off as it lands.
- **Tasks are TDD-shaped:** for a stage that lands code, the first tasks define and write tests; later tasks make the tests pass. **Mocked unit/component tests only** per the Testing policy above — unit-level at the class/object boundary, component-level at the inter-class boundary, all collaborators mocked. Testcontainers, integration, and E2E tests are scheduled in the separate integration-test suite, not inside the implementation stage.
- **Library API references.** Where a task touches a complex library (Koog, Kotlin MCP SDK, Calcite, Ktor, vega-embed, etc.), the task includes either: a snippet from the locally-cloned library at `~/Dev/view-only/<lib>/`, or a `context7` query to fetch current-version API documentation. Don't leave the executor to guess.
- **Examples cited** from `ai-platform/EXAMPLES.md` (Ktor setup, serialization, MCP server, frontend, Apache Calcite) when applicable.
- **No imagination required.** Explicit file paths, function signatures, expected behaviour, edge cases.

### Stage overview document

For each phase, a top-level overview document `tasks-p<phase>-overview.md` references the per-stage task lists and tracks aggregate progress. It is the entry point for any executor picking up the phase.

## 5. Examples

The first instance of this convention applied is the Themis arc:

- `kantheon/docs/v1/themis/architecture.md`
- `kantheon/docs/v1/themis/contracts.md`
- `kantheon/docs/v1/themis/plan.md`
- per-stage task lists at `kantheon/docs/v1/themis/tasks-p<n>-s<n.m>-*.md` (written after the three above)

Future arcs (Iris BFF, Pythia, Golem rewrite, capabilities-mcp follow-ups) mirror the same structure.

## 6. Library reference policy

- **Cloned-on-disk libraries** to consult before writing task instructions:
  - Apache Calcite — `~/Dev/view-only/calcite`
  - JetBrains Koog — `~/Dev/view-only/koog`
  - Kotlin MCP SDK — `~/Dev/view-only/kotlin-mcp-sdk`
  Each has a `graphify-out/` directory you can query for symbol graphs and call sites when writing task references.
- **context7 MCP** for current-version library APIs not covered above.
- **ai-platform/EXAMPLES.md** for canonical Ktor / serialization / MCP / Calcite / OTel patterns that the kantheon code should mirror.

## 7. What this convention does NOT cover

- **Brainstorming records** — `*-brainstorming.md` files capture the *why* of decisions and live alongside `*-design.md`. They are not subject to the phase/stage structure.
- **Design documents** — `*-design.md` files describe the agent's outward shape, not the implementation path. They are the input to architecture.md.
- **Status reports / audits** — `*-status-audit.md`, `*-status-review-*.md`, `next-steps.md` capture state-as-of-date snapshots. They reference the planning artefacts but are not part of them.
- **Memory files** — Claude's per-space memory (`spaces/<id>/memory/`) is for cross-session context. It mirrors and references planning docs but is not their source.

---

*Convention owner: Bora. First applied 2026-05-15 to the Themis-in-kantheon arc.*
