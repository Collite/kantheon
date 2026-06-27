# Kantheon — Developer Guide (AGENTS.md)

> **Status:** living document. Read with [`CLAUDE.md`](./CLAUDE.md) (architecture + planning) and [`EXAMPLES.md`](./EXAMPLES.md) (canonical code patterns).
>
> **Supplements** [`/Users/bora/Dev/ai-platform/AGENTS.md`](../ai-platform/AGENTS.md). Kantheon mirrors ai-platform's conventions where they make sense. Departures are called out below.

This is the day-to-day reference for working in the kantheon repo. If you're trying to *understand* the constellation, start at [`CLAUDE.md`](./CLAUDE.md). If you're trying to *write code*, you're in the right place.

---

## 1. Tech stack

### 1.1 Backend (Kotlin)

| Layer                                | Choice                                                                 | Notes                                                                                              |
|--------------------------------------|------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------|
| Language                             | **Kotlin 2.0+**                                                        | Constellation-wide; locked 2026-05-10                                                              |
| JDK                                  | **Temurin 21 LTS**                                                     | Match ai-platform                                                                                  |
| Service framework                    | **Ktor 3.x**                                                           | `KtorServerBootstrap` + `installKtorServerBase()` from the in-repo `shared/libs/kotlin/ktor-configurator` (forked Phase 1) |
| Agent framework                      | **Koog 0.8.x** (or current)                                            | All backend agents incl. Themis (Stage 2.1 spike GO, 2026-05-29; the plain-coroutines hedge is retired) |
| MCP framework                        | **Kotlin MCP SDK** (`io.modelcontextprotocol:kotlin-sdk`) + the MCP/Ktor base from the in-repo `ktor-configurator` | `mcp-server-base` does not exist as an artifact (corrected 2026-06-12)                             |
| Proto / wire                         | **protobuf 3**                                                         | All protos owned in this repo (`org.tatrman.*` / `org.tatrman.kantheon.*`); **no `cz.dfpartner` Maven proto** remains (fork Stage 2.6) |
| Persistence                          | **Postgres** — one Kantheon PG, one database per agent (`iris`, `pythia`, `golem` schema-per-Shem, `midas`, `hebe` schema-per-instance) | kantheon-architecture §7.1 (locked 2026-06-12); none at v1 in Themis/`capabilities-mcp`            |
| Concurrency                          | **kotlinx-coroutines** + structured concurrency                        | No threads-by-hand                                                                                 |
| Serialization                        | **kotlinx-serialization** (JSON) + protobuf-kotlin                     | camelCase keys on the wire (Rule 7)                                                                |
| HTTP client                          | **Ktor client** (CIO engine) + retries via `HttpRequestRetry`           | Per ai-platform pattern                                                                            |
| Logging                              | **SLF4J + Logback** via the in-repo `shared/libs/kotlin/logging-config` (forked Phase 1) | Structured JSON in production overlay                                                              |

### 1.2 Frontend

| Layer            | Choice                                | Notes                                                          |
|------------------|---------------------------------------|----------------------------------------------------------------|
| Framework        | **Vue 3** (Composition API + `<script setup>`) | Extracted from `golem/frontend/`                               |
| Language         | **TypeScript** strict mode             | Generated TS bindings via `just proto`                         |
| Build            | **Vite**                              | Match Iris-FE extraction's tooling                             |
| UI kit           | **PrimeVue Aura** + **dockview**       | Panel/dock UI from current Golem FE                            |
| Charts           | **vega-embed** (Vega-Lite specs)       | Server-rendered `ChartIntent` → client `vega-embed`            |
| State            | **pinia**                              | Per ai-platform Vue convention                                 |

### 1.3 Build, deploy, observability

