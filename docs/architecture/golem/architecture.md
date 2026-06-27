# Golem — Solution Architecture (kantheon arc, Phases 1–4)

> **Scope.** Kantheon-side architecture for the Golem template rewrite: `shared/libs/kotlin/envelope-render` (constellation format library), the `agents/golem` Kotlin + Koog template, the Golem-ERP Shem, and the cutover from `ai-platform/agents/golem` (new-golem v2, Python + LangGraph).
>
> **Source-of-truth decision (locked 2026-06-12).** The rewrite ports the semantics of **new-golem v2** (`ai-platform/agents/golem/src/agent/graph_v2.py` + `nodes_v2/`) — package context via `metadata.GetModel`, five plan sources, chips-in-format, clarification resume — into the Shem-template shape from [`../../design/golem/golem-template-design.md`](../../design/golem/golem-template-design.md). Where the May design and new-golem v2 disagree, v2 semantics win; the design doc carries a reality note.
>
> **Layering decision (locked 2026-06-12).** Golem **trusts Themis upstream**: `GolemRequest.resolved_intent` carries Themis's `Resolution`; Golem never calls Themis / fuzzy-mcp / nlp-mcp itself. new-golem's `extract_entities` node (Resolver ENTITIES_ONLY) has no Kotlin descendant. Entity clarifications happen upstream; Golem issues its own clarifications only for plan-level gaps (`intent_choice`, `missing_arg`).
>
> **Shem-source decision (CONVERGED 2026-06-25 — supersedes 2026-06-13).** The Shem is **assembled** at boot from the ai-models agent definition (identity + model slice), the Ariadne model (entities/queries/terminology by package), a thin kantheon overlay (`visibility_roles` + optional router/locale overrides), and Golem-template constants — *not* a single hand-curated rich manifest. **The model is just the model (entities/packages/areas); prompts belong to the Shem.** This reverses the 2026-06-13 "Ariadne serves model + prompts": prompts now live with each Shem (`agents/golem/shems/<id>/prompts/`, seeded from `ai-models/prompts/golem`), loaded by `PromptStore` from the mounted Shem, and **Ariadne loses prompt-serving** (`GetPrompts`/`get_prompts` removed). Ariadne instead gains `ResolveArea(area)→packages`. Vocabulary: **"area"**, not "domain"; `agent_kind` is **`AREA_QA`**. Authoritative: [`./contracts.md`](./contracts.md) §6, [`../fork/contracts.md`](../fork/contracts.md) §1.1, §4.1 below.
>
> **Reads with.** [`./contracts.md`](./contracts.md), [`../../implementation/v1/golem/plan.md`](../../implementation/v1/golem/plan.md), [`../iris/contracts.md`](../iris/contracts.md) §1.1 (envelope/v1 — Golem emits it), [`../themis/contracts.md`](../themis/contracts.md) §1.2 (Resolution consumed).

## 1. Architectural goal

Four deployable outcomes, in order:

1. **Phase 1:** `shared/libs/kotlin/envelope-render` — the format catalog (4 render kinds as Koog structured-output tools, retry + deterministic fallback, header inference, Vega-Lite compilation) proven against the G-21…G-25 gotcha fixtures. This is the long-promised **format-catalog Koog spike**, landed as a real library.
2. **Phase 2:** `agents/golem` template core in local K3s — Shem loads at boot, registers in capabilities-mcp, composes + executes mini-plans against query-mcp, persists `golem_turns`.
3. **Phase 3:** Full conversational surface — format pipeline + chips + drilldowns + streaming + typed actions + clarification resume. Envelope parity with new-golem v2 on the diff harness.
4. **Phase 4:** Golem-ERP Shem content + Iris native dispatch + side-by-side soak + cutover; new-golem v2 and the `/v2` adapter in iris-bff retire.

## 2. Tech stack

