# Kantheon — Project Guide for Claude

> **Status:** living document. Supersedes the former separate Pythia and Golem projects (which are now design folders under `docs/design/pythia/` and `docs/design/golem/`).
>
> **Read with.** [`AGENTS.md`](./AGENTS.md) for tech stack / dev hints / build commands, [`EXAMPLES.md`](./EXAMPLES.md) for canonical code patterns, and [`docs/README.md`](./docs/README.md) for the documentation map.
>
> **Cross-repo authority.** [`/Users/bora/Dev/ai-platform/CLAUDE.md`](../ai-platform/CLAUDE.md) governs the ai-platform repo only (maintenance-only since the 2026-06-12 fork). This file governs everything in kantheon, including the forked services — most conventions are deliberately identical to ai-platform's, since the code was born there.

---

## 1. What this repo is

**Kantheon** ("Kotlin pantheon") is the agent constellation **and — since the 2026-06-12 fork decision — the new platform** that succeeds `ai-platform`. It owns:

- The user-facing frontend (**Iris** — Vue SPA) and the dispatch BFF (**Iris-BFF** — Kotlin/Ktor).
- The question understanding + routing agent (**Themis** — Kotlin/Koog, extracted from `ai-platform/agents/resolver`).
- The autonomous analytical investigator (**Pythia** — Kotlin/Koog + custom DAG executor).
- The per-domain Q&A template (**Golem** — Kotlin/Koog; one pod per Shem: Golem-ERP, Golem-HR, Golem-Sales, …).
- The unified agent + tool registry (**`tools/capabilities-mcp`** — Kotlin/Ktor + MCP SDK).

**The fork (decided 2026-06-12, supersedes the "boundary shift in progress" framing).** ai-platform's intelligent services are **forked** into kantheon — copy-paste, not cut-paste. ai-platform stays exactly as it is, keeps running, and goes maintenance-only; kantheon becomes a **self-contained new platform** with zero cross-repo coupling *to ai-platform* at end state (no ai-platform Maven consumption, no runtime calls in either direction) — the one standing external Maven dep is the `Collite/modeler` TTR toolchain, which is not ai-platform coupling (§7.3). The fork roster, renamed into the pantheon: **Ariadne** (metadata/model graph), **Theseus** (query orchestrator), **Echo** (fuzzy matcher), **Kadmos** (NLP, Python), **Proteus** (translator), **Kyklop** (dispatcher), **Argos** (validator — absorbs sql-security; bearer-role rework), **Prometheus** (LLM gateway, Spring Boot), and the Kyklops **Brontes** (MSSQL worker) + **Steropes** (Polars worker, Python) in `workers/` (joined 2026-06-23 by **Arges**, the Postgres worker — `workers/arges`, active arc, gates Midas P3 S3.2; see `docs/architecture/arges/`).

**Scope widened to "everything forks" (2026-06-13, Bora).** The four "technical" services originally left behind now fork too, so that ai-platform can be switched off with **zero** kantheon relation — **`whois`** (user/role directory + OPA bundle server), **`health`** (cluster health aggregator), **`landing`** (Vue landing page), **`backstage`** (developer portal). These carry **no persona** — they are infrastructure, not constellation citizens — and land under a new top-level **`infra/`** (except `landing` → `frontends/`). Their package roots move off `infra.*` / `com.platform.*` onto `org.tatrman.*`; whois's `erp-sql-common.auth` coupling is cut by extracting a generic `shared/libs/kotlin/keycloak-auth` lib (the legacy ERP-SQL line itself is **not** forked). whois stays off the query data path by default; Argos gains a configurable role source (`bearer` default | `whois` opt-in for ERP role-hierarchy enrichment) — identity stays bearer-only at the theseus-mcp edge, so this is additive, not a D3 revert. This is the fork's **Phase 5** (off the critical path; does not gate Iris). The only thing kantheon does **not** fork is the legacy ERP-SQL line (`erp-sql*`, `sql-*-service`, `sql-metadata`, `sql-validator`, `erp-data-mcp`, `erp-sql-fe`), which is sunsetting. **Authoritative docs:** [`docs/architecture/fork/architecture.md`](./docs/architecture/fork/architecture.md) (§2.1 technical wave) + [`fork/contracts.md`](./docs/architecture/fork/contracts.md) + [`docs/implementation/v1/fork/plan.md`](./docs/implementation/v1/fork/plan.md) (Phase 5). Sequencing: **fork first, then develop** — fork Phases 1–4 precede Iris task-list execution; Phase 5 runs independently any time after Phase 1.

Charon and Metis predate the fork as "migrated services" — in hindsight, the first two citizens of the new platform. Conventions they settled now apply to all arrivals: **proto** package root `org.tatrman.<service>.v1` (*not* `org.tatrman.kantheon.*`, which is reserved for constellation/agent **contracts/protos**) — but the forked Phase 2 services' **Kotlin source roots** are `org.tatrman.kantheon.<service>` (e.g. `org.tatrman.kantheon.ariadne.*`, mirrored by `org.tatrman.kantheon.ariadne.mcp` in the wrapper; locked with Stage 2.1, 2026-06-13 — the reservation governs proto contracts, not service Kotlin packages; the no-proto Phase 5 technical-wave services are the exception, keeping `org.tatrman.<service>` per §4); logic in `services/`/`workers/`, thin MCP wrappers in `tools/`; **Kotlin unless a library moat says otherwise** (Kadmos and Steropes are Python for the same reason Metis is).