| Tool                | Choice                                                            | Notes                                                                       |
|---------------------|-------------------------------------------------------------------|-----------------------------------------------------------------------------|
| Build               | **Gradle (Kotlin DSL)** + **`gradle/libs.versions.toml`**         | Versions never hardcoded in module `build.gradle.kts`                       |
| Framework           | **Ktor** for every JVM service — **except `services/prometheus`**  | Prometheus (LLM gateway) is the repo's one **Spring Boot** module (forked as-is from ai-platform `infra/llm-gateway`, Stage 2.5); documented exception |
| Container           | **Jib** for Kotlin services                                       | No Dockerfiles for Kotlin services                                          |
| Orchestration       | **K3s** (local), Kustomize `base/` + `overlays/local/`            | `imagePullPolicy: Never` in local overlay                                   |
| Task runner         | **`just`**                                                        | Recipes mirror ai-platform; see §3                                          |
| Observability       | **OpenTelemetry** via `:shared:libs:kotlin:otel-config` (in-repo) | Backends: Alloy → Tempo / Prometheus-TSDB / Loki (the metrics store, *not* the `services/prometheus` LLM gateway — name clash); per-module wiring map in `docs/architecture/fork/observability.md` |
| CI                  | **GitHub Actions** (`.github/workflows/ci.yml`)                   | `init → lint-check → test-all`. Jib vs Docker auto-detected per module      |

### 1.4 Test stack

| Layer                     | Tooling                                                                                       |
|---------------------------|-----------------------------------------------------------------------------------------------|
| Unit                      | **Kotest** (StringSpec). `*Spec` suffix on test class names; collaborators mocked              |
| Mocking                   | **mockk**                                                                                     |
| HTTP stubs                | **Wiremock** (standalone, in-process)                                                         |
| In-process service tests  | `testApplication { ... }` — full Koog graph, collaborators **mocked**; part of the `test-all` PR gate |
| **Component** (real-dep)  | **Testcontainers** — service vs **real** backing deps, **no cluster**; `componentTest` source set, `just test-component`, every PR+merge. Postgres/Redis/WireMock native; **MSSQL is CI-only** (amd64-only image) |
| **Integration** (cluster) | full forked constellation on the **olymp test cluster** (non-ArgoCD, scripted); `integrationTest` source set, **nightly**. See [`docs/architecture/testing/`](./docs/architecture/testing/) |
| Eval (Themis/Pythia)      | Fixture corpus under `eval/`; harness invokes agent against gold                              |

**Vocabulary (ratified 2026-06-19, testing arc §2.1):** at the arc level "**component**" = the real-dependency Testcontainers tier and "**integration**" = the full-constellation cluster tier. The older in-process `testApplication` specs (once called "component") are mocked and run with **unit** in `test-all` on every PR. Component/integration tiers are the **separate** suite per `planning-conventions.md` §4 — never gating an implementation *stage*.

---

## 2. Bootstrap — first-time setup

```bash
# 1. Install prerequisites (one-time)
brew install just                 # macOS — task runner
brew install --cask temurin@21    # JDK 21 (or your preferred installer)
# protoc is fetched by Gradle's protobuf plugin; no system install needed.
# Docker / K3s already running.

# 2. GitHub Packages auth — a `read:packages` PAT is needed for ONE group:
#    PERMANENT: `org.tatrman:ttr-{parser,writer,semantics}` from the
#    `Collite/modeler` repo (the TTR toolchain; Ariadne/Proteus consume it —
#    a standing third-party dep, NOT ai-platform coupling; see CLAUDE.md §7.3).
#    The ai-platform `cz.dfpartner:*` coupling is fully gone (fork Stage 2.6
#    cut the last residual, Themis's shared-proto/nlp.v1) — no cz.dfpartner
#    Maven coordinate remains. The PAT is still required for the modeler group.
#    Create a classic PAT at https://github.com/settings/tokens with scope: read:packages
#    Write to ~/.gradle/gradle.properties:
cat >> ~/.gradle/gradle.properties <<EOF
gpr.user=<your-github-handle>
gpr.token=ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
EOF
# Confirm ~/.gradle/gradle.properties is in your global .gitignore.

# 3. Clone + init
git clone git@github.com:<org>/kantheon.git
cd kantheon
just init                         # gradle wrapper + uv (frontends) + proto codegen

# 4. Smoke-test the build
just build-kt capabilities-mcp
just test-all
```

