# Golem — Phased Implementation Plan (kantheon arc)

> **Scope.** From "new-golem v2 (Python) serves all turns" to the **Kotlin Golem template + the assembled golem-ucetnictvi Shem, built and green in-repo**. Four phases, eleven stages. The live deploy/route/**soak**/**cutover** legs are **out of this arc** — they ride **Stream T** (the golem live context) + a Bora-owned release checklist (§6 Handoff). The Golem arc (build) closes at Stage 4.4.
>
> **Companions.** [`../../../architecture/golem/architecture.md`](../../../architecture/golem/architecture.md), [`../../../architecture/golem/contracts.md`](../../../architecture/golem/contracts.md), [`../../planning-conventions.md`](../../planning-conventions.md), [`../../../design/golem/golem-template-design.md`](../../../design/golem/golem-template-design.md) (with 2026-06-12 reality note).
>
> **Arc ordering.** Runs after Iris Phase 2 (BFF + FE live on the transitional adapter). Phase 1 (envelope-render) has no Iris dependency and may start any time — it is also the named "format-catalog Koog spike" that has been pending since 2026-05.
>
> **Testing.** Per the testing policy ([`../../planning-conventions.md`](../../planning-conventions.md) §4): plans develop against mocked unit/component tests only (MockK, in-memory / mocked DB fakes, Wiremock, mock executors, fixture turns); live soak and in-cluster e2e are deferred to the separate **Stream T** integration-test suite.
>
> **⚠ Parity refresh (2026-06-24).** This plan was a faithful **2026-06-12 snapshot** of new-golem v2. A review of the current ai-platform Golem found **five feature arcs that landed after the snapshot** — the port targets have moved. The deltas are folded into the affected stages below and enumerated authoritatively in **[§10](#10--2026-06-24-ai-platform-parity-refresh)**. Read §10 before executing S2.4 or S3.2 (the most-changed stages).

## 1. Phase summary

| Phase | Goal — what deploys at the end | Stages | Estimated effort |
|---|---|---|---|
| **Phase 1 — envelope-render lib (format-catalog spike, landed)** | `shared/libs/kotlin/envelope-render` green against G-21…G-25 fixture suite; Vega-Lite compiler at parity with Python + TS implementations. | 1.1 / 1.2 | ~1 week |
| **Phase 2 — template core** | `agents/golem` in local K3s: Shem boot + registration, PackageContext, plan composer + gate, query execution, persistence. Sync answers correct on fixture turns. | 2.1 / 2.2 / 2.3 / 2.4 | ~2–3 weeks |
| **Phase 3 — conversational surface** | Format pipeline + chips + drilldowns + SSE streaming + typed actions + clarification resume. Diff harness running; envelope parity on the curated corpus. | 3.1 / 3.2 / 3.3 | ~2–3 weeks |
| **Phase 4 — Golem-ucetnictvi (assembled Shem) — build** | `domain→area` rename; Ariadne `ResolveArea` + prompt-serving removed; Shem-assembly refactor + prompts-into-Shem; golem-ucetnictvi bundle + Helm Shem-mount ship. **Live deploy/register/route/soak/cutover → Stream T + release (§6 Handoff).** | 4.1 / 4.2 / 4.3 / 4.4 | ~2 weeks |

Critical path: P1 → P2 → P3 → P4. P1 can run before/parallel to the Iris arc.

## 2. Pre-flight — before Phase 2 starts

| Item | Status (2026-06-12) |
|---|---|
| Iris Phase 2 closed (BFF is the dispatch surface to cut over) | pending — Iris arc |
| Themis Phase 3 (`themis/v0.2.0`) — `Resolution.intent_kind` + routing live | pending — Golem trusts Themis upstream; **hard dependency for Phase 4 cutover, soft for Phases 2–3** (fixture Resolutions suffice) |
| envelope/v1 proto locked (Iris Stage 1.1) | pending — P2 imports it |
| **ariadne-mcp** `GetModel(include_drill_map)` reachable from kantheon namespace (the forked model edge, was meta-mcp) | forked — fork Stage 2.1 (`ariadne/v0.1.0`) |
| Ariadne serves the **Shem = model + prompts** — `GetModel` **+ `get_prompts`** (consolidation locked 2026-06-13) | pending — fork Stage 2.1 (`ariadne/v0.1.0`); **hard dependency for Stage 2.2/2.3** (Golem reads prompts from Ariadne, no per-pod git-fetch). Bundled YAML fallback means Golem can build before Ariadne's prompt surface ships, but the live path needs it |
| **theseus-mcp** is the data/query edge Golem's `QueryClient` targets (the forked query path, was query-mcp) — `theseus-mcp/v0.1.0` | **closed 2026-06-17** — fork Phase 3 (Stage 3.5 + 3.6 sign-off); soft for Phases 2–3 (Wiremock platform suffices), hard for the Phase 4 live cutover |
| **theseus-mcp `query` accepts a typed `parameters` map** (`{name: {value, type}}`, declared-type-wins) — the pattern-parametrization rail (§10 Δ1). The Python side relied on the Translator's ParameterBridge to rewrite `{name}→?` | **VERIFIED 2026-06-24 — PARTIAL; a real fork-regression gap gates S2.4.** The typed contract is present end-to-end *except the middle*: the MCP edge accepts `{value,type}` (`tools/theseus-mcp/.../tools/Conversions.kt:57-129`), the proto carries it (`plan/v1/parameters.proto`, `PipelineContext.parameters`), and the **Brontes** worker JDBC-binds by type (`workers/brontes/.../pipeline/ExecutePipeline.kt:262-291`) — **but the forked Proteus orchestrator ignores `context.parameters` during translation.** `ParameterBridge` (`{name}→?`) + `ParameterTyper` (Calcite pre-typing) exist in `shared/libs/kotlin/query-translator/.../params/` and pass unit tests, but the fork dropped the wiring: `orchestrator/Translator.parseToRelNode` has no `parameters` arg and calls bare `Resolve.apply(rel, framework)` (no `preparedSql`), and `services/proteus/.../grpc/TranslatorServiceImpl.kt` has no `toSqlParams`. ai-platform wired all three (`toSqlParams(request.context)` → `parseToRelNode(parameters=…)` → `Resolve.apply(rel, framework, preparedSql)`). **Consequence:** native `?`/positional SQL already binds with correct types; **`{name}`-style named-parameter SQL (the stored-query / Golem-pattern case) reaches the worker un-rewritten.** **→ Proteus wiring-restoration is now Golem S2.4 T7 — the first slice of the stage** (the parametrization rail moved to T8; see the task list + master-plan §7). |
| **Themis RESUME mode** surfaces a `resolver_resume_token` + per-option `resolved_id`/`entity_type_ref` for **pin-by-id** entity resume (§10 Δ3) | **Themis-side contract** — Themis P3 shipped routing, but confirm the RESUME surface exists; hard dependency for Stage 3.2 pin-by-id (text-splice fallback otherwise) |
| G-21…G-25 fixture distillation from legacy `golem/docs/v2` gotchas | Claude task in Stage 1.1 |

## 3. Phase 1 — envelope-render (format-catalog Koog spike, landed as a library)

### Stage 1.1 — render catalog + retry/fallback core

**Goal.** Four render kinds as Koog structured-output tools; retry + deterministic fallback proven on the gotcha fixtures.

**Tasks (7).**
1. Module skeleton `shared/libs/kotlin/envelope-render`; deps: envelope/v1 + common/v1 bindings (or temporary local types if Iris S1.1 not landed — reconciled in T7), Koog umbrella, kotest.
2. Distil G-21…G-25 into fixtures: chart-on-text-heavy-data, tool_choice not honoured, markdown re-parse trap, missing-headers, retry exhaustion (sources: legacy `golem/docs/v2/v2-overview.md`, `format_catalog.py`, `nodes_v2/format.py`).
3. Tests first: `FormatCatalogSpec` against mock executor (`agents-test-jvm`) covering all fixtures incl. fallback paths.
4. Implement `RenderPlaintext/RenderMarkdown/RenderTable/RenderChart` data classes + `StructureFixingParser` wiring + retry policy (max 2) + deterministic table/plaintext fallback.
5. **Typed table column-spec emitter (refreshed 2026-06-24 — was "header inference").** Port the current `_table_details_from_columns` (not the old `infer_table_headers`): emit the `TableDetails` shape `{headers:[{name,title}], columns:{name:{alignment, number, format}}}` — numeric columns → `alignment:"right"`; float columns (`_FLOAT_TYPE_STEMS`) → rounded `number`-format intent `{minimumFractionDigits:2, maximumFractionDigits:2, useGrouping:true}` (+ deprecated `%.2f` `format` fallback); **integers deliberately left raw** (codes/IDs/years, `_NUMERIC_TYPE_STEMS`). `Block.table.content` is the **rows ARRAY** (not `{columns,rows}`) — paging is client-side in the FE. Property tests on stability. *(§10 Δ5.)*
6. **Provenance stamping (PD-9, added 2026-06-12 cohesion review):** envelope-render stamps `Block.provenance` (`common.v1.BlockProvenance`: view + producing agent id; callers add step/hypothesis/model refs; `source_tables` copied from `PipelineContext.used_objects` where available) uniformly at format time; absence renders "provenance unavailable", never an error. Tests on the gotcha fixtures.
7. Reconcile types with envelope/v1 bindings; lint; spike-report section in module README (what Koog handled, what needed work — closes the open question from `golem-template-design.md` §10).

**DONE.** Catalog suite green incl. all five gotcha classes; README spike verdict written.

### Stage 1.2 — charts

**Scope decision (2026-06-18, Bora).** envelope-render stays a **generic** render library: catalog + tables + charts + provenance. The **chip and drilldown builders move out of envelope-render into the Golem arc** (Stage 2.x / Phase 3 `format/`), where their real inputs live — the heuristic chips are domain content (Czech ERP column literals `UCET_OBD`/`UCETNI_HODNOTA`/`KOD_STR`/`KOD_UCTU`), and the pattern-derived chips + drilldowns need PackageContext (pattern catalog, `drill_map`) and current bindings. Putting them in a constellation-wide lib would force Golem-domain types and ERP literals into it. (Originally "charts + chips".)

**Tasks (3).** 1. Tests first: Vega-Lite compiler parity specs — port `vega_lite_compiler.py` test cases + cross-check against agents-fe `compileVegaLite.ts` outputs on shared fixtures. 2. Implement `ChartIntent → Vega-Lite` compiler (line/bar/pie/scatter/area, series, stacking, hide_series); wire into `FormatCatalog`. 3. Tag.

**Relocated to the Golem arc** (was T3/T4): heuristic + pattern-derived + llm_topup chip builders and drilldown derivation (explicit_ttr + auto_overlap) land in `agents/golem/.../chips/` + `.../format/` — see Stage 3.1 (chips/drilldowns attached to the envelope) and the architecture §3 module map.

**Refresh note (2026-06-24).** The **chart-on-compare kind-inference** heuristic (`kind_inference.py`, gated `GOLEM_CHART_ON_COMPARE_ENABLED`) — fire a chart when an AMEND adds a *second value for an axis the prior turn already had*, with precedence **pattern `result_kind_hint` > amend-on-compare > table** — is *Golem-arc* logic (it needs PackageContext + prior bindings), not envelope-render. It lands in **Stage 3.1 (format)**, alongside the relocated chips. envelope-render stays the generic `ChartIntent → Vega-Lite` compiler only. *(§10 Δ5.)*

**Carry-in from the Stage 2.1 review (2026-06-18).** `VegaLiteCompiler.compile`'s `when (intent.kind)` has no `else` — an out-of-domain `kind` (it's a free string) silently yields a `$schema`+`data`-only spec, and `emptySpec`/`visibleY` call `yList.first()` which throws on empty `y`. Both unreachable on valid intents; add an explicit `else -> error(...)` (and an empty-`y` guard) so a future bad `ChartIntent` fails loud rather than rendering blank. Fold into the Stage 1.2 close before tagging.