### The split, in one paragraph (historical)

ai-platform owned *capabilities*, kantheon owned *agents*. The fork dissolves the split: kantheon now owns both, with the internal layering agents → tools (MCP wrappers) → services/workers. ai-platform remains a running, untouched, maintenance-only system until its own sunset.

---

## 2. The constellation at a glance

| Module                      | Role                                                                                       | Stack                                              |
|-----------------------------|--------------------------------------------------------------------------------------------|----------------------------------------------------|
| `frontends/iris`            | Vue SPA — user-facing chat UI                                                              | Vue 3 + TS + dockview + PrimeVue + Vega-Lite       |
| `agents/iris-bff`           | Dispatch BFF — conversation state, Themis dispatch, stream multiplex back to Vue           | Kotlin + Ktor                                      |
| `agents/themis`             | Question understanding + agent routing (= Resolver post-extraction)                        | Kotlin + Koog¹                                     |
| `agents/pythia`             | Autonomous analytical investigator (RCA, forecast, simulation, cross-domain)               | Kotlin + Koog + custom DAG executor                |
| `agents/golem`              | Per-domain Q&A template — one pod per Shem                                                 | Kotlin + Koog                                      |
| `tools/capabilities-mcp`    | Unified registry of tool + agent capabilities (`kind: TOOL | AGENT`)                       | Kotlin + Ktor + MCP SDK                            |
| `services/charon`           | Arrow data mover — Seaweed / Redis / worker sessions / DB tables (named connections); first migrated platform-grade service | Kotlin + Ktor + gRPC + ADBC; thin `tools/charon-mcp` wrapper |
| `services/metis`            | Model estimation — SARIMAX (auto-order) / Prophet / linear; diagnose / project / simulate; session workspace for series + fitted models; second migrated service | **Python** + statsmodels + prophet + gRPC; thin Kotlin `tools/metis-mcp` wrapper |
| **the fork roster** — `services/{ariadne, theseus, echo, kadmos, proteus, kyklop, argos, prometheus}` + `workers/{brontes, steropes}` | Forked platform line (2026-06-12): model graph (Ariadne), query orchestration + plan cache (Theseus), Czech-aware fuzzy (Echo), NLP (Kadmos), lang ↔ RelNode ↔ SQL (Proteus), worker dispatch (Kyklop), validation + RLS policy (Argos, sql-security folded in), LLM gateway (Prometheus); MSSQL + Polars workers (the Kyklops). Thin wrappers `tools/{ariadne,theseus,echo,kadmos}-mcp`. See `docs/architecture/fork/`. | Kotlin + Ktor + gRPC (Kadmos + Steropes: **Python**; Prometheus: Spring Boot, forked as-is) |
| **the technical wave** — `infra/{whois, health, backstage}` + `frontends/landing` | Forked infrastructure (2026-06-13, fork Phase 5) — **no personas**: `whois` (user/role directory + OPA bundle server; own Postgres; optional Argos role-enrichment source), `health` (cluster health aggregator), `backstage` (developer portal), `landing` (multilingual landing page / service dispatcher, rebranded). Package roots → `org.tatrman.{whois,health}`; the `erp-sql-common.auth` coupling is cut via the extracted `shared/libs/kotlin/keycloak-auth` lib. See `docs/architecture/fork/architecture.md` §2.1. | Kotlin + Ktor (whois/health); Vue 3 + Nginx (landing); Backstage/Node (backstage) |
| `agents/hebe`               | Personal autonomous agent — per-user instances; CLI/web/Telegram channels, cron scheduler + routines, security/receipts, PF4J plugins, MCP server+client. **Four deployment profiles as presets over orthogonal axes** (2026-06-13): `local` (SQLite, BYOK, no platform), `personal` (SQLite, platform client, intermittent — offline tolerance: outbox + catch-up + circuit-breaker + byok fallback), `server` (external PG, file workspace/receipts, always-on), `k8s` (in-cluster PG, ephemeral FS → workspace+receipts in PG). Keycloak OBO for any platform-reaching profile, not k8s alone. Calls **iris-bff** for scheduled constellation turns; registers `non_routable` in capabilities-mcp. Owner of scheduled investigations + out-of-band notifications (PD-2/PD-10). **Integration arc planned 2026-06-12** — `docs/architecture/hebe/` + `docs/implementation/v1/hebe/plan.md`; Gradle merge into root build is arc Phase 1. | Kotlin + Koog + Ktor; SQLite (local/personal) / PG (server/k8s) |

¹ Themis migrates to Koog 0.8.x in Phase 2 Stage 2.3. The earlier "plain coroutines initially, Koog after v1.1" drift was retired by the Stage 2.1 spike (GO, 2026-05-29). See `pythia_framework_choice` memory and `docs/architecture/themis/architecture.md` §6.3.