If you see Maven auth failures during `just init`, double-check `gpr.user` and `gpr.token` in `~/.gradle/gradle.properties` and that the PAT has `read:packages` (now needed only for the `Collite/modeler` `org.tatrman` group — CLAUDE.md §7.3). See [`docs/implementation/v1/_archive/aip-v1-gap-closure-plan.md`](./docs/implementation/v1/_archive/aip-v1-gap-closure-plan.md) Gap 1 for the historical full recipe.

---

## 3. `just` recipes

Recipes mirror ai-platform. Run `just` with no args to list everything. The canonical set:

```bash
# First-time / after dependency changes
just init                      # bootstrap: gradle wrapper, uv, proto codegen

# Proto codegen — Kotlin + Python + TypeScript outputs
just proto                     # regenerate all bindings

# Kotlin build
just build-kt <module>         # e.g. just build-kt capabilities-mcp
just build-kt themis

# Python build (uv + ruff + pytest lane — Kadmos, Steropes, Metis)
just build-py <path>           # e.g. just build-py services/kadmos
just test-py <path>            # per-module pytest
just lint-py <path>            # ruff

# Deploy to local K3s
just deploy-kt <module>        # Jib-built image, applied via Kustomize; e.g. just deploy-kt themis
just deploy-py <path>          # uv multi-stage image + kubectl apply -k; e.g. just deploy-py services/kadmos

# Whole-fork bring-up (Stage 4.1 T5) — all 15 forked modules in dependency order
just local-infra-up            # ns + MSSQL + seeds (kubectl apply -k deployment/local), waits on rollout
just deploy-fork               # capabilities-mcp → leaf services → proteus → workers → argos → kyklop → theseus → 4 MCP edges

# Test
just test-kt <module>          # per-module
just test-all                  # full Kotest suite
just lint-all                  # ktlint + detekt where wired

# Local infra teardown / logs
just local-infra-logs
just local-infra-down
```

> **Naming note.** The recipe is `just proto`, not `just proto-all`. Earlier draft docs may say `proto-all` — that name was dropped to mirror ai-platform's current state.

---

## 4. Module layout (Kotlin services)

Every Kotlin service follows the same skeleton. Reference: [`EXAMPLES.md`](./EXAMPLES.md) §1 (Ktor bootstrap), §2 (MCP server), §8 (OTel init).

```
<module>/
├── build.gradle.kts                          # alias(libs.plugins.*) — no hardcoded versions
├── src/
│   ├── main/
│   │   ├── kotlin/org/tatrman/kantheon/<module>/
│   │   │   ├── App.kt                         # main(); ≤45 lines per EXAMPLES.md §1b
│   │   │   ├── api/                           # Ktor routes + MCP tools
│   │   │   ├── domain/                        # service-specific domain types
│   │   │   ├── infra/                         # adapters: HTTP clients, MCP clients, DB
│   │   │   └── observability/                 # metrics + tracing helpers
│   │   ├── resources/
│   │   │   ├── application.conf               # HOCON config
│   │   │   ├── logback.xml                    # via logging-config
│   │   │   └── manifests/                     # YAML fixtures (capabilities-mcp only)
│   │   └── proto/                             # (rare) module-private protos
│   └── test/
│       ├── kotlin/org/tatrman/kantheon/<module>/
│       │   └── *Spec.kt                       # Kotest StringSpec
│       └── resources/
│           └── fixtures/
├── prompts/                                   # externalised LLM prompts (agents only)
├── eval/                                      # eval corpus + harness (agents only)
└── k8s/
    ├── base/                                  # Kustomize base
    └── overlays/
        └── local/                             # imagePullPolicy: Never
```

**Conventions:**

