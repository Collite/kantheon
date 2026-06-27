# Stage 5.1 — kleio proto + Koog graph + grounded turn

> **Phase 5, Stage 5.1.** Branch `feat/docwh-p5-s5.1-grounded-turn`.
>
> **Reads with.** [`tasks-p5-overview.md`](./tasks-p5-overview.md), [`plan.md`](./plan.md) §7 Stage 5.1, [`../../../architecture/kleio/contracts.md`](../../../architecture/kleio/contracts.md) §8 (`kleio.proto`) + §5 (citation ↔ envelope grounding contract) + §3 (`kleio` DB), [`../../../architecture/kleio/architecture.md`](../../../architecture/kleio/architecture.md) §4 (the node graph), [`../golem/architecture.md`](../../../architecture/golem/architecture.md) (Koog `AIAgentStrategy` + trust-Themis + envelope emission), [`../../../../EXAMPLES.md`](../../../../EXAMPLES.md) §5 (Koog graph node).

## Goal

`agents/kleio` — a Koog graph (`Scope → Retrieve → GroundedAnswer → Render`) that produces a `GroundedResponse` citing **only** retrieved nodes. The `kleio.proto` lands; the agent mirrors the Golem bootstrap (trusts Themis upstream, emits `envelope/v1`). `NO_GROUNDING` → an honest CALLOUT refusal. DONE = a grounded mart turn renders cited `envelope/v1` blocks against mocks.

## Tasks (6)

- [ ] **T1 — Write `kleio.proto` (contracts §8); `just proto`.**

  Author `shared/proto/src/main/proto/org/tatrman/kantheon/kleio/v1/kleio.proto` **byte-for-byte** from contracts §8: `KleioRequest` (+ `KleioContext`/`ConversationTurn`/`Caller`), `GroundedResponse` (+ `Status` incl. `STATUS_NO_GROUNDING`, `SourceUse`, `ResourceUsage`), `ArtifactRequest`/`ArtifactResponse`/`ArtifactKind`. Constellation root `org.tatrman.kantheon.kleio.v1`; imports `envelope/v1`, `themis/v1`, `common/v1` handoff + response_message. Round-trip spec (mirrors the Golem proto spec).

  Acceptance: proto compiles; round-trip spec green via `just test-kt shared:proto`.

- [ ] **T2 — `agents/kleio` (Ktor + Koog) bootstrap; clients.**

  Create the module (architecture §4) mirroring the Golem bootstrap: Ktor + Koog (`AIAgentStrategy`), `clients/KallimachosMcpClient` (calls `library.*` with the caller OBO bearer) + `clients/PrometheusClient` (grounded synthesis). Config (contracts §11): `kleio.{port=7270, db.url, kallimachos-mcp.{host,port}, prometheus.{host,port}, retrieval.{k=8, min-score}}`. `include(":agents:kleio")`.

  Acceptance: module compiles; pod starts; clients wired.

- [ ] **T3 — Tests first: `KleioStrategySpec` (Koog mock executor + mocked `getContext`).**

  Spec the Koog strategy with a **mock executor** + a mocked `getContext`: `Scope → Retrieve → GroundedAnswer → Render` yields a `GroundedResponse` whose every cited claim maps to a node in the retrieved set (the grounding invariant — architecture §13). Cover the four nodes' input/output ports (EXAMPLES.md §5).

  Acceptance: spec written and failing. Commit `[docwh-p5-s5.1] failing kleio strategy spec`.

- [ ] **T4 — `GroundedAnswerNode` + `RenderNode` (the grounding contract).**

  Implement `GroundedAnswerNode` — its prompt (in `prompts/`) constrains synthesis to the retrieved chunk ids (Prometheus). `RenderNode` maps citations onto `envelope/v1` per contracts §5 (`source_ref` → `Block.provenance.source_tables[]`; ids → `Drilldown.arg_mapping`; `title`+`locator` → `Drilldown.display`; `producing_agent_id = "kleio"`) and **drops any model-emitted citation whose `part_id`/`page_id` is not in the turn's retrieved set** (provenance points only at what was retrieved — contracts §5). `Scope`/`Retrieve` nodes bind the mart + call `getContext`.

  Acceptance: T3 strategy spec green; uncited/hallucinated citations are dropped at render.

- [ ] **T5 — `NO_GROUNDING` → CALLOUT refusal.**

  When `getContext` returns nothing above `min-score` (the S2.3 threshold), the turn ends `STATUS_NO_GROUNDING` → a CALLOUT block, **no fabricated citations** (contracts §8). Spec: an out-of-mart question yields the honest refusal, not an invented answer.

  Acceptance: NO_GROUNDING path spec green.

- [ ] **T6 — `kleio_turns` persistence; `kleio` DB migration + provisioning.**

  Implement `persistence/KleioTurns` (contracts §3 `kleio_turns`: turn_id, session_id, notebook_id, question, status, envelopes jsonb, sources_used jsonb, resource_usage jsonb). Flyway migration for the `kleio` DB; provision the database. Conversation memory is **Iris's** job (the Golem rule) — Kleio persists one turn row per turn.

  Acceptance: a turn persists one `kleio_turns` row; migration applies. PR `[docwh-p5-s5.1] kleio proto + koog graph + grounded turn`.

## DONE — Stage 5.1

- [ ] All six tasks checked.
- [ ] `kleio.proto` landed (`org.tatrman.kantheon.kleio.v1`); round-trip spec green.
- [ ] Koog graph `Scope→Retrieve→GroundedAnswer→Render` yields a `GroundedResponse` citing only retrieved nodes.
- [ ] `RenderNode` drops uncited claims (the grounding contract); `NO_GROUNDING` → CALLOUT.
- [ ] `kleio_turns` persistence + `kleio` DB migration.
- [ ] A grounded mart turn renders cited `envelope/v1` blocks against mocks.
- [ ] PR merged.

## Library / pattern references

- **contracts.md §8** — `kleio.proto`. **§5** — the citation ↔ envelope grounding contract (authority for T4). **§3** — `kleio_turns`.
- **golem architecture** — the Koog `AIAgentStrategy` + trust-Themis + envelope-emission patterns Kleio borrows.
- **EXAMPLES.md §5** — Koog graph node (typed ports, OTel span, retry). **§6** — envelope `Block` + `BlockProvenance` + `Drilldown`.

## Out of scope for Stage 5.1

- Artifacts (Stage 5.2).
- capabilities registration + the additive enums + the Iris picker (Stage 5.2).
- Themis routing + eval (Stage 5.3).