**Two load-bearing invariants:**

1. **`envelope/v1` is the shared rendering contract.** Every backend agent emits `envelope/v1.Block`s; Iris consumes them. Pythia investigations and Golem turns render through the same chat-bubble pipeline. envelope/v1 imports **only `common/v1`** (`AgentId` moved there 2026-06-12 — a themis/v1 import would drag ai-platform protos into every envelope consumer incl. `envelope-ts`).
2. **`capabilities/v1` is a single registry for both tools and agents** (`kind: TOOL | AGENT`). Themis reads agent manifests for routing; Pythia reads agent + tool manifests for cross-domain plans. One MCP service, one search surface.

**Persistence topology (locked 2026-06-12; kantheon-architecture §7.1):** one internal Kantheon PG instance; one database per agent — `iris`, `pythia`, `golem` (schema per Shem), `midas` (folded in — no separate instance), `hebe` (schema per instance). Forked platform services need no DB at v1 (in-memory from fixtures / in-process caches). **Identity discipline (`kantheon-security.md`):** agents call theseus-mcp (pre-fork: query-mcp) with the user's OBO token, never service identity; caller roles travel as the forwarded bearer; long runs fail closed on token expiry and resume under a fresh bearer. Post-fork, IdentityResolver lives at the theseus-mcp edge and Argos reads roles from the bearer (whois hop removed).

**Naming — the two-tier mythology rule (locked 2026-06-12):** **agents** are the speaking gods (Iris = messenger, Themis = divine order, Pythia = oracle, Hebe = cup-bearer); **platform services** are the older, chthonic, or heroic figures who serve them (Charon, Metis, Ariadne, Theseus, Echo, Kadmos, Proteus, Kyklop, Argos, Prometheus; workers are the Kyklops, individually named, with the dispatcher carrying the genus — Kyklop; **Arges** (Postgres) is an active arc as of 2026-06-23; bench for further workers: Pyrakmon, Halimedes, Euryalos, Elatreus, Trachios). Names use Greek transliterations, not Latin/English (Kadmos not Cadmus, Argos not Argus, Kyklops not Cyclopes). Golem is the one non-Greek persona — kept for the Hebrew/Yiddish inscription/Shem metaphor (each Golem instance is brought to life with a domain manifest).

---

## 3. Repo layout

```
kantheon/
├── agents/
│   ├── iris-bff/                    # Kotlin + Ktor
│   ├── themis/                      # Kotlin + Koog (post-extraction from ai-platform; migrates in Phase 2 Stage 2.3)
│   ├── pythia/                      # Kotlin + Koog
│   ├── golem/                       # Kotlin + Koog (template; one pod per Shem)
│   └── hebe/                        # Kotlin + Koog (moved in 2026-06-12; arc planned — root-build merge is Phase 1)
├── services/
│   ├── charon/                      # Arrow data mover (pre-fork arrival) + tools/charon-mcp
│   ├── metis/                       # model estimation (Python; pre-fork arrival) + tools/metis-mcp
│   ├── ariadne/                     # model graph / metadata (fork: infra/metadata) + tools/ariadne-mcp
│   ├── theseus/                     # query orchestrator + plan cache (fork: services/query-runner) + tools/theseus-mcp
│   ├── echo/                        # Czech-aware fuzzy matcher (fork: services/fuzzy-matcher) + tools/echo-mcp
│   ├── kadmos/                      # NLP foundation (Python; fork: infra/nlp) + tools/kadmos-mcp
│   ├── proteus/                     # lang ↔ RelNode ↔ SQL translator (fork: services/translator)
│   ├── kyklop/                      # worker dispatcher (fork: services/dispatcher)
│   ├── argos/                    # validator + RLS policy (fork: services/validator + infra/sql-security)
│   └── prometheus/                  # LLM gateway (Spring Boot; fork: infra/llm-gateway)
├── workers/
│   ├── brontes/                     # MSSQL worker (fork: workers/mssql)
│   ├── steropes/                    # Polars worker (Python; fork: workers/polars)
│   └── arges/                       # Postgres worker (active arc 2026-06-23; mirrors Brontes + SET LOCAL app.tenant_id RLS; gates Midas P3 S3.2). Pyrakmon/Halimedes/… still reserved
├── infra/                           # off-constellation infrastructure (fork Phase 5; no personas)
│   ├── whois/                       # user/role directory + OPA bundle server (fork: infra/whois); own Postgres
│   ├── health/                      # cluster health aggregator (fork: infra/health)
│   └── backstage/                   # developer portal (fork: infra/backstage; Node/Backstage)
├── frontends/
│   ├── iris/                        # Vue 3 SPA (extracted from golem/frontend/)
│   └── landing/                     # multilingual landing page / dispatcher (fork: frontends/landing; rebranded)
├── tools/
│   └── capabilities-mcp/            # Kotlin + Ktor + MCP SDK
├── shared/
│   ├── proto/
│   │   └── src/main/proto/org/tatrman/kantheon/
│   │       ├── common/v1/               # ResponseMessage stand-in, AgentId, handoff/provenance types
│   │       ├── capabilities/v1/
│   │       ├── envelope/v1/
│   │       ├── themis/v1/
│   │       ├── pythia/v1/
│   │       ├── golem/v1/
│   │       ├── iris/v1/
│   │       └── hebe/v1/                 # lands Hebe arc Phase 4
│   └── libs/
│       ├── kotlin/
│       │   ├── capabilities-client/    # client for tools/capabilities-mcp
│       │   └── envelope-render/        # server-side envelope rendering helpers
│       └── ts/
│           └── envelope-ts/            # TS bindings + render helpers for Iris FE
├── docs/                                # see §5
├── deployment/local/                    # local-infra (Postgres / Wiremock / etc.)
├── gradle/libs.versions.toml            # central version catalog — no hardcoded versions
├── settings.gradle.kts
├── build.gradle.kts
├── justfile                             # mirrors ai-platform's justfile
├── .github/workflows/ci.yml             # init → lint-check → test-all
├── CLAUDE.md                            # this file
├── AGENTS.md                            # tech stack + dev hints
├── EXAMPLES.md                          # canonical code patterns
└── README.md
```