- One node / one route / one tool per file. Discoverability beats keystroke economy.
- Package root: `org.tatrman.kantheon.<module>` for Kotlin. Proto root: `org.tatrman.kantheon.<package>.v1` for constellation/agent contracts — **but platform services use `org.tatrman.<service>.v1` and cross-service pipeline packages keep functional names `org.tatrman.{plan,worker,transdsl,dfdsl}.v1`** (see [`CLAUDE.md`](./CLAUDE.md) §4).
- `App.kt` does only bootstrap. Business logic lives below.
- Externalise prompts to `prompts/` (one file per prompt, with the prompt's input/output schema documented in a sibling comment).

### 4.1 Python modules

> Added with fork Phase 1 Stage 1.1 (T4), 2026-06-12. Settles the Python lane conventions for Kadmos, Steropes, Metis (which inherits — see `metis/architecture.md` §"only Python module" amendment in [`docs/architecture/fork/contracts.md`](./docs/architecture/fork/contracts.md) §6).

**Library moat first.** A module is Python only when the ecosystem forces it — spaCy / Stanza / MorphoDiTa for Kadmos, Polars for Steropes, statsmodels / Prophet for Metis. Otherwise, Kotlin.

**Tooling** (every Python module, no exceptions):

| Concern        | Choice                                                                 |
|----------------|------------------------------------------------------------------------|
| Deps / lock    | **uv** — `pyproject.toml` + `uv.lock`; `uv sync` for the module         |
| Imports        | **Generated `shared-proto` package only** (from `just proto-py`): `from org.tatrman.<pkg>.v1 import <file>_pb2`. Never reach into the source tree. |
| Tests          | **pytest** under `<module>/tests/`                                      |
| Lint / format  | **ruff** (`uv run ruff check .` + `ruff format`)                        |
| Types          | **mypy --strict** on `src/`, with `pydantic` models at module boundaries |
| Container      | **uv multi-stage Dockerfile** (Jib is JVM-only). Same pattern as Metis once Metis lands |
| CI             | `just test-py <service>` + `just lint-py <service>` (see `justfile`)    |

**Layout:**

```
<module>/
├── pyproject.toml
├── uv.lock
├── Dockerfile                         # uv multi-stage
├── src/<module>/
│   ├── __init__.py
│   ├── main.py                        # entrypoint; mirrors Kotlin App.kt (thin)
│   ├── api/                           # gRPC / HTTP handlers
│   ├── domain/                        # service-specific domain types
│   ├── infra/                         # adapters (DB, HTTP clients, MCP)
│   └── observability/                 # OTel + logging setup
├── tests/
│   ├── unit/                          # pure functions
│   └── integration/                   # hits Testcontainers / fixtures
└── prompts/                           # (LLM modules only) externalised LLM prompts
```

**Conventions:**

- One module per file at the public surface; service-specific types under `domain/`.
- `main.py` does only bootstrap; domain logic lives below.
- Proto imports come from the **generated** `shared-proto` package — `from org.tatrman.<pkg>.v1 import <file>_pb2` — never by reaching into the source tree.
- Proto types stay generated by `just proto-py`; hand-edits to the generated `org/` tree get clobbered.

**Just recipes** (settled by this stage; see `justfile` for bodies — adapted from ai-platform):

- `py-sync-all` — `uv sync` for every module with a `pyproject.toml`. Dep: `proto-py`.
- `test-py <service>` — `uv run pytest` in the named module. Exits 0 on "no tests collected". Dep: `proto-py`.
- `lint-py <service>` — `uv run ruff check . --fix` in the named module.

> **These conventions settle the lane.** Metis's Phase 1 task list reads them and adopts them verbatim — no second convention to negotiate. If a later stage finds the rules need a tweak, it edits this section first, then propagates.

---

## 5. Gradle — version catalog discipline

Every dependency referenced from a module's `build.gradle.kts` goes through `gradle/libs.versions.toml`. Hardcoded versions in module files are a planning bug.

```toml
# gradle/libs.versions.toml — excerpt
[versions]
kotlin                    = "2.0.21"
ktor                      = "3.0.0"
koog                      = "0.8.0"
kotlin-mcp-sdk            = "0.12.0"
protobuf                  = "3.25.5"
kotest                    = "5.9.1"
mockk                     = "1.13.13"
testcontainers            = "1.20.3"
wiremock                  = "3.9.2"
jib                       = "3.4.4"

# Modeler — TTR parser/writer/semantics, third-party Maven from Collite/modeler
# (the ONE standing external Maven group; NOT ai-platform — CLAUDE.md §7.3)
tatrman-modeler           = "0.4.0"

[libraries]
tatrman-ttr-parser        = { module = "org.tatrman:ttr-parser",    version.ref = "tatrman-modeler" }
tatrman-ttr-writer        = { module = "org.tatrman:ttr-writer",    version.ref = "tatrman-modeler" }
tatrman-ttr-semantics     = { module = "org.tatrman:ttr-semantics", version.ref = "tatrman-modeler" }

ktor-server-core          = { module = "io.ktor:ktor-server-core",  version.ref = "ktor" }
# ... etc

[plugins]
kotlin-jvm                = { id = "org.jetbrains.kotlin.jvm",                  version.ref = "kotlin" }
kotlin-serialization      = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
protobuf                  = { id = "com.google.protobuf",                       version = "0.9.4" }
jib                       = { id = "com.google.cloud.tools.jib",                version.ref = "jib" }
```

The forked shared libs (`otel-config`, `ktor-configurator`, `logging-config`, `fuzzy-common`, …) and `:shared:proto` are **in-repo Gradle modules**, consumed as project dependencies — not Maven artifacts. Inside a module's `build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.jib)
}