| Layer | Choice | Why |
|---|---|---|
| Language / framework | **Kotlin 2.x + Ktor 3.2.x + Koog 0.8.x** (umbrella artifact `ai.koog:koog-agents-jvm`) | Constellation-wide; Koog spike GO 2026-05-29; Themis Stage 2.3 established the node-port patterns |
| Graph | **Koog `AIAgentStrategy`** (full graph runtime — unlike Pythia, Golem's flow is graph-shaped) | Mirrors graph_v2's StateGraph; Themis precedent |
| Structured output | **Koog `StructureFixingParser`** + deterministic fallbacks from envelope-render | G-21…G-25 lessons institutionalised |
| Persistence | **Postgres + Flyway + jOOQ** | One idiom across kantheon (iris-bff, midas-core) |
| Platform clients | query-mcp (MCP streamable-HTTP), metadata-mcp/ariadne-mcp (**gRPC** — `GetModel` **+ `GetPrompts`**), llm-gateway (HTTP), capabilities-mcp (register/heartbeat via `capabilities-client`) | Matches new-golem v2's integration points; metadata is gRPC-only since 2026-06; prompts move onto the metadata client 2026-06-13 (no separate prompt git-fetch) |
| Test stack | Kotest + Testcontainers + Wiremock + `agents-test-jvm` mock executor | Themis pattern |
| Container / deploy | Jib; Kustomize `base/` + per-Shem overlays | One image, one pod per Shem |

## 3. Module map

```
kantheon/
├── shared/libs/kotlin/envelope-render/        # Phase 1 — NEW
│   └── src/main/kotlin/org/tatrman/kantheon/envelope/render/
│       ├── catalog/        # RenderCall (4 kinds) + RenderCallCodec + FormatPrompt + KoogStructuredFormatter
│       ├── fallback/        # StructuredFormatter boundary; FormatCatalog (retry + deterministic fallback)
│       ├── tables/          # header inference (port of infer_table_headers), column directives
│       ├── charts/          # ChartIntent → Vega-Lite spec compiler (port of vega_lite_compiler.py,
│       │                    #   reconciled with agents-fe compileVegaLite.ts)
│       └── BlockAssembler.kt # FormatResult.toBlock — stamps Block.provenance (PD-9)
│                            # NOTE (2026-06-18): chip/drilldown builders are NOT here — they are
│                            #   domain-coupled (PackageContext, bindings, ERP column literals) and
│                            #   live in agents/golem/.../chips + format/ instead. envelope-render is generic.
│
├── agents/golem/                               # Phases 2–4 — NEW (template)
│   ├── src/main/kotlin/org/tatrman/kantheon/golem/
│   │   ├── App.kt
│   │   ├── api/            # AnswerRoutes (POST /v1/answer SSE), ActionRoutes, ResumeRoutes, Health
│   │   ├── shem/           # ShemLoader, ShemRegistration (capabilities-client), ShemContext
│   │   ├── context/        # PackageContext — metadata.GetModel(packages, include_drill_map) cache
│   │   ├── prompts/        # PromptStore — ariadne-mcp.get_prompts(agent_id, locale); bundled YAML offline fallback
│   │   ├── graph/          # GolemGraph (Koog AIAgentStrategy) + nodes/ (one per file)
│   │   ├── plan/           # MiniPlan types, PlanComposer (5 sources), PlanValidator, confidence gate
│   │   ├── execution/      # MiniPlanExecutor, QueryClient (query-mcp), HandleTable (in-turn)
│   │   ├── format/         # FormatEnvelopeNode (delegates to envelope-render), drilldown derivation
│   │   ├── chips/          # heuristic + pattern-derived + llm_topup chip builders (relocated from
│   │   │                   #   envelope-render 2026-06-18 — need PackageContext + bindings + ERP literals)
│   │   ├── resume/         # HMAC resume tokens for plan-level clarification (Themis codec pattern)
│   │   ├── persistence/    # TurnsRepository (golem_turns), Flyway
│   │   └── streaming/      # EventEmitter (SSE per contracts §3)
│   ├── src/main/resources/{application.conf}
│   ├── src/test/kotlin/
│   ├── eval/               # ported v2 conversation corpus + diff harness
│   ├── prompts/            # bundled OFFLINE FALLBACK copies (golem-plan-cs.yaml, free-sql-cs.yaml, chip-topup-cs.yaml); live set served by Ariadne get_prompts
│   ├── k8s/{base, overlays/{local, golem-erp}}/
│   └── build.gradle.kts
└── tools/capabilities-mcp/src/main/resources/manifests/agents/golem-erp.yaml   # content fill (Phase 4)
```

## 4. The graph — new-golem v2 → Kotlin mapping

| graph_v2 node (Python) | Kotlin descendant | Delta |
|---|---|---|
| `bootstrap` | `loadShemContext` (boot-time, not per-turn) + `PackageContext` TTL cache + `PromptStore` load | Shem replaces env-driven `GOLEM_ENTITIES`; package list comes from `ShemManifest.preferred_queries`' packages. **Prompts load here too** from `ariadne-mcp.get_prompts` (was a per-pod git-fetch in v2) — model + prompts share the one boot fetch and the one `/refresh` (§4.1) |
| `resolve_selection` | dropped at v1 | one Shem = one curated package set; multi-package pick inside one domain returns as a clarification if genuinely ambiguous |
| `extract_entities` (Resolver ENTITIES_ONLY) | **dropped** | Themis upstream; `resolved_intent.bindings` arrive in the request |
| `classify_and_plan` (5 plan sources; thresholds 0.95/0.85/0.6) | `composePlan` — CHEAP-tier, `task_kind: GOLEM_PLAN`, StructureFixingParser → `MiniPlan` | same source semantics: `PATTERN`, `FREE_SQL`, `AMEND`, `DRILL`, `CLARIFICATION` |
| `pick_plan` (confidence gate) | `gatePlan` — deterministic gate on composer output | thresholds config-driven, defaults carried (0.95 auto / 0.85 with warning / <0.6 clarify) |
| `execute` | `executeMiniPlan` — query-mcp `query` (+ `compile` pre-check for FREE_SQL) | compile-before-run inherited for generated SQL only |
| `format` (kind inference + chips) | `formatEnvelope` — envelope-render catalog/charts + Golem-side chips + drilldowns | identical envelope semantics (golden-sample gate); chips/drilldowns are Golem's (2026-06-18) |
| `update_state` | `persistTurn` — one `golem_turns` row | replaces LangGraph MemorySaver thread state; conversation memory is Iris's job |
| `awaiting_clarification` | `emitClarification` — Golem-issued HMAC token, `pending_clarification` envelope | entity-choice kind never originates here (Themis's) |

Conditional edges mirror v2: `gatePlan → {executeMiniPlan | emitClarification}`, `executeMiniPlan → {formatEnvelope | emitFailure}`.

### 4.1 Shem assembly — model from Ariadne, prompts from the Shem (CONVERGED 2026-06-25)

The Shem is **assembled** at boot from four sources (full table: [`./contracts.md`](./contracts.md) §6):

- **(1) ai-models agent def** (`agents/<id>.yaml`) → identity (`agent_id=golem-<id>`, `display_name=label`) + model slice (`areas`/`packages`/`entities`).
- **(2) Ariadne model** → `GetModel(packages, include_drill_map)` → `ModelBundle` → `PackageContext` (TTL cache) → `area_entities` / `preferred_queries` / `area_terminology`. Packages come from resolving the agent def's `areas` via **`ResolveArea`** (new Ariadne RPC; areas are not in the metadata graph today). Ariadne also serves the area `description`/`tags` to seed `description_for_router`.
- **(3) kantheon overlay** (`agents/golem/shems/<id>/shem.yaml`) → `visibility_roles` + optional router/example/locale overrides.
- **(4) template constants** → `AREA_QA`, `[PROCEDURAL]`, capability refs, `hitl_default`, endpoint.

**Prompts belong to the Shem, not the model (reverses 2026-06-13).** Each Shem carries its own `prompts/{cs,en}/{intent,free-sql,chip-topup}.yaml`, mounted with the Shem and seeded from `ai-models/prompts/golem`. `PromptStore` loads from the **mounted Shem** — the `{{ … }}` contract, in-node substitution, and atomic reload are unchanged; only the source moves. **Ariadne loses prompt-serving entirely** (`GetPrompts` RPC + `get_prompts` tool removed). The model is *just* the model (entities/packages/areas); the prompt is the inscription that animates each Golem and is rightly per-Shem (it can now diverge per area). Contract: [`../fork/contracts.md`](../fork/contracts.md) §1.1.

`/v1/refresh` re-pulls the model from Ariadne; prompt reload is a Shem (ConfigMap) remount.

## 5. Component diagram

```mermaid
flowchart TB
    irisbff["agents/iris-bff"] -->|"POST /v1/answer (SSE) · /v1/action · /v1/resume"| golem
    subgraph golem["agents/golem-ucetnictvi pod (template image + Shem bundle mount)"]
        graph["GolemGraph (Koog)"]
        shem["ShemContext (assembled)"]
        pkg["PackageContext cache"]
        prompts["PromptStore<br/>(from mounted Shem)"]
        render["envelope-render lib"]
    end
    golem -->|register + heartbeat| capmcp["tools/capabilities-mcp"]
    golem -->|"query / compile (MCP)"| querymcp["query-mcp → theseus-mcp"]
    golem -->|"GetModel + ResolveArea (gRPC)"| metamcp["ariadne-mcp (model only)"]
    golem -->|"chat (HTTP)"| llmgw["llm-gateway → prometheus"]
    golem --> pg[("Postgres golem_turns")]
    style golem fill:#dbeafe,stroke:#2563eb
```

Golem does **not** call fuzzy-mcp, nlp-mcp, or resolver/Themis. Dependency failures: query-mcp errors → step failure → FAILED response with partials; metadata unavailable at boot → pod not Ready (PackageContext is load-bearing); capabilities-mcp unreachable → warn-and-continue (standard rule).

## 6. Deployment — one image, one pod per Shem

Per-Shem Kustomize overlay mounts the **Shem bundle** (`agents/golem/shems/<id>/` — `shem.yaml` overlay + `prompts/{cs,en}/`) as a ConfigMap at `/etc/golem/shem/`; `agent_id` derives from the referenced ai-models `id` (`golem-<id>`), served packages from `ResolveArea`/`packages`, prompts from the mounted `prompts/`. v1 ships **Golem-ucetnictvi only** (the accounting Golem — the first Kantheon Golem; further areas are config exercises later; multi-Shem deployment automation stays an open item). Image name `golem`; service `golem-ucetnictvi.kantheon.svc.cluster.local`.

## 7. Observability

```
golem_turns_total{golem_id, plan_source, outcome="done|failed|clarification"}
golem_plan_confidence            (histogram, by plan_source)
golem_plan_gate_total{verdict="auto|warned|clarify"}
golem_step_duration_ms{node_kind}
golem_query_rows                 (histogram)
golem_format_fallback_total{from_kind, to_kind}       ← the G-21 counter
golem_llm_calls_total{task_kind}
golem_resume_total{result="ok|expired|invalid"}
```

Span-per-node tracing (Themis pattern); turn trace joins iris-bff's via propagated `traceparent`.

## 8. Testing strategy

- **Unit:** plan composer (mock executor), gate thresholds, header inference, Vega-Lite compiler (port the Python tests), chip builders, resume codec.
- **Component:** full graph vs Wiremock (query-mcp/meta-mcp/llm-gateway) + Testcontainers PG; clarification round-trip; AMEND/DRILL flows referencing a prior turn.
- **Diff harness (the migration gate):** recorded new-golem v2 conversations (request + envelope fixtures) replayed against Kotlin Golem; envelopes compared field-wise modulo ids/timestamps/agent_version. Target: zero semantic divergences on the curated corpus before cutover.
- Full E2E excluded per planning-conventions §4.

## 9. Risks

| Risk | Mitigation | Stage |
|---|---|---|
| Koog structured output can't reproduce G-21…G-25 behaviour cleanly | Phase 1 is exactly this validation, on fixtures distilled from the Python gotchas, before any template code | P1 |
| v2 envelope parity drifts subtly (formats, chips, drilldowns) | diff harness from Phase 3 on; golden samples shared with the Iris arc | P3–P4 |
| AMEND/DRILL need prior-turn state that LangGraph's thread held implicitly | `current_view` + prior `MiniPlan` persisted on `golem_turns`; AMEND/DRILL resolve against the referenced bubble's stored view (contracts §4) | P2 S2.4 |
| Czech prompt quality regresses in port | prompts ported verbatim first (cs), eval corpus replay before any prompt editing | P3 |
| Routing quality with model-derived (not hand-curated) Shem content | `area_entities`/`preferred_queries`/`area_terminology` come from the Ariadne model; the overlay supplies only the router description + examples; tune on the Themis routing eval | P4 S4.4 |
| metadata GetModel payload growth (ModelBundle) slows boot | PackageContext cached with TTL + `/refresh` endpoint (mirrors new-golem) | P2 |

## 10. References

- new-golem v2 (porting source): `ai-platform/agents/golem/src/agent/graph_v2.py`, `state_v2.py`, `nodes_v2/*`, `chips/*`, `resume_tokens.py`, `package_context.py`, `src/api/v2/models.py`, prompts `src/prompts/*-cs.yaml`. Prompt-fetch being **replaced** (not ported): `src/prompt_source.py` + `src/agent/nodes_v2/prompt_loader.py` (`GOLEM_PROMPTS_GIT_*`) → Ariadne `get_prompts`.
- Shem source repo: `DFPartner/ai-models` (`model-ttr/<package>/`, `prompts/golem/`) — the single repo Ariadne owns; fork contracts §1.1 (`GetPrompts`).
- Gotchas: `golem/docs/v2/v2-overview.md` G-21…G-25 (legacy repo) — fixture source for Phase 1.
- [`../iris/contracts.md`](../iris/contracts.md) §1.1 envelope/v1; [`../themis/contracts.md`](../themis/contracts.md) §1.2 Resolution.
- `~/Dev/view-only/koog` (AIAgentStrategy, StructureFixingParser), `~/Dev/view-only/kotlin-mcp-sdk` (streamable-HTTP client), `ai-platform/EXAMPLES.md` §1/§2/§3/§8.

---

*Architecture owner: Bora. Golem arc planned 2026-06-12. Update on every load-bearing decision.*