Each Kotlin module follows the standard layout (`src/main/kotlin/`, `src/main/resources/`, `src/test/kotlin/`, `build.gradle.kts`). Deployable services additionally carry `k8s/{base,overlays/local}/` with Kustomize manifests (`imagePullPolicy: Never` in the local overlay). Each agent gets an `eval/` corpus directory and an externalised `prompts/` directory.

---

## 4. Proto packaging

All constellation/agent protos live under `org.tatrman.kantheon.<package>.v1`. **Platform services use `org.tatrman.<service>.v1`** (charon, metis, ariadne, theseus, echo, kadmos, proteus, kyklop, argos, prometheus); cross-service pipeline packages keep functional names: `org.tatrman.{plan,worker,transdsl,dfdsl}.v1`. **These are *proto* packages.** The forked Phase 2 services' **Kotlin source roots** are `org.tatrman.kantheon.<service>` (e.g. `org.tatrman.kantheon.ariadne.*` + `org.tatrman.kantheon.ariadne.mcp`) — locked with Stage 2.1, 2026-06-13: the `org.tatrman.kantheon.*` reservation applies to agent proto **contracts**, not to service implementation packages. (The no-proto **technical-wave** services below are the exception — their Kotlin roots are `org.tatrman.whois.*` / `org.tatrman.health.*`, since there is no proto to anchor a `kantheon.<service>` split against.) **The technical-wave services (Phase 5) follow the same `org.tatrman.<service>.v1` rule for their Kotlin roots even though they carry no proto** — `org.tatrman.whois.*`, `org.tatrman.health.*` (moved off `infra.*` / `com.platform.*`); the extracted auth lib is `org.tatrman.keycloak.auth.*`, whois domain records `org.tatrman.whois.domain.*`. The fork renames every arriving `cz.dfpartner.*` package one-shot at landing (no external consumers of the copies) — the full old→new map is [`docs/architecture/fork/contracts.md`](./docs/architecture/fork/contracts.md) §1. The remaining `cz.dfpartner.nlp.v1` Maven import (Themis) is swapped to `org.tatrman.kadmos.v1` in fork Phase 2; after fork Phase 4 no `cz.dfpartner.*` reference exists, and after Phase 5 no `com.platform.*` / bare `infra.*` root remains either.

| Package           | Contents                                                                                                          | Imports                                                     |
|-------------------|-------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------|
| `common/v1`       | `ResponseMessage` (Rule 6 — **kantheon-local stand-in**: ai-platform's lives in `cz.dfpartner.metadata.v1` and isn't portable; all kantheon protos use this one until ai-platform extracts a domain-free version — cohesion review 2026-06-12, `kantheon-v1.1.md` §1); `AgentId` (**moved from themis/v1 2026-06-12** so envelope imports only common); `HandoffContext` + `EntityBinding` + `ViewProvenance` (PD-1/PD-4 resolution 2026-06-12 — typed cross-agent context handoff; defines `themis_prior_context`); `BlockProvenance` (PD-9 — per-block "how was this computed") | —                                                           |
| `capabilities/v1` | `Capability` sealed union; `ToolCapability`; `AgentCapability` (with `AgentManifest` / `ShemManifest` semantics; `non_routable` flag)  | —                                                           |
| `envelope/v1`     | `FormatEnvelope`; `Block` (text/table/chart/markdown; `provenance` per PD-9); `Chip` (incl. `RoutingPickChip`, `InvestigateChip`); `Drilldown`; `ChartIntent` | `common/v1` (PD-9)                                          |
| `themis/v1`       | `ResolveRequest`/`Response`; `Resolution`; `RoutingDecision`; `AwaitingClarification`; `RefusalWithGaps`; `Profile` | `cz.dfpartner.nlp.v1` (ai-platform), `capabilities/v1`      |
| `pythia/v1`       | `Investigation`; `InvestigationArtifact`; `PlanDag`; `Hypothesis`; `StepRecord`; `Conclusion`                       | `envelope/v1`, `capabilities/v1`                            |
| `golem/v1`        | `GolemRequest`; `ConversationalResponse`; `MiniPlan`; step records                                                 | `envelope/v1`, `capabilities/v1`                            |
| `iris/v1`         | `Session`; `TurnPointer` (PD-1 `current_view`/`applied_context` snapshot); `ChatTurnRequest` (`TurnOrigin`/`origin_ref` — Hebe co-design, landed 2026-06-12); `IrisStreamEvent`; slash-command surface | `envelope/v1`, `common/v1` *(no agent protos — deliberate)* |
| `hebe/v1`         | `Routine`; `RoutineBody` (incl. `kantheon_question`); `RoutineRun`; `DeliveryRecord` — boundary-crossing types only; lands Hebe arc Phase 4 | `common/v1`                                                 |