dependencies {
    implementation(project(":shared:libs:kotlin:ktor-configurator"))
    implementation(project(":shared:libs:kotlin:otel-config"))
    implementation(project(":shared:proto"))
    implementation(libs.ktor.server.core)
    // Ariadne / Proteus only: the third-party TTR toolchain
    // implementation(libs.tatrman.ttr.parser)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.mockk)
}
```

**No convention plugins.** ai-platform's `CLAUDE.md` describes aspirational `id("my.kotlin-ktor")` plugins; they were never built. Decision 2026-05-12: kantheon stays on direct `alias(libs.plugins.*)` calls.

### 5.1 GitHub Packages — `settings.gradle.kts`

The ai-platform `cz.dfpartner` Maven repo is **gone** (fork Stage 2.6). The only remaining GitHub Packages repo is `Collite/modeler` for the third-party TTR toolchain (`org.tatrman` group) — a standing dep that keeps the `gpr.*` PAT permanently (CLAUDE.md §7.3):

```kotlin
rootProject.name = "kantheon"

pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral() }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        // TTR parser/writer/semantics (third-party, from the `Collite/modeler` repo).
        // NOT ai-platform coupling — see CLAUDE.md §7.3.
        maven {
            name = "ColliteModeler"
            url = uri("https://maven.pkg.github.com/Collite/modeler")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull
                    ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.token").orNull
                    ?: System.getenv("GITHUB_TOKEN")
            }
            content {
                includeGroup("org.tatrman")           // only the modeler toolchain resolves here
            }
        }
    }
}
```

---

## 6. Wire-format rules

Two rules inherited platform-wide from ai-platform. Both apply to every kantheon proto.

### 6.1 Rule 6 — `repeated ResponseMessage messages = 99;` on every response

Every response proto carries:

```proto
import "org/tatrman/kantheon/common/v1/response_message.proto";