**DONE.** Tag `envelope-render/v0.1.0`. **Phase 1 DONE.**

## 4. Phase 2 — template core

### Stage 2.1 — golem/v1 proto + module skeleton

**Tasks (6).** 1. Write `golem.proto` per contracts §1; `just proto`; bindings compile. 2. Module skeleton + App.kt + health routes + k8s base. 3. Flyway `golem_turns` per contracts §4 + jOOQ. 4. Tests first: `TurnsRepositorySpec` against an in-memory / mocked DB fake (real-PG fidelity deferred to the separate integration-test suite). 5. Implement repository. 6. CI wiring + lint.

**DONE.** Module compiles; persistence green.

### Stage 2.2 — Shem + PackageContext

**Tasks (8).** 1. Tests first: `ShemLoaderSpec` (valid/invalid YAML, agent_id derivation, discipline-rule lint: correctness fields non-empty when referenced). 2. `ShemLoader` + `ShemContext`. 3. `ShemRegistration` via capabilities-client (register at boot incl. `visibility_roles`, heartbeat, warn-and-continue). 4. **Request admission (PD-8, added 2026-06-12):** bearer validation + `visibility_roles` re-check at every endpoint; 403 + Rule-6 message; tests incl. Themis-bypass path. 5. Tests first: `PackageContextSpec` against recorded `GetModel` ModelBundle fixtures. 6. `PackageContext` (gRPC MetadataClient/AriadneClient, TTL cache, `/v1/refresh`). 7. **`PromptStore` from Ariadne (2026-06-13 consolidation):** tests first (`PromptStoreSpec` — Ariadne `get_prompts` source, atomic swap on reload, **bundled-YAML offline fallback when Ariadne unreachable**, `{{ }}` substitution preserved); implement `PromptStore` on the same AriadneClient (no golem-own git-fetch — `prompt_source.py` is not ported). 8. `/ready` gating on Shem + PackageContext + PromptStore; `/v1/refresh` re-pulls model **and** prompts.