**Wire policy.** Protobuf is the source of truth for every cross-service contract, even when the wire is REST (not gRPC). REST endpoints are tested against the proto definitions in CI. Hand-rolled JSON shapes that bypass the proto are not permitted.

**Two rules inherited from ai-platform** (see [`AGENTS.md`](./AGENTS.md#wire-format-rules) for details):

- **Rule 6** — every response carries `repeated cz.dfpartner.common.v1.ResponseMessage messages = 99;`. This is the channel for warnings, hints, non-fatal errors. Field number 99 is reserved across the board.
- **Rule 7** — function-call args ride the wire as `string argsJson`, not `google.protobuf.Struct`. camelCase keys.

---

## 5. Documentation structure

Docs are organised into three top-level areas: *what we're building*, *how it's built*, *what we're building right now*. See [`docs/README.md`](./docs/README.md) for the full index.

```
docs/
├── design/                       # what we decided to build, and why
│   ├── README.md
│   ├── iris/                     # FE + BFF design (brainstorming, briefs, design)
│   ├── themis/                   # routing + question-understanding design
│   ├── pythia/                   # analytical investigator design
│   └── golem/                    # per-domain Q&A template design
│
├── architecture/                 # how it is built — technical architecture, contracts, deployment
│   ├── README.md
│   ├── kantheon-architecture.md  # the overall constellation architecture (cross-cutting)
│   ├── kantheon-security.md      # authorization + audit (PD-8 resolution, 2026-06-12; cross-cutting)
│   ├── themis/                   # architecture.md + contracts.md (first arc applied)
│   ├── iris/                     # architecture.md + contracts.md (owns envelope/v1; planned 2026-06-12)
│   ├── pythia/                   # architecture.md + contracts.md (planned 2026-06-12)
│   ├── golem/                    # architecture.md + contracts.md (planned 2026-06-12)
│   ├── charon/                   # architecture.md + contracts.md (planned 2026-06-12; first migrated service)
│   ├── metis/                    # architecture.md + contracts.md (planned 2026-06-12; second migrated service, Python)
│   ├── hebe/                     # architecture.md + contracts.md + standalone-v1-architecture.md (arc planned 2026-06-12)
│   ├── capabilities-mcp/         # design companion
│   ├── fork/                     # the platform fork (architecture.md + contracts.md; decided 2026-06-12)
│   └── midas/                    # Midas arc (brief + architecture + contracts)
│
└── implementation/               # what we're building right now — plans, task lists, status
    ├── README.md
    ├── planning-conventions.md   # task / stage / phase hierarchy (cross-cutting)
    ├── kantheon-v1.1.md          # deferred-items ledger (cross-cutting; "deferred to v1.1/v1.x/v2" decisions land here)
    └── v1/
        ├── aip-v1-*.md           # ai-platform status / gap-closure (cross-cutting prereqs)
        ├── next-steps.md
        ├── fork/                 # plan.md — the platform fork; runs before Iris execution (2026-06-12)
        ├── themis/               # plan.md + per-stage task lists (first arc applied; mid-Stage 2.4; + fork switch-over stage)
        ├── iris/                 # plan.md (next arc — order Iris → Golem → Pythia locked 2026-06-12)
        ├── golem/                # plan.md (second arc; ports new-golem v2)
        ├── pythia/               # plan.md (third arc; unparked 2026-06-12) + v1.5-backlog.md
        ├── charon/               # plan.md (independent arc; charon/v0.3.0 gates Pythia Phase 4)
        ├── metis/                # plan.md (independent arc; metis/v0.3.0 gates Pythia Phase 4 Stage 4.2)
        ├── hebe/                 # plan.md (independent arc P1–P3; P4 gated by iris-bff) + standalone/ M0–M10 history
        └── midas/                # plan.md (parallel arc)
```

**Where to start when returning to the project:**

1. [`docs/architecture/kantheon-architecture.md`](./docs/architecture/kantheon-architecture.md) — the overall constellation. Cross-cutting; covers the five modules, the cross-repo coupling with ai-platform, the routing model, the conversation-state model.
2. [`docs/implementation/planning-conventions.md`](./docs/implementation/planning-conventions.md) — task / stage / phase hierarchy. All planning in this repo follows this.
3. [`docs/implementation/v1/themis/plan.md`](./docs/implementation/v1/themis/plan.md) — the currently active arc (Themis extraction + routing layer).
4. [`docs/implementation/v1/next-steps.md`](./docs/implementation/v1/next-steps.md) — what's queued next.

---

## 6. Planning conventions (summary)

Full spec at [`docs/implementation/planning-conventions.md`](./docs/implementation/planning-conventions.md). Locked 2026-05-15. Applies to all planning in this repo.

### 6.1 Hierarchy — bottom-up

| Unit      | Size                              | Done means                                                                                            |
|-----------|-----------------------------------|-------------------------------------------------------------------------------------------------------|
| **Task**  | atomic; ~½ – 1 day; single concern | the change is committed and tests for that change pass                                                |
| **Stage** | ~6 tasks (5–8 acceptable)         | something **testable** ships — module compiles, endpoint responds, graph passes integration tests     |
| **Phase** | a set of stages                   | something **deployable** ships — a service or capability that runs on local K3s and serves callers    |

### 6.2 The three artefacts before any task list

For each planning arc (the work big enough to take a phase or more), three documents exist **before** task lists are written:

- **`architecture.md`** — shape of the solution (component diagram, module deps, deployment topology, tech stack, build/test/deploy flow, observability). Under `docs/architecture/<agent>/`.
- **`contracts.md`** — all wire contracts (proto packages, MCP tool schemas, REST endpoints, manifest YAMLs, persistence shapes). Source of truth for cross-service boundaries. Under `docs/architecture/<agent>/`.
- **`plan.md`** — phased plan: per-phase deliverable, per-stage goal + ~6 task titles + pre-flight + DONE criteria + dependencies. Under `docs/implementation/v<n>/<agent>/`.

Per-stage task lists land at `docs/implementation/v<n>/<agent>/tasks-p<phase>-s<phase.stage>-<short>.md`. Every task gets a checkbox, TDD-shaped (tests first), explicit library references, examples cited from `EXAMPLES.md` where applicable.

### 6.3 Naming

- Phases: `Phase 1`, `Phase 2`, `Phase 3`, …
- Stages: `Stage 1.1`, `Stage 1.2`, `Stage 2.1`, …
- Tasks: `T1`, `T2`, … within a stage (or `1.1.T1` when context is ambiguous).

**Branches.** `feat/<phase-id>-<stage-id>-<short-name>` — e.g. `feat/p1-s1.2-capabilities-proto`.

**Tags.** Per service, mirroring ai-platform's pattern: `<service-directory-name>/v<major>.<minor>.<patch>` — e.g. `themis/v0.1.0`, `capabilities-mcp/v0.1.0`. Bumps coordinated with `gradle/libs.versions.toml`.

### 6.4 The first arc applied

Themis-in-kantheon is the reference implementation of the convention:

- [`docs/architecture/themis/architecture.md`](./docs/architecture/themis/architecture.md)
- [`docs/architecture/themis/contracts.md`](./docs/architecture/themis/contracts.md)
- [`docs/implementation/v1/themis/plan.md`](./docs/implementation/v1/themis/plan.md)
- per-stage task lists at `docs/implementation/v1/themis/tasks-p<n>-s<n.m>-*.md`

Future arcs (Iris BFF, Pythia, Golem rewrite, capabilities-mcp follow-ups) mirror the same structure.

---

## 7. Cross-repo coupling with ai-platform — dissolved by the fork (pipeline: Phases 1–4 complete 2026-06-17; technical wave: Phase 5 complete 2026-06-24)

**Independence ACHIEVED — pipeline AND technical wave (fork Phases 1–5): zero coupling *to ai-platform*, in both directions.** No ai-platform Maven consumption, no runtime calls either way — the forked constellation builds, tests (full mocked suite), and serves callers with no ai-platform client wired in (independence assertion: `docs/architecture/fork/architecture.md` §9). ai-platform runs untouched as a separate, maintenance-only platform. **The technical wave (§7.4) forked in Phase 5 (2026-06-24):** `whois`/`health`/`landing`/`backstage` are served in-repo, so no non-pipeline path depends on an ai-platform-hosted equivalent. **ai-platform can now be switched off without breaking a single kantheon path — One Kantheon.** The standing third-party `Collite/modeler` Maven dep (§7.3) is **not** ai-platform coupling and does not dissolve.

> **"Zero coupling" means ai-platform, not "zero external Maven deps."** Kantheon retains one **standing third-party Maven dependency**: the TTR toolchain `org.tatrman:ttr-{parser,writer,semantics}` published from the **`Collite/modeler`** GitHub Packages repo (consumed by Ariadne in Stage 2.1 and Proteus in Stage 2.4, exactly as ai-platform consumed it). This is **not** ai-platform coupling and does **not** go away at the fork end-state — it is a normal external dependency that happens to live on a private registry. Consequence: a clean-clone build needs a `gpr.*` PAT **permanently** for the `org.tatrman:*` (modeler) group, independent of the temporary `cz.dfpartner:shared-proto` (Themis `nlp.v1`) residual that is deleted in Stage 2.6. Decision: do **not** vendor the modeler source (Bora, 2026-06-13). See §7.3.

### 7.1 Maven consumption — removed (fork Phase 1, done)

Kantheon **no longer** consumes ai-platform's `shared/proto` + `shared/libs/kotlin/*` (`otel-config`, `fuzzy-common`, `ktor-configurator`, `logging-config`) from GitHub Packages: fork Phase 1 forked these libs in-repo and deleted the GitHub Packages consumption + the `read:packages` PAT bootstrap for the *ai-platform* (`cz.dfpartner`) group. (The `org.tatrman`/modeler PAT requirement stands — §7.3.) There was **no reverse publishing** — ai-platform never consumed from kantheon.

### 7.2 Capabilities registration — removed (fork Phase 4, done)

The ai-platform `query-mcp` PoC heartbeat into `tools/capabilities-mcp` is **decommissioned** (capabilities-mcp README "Fork note", Stage 4.1 T2); only in-repo tools register (warn-and-continue discipline unchanged). Themis's runtime calls to ai-platform nlp-mcp/fuzzy-mcp/llm-gateway were swapped to in-repo Kadmos/Echo/Prometheus in fork Phase 2.

### 7.4 Technical services — removed in fork Phase 5 (done 2026-06-24)

The four "technical" services (`whois`, `health`, `landing`, `backstage`) **forked in fork Phase 5 (2026-06-24)** so that nothing operational ties kantheon to ai-platform: the developer portal, the landing page, the health roll-up, and the identity/role directory are all served in-repo. whois needed two shared libs not covered by Phase 1 — `whois-common` (forked, `org.tatrman.whois.domain`) and `keycloak-auth` (extracted from `erp-sql-common.auth`, which is otherwise not forked, → `org.tatrman.keycloak.auth`). Argos gained an *optional* `whois` role-enrichment source (`argos.roleSource = bearer | whois`, default `bearer`) — identity stays bearer-only at the theseus-mcp edge, so this is additive and does not revert the Phase-3 bearer-roles decision. health's check targets re-point to the kantheon estate (constellation + fabric-infra), landing rebrands to Kantheon (catalog `tech` keys mirror health), backstage re-points its catalog to the pantheon. Phase 5 was off the critical path (did not gate Iris); after it, **ai-platform can be retired.** Tags: `whois`/`health`/`backstage`/v0.1.0, `argos`/v0.2.0 (role source). Docs: `docs/architecture/fork/architecture.md` §2.1, `docs/implementation/v1/fork/plan.md` Phase 5.

### 7.3 Collite/modeler TTR toolchain — a standing third-party dependency (NOT removed)

Distinct from the ai-platform coupling above: kantheon consumes `org.tatrman:ttr-{parser,writer,semantics}` (currently `0.4.0`) from the **`Collite/modeler`** GitHub Packages repo (`settings.gradle.kts` → `ColliteModeler` repository, `includeGroup("org.tatrman")`). Ariadne (Stage 2.1) and Proteus (Stage 2.4) parse/write TTR via these. This is the model-authoring toolchain, owned by a separate team/repo; kantheon is a consumer exactly as ai-platform was. It is **permanent** — it does not dissolve with the fork — and it keeps a `gpr.*` `read:packages` PAT in the bootstrap loop forever (the `org.tatrman` group; orthogonal to the Stage-2.6 removal of the `cz.dfpartner` Themis residual). Not vendored (Bora, 2026-06-13): tracking modeler upstream beats forking a live toolchain. The Phase-1 "clean-clone needs no PAT" goal is therefore scoped to the *ai-platform* coupling only; the modeler PAT requirement stands.

**Also not ai-platform coupling — the testing-harness credentials.** The integration nightly (`integration-nightly.yml`, testing arc Stage 2.3) checks out the private **`Collite/olymp`** harness repo via a CI secret **`OLYMP_GITHUB_TOKEN`** (fine-grained PAT, `Collite/olymp` Contents:Read). This is a *testing* credential for the cross-repo `infra-up/down` loop — distinct from the modeler PAT above and **not** ai-platform coupling. See [`docs/architecture/testing/howto.md`](./docs/architecture/testing/howto.md) §5.1.

---

## 8. Sequencing — fork first, then the constellation arcs

**Inserted 2026-06-12:** the platform fork (Phases 1–4, [`docs/implementation/v1/fork/plan.md`](./docs/implementation/v1/fork/plan.md)) runs **before Iris task-list execution** — "first bring the new guys in, then start the development." Task-list *writing* for Iris/Golem/Pythia may proceed in parallel. The Themis arc takes one added switch-over stage (fork Phase 2 exit). The waypoint list below predates the fork; waypoints 6–8 now build against the in-repo forked services (theseus-mcp / ariadne-mcp, not query-mcp / metadata-mcp).

The path from "today's `golem` repo running" to "Kantheon constellation live" is documented in [`docs/architecture/kantheon-architecture.md`](./docs/architecture/kantheon-architecture.md) §11. Eight waypoints, summarised:

1. ai-platform Resolver Stage 04 closes — Koog graph ships, eval gate passes. **(closed; carry-over from ai-platform)**
2. **Kantheon bootstrap** — repo + build + CI + proto codegen + Maven-from-ai-platform consumption. *(Phase 1 Stage 1.1 of the Themis arc.)*
3. **`tools/capabilities-mcp` lands** — registry running with seed `AgentManifest`/`ShemManifest` fixtures; ai-platform `query-mcp` heartbeats as PoC. *(Phase 1 Stages 1.2–1.4.)*
4. **Resolver → Themis extraction** — `git filter-repo` moves `ai-platform/agents/resolver` into `kantheon/agents/themis`; proto package renames `cz.dfpartner.resolver.v1` → `org.tatrman.kantheon.themis.v1`. *(Phase 2.)*
5. **Routing layer added** — `classifyIntentKind`, `routeToAgent`, four-layer cascade, profiles, `MultiQuestionDetected`, `RefusalWithGaps`, Iris chip flow. *(Phase 3.)*
6. **Iris BFF + FE extraction from golem** — Vue FE and Kotlin/Ktor BFF land in `kantheon/frontends/iris` and `kantheon/agents/iris-bff`. *(Separate arc, design pending.)*
7. **Golem Python → Kotlin + Koog rewrite** — residual Python BE in today's `golem` rewrites to Kotlin. First Shem (`Golem-ERP`) lands. *(Separate arc, design at `docs/design/golem/golem-template-design.md`.)*
8. **Cutover.** Today's `golem` repo retires after Iris + Themis + Golem-ERP are live.

The existing `golem` repo stays running throughout the transition; nothing is deleted prematurely.

---

## 9. Vocabulary canon

Use these terms; avoid the alternatives.

| Use                                | Not                              | Why                                                                                |
|------------------------------------|----------------------------------|------------------------------------------------------------------------------------|
| `query`                            | "named query"                    | Locked in Pythia vocabulary review                                                 |
| `stack` (of patterns)              | "stackable pattern"              | Locked in Pythia vocabulary review                                                 |
| `ShemManifest`                     | "domain manifest" / "DomainSpec" | The proto type is `AgentCapability` with discriminator `agent_kind = AREA_QA` (renamed from `DOMAIN_QA` 2026-06-25) |
| **area** (subject area)            | "domain" (for a subject area)    | Renamed 2026-06-25: "domain" is now a **TTR value concept**, so a Golem's subject area is an **area**. Affects `area_name`/`area_entities`/`area_terminology` (capabilities/v1), `AREA_QA`, role naming `kantheon-area-<area>`, ai-models `shem.areas` / `def area`. A Golem Shem is **assembled** (ai-models def + Ariadne model + overlay + template constants); prompts live with the Shem, the model is just the model. See [`docs/architecture/golem/contracts.md`](./docs/architecture/golem/contracts.md) §6 |
| `RoutingDecision` / `Resolution`   | "routing result" / "parse"       | Match the proto type names exactly                                                 |
| `intent_kind`                      | "intent class" / "intent label"  | Match the proto field name                                                         |
| `capabilities-mcp`                 | "registry-mcp" / "agent-registry"| The module name in `tools/` is `capabilities-mcp`                                  |
| persona names (Theseus, Ariadne, …) | "query-runner", "metadata service" | Post-fork, forked services are referred to by persona; the functional description is apposition ("Theseus — the query orchestrator"). Pre-fork ai-platform modules keep their original names |
| functional names for infra (`whois`, `health`, `landing`, `backstage`) | a forced persona | **Technical wave (2026-06-13):** off-constellation infrastructure keeps its functional name — constellation services and workers get personas, infra does not. |
| `the fork`                         | "the migration"                  | 2026-06-12 reframe: copy-paste, not cut-paste; ai-platform stays untouched          |
| **component** = real-dep/Testcontainers tier; **integration** = full-constellation/cluster tier | "component" for the in-proc mocked `testApplication` specs | Testing arc, ratified 2026-06-19. The old in-proc "component" specs run with **unit** in `test-all`; the arc-level words "component"/"integration" carry the meanings here. See [`docs/architecture/testing/architecture.md`](./docs/architecture/testing/architecture.md) §2.1 |

Filenames: lowercase-hyphenated (`themis-design.md`, not `Themis-Design.md`) — except legacy `Pythia-Brainstorming.md` and `Pythia-v1-Design.md` which keep their original capitalisation.

---

## 10. Notes about Claude's role on this project

Bora is the solo architect and lead developer; treat as a senior engineer. Skip the basics, lead with proposals + trade-offs, expect decisive answers. Two recurring preferences worth holding onto:

- **Rules-first, envelope-first, typed contracts.** When two parallel paths are on the table (Python+Kotlin mix, one-agent-two-shapes, etc.), Bora wants the consolidation move surfaced immediately rather than the hedge.
- **Documentation lives in the repo, not in chat.** Decisions become updates to the architecture / contracts / plan docs. Memory files (`spaces/<id>/memory/`) mirror those, but the repo is the source of truth.

When in doubt, read the architecture doc and the planning conventions before proposing changes.

---

*Doc owner: Bora. Update on every load-bearing decision; revision history via git.*