message MyResponse {
    // ... payload fields ...
    repeated org.tatrman.kantheon.common.v1.ResponseMessage messages = 99;   // reserved field number
}
```

> The Rule-6 type is the **kantheon-local stand-in** `org.tatrman.kantheon.common.v1.ResponseMessage` (CLAUDE.md §4) — ai-platform's `cz.dfpartner.metadata.v1.ResponseMessage` is not portable and no longer imported anywhere (the last Themis residual was swapped in fork Stage 2.6).

This is the channel for application-layer outcomes — warnings, hints, non-fatal errors, pipeline diagnostics. Field number **99** is reserved across the board.

Used pervasively: `ResolveResponse` carries Czech-localised clarification messages here; `InvestigationArtifact` carries plan warnings; `ConversationalResponse` carries pattern-stack diagnostics.

### 6.2 Rule 7 — function-call args as JSON string

Function-call args (Resolver/Themis outputs, Pythia plan-node specs, etc.) ride the wire as a JSON string, not `google.protobuf.Struct`:

```proto
message ToolCall {
    string capability_id = 1;        // e.g. "theseus.query:v1"
    string args_json     = 2;        // JSON object, camelCase keys, validated against ParamSpec
}
```

**Why:** `Struct` is awkward across JVM/Python/TS bindings; JSON-string round-trips cleanly and lets the caller validate against its own registry's `ParamSpec` schema. **camelCase keys** documented expectation.

### 6.3 MCP outputs use `structuredContent`

All MCP tools return `structuredContent` per the MCP spec — not a hand-rolled JSON in `content[0].text`. See [`EXAMPLES.md`](./EXAMPLES.md) §2.

---

## 7. Observability

OTel is wired at the bottom of every Kotlin service via `createOpenTelemetrySdk()` from the in-repo `:shared:libs:kotlin:otel-config` (see [`EXAMPLES.md`](./EXAMPLES.md) §8); Python services (Kadmos, Steropes) use the Python `otel-config`; **Prometheus** (Spring Boot) wires `opentelemetry-spring-boot-starter` instead. Local backends are Alloy → Tempo (traces) / Prometheus-TSDB (metrics) / Loki (logs). The per-module client-config map + the `run_query` trace tree + the fabric-infra panel/alert wishlist live in [`docs/architecture/fork/observability.md`](./docs/architecture/fork/observability.md).

**Tracing conventions:**

- One span per Koog node (Themis, Pythia, Golem). Span name = node name.
- Cross-service calls propagate `traceparent` — every HTTP/MCP client must respect this. `traceId` is echoed in response protos for correlation.
- Resource attributes: `service.name`, `service.version`, `kantheon.module = "<module>"`.

**Metric naming:**

- Service-scoped prefix: `themis_*`, `pythia_*`, `golem_*`, `capabilities_*`, `iris_*`.
- Outcome label `outcome="resolution|awaiting|refusal"` for terminal classifications.
- Timer histograms in milliseconds, suffix `_ms`.
- Counter suffix `_total`. Gauge suffix `_seconds` / `_size` / etc.

See [`docs/architecture/themis/architecture.md`](./docs/architecture/themis/architecture.md) §10 for the Themis + capabilities-mcp metric catalogue.

---

## 8. Testing — TDD discipline

Per [`docs/implementation/planning-conventions.md`](./docs/implementation/planning-conventions.md) §4, every implementation stage is TDD-shaped: the first tasks write tests, later tasks make them pass.

**Layers** (ladder + CI gate — full model in [`docs/architecture/testing/architecture.md`](./docs/architecture/testing/architecture.md) §2):

1. **Unit (Kotest StringSpec).** Per node, per route, per registry operation, per loader. Mock dependencies (`mockk` for in-process objects, Wiremock for HTTP). PR gate (`test-all`).
2. **In-process service tests (Ktor `testApplication`).** Inter-class boundary inside one service; full Koog graph against **mocked** tools. PR gate (`test-all`). *(Formerly labelled "component" — the arc-level term "component" now means layer 3; see testing arc §2.1.)*
3. **Component — real-dep (Testcontainers).** One service (or a small cluster of services) against its **real** backing deps; **no Kubernetes**. `componentTest` source set, `just test-component`, **every PR + merge**. (Postgres/Redis/WireMock native; **MSSQL CI-only** — amd64.)
4. **Integration — cluster.** Full forked constellation on the **olymp test cluster** (non-ArgoCD, scripted; named contexts; `@RequiresContext`). `integrationTest` source set, driven by olymp `infra-up/down`, **nightly + release tags**.
5. **Eval-gate.** Themis/Pythia evals run in CI against a fixture corpus; gate exits red if quality drops vs the baseline.

Layers 3–4 are the **separate** suite (`planning-conventions.md` §4) — they never gate an implementation *stage*; stages gate on layers 1–2 (mocked) only.

**File naming:** test class `FooSpec`, file `FooSpec.kt`, mirrors `Foo`'s package.

**Wiremock:** prefer fixture JSON files under `src/test/resources/fixtures/<service>/<scenario>.json`; one Wiremock stub per fixture. See [`EXAMPLES.md`](./EXAMPLES.md) §9.

---

## 9. Coding conventions

- **`@file:Suppress("WildcardImport")`** is not used. ktlint enforces no wildcard imports.
- **Sealed types** for finite outcome unions (`NodeResult`, `Resolution | AwaitingClarification | RefusalWithGaps`).
- **Data classes** for value types; immutable by default.
- **Result + error type pairs** — use `Result<T, E>` (or a sealed `Outcome`) for fallible operations; don't throw across service boundaries.
- **Structured concurrency**: every `launch` lives inside a `CoroutineScope` you own. No `GlobalScope`.
- **One public class per file.** Internal helper classes/objects co-locate freely.
- **Package layout:** see §4 — `api/`, `domain/`, `infra/`, `observability/`. Don't invent new top-level packages without a reason.

**Comments:**

- Comment **why**, not what. KDoc on public functions; inline comments sparingly.
- Where a class implements a load-bearing pattern from `EXAMPLES.md`, cite it: `// see EXAMPLES.md §1b`.