**Refresh note (2026-06-24).** The `PackageContext` dataclasses have grown since the snapshot — the `PackageContextSpec` fixtures (T5) and the model must cover: **`PatternParam.{optional, default_value}`** (drives the parametrization rail), **`PatternQueryDesc.result_kind_hint`** (drives chart-on-compare), and **`DrillSpec.{override_auto, display(locale-tuple)}`**. Also new: **`GOLEM_ENTITIES` individual-entity loading** — `resolve_entity_selectors` accepts short `package.entity` selectors and `prune_to_selection` keeps entities/relations/patterns/drills by a documented closure rule. Decide whether the Shem's `preferred_queries`-derived package list also supports individual-entity pruning, and add a `prune_to_selection` closure spec. *(§10 Δ1/Δ4.)*

**DONE.** Boot sequence green against Wiremock/gRPC fixtures; prompts served from Ariadne with bundled fallback verified; PackageContext carries the new param/hint/drill fields and the selection-prune closure is spec'd.

### Stage 2.3 — plan composer + gate

**Tasks (6).** 1. **Seed prompts (refreshed 2026-06-24).** Port the **current** bundled set — `intent.yaml` / `free-sql.yaml` / `chip-topup.yaml` (language-neutral filenames; locale chosen by subdir, e.g. `prompts/golem/cs`, the `GOLEM_PROMPTS_GIT_SUBDIR` convention) — into `src/main/resources/prompts/` as the **offline fallback**, and stage the same files under `ai-models/prompts/golem/<locale>/` so Ariadne serves them as the live set (source of truth for edits is `ai-models`). Placeholders are `{{ name }}` (regex `\{\{\s*(\w+)\s*\}\}`; unknown left literal). **The intent prompt now renders a `params:` line per catalog row** (`name (type — "label")`) — load-bearing for parametrization; reproduce it. 2. Tests first: `PlanComposerSpec` — five plan sources on fixture Resolutions (pattern hit, free-sql fallback, amend-prior-view, **drill/row-detail-from-selection**, clarify-on-ambiguity); mock executor. 3. `PlanComposer` + `pick_plan` (CHEAP tier, `task_kind: GOLEM_PLAN`, StructureFixingParser → MiniPlan) — incl. **`_detect_amend`** (arg-merge on same-pattern follow-up) and **`_bind_selection_args`** (fill unfilled pattern params from a selected row by case-insensitive column→param match; the `drill` source now also originates from `resolve_selection`/`selection_context`, not only TTR drilldowns). 4. `PlanValidator` (nodes ≤ max, pattern_ids exist in PackageContext, params validate against `ParamSpec`/`optional`/`default_value`, FREE_SQL ⇒ compile_first). 5. `gatePlan` thresholds + losing-plan summary. 6. `GolemGraph` skeleton wiring composed nodes (Koog AIAgentStrategy; Themis node-port pattern). *(§10 Δ1/Δ4.)*

**DONE.** Composer + gate green on all five sources.

### Stage 2.4 — execution + sync answers  *(materially expanded 2026-06-24 — the pattern-parametrization rail + selection landed here)*

> **Refresh (2026-06-24).** This is the most-changed stage. The original 6 tasks describe SQL execution but **predate pattern parametrization entirely** (the snapshot still string-inlined values). The current Golem (a) sends pattern queries as a **verbatim `sql_template` with `{name}` intact** plus a typed `parameters` map and lets Proteus's ParameterBridge rewrite `{name}→?`, (b) adds a **`resolve_selection` graph node** + selection state, and (c) **fails fast** on unfilled params. **The first parity slice is the Proteus parameter-bridge restoration (T3 below) — a verified fork regression that the rail depends on** (see §2 pre-flight + master-plan §7). T0–T6 of the *execution* work are tracked in the task list; the parity tasks (Proteus → rail → guard → selection → tabledetails) are the execution list's T7–T11. The numbering below is the plan narrative.