---

## 10. Library references — where to look up APIs

When writing code against a complex library, consult one of these **before** guessing the API shape:

- **Locally-cloned read-only mirrors** under `~/Dev/view-only/`:
  - [`~/Dev/view-only/koog`](file:///Users/bora/Dev/view-only/koog) — JetBrains Koog
  - [`~/Dev/view-only/calcite`](file:///Users/bora/Dev/view-only/calcite) — Apache Calcite (SQL validation)
  - [`~/Dev/view-only/kotlin-mcp-sdk`](file:///Users/bora/Dev/view-only/kotlin-mcp-sdk) — Kotlin MCP SDK

  Each has a `graphify-out/` directory you can query for symbol graphs and call sites.

- **context7 MCP** for current-version library docs not covered above (Ktor 3.x, vega-embed, dockview, PrimeVue, etc.).

- **`EXAMPLES.md`** for the canonical kantheon-side pattern. Always check here first — if the pattern is documented, use it verbatim rather than inventing a variant.

- **`ai-platform/EXAMPLES.md`** for the upstream Ktor / serialization / MCP / OTel / Calcite snippets that kantheon mirrors.

---

## 11. Branching, tagging, releasing

- **Branches.** `feat/<phase-id>-<stage-id>-<short-name>` for planned work (e.g. `feat/p1-s1.2-capabilities-proto`). Free-form `chore/`, `fix/`, `docs/` branches otherwise.
- **Commits.** Conventional subject line; body explains *why*.
- **PRs.** Title matches the stage doc; body links to the per-stage task list and ticks off the tasks closed by the PR.
- **Tags.** Per service, `<service-directory-name>/v<major>.<minor>.<patch>` — e.g. `themis/v0.1.0`, `capabilities-mcp/v0.1.0`. The tagged commit bumps the corresponding `[versions]` entry in `gradle/libs.versions.toml`.
- **Releases.** Each tag triggers Jib publish + Kustomize manifest update via CI.

---

## 12. Common gotchas

- **Empty capabilities-mcp at Themis boot.** Themis fail-fasts if `list_agents() == []`. Seed YAML fixtures live in `tools/capabilities-mcp/src/main/resources/manifests/` — runtime registrations supersede them. Don't ship a Themis pod without the fixture loader running first.
- **Trace context propagation.** Every HTTP/MCP client must forward `traceparent`. The shared Ktor-client extension does this — use it; don't roll your own client.
- **Koog vs Ktor version conflict.** The Phase 2 Stage 2.1 spike validated Koog against current Ktor (GO, 2026-05-29); the plain-coroutines hedge is **retired** — all backend agents incl. Themis run on **Koog 0.8.x**. See `pythia_framework_choice` memory.
- **ResponseMessage field number.** Always **99**. Don't reuse it for payload fields. (`reserved 99;` in the proto if you must.)
- **`args_json` not `args`.** Function-call args are JSON-string, not a typed proto. Validate against the caller's `ParamSpec`, don't bake the schema into the proto.
- **Stage doc checkboxes drift.** Verify code state from `git log` and code inspection, not from `tasks-*.md` checkboxes (carried over from ai-platform habit). If you're closing a task, tick the box in the same commit.
- **Maven 401 on first build.** `gpr.user` / `gpr.token` missing or PAT lacks `read:packages`. See §2.
- **ktlint_official + Kotest = run `ktlintFormat` before "done".** The repo uses `ktlint_code_style = ktlint_official` (`.editorconfig`). Kotest's fluent idioms — `StringSpec({ ... })`, `a shouldBe b`, `Builder.newBuilder().setX().build()` chains — violate its `class-signature` and `chain-method-continuation` rules unless formatted. **Always run `./gradlew ktlintFormat` (or `just lint-all` and fix) before claiming a task complete** — newly-authored specs are the usual offender, and the violations are auto-fixable. Do *not* wave off `lint-all` red as an "inherited baseline": as of review-002 (2026-06-13) the repo is ktlint-green, so any red is something you (or a recent change) introduced. Forked ai-platform libs arrive pre-formatted and stay clean.

### 12.1 Forked modules

> Added with fork Phase 1 Stage 1.1 (T2), 2026-06-12. The platform fork ([`docs/architecture/fork/architecture.md`](./docs/architecture/fork/architecture.md)) copies ai-platform code into kantheon; every copied module must record *what it was forked from* in its README so we can always find the original and diff against it.

- **Rule:** every module under `services/`, `workers/`, `tools/`, or `shared/libs/` that originates in ai-platform must carry the **provenance header** at the top of its `README.md`:
  ```markdown
  > **forked-from:** `ai-platform@<sha>` (`<original path>`), tag `kantheon-fork-point`, forked <YYYY-MM-DD>.
  > Maintained independently since the fork; do not assume parity with the ai-platform original.
  ```
- **Authoritative template:** [`docs/implementation/v1/fork/provenance-template.md`](./docs/implementation/v1/fork/provenance-template.md) — copy from there, do not retype. The current fork-point SHA is recorded in [`docs/architecture/fork/architecture.md`](./docs/architecture/fork/architecture.md) §1.
- **Non-forked modules** (capabilities-mcp, themis, pythia, golem, iris-bff, iris FE, envelope-render, capabilities-client, hebe, charon, metis) get *no* provenance header — they were written in kantheon from the start.
- **Drift is accepted.** A bug fix in the ai-platform original does **not** propagate to the kantheon copy. The header keeps the diff reachable; double-maintenance is the cost of the fork (architecture §1).

---

## 13. Reference pointers — where canonical write-ups live

| Topic                                          | Location                                                                                                  |
|------------------------------------------------|-----------------------------------------------------------------------------------------------------------|
| Overall constellation architecture             | [`docs/architecture/kantheon-architecture.md`](./docs/architecture/kantheon-architecture.md)              |
| Planning conventions (task / stage / phase)    | [`docs/implementation/planning-conventions.md`](./docs/implementation/planning-conventions.md)            |
| ai-platform gap closure (Maven, G1, etc.)      | [`docs/implementation/v1/_archive/aip-v1-gap-closure-plan.md`](./docs/implementation/v1/_archive/aip-v1-gap-closure-plan.md) (archived)|
| ai-platform service map                        | `/Users/bora/Dev/ai-platform/CLAUDE.md`                                                                   |
| ai-platform code examples                      | `/Users/bora/Dev/ai-platform/EXAMPLES.md`                                                                 |
| Themis-in-kantheon arc (first applied)         | [`docs/architecture/themis/architecture.md`](./docs/architecture/themis/architecture.md), [`contracts.md`](./docs/architecture/themis/contracts.md), [`plan.md`](./docs/implementation/v1/themis/plan.md) |
| Pythia design (relocated from `~/Dev/pythia/`) | [`docs/design/pythia/Pythia-v1-Design.md`](./docs/design/pythia/Pythia-v1-Design.md)                      |
| Golem template design (Kotlin + Koog rewrite)  | [`docs/design/golem/golem-template-design.md`](./docs/design/golem/golem-template-design.md)              |
| Iris design (FE + BFF)                         | [`docs/design/iris/iris-design.md`](./docs/design/iris/iris-design.md)                                    |
| Themis design (outward shape)                  | [`docs/design/themis/themis-design.md`](./docs/design/themis/themis-design.md)                            |

---

*Doc owner: Bora. Update when the dev stack or conventions change.*