**Tasks (9).**
1. Tests first: `MiniPlanExecutorSpec` (linear deps, partial failure → FAILED with partials, row caps via `GOLEM_SAMPLE_ROW_LIMIT`=500; `total_rows` exposed).
2. `QueryClient` (**theseus-mcp** MCP streamable-HTTP — the forked query edge; `compile` pre-check path; **user's OBO bearer forwarded on every call — never service identity, PD-8/kantheon-security §2**).
3. **Proteus parameter-bridge restoration (NEW, §10 Δ1 pre-flight — the first parity slice; the rail depends on it).** The forked Proteus orchestrator ignores `context.parameters` during translation, so `{name}` named-parameter SQL is not rewritten to `?` (verified 2026-06-24 — §2 pre-flight). Re-wire it from the ai-platform reference: `orchestrator/Translator.parseToRelNode(parameters=…)` → `ParameterBridge.prepareSqlForCalcite` → `Resolve.apply(rel, framework, preparedSql)`, and add `toSqlParams(request.context)` in `services/proteus/.../grpc/TranslatorServiceImpl.kt`. The leaf classes (`ParameterBridge`/`ParameterTyper`) are already in-repo + unit-tested; this restores their wiring. Tests first (Proteus-level round-trip).
4. **Pattern-parametrization rail (NEW, §10 Δ1 — the biggest single addition; depends on T3).** Port the `aip_pattern_params` Python stdlib lib to a **shared Kotlin lib** (`shared/libs/kotlin/pattern-params`, since Pythia/Wrangler are meant to reuse it): `ParamSpec`, `normaliseArgKeys`, `buildPatternParameters` → `{name: {value, type}}`, `coerceValue`, `typeTag`, with the surface→wire type map (`varchar/char/text→text`, `int/bigint→int`, `decimal/numeric/float/double→float`, `bool→bool`, `date/datetime/timestamp→datetime`). Execute sends the `sql_template` **verbatim** + the typed `parameters` map. *(Replaces the old string-inline path; the deleted `named_query_utils.py`/`pattern_catalog.py`/`typed_action_handler.py` are NOT ported.)*
5. **`PATTERN_PARAM_UNFILLED` fail-fast guard (NEW).** Any residual `{…}` at the execution boundary → `error_code="PATTERN_PARAM_UNFILLED"`, never forwarded. Spec the guard.
6. `MiniPlanExecutor` + in-turn `HandleTable`; emit the typed `TableDetails` (per Stage 1.1 T5).
7. AMEND/DRILL prior-view resolution from `golem_turns` (contracts §4).
8. **`resolve_selection` node + selection state (NEW, §10 Δ4).** Add the graph node (between bootstrap and entity-extraction) that resolves a row-detail `{bubble_id, row_indices}` reference server-side against history into `selected_rows` + a flattened `selection_context` that `pick_plan` binds. Persist enough of `golem_turns` history that a turn's rows are row-resolvable by `bubble_id`. State fields to thread/persist: `selection`/`selected_rows`/`selection_context`, `current_view.total_rows`, `user_id`.
9. `/v1/answer/sync` end-to-end against Wiremock platform; `persistTurn`. Component suite: full graph fixture turns (cs), all sources incl. the parametrized-pattern + row-detail paths.

**Carry-ins from the Stage 2.1 review (2026-06-18).** Resolve while wiring T4/T5 (`persistTurn` + AMEND/DRILL):
- **`findByRequestId` tiebreak.** Both repos order resume-vs-clarification solely by caller-supplied, second-granular `created_at` with no tiebreak — same-second/equal timestamps are non-deterministic. Add a deterministic tiebreak (order by `created_at` then `id`, or a monotonic column) and a same-timestamp test, **or** enforce + document that a resume turn carries a strictly-greater `created_at`. This query is the one resume correctness rides on.
- **Duplicate-id exception parity.** `InMemoryTurnsRepository` throws `IllegalArgumentException` on a dup id; `ExposedTurnsRepository` surfaces the raw Postgres PK-violation type. Once `persistTurn` is the first real caller, normalise the Exposed path to the same exception type (or document on `TurnsRepository` that the type is unspecified across impls).

## 5. Phase 3 — conversational surface

### Stage 3.1 — format pipeline + envelopes

**Tasks (8).** 1. Tests first: `FormatEnvelopeNodeSpec` — kind inference parity with v2 (`format.py` cases), fallback counter, chips + drilldowns attached, **current_view + applied_context echoed (PD-1/PD-4: the bindings the turn actually used)**. 2. `formatEnvelope` node delegating to envelope-render (provenance stamped per Phase 1 T6). 3. Envelope assembly (bubble/turn ids, plan_source/plan_score, warnings, agent_version). 4. **InvestigateChip emission (PD-1):** confidence-gate failure × analytical intent (RCA/FORECAST/SIMULATION) → chip with filled handoff incl. `suggested_focus` (entity cap per iris contracts §1.1); may accompany partial answers; Golem never calls Pythia; tests on gate fixtures. 5. chip_topup LLM call (CHEAP) behind config flag. 6. Golden-sample check: Kotlin envelopes parse via `envelope-ts` (shared CI job with Iris). 7. `/v1/answer` SSE events per contracts §3. 8. Component: echo + chip fields verified end-to-end against the BFF's PD-4 comparison.

**Refresh note (2026-06-24, §10 Δ2/Δ5).** Fold into the tasks above: (a) **kind-inference precedence** `pattern result_kind_hint > amend-on-compare > table` (the chart-on-compare heuristic from Stage 1.2's refresh note, gated `GOLEM_CHART_ON_COMPARE_ENABLED`) belongs in the T1/T2 format node. (b) `CurrentView` now carries **`total_rows`**; `FormatEnvelope` carries **`update_tab_id`** and **`losing_plan_summary`** — assemble them in T3. (c) Chip detail: 4 sources `static | heuristic | pattern_derived | llm_topup`; heuristic chips are the hard-coded `ucetnictvi` column literals (`UCET_OBD`/`UCETNI_HODNOTA`/`KOD_STR`/`KOD_UCTU`, `PATTERN_ROW_CAP`=100; package-coupling debt — note it); pattern-derived chips approximate entity-overlap via parameter-name intersection + a `_UNIVERSAL_SYNONYMS` bridge (since `PatternQueryDesc.entities` is empty); top-up gate `GOLEM_CHIP_MIN_BEFORE_TOPUP`=2; drilldowns `explicit_ttr | auto_overlap` with `override_auto` suppression. (d) **Fixed SSE event-name set**: `node_start`, `node_done`, `plan_pick`, `exec_done`, `envelope` (terminal), `error` (terminal); `: ping` keepalive 5s; initial `: ready`. **Do NOT port any live-log `/log` EventSource** — it was removed in ai-platform Golem Phase 8. (e) **Outbound OTel W3C `traceparent` propagation (NEW)** on every outbound MCP/HTTP + the Themis call — add a task (the §7 observability section only covers the inbound join).

**DONE.** Streamed envelopes render in Iris dev against the native client (behind a flag); event names match the fixed set; no live-log stream; `total_rows`/`update_tab_id`/`losing_plan_summary` present.

### Stage 3.2 — clarification + resume + selection  *(re-scoped 2026-06-24 — "typed actions" was mostly stale)*

> **Refresh (2026-06-24, §10 Δ2/Δ3/Δ4).** The snapshot's "typed actions (sort/filter/paginate/select_row)" **largely no longer exist** in current Golem: paging is **client-side in the FE** over forwarded rows, sort/filter were never built, and `typed_action_handler.py` was **removed**. Only **`select_row`** survives — and as the **row-detail selection path** (`resolve_selection` + `selection_context`, wired in Stage 2.4), not a typed `/v1/action` re-issue. Two clarification/resume features also landed: **`param_fill`** (4th clarification kind) and **pin-by-id entity resume**. Tasks re-scoped accordingly.

**Tasks (6).**
1. Tests first: resume codec specs + clarify→resume round-trip, covering **all four** `PendingClarification.kind` values — `entity_choice | intent_choice | missing_arg | param_fill`.
2. HMAC resume tokens (`resume/`), `emitClarification` node, `/v1/resume`. **`param_fill` resume (NEW):** an unbound required param emits `awaiting_clarification` `kind="param_fill"` (`error_code="PARAM_FILL_CLARIFICATION"`, option `id == param name`); resume re-enters via `resume_param_fill=true` + `bindParamFill(plan, paramName, answer)`, **skipping the cascade** (the `bootstrap→execute` shortcut edge). Spec the cascade-skip.
3. **Pin-by-id entity resume (NEW, §10 Δ3 — Themis contract dep).** Resume no longer splices text + re-resolves; it **pins the chosen entity by `resolved_id`** via a Themis/Resolver RESUME call (`resume_pinned`). The resume token carries `resolver_resume_token`; `ClarificationOption` carries `entity_type_ref` + `resolved_id`. Keep text-splice (`_splice_entity_choice`) only as a fallback. *(Pre-flight: confirm the Themis RESUME surface — see §2.)*
4. **Selection (`select_row`) wiring** — verify the row-detail selection path from Stage 2.4 round-trips end-to-end (selection → `selection_context` → `_bind_selection_args` → drill plan); **drop** sort/filter/paginate from scope (FE owns paging; mark sort/filter as kantheon-net-new beyond v2 parity if ever wanted).
5. iris-bff: native `GolemClient` added to AgentDispatcher (small Iris PR; flag-gated).
6. Component: BFF→Golem clarification (all 4 kinds) + selection round-trips.

**DONE.** Full conversational loop via BFF in dev — entity/intent/missing-arg/param_fill clarifications resume; pin-by-id (with splice fallback); row-detail selection binds.

### Stage 3.3 — diff harness + corpus

**Tasks (5).** 1. Capture corpus: ≥30 recorded v2 conversations across sources/formats (Bora picks representative real sessions; Claude tools the capture). 2. `eval/diff-harness` replay CLI (`just eval-golem`) per contracts §8. 3. First parity run; classify divergences (bug / acceptable / v2-bug). 4. Fix bugs; document acceptable divergences. 5. Tag.

**DONE.** Tag `golem/v0.2.0`; parity report committed. **Phase 3 DONE.**

## 6. Phase 4 — Golem-ucetnictvi (assembled Shem) + cutover

> **Re-scoped 2026-06-25 (converged design).** The first Kantheon Golem is **`golem-ucetnictvi`**
> (accounting), defined from `ai-models/agents/ucetnictvi.yaml`, **not** a hand-written `golem-erp`
> rich Shem. The Shem is **assembled** at boot (ai-models def + Ariadne model + thin overlay +
> template constants); **prompts move into the Shem**; **the model is just the model**; and the
> `domain → area` vocabulary lands (`AREA_QA`, `kantheon-area-<area>`). Authoritative:
> [`../../../architecture/golem/contracts.md`](../../../architecture/golem/contracts.md) §6 +
> [`../../../architecture/golem/architecture.md`](../../../architecture/golem/architecture.md) §4.1.
> The old Stages 4.1–4.3 (hand-fill `golem-erp.yaml` → soak → cutover) are replaced by 4.1–4.4.
>
> **Golem-arc close (2026-06-25).** Phase 4 closes at the **build deliverable** (4.1 rename → 4.2 Ariadne `ResolveArea`/prompt-removal → 4.3 assembly → 4.4 bundle + chart). The live legs (deploy / register / route / latency / **soak** / **cutover**) are **not Golem development** — they moved to **Stream T** (the golem live context) + a **release checklist**. See the Handoff at the end of §6. The former Stages 4.5 (soak) / 4.6 (cutover) task lists were retired into that handoff.

### Stage 4.1 — `domain → area` rename + `AREA_QA` (proto + code + roles)

**Goal.** The vocabulary lands as a typed contract change before the Shem refactor builds on it.

**Tasks (~6).** 1. `capabilities/v1`: rename `domain_name`/`domain_entities`/`domain_terminology` → `area_name`/`area_entities`/`area_terminology`; `AgentKind.DOMAIN_QA` → `AREA_QA`; `just proto`; bindings compile. 2. Sweep Kotlin consumers (`ShemContext`, `ShemLoader`, `ShemYaml`, Themis routing on `agent_kind`, capabilities-client). 3. Security: role convention `kantheon-domain-<shem>` → `kantheon-area-<area>` in `kantheon-security.md` §3.1 + any Shem fixtures; align `GolemErpIntegrationSpec` (`erp_user` → `kantheon-area-accounting`). 4. Doc sweep: CLAUDE.md vocabulary canon, themis/contracts §3.2, fork docs. 5. Fixtures/specs (`ShemTestFixtures`, `ShemLoaderSpec`) updated. 6. Full `test-all` green.

**DONE.** No `domain_*` Shem field or `DOMAIN_QA` reference remains; suite green.

### Stage 4.2 — Ariadne: `ResolveArea` + drop prompt-serving

**Goal.** The model becomes *just* the model; areas are resolvable; prompts leave Ariadne.

**Tasks (~6).** 1. Tests first: `ResolveAreaSpec` — load `model-ttr/areas/*.ttrm`, `ResolveArea(accounting) → [obchodni_doklady, ucetnictvi]` + `description`/`tags`. 2. `ResolveArea` RPC on `AriadneService` (+ ariadne/v1 proto). 3. **Remove `GetPrompts` RPC + `get_prompts` ariadne-mcp tool**; narrow the Git source back to `model-ttr/` (drop `prompts/`). 4. Update `fork/contracts.md` §1.1 (prompts removed; `ResolveArea` added) + ariadne README. 5. Component: area resolution against the in-repo `model-ttr/areas`. 6. Tag `ariadne/v0.x.0`.

**DONE.** Ariadne serves model + `ResolveArea` only; no prompt surface.

### Stage 4.3 — Shem-assembly refactor + prompts-into-Shem

**Goal.** Golem assembles its `AgentCapability` from the four sources; `PromptStore` reads the mounted Shem.

**Tasks (~7).** 1. Tests first: `ShemAssemblySpec` — assemble from a fixture ai-models def + `ResolveArea` + Ariadne model + overlay + template constants → expected `AgentCapability`. 2. New overlay parser for `kantheon.shem/v1` (`source` + `overlay`); replaces the rich `ShemYaml` parse. 3. Golem boot: read agent def + `ResolveArea(areas)` → packages → `PackageContext`; derive `area_entities`/`preferred_queries`/`area_terminology` from the model snapshot. 4. `PromptStore` loads from the mounted Shem (`/etc/golem/shem/prompts/`), not Ariadne; drop the Ariadne prompt client. 5. `description_for_router` precedence: overlay override → area description/tags. 6. `ShemContext` getters map to the assembled capability + `area_*`. 7. Component: full boot against fixtures.

**DONE.** Golem boots an assembled Shem; prompts from the Shem; no Ariadne prompt dependency.

### Stage 4.4 — Golem-ucetnictvi bundle + chart (the build deliverable)

**Scope (re-scoped 2026-06-25 — Golem-arc close).** Stage 4.4 keeps only the in-repo, build-time deliverable; everything requiring a live cluster (deploy / register / route / measure / soak / cutover) **moved to Stream T + the release checklist** — see the Handoff below.

**Tasks (2).** 1. Shem bundle `agents/golem/shems/golem-ucetnictvi/` (`shem.yaml` overlay + `prompts/{cs,en}`, seeded from ai-models); reconcile against the Stage 4.3 `ShemOverlayParser` (`GolemUcetnictviBundleSpec` parses it + checks the prompt set). 2. golem Helm chart Shem-mount: `shem.configMapName` → ConfigMap volume + read-only mount at `GOLEM_SHEM_DIR` (default `/etc/golem/shem`); `helm template` validated. *(The ConfigMap built from the bundle dir + per-context values are the deploying context's — olymp.)*

**DONE.** The assembled golem-ucetnictvi Shem + bundle + chart-mount ship in-repo, green. **Phase 4 DONE — the Golem arc (build) is closed.**

### Handoff — live verification (Stream T) + release/cutover

The cluster/live legs are **not Golem-arc development** — they ride the testing + release flow:

- **Live verification → Stream T** (`docs/implementation/v1/testing/tasks-p3-s3.1-contexts.md`, the golem context — `golem-erp`, to be renamed `golem-ucetnictvi`): deploy from the bundle + readiness; **registration** visible in capabilities-mcp (`AREA_QA`, `kantheon-area-accounting`); **Themis Layer-1 routing** joint test (PROCEDURAL/accounting → golem-ucetnictvi; counter-examples don't); **latency/cost** measured + the Shem's `typical_latency_ms`/`typical_cost_usd` filled; **side-by-side soak** (per-session golem-v2 vs golem-ucetnictvi flag, one-week divergence log via the diff-harness on live traffic, Bora's cs prompt-quality review, perf vs v2, go/no-go). Gated on Ariadne/Prometheus charts + a live Iris on bp-dsk + the native iris-bff `GolemClient`.
- **Release / cutover (Bora-owned, post-soak go):** flip Iris default to `golem-ucetnictvi` + delete the `dispatch/golemv2/` adapter + `iris_v2_threads` (Iris-arc PR); deprecate ai-platform `agents/golem` (separate ai-platform PR; nothing deleted prematurely); fold the reality note into `golem-template-design.md` + update kantheon-architecture §11 waypoints 7–8; tags `ariadne/v0.2.0` + `golem/v0.1.0`/`v0.2.0` + `golem/v1.0.0`. **⚠ Pre-deploy:** migrate the live ai-models repo model to 0.7.0 TTR syntax (`binding:` / `schema binding`) — Ariadne 0.7.0 won't parse the 0.4.0 syntax. Constellation cutover (waypoint 8) for the accounting area completes here.

## 7. Out of scope

- Golem-HR / Golem-Sales Shems (config exercise post-arc); multi-Shem deployment automation (Helm-per-Shem decision deferred until third Shem).
- `DataFrameNode` in mini-plans (Polars Worker path) — Pythia arc proves the Worker integration first; Golem adopts in v1.x. *(Still absent in current v2 — confirmed 2026-06-24.)*
- **Typed sort/filter/paginate actions** — FE-owned (client-side paging over forwarded rows); the v2 `typed_action_handler.py` was removed. Out of scope for parity; only `select_row` survives, as the row-detail **selection** path (Stage 2.4/3.2), not a typed action.
- **Live-log `/log` SSE stream** — removed in ai-platform Golem Phase 8; not ported.
- MCP tool surface on Golem (no consumer at v1).
- Plan-node-level delegation from Pythia (v1.5+ per Pythia design).
- Conceptual-layer (cnc) awareness. *(Still genuinely out of scope — no cnc in v2.)*

## 8. Open questions / Bora-owned content

| Item | Blocking | Note |
|---|---|---|
| Representative v2 conversation picks for the corpus | Stage 3.3 | ~30 sessions |
| English prompt variants (`*-en.yaml`) | none (post-arc) | v2 is cs-only today (OQ-06.B); keep cs-first |
| Plan-threshold re-tune after diff-harness data | Stream T soak | defaults carried from v2 |
| Latency/cost hints (`typical_latency_ms`/`typical_cost_usd`) — measured, not authored | Stream T (golem context) | live measurement; moved out of the Golem arc with the §6 Handoff |

## 9. Phase progression checklist

- [x] **Stage 1.1** — render catalog + fallback core. *(2026-06-18: 33 tests; Koog spike verdict written.)*
- [ ] **Stage 1.2** — charts (chips/drilldowns relocated to the Golem arc, 2026-06-18). **Phase 1 DONE — `envelope-render/v0.1.0`.**
- [x] **Stage 2.1** — proto + skeleton + persistence. *(2026-06-18: golem/v1 proto, agents/golem skeleton, golem_turns; 6 tests.)*
- [x] **Stage 2.2** — Shem + PackageContext. *(2026-06-18: shared ariadne-client extracted; ShemLoader/Context, registration, PD-8 admission, PackageContext, PromptStore + bundled fallback, /ready gate + /v1/refresh; 43 tests.)*
- [x] **Stage 2.3** — composer + gate. *(2026-06-19: shared llm-gateway-client extracted; PlanComposer + MiniPlanCodec, PlanValidator + gatePlan, bundled prompt scaffolds, GolemGraph skeleton; 83 golem tests.)*
- [x] **Stage 2.4** — execution + sync **+ parity slice: T7 Proteus parameter-bridge restoration → T8 rail (Δ1) → T9 guard → T10 `resolve_selection` (Δ4) → T11 TableDetails (Δ5)** done 2026-06-24. New: shared `pattern-params` lib; `QueryParameterDef.optional` + `GolemContext.selection`/`RowSelection` + `TableColumnSpec.number`/`NumberFormatSpec` protos; envelope-ts `number` binding regenerated. **Phase 2 code-complete — `golem/v0.1.0` tag deferred to Bora** (release action).
- [x] **Stage 3.1** — format pipeline + envelopes (2026-06-24): `FormatEnricher` (kind-inference `result_kind_hint`>amend-on-compare>table, chips heuristic/pattern_derived/llm_topup, drilldowns explicit_ttr/auto_overlap, `InvestigateChip`, current_view/losing_plan); `POST /v1/answer` SSE fixed event set + outbound `traceparent`; envelope-ts golden fixture. New proto: `QueryDescriptor.result_kind_hint`. Follow-ups: render-in-Iris-dev (deploy), true per-node SSE streaming (Koog hooks), static chips (Shem-config).
- [x] **Stage 3.2** — clarification + resume + selection (golem-side, 2026-06-24): HMAC `ResumeCodec` + `/v1/resume`; `param_fill` (Δ2) clarification + `nodeStart→execute` cascade-skip resume (+ validator relaxed: missing-required → param_fill, not a validation failure); entity-choice splice fallback + pin-by-id proto fields (`ClarificationOption.entity_type_ref`/`resolved_id`, Δ3 — Resolver RESUME call deferred, cross-arc); selection round-trip (Δ4). **Iris-arc follow-up:** the native `GolemClient` in iris-bff (T5) + BFF→Golem component (T6).
- [x] **Stage 3.3** — diff harness + corpus (2026-06-24): `CorpusReplay` + `EnvelopeDiff` (field-wise, BUG/ACCEPTABLE/V2_BUG classification) + `just eval-golem` + seed corpus + generated parity report (0 bug-class). **`golem/v0.2.0` tag + ≥30-session curated corpus deferred to Bora.** **Phase 3 code-complete — conversational surface at v2 parity (modulo documented intended diffs).**
- [x] **Stage 4.1** — `domain → area` rename + `AREA_QA` (proto + code + roles). *(2026-06-25: capabilities.proto `area_*`/`AREA_QA`; swept golem `shem/`, Themis `RouteToAgentNode`, capabilities-mcp loader/registry/manifests; roles `kantheon-area-<area>`; full `test`+`ktlintCheck` green. Task list: `tasks-p4-s4.1-area-rename.md`.)*
- [x] **Stage 4.2** — Ariadne `ResolveArea` + drop prompt-serving. *(2026-06-25: modeler bumped 0.4.0→0.7.0 for `AreaDef`; `ResolveArea(area)→packages+description+tags` RPC + parsing + `resolve_area` MCP tool; `GetPrompts` removed wholesale across ariadne/ariadne-mcp/ariadne-client. Task list: `tasks-p4-s4.2-ariadne-resolvearea.md`.)*
- [x] **Stage 4.3** — Shem-assembly refactor + prompts-into-Shem. *(2026-06-25: `ShemOverlay`/`ShemAssembler` assemble the AgentCapability from overlay+ResolveArea+GetModel+constants; identity carried in overlay `source` (no ai-models client); `area_*` model-derived; swappable `ShemContext`; rich `ShemYaml`/`ShemLoader` deleted. Task list: `tasks-p4-s4.3-shem-assembly.md`.)*
- [x] **Stage 4.4** — Golem-ucetnictvi bundle + Helm Shem-mount (the build deliverable). *(2026-06-25: `GolemUcetnictviBundleSpec` reconciles the real bundle against `ShemOverlayParser`; golem chart `shem.configMapName` → volume + `GOLEM_SHEM_DIR` mount, `helm template` validated. Task list: `tasks-p4-s4.4-ucetnictvi-deploy.md`.)* **Phase 4 DONE — the Golem arc (build) is closed.**
- **Live verification → Stream T** ([`../testing/tasks-p3-s3.1-contexts.md`](../testing/tasks-p3-s3.1-contexts.md), golem context): deploy + register + route + latency + **soak**. *(was Stages 4.4 T3–T5 / 4.5)*
- **Release / cutover → Bora-owned release checklist** (§6 Handoff): Iris default-flip + `/v2` adapter delete (Iris PR), ai-platform golem deprecation, tags incl. `golem/v1.0.0`. *(was Stage 4.6)*

## 10 — 2026-06-24 ai-platform parity refresh

This plan was written **2026-06-12** against a snapshot of new-golem v2. A review of the **current** ai-platform Golem (`agents/golem/src/agent/{graph_v2,state_v2}.py`, `nodes_v2/*`, `chips/*`, `format/*`, `api/*`, `prompts/*`, and the shared lib `shared/libs/python/aip_pattern_params/`) found **five post-snapshot feature arcs**. Each is folded into the stages above; this section is the authoritative index. Source feature plans: ai-platform `docs/history/new golem 02/feature-pattern-parametrization-plan.md` + `feature-clarification-resume-plan.md`.

| Δ | Feature arc (post-2026-06-12) | What changed | Lands in |
|---|---|---|---|
| **Δ1** | **Pattern parametrization** *(biggest)* | Pattern queries no longer string-inline values. `execute` sends the `sql_template` **verbatim with `{name}` intact** + a typed `parameters` map `{name:{value,type}}`; **Theseus's ParameterBridge** rewrites `{name}→?`. Shared lib `aip_pattern_params` (`ParamSpec`, `normalise_arg_keys`, `build_pattern_parameters`, `coerce_value`, `type_tag`; surface→wire type map) — **port to a shared Kotlin lib** (`shared/libs/kotlin/pattern-params`). `PatternParam` gained `optional`+`default_value`; `PatternQueryDesc` gained `result_kind_hint`. **`PATTERN_PARAM_UNFILLED` fail-fast guard.** Old `named_query_utils.py`/`pattern_catalog.py`/`typed_action_handler.py` **deleted — not ported.** | **S2.4** — **T7 Proteus parameter-bridge restoration (first slice)** → T8 rail + T9 guard; S2.2 (PackageContext fields), S2.3 (`params:` prompt line) · **Verified gap (§2):** the Proteus wiring is the regression T7 fixes |
| **Δ2** | **`param_fill` missing-param clarification/resume** | Unbound required param → `awaiting_clarification` `kind="param_fill"` (option id = param name); resume via `resume_param_fill=true` + `bind_param_fill`, **skipping the cascade** (`bootstrap→execute` shortcut edge). `PendingClarification.kind` is now a **4-value** Literal: `entity_choice \| intent_choice \| missing_arg \| param_fill`. | **S3.2** (T1/T2) |
| **Δ3** | **Pin-by-id entity resume** | Resume **pins the chosen entity by `resolved_id`** via a Themis/Resolver RESUME call (`resume_pinned`) instead of text-splice + re-resolve. Resume token gains `resolver_resume_token`; `ClarificationOption` gains `entity_type_ref`+`resolved_id`. Text-splice retained as fallback. **Themis contract dependency** (RESUME mode + surfaced token). | **S3.2** (T3) · **Pre-flight:** Themis RESUME surface (§2) |
| **Δ4** | **Row-detail selection** | New **`resolve_selection` graph node** (bootstrap→**resolve_selection**→extract_entities) resolves a `{bubble_id, row_indices}` reference server-side into `selected_rows`+`selection_context`; `pick_plan` binds unfilled pattern params from the selected row (`_bind_selection_args`). New state: `selection`/`selected_rows`/`selection_context`, `current_view.total_rows`, `user_id`. Also `GOLEM_ENTITIES` individual-entity loading (`prune_to_selection`, short `package.entity` selectors). | **S2.4** (node+state), S2.3 (selection-arg binding), S2.2 (prune closure) |
| **Δ5** | **Typed table formatting + client-side paging** | `format.table` carries `TableDetails` `{headers:[{name,title}], columns:{name:{alignment,number,format}}}` — numeric→right, float→rounded `Intl.NumberFormat` intent (+deprecated `%.2f`), **integers raw**; `content` is the **rows ARRAY**. **Paging is client-side in the FE**; golem bounds the fetch (`GOLEM_SAMPLE_ROW_LIMIT`=500) + exposes `total_rows`. **Chart-on-compare** kind-inference (`GOLEM_CHART_ON_COMPARE_ENABLED`), precedence pattern-hint > amend-on-compare > table. | **S1.1** (column-spec emitter), S1.2 note + **S3.1** (kind-inference) |

**Also folded in (cross-cutting):**

- **Edge auth** (`api/security.py`, v2 Phase 7): `/v2` has `require_user`, always records `X-User-ID`, rejects `401 / AUTH_REQUIRED` when `SECURITY_ENABLED=true` — a concrete realization of the PD-8 admission task (S2.2 T4). Capture the header/error-code contract.
- **Outbound OTel W3C `traceparent` propagation** on every outbound MCP/HTTP + the Themis call — **new task in S3.1** (the §7-architecture observability section only covered the inbound join).
- **Removed (do not port):** the live-log `/log` EventSource (Phase 8).

**Stage-impact summary:** S2.4 expanded most (Δ1 rail + Δ4 selection — 6→8 tasks); S3.2 re-scoped (typed actions mostly dropped; Δ2+Δ3 added); S1.1/S2.2/S2.3/S3.1 amended; §7 out-of-scope tightened. Per-stage task-list files (`tasks-p2-s2.4-execution-sync.md`, `tasks-p3-s3.2-*.md`) carry the same deltas.

---

*Plan owner: Bora. Golem arc planned 2026-06-12; **parity-refreshed against current ai-platform Golem 2026-06-24**. Per-stage task lists at `docs/implementation/v1/golem/tasks-p<n>-s<n.m>-*.md`.*
