# Stage 1.1 — Repo bootstrap

> **Phase 1, Stage 1.1.** First task list of the kantheon arc.
>
> **Reads with.** [`tasks-p1-overview.md`](./tasks-p1-overview.md), [`plan.md`](./plan.md) §3.1, [`../../../architecture/themis/architecture.md`](../../../architecture/themis/architecture.md) §3 (module map) + §9 (just recipes), [`../../planning-conventions.md`](../../planning-conventions.md).

## Goal

Bring the kantheon repo skeleton to life. End state: a clean checkout of `/Users/bora/Dev/kantheon` can run `just init && just build-kt _smoke-test && just test-all && just lint-all` all green; CI green on PR merge.

## Pre-flight

- [ ] **Maven publishing** of ai-platform's `shared/proto` and shared libs to GitHub Packages is live (closed via `gap-v1` PR #48 — re-verify with `git log --oneline ai-platform | head -20` showing the v1gap commits).
- [ ] **Developer PAT** with `read:packages` scope written to `~/.gradle/gradle.properties` as `gpr.user=<github-user>` + `gpr.token=ghp_...`. Confirm the file is in `~/.gitignore` globally.
- [ ] **GitHub `<org>`** confirmed by Bora (replaces `<org>` placeholders in `settings.gradle.kts`).
- [ ] **Branch created**: `feat/p1-s1.1-repo-bootstrap` from `main`.
- [ ] **Tooling installed**: JDK 21 (Temurin), Gradle (via wrapper, auto-installs), `just` (Homebrew: `brew install just`), protoc 3.21+ (via Gradle protobuf plugin), Docker / K3s already running.

## Tasks

- [x] **T1 — Top-level skeleton directories and dotfiles.**

  Create:
  ```
  kantheon/
  ├── agents/                # empty for now
  ├── tools/                 # empty for now
  ├── frontends/             # empty for now
  ├── shared/
  │   ├── proto/
  │   └── libs/
  │       └── kotlin/
  ├── gradle/
  ├── .github/workflows/
  └── deployment/local/      # local-infra placeholder (mirror ai-platform later)
  ```

  Add at repo root:
  - `.gitignore` — JVM + Kotlin + Gradle + macOS standards. Crib from `ai-platform/.gitignore`.
  - `.editorconfig` — UTF-8, LF, 4-space indent for `.kt`/`.kts`, 2-space for `.yaml`/`.json`/`.md`.
  - `README.md` — one-paragraph project description + pointer to `docs/README.md`.
  - `CLAUDE.md` — copy ai-platform's `CLAUDE.md` as starting point; **strip ai-platform-specific service inventory**; keep the conventions section + commands; add a banner "Kantheon CLAUDE.md — supplements [ai-platform/CLAUDE.md](../ai-platform/CLAUDE.md) for the constellation side. Use ai-platform's conventions unless overridden here."

  Acceptance: `find . -maxdepth 2 -type d | sort` shows the structure above; `git status` shows no unstaged ignored files.

- [x] **T2 — Gradle wiring (root, version catalog, settings).**

  Create `gradle/libs.versions.toml`. Start from ai-platform's `gradle/libs.versions.toml` (`/Users/bora/Dev/ai-platform/gradle/libs.versions.toml`) and **trim**: keep `kotlin`, `kotlinx-coroutines`, `kotlinx-serialization`, `ktor`, `koog` (0.8.0), `kotlin-mcp-sdk` (0.12.0 — `io.modelcontextprotocol:kotlin-sdk`), `protobuf`, `protobuf-kotlin`, `kotest`, `mockk`, `wiremock`, `slf4j`, `logback`, `kotlin-logging`, `jib` (no `testcontainers` — per the testing policy, planning-conventions.md §4, dev is against mocked unit tests only; Wiremock/MockK are the sanctioned mocks). Add ai-platform Maven-consumed versions per [`contracts.md`](../../../architecture/themis/contracts.md) §9.2:

  ```toml
  [versions]
  ai-platform-proto         = "1.0.0"     # confirm against latest published tag
  ai-platform-otel          = "1.0.0"
  ai-platform-fuzzy-common  = "1.0.0"
  ai-platform-mcp-base      = "1.0.0"
  ai-platform-logging       = "1.0.0"
  ai-platform-ktor-config   = "1.0.0"

  [libraries]
  ai-platform-proto         = { module = "cz.dfpartner:shared-proto",      version.ref = "ai-platform-proto" }
  ai-platform-otel-config   = { module = "cz.dfpartner:otel-config",       version.ref = "ai-platform-otel" }
  ai-platform-fuzzy-common  = { module = "cz.dfpartner:fuzzy-common",      version.ref = "ai-platform-fuzzy-common" }
  ai-platform-mcp-base      = { module = "cz.dfpartner:mcp-server-base",   version.ref = "ai-platform-mcp-base" }
  ai-platform-logging       = { module = "cz.dfpartner:logging-config",    version.ref = "ai-platform-logging" }
  ai-platform-ktor-config   = { module = "cz.dfpartner:ktor-configurator", version.ref = "ai-platform-ktor-config" }
  ```

  Create `settings.gradle.kts` declaring the GitHub Packages repo per `aip-v1-gap-closure-plan.md` Gap 1 "Consumer-side configuration":

  ```kotlin
  rootProject.name = "kantheon"

  pluginManagement {
      repositories { gradlePluginPortal(); mavenCentral() }
  }

  dependencyResolutionManagement {
      repositories {
          mavenCentral()
          maven {
              name = "AiPlatformPackages"
              url = uri("https://maven.pkg.github.com/<org>/ai-platform")  // ← replace <org>
              credentials {
                  username = providers.gradleProperty("gpr.user").orNull
                      ?: System.getenv("GITHUB_ACTOR")
                  password = providers.gradleProperty("gpr.token").orNull
                      ?: System.getenv("GITHUB_TOKEN")
              }
          }
      }
  }
  ```

  Create top-level `build.gradle.kts` — minimal; just sets group + version + applies kotlin plugin in subprojects via `subprojects { ... }` block (see ai-platform's root build.gradle.kts for shape — keep it lean).

  Add the gradle wrapper: `gradle wrapper --gradle-version 8.10` (or current LTS matching ai-platform). Commit `gradle/wrapper/`.

  Acceptance: `./gradlew help --no-daemon` succeeds.

- [x] **T3 — `justfile` recipes.**

  Mirror ai-platform's structure (`/Users/bora/Dev/ai-platform/justfile`). Add these recipes (initial cut — more added in later stages):

  ```just
  default:
      @just --list

  # First-time setup. Verifies PAT, installs gradle deps, regenerates protos.
  init:
      @./scripts/check-pat.sh
      ./gradlew help --no-daemon
      just proto

  # Regenerate proto bindings for all languages.
  proto:
      ./gradlew :shared:proto:assemble
      # py + ts targets added when those packages exist (Stage 1.2)

  # Build one Kotlin service.
  build-kt service:
      ./gradlew :{{service}}:build --no-daemon

  # Run tests for one Kotlin service.
  test-kt service:
      ./gradlew :{{service}}:test --no-daemon

  # Run all Kotlin tests across modules.
  test-all:
      ./gradlew test --no-daemon

  # ktlint.
  lint-all:
      ./gradlew ktlintCheck --no-daemon

  # Deploy one Kotlin service to local K3s via Jib.
  deploy-kt service:
      ./gradlew :{{service}}:jibDockerBuild --no-daemon
      kubectl -n kantheon rollout restart deployment/{{service}}  # added when first manifest lands
  ```

  Also add `scripts/check-pat.sh` — verifies `~/.gradle/gradle.properties` contains `gpr.user` and `gpr.token`; if missing, prints a clear error and the recipe documented in this stage's pre-flight, then exits 1. Reference: shell script template at `/Users/bora/Dev/ai-platform/scripts/` (any similar guard).

  Acceptance: `just --list` shows the recipes; `just init` succeeds with a valid PAT and fails with a clear message without.

- [x] **T4 — CI workflow `.github/workflows/ci.yml`.**

  Mirror ai-platform's `ci.yml` (`/Users/bora/Dev/ai-platform/.github/workflows/ci.yml`) with these differences:
  - Job name: `kantheon-ci`.
  - Cache: gradle (use `actions/setup-java@v4` with `cache: gradle`).
  - Provide `GITHUB_TOKEN` to gradle for Maven resolution (consumer-side; built-in to GH Actions).
  - Steps: `checkout` → `setup-java 21 temurin` → `just init` → `just lint-all` → `just test-all`.

  ```yaml
  name: kantheon-ci
  on:
    push:    { branches: [main] }
    pull_request: { branches: [main] }
  jobs:
    ci:
      runs-on: ubuntu-latest
      permissions:
        contents: read
        packages: read       # required to pull ai-platform Maven artifacts
      steps:
        - uses: actions/checkout@v4
        - uses: actions/setup-java@v4
          with:
            distribution: 'temurin'
            java-version: '21'
            cache: 'gradle'
        - name: just install
          uses: extractions/setup-just@v2
        - name: init
          run: just init
          env:
            GITHUB_ACTOR: ${{ github.actor }}
            GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        - name: lint
          run: just lint-all
        - name: test
          run: just test-all
  ```

  Acceptance: workflow shows up in `.github/workflows/ci.yml` and validates via `actionlint` (`brew install actionlint && actionlint .github/workflows/ci.yml`).

- [x] **T5 — Proto module + codegen.** _(Module + smoke.proto + plugin wired. `:shared:proto:assemble` requires ai-platform Maven access — blocked on pre-flight items below.)_

  Create `shared/proto/build.gradle.kts` with the protobuf-gradle-plugin applied. Generate **Kotlin** outputs only at this stage (Python + TS land in later stages when their consumers exist):

  ```kotlin
  plugins {
      `java-library`
      alias(libs.plugins.protobuf)
      alias(libs.plugins.kotlin.jvm)
  }

  protobuf {
      protoc { artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}" }
      generateProtoTasks {
          all().forEach { it.builtins { id("kotlin") } }
      }
  }

  dependencies {
      api("com.google.protobuf:protobuf-kotlin:${libs.versions.protobuf.get()}")
      api(libs.ai.platform.proto)   // pulls in cz.dfpartner.nlp.v1 etc.
  }
  ```

  Add a placeholder `shared/proto/src/main/proto/org/tatrman/kantheon/_smoke/v1/smoke.proto` with one message:

  ```proto
  syntax = "proto3";
  package org.tatrman.kantheon._smoke.v1;
  message Smoke { string echo = 1; }
  ```

  Add `shared/proto` to `settings.gradle.kts`: `include(":shared:proto")`.

  Acceptance: `./gradlew :shared:proto:assemble` succeeds; generated source appears under `shared/proto/build/generated/`.

- [x] **T6 — `_smoke-test` Ktor service to exercise the build.** _(Diverges from spec: uses self-contained Ktor bootstrap instead of `ai-platform.ktor-config` so the exercise module is buildable without ai-platform Maven access. Replace with the canonical EXAMPLES.md §1b template once pre-flight closes.)_

  Create `tools/_smoke-test/` Ktor service mirroring `ai-platform/EXAMPLES.md` §1b (the canonical `Application.kt` template). Keep under 45 lines.

  - `tools/_smoke-test/build.gradle.kts` — applies `kotlin.jvm` + `ktor`; depends on `libs.ai.platform.ktor.config`, `libs.ai.platform.otel.config`, `libs.ai.platform.logging`, `libs.ktor.server.netty`, `libs.kotlin.logging`.
  - `tools/_smoke-test/src/main/kotlin/org/tatrman/kantheon/smoke/App.kt` — follows EXAMPLES.md §1b template. One `/health` route returns `{"status":"ok"}` via `buildJsonObject { put("status", JsonPrimitive("ok")) }` (per EXAMPLES.md §2a — **never** `mapOf` in `call.respond`).
  - `tools/_smoke-test/src/main/resources/application.conf` — minimal HOCON with port 7099.
  - `tools/_smoke-test/src/test/kotlin/.../HealthSpec.kt` — Kotest `StringSpec` using Ktor `testApplication`:

    ```kotlin
    class HealthSpec : StringSpec({
        "health endpoint returns ok" {
            testApplication {
                application { module(KtorServerConfig.test()) }
                client.get("/health").apply {
                    status shouldBe HttpStatusCode.OK
                    bodyAsText() shouldContain "\"status\":\"ok\""
                }
            }
        }
    })
    ```

  Add `tools/_smoke-test` to `settings.gradle.kts`: `include(":tools:_smoke-test")`.

  Acceptance: `just build-kt _smoke-test` succeeds; `just test-kt _smoke-test` green.

  **Note:** this module is deleted at end of Phase 1 (it was exercise-only). Leave a TODO comment in the App.kt: `// TODO: delete in Phase 1 close — exercise module only`.

## DONE — Stage 1.1

All boxes above checked. Plus:

- [x] `just init && just build-kt _smoke-test && just test-all && just lint-all` all succeed on a clean clone (2026-05-28).
- [ ] CI green on the PR that merges this branch.
- [ ] PR opened with title `[p1-s1.1] kantheon bootstrap` and merged to `main`.

### Open carry-over to Stage 1.2

- `shared/proto/build.gradle.kts` deliberately omits `api(libs.ai.platform.proto)`. The ai-platform tag `shared/v0.0.1` predated `publish.yml`, so `cz.dfpartner:shared-proto:0.0.1` is not in GitHub Packages. **Action:** Bora pushes a fresh `shared/v<x.y.z>` tag from ai-platform to trigger the publish workflow, then this dep is re-added (and Stage 1.2 capabilities.proto can `import "cz/dfpartner/common/v1/response_message.proto";`).
- `ai-platform-mcp-base` removed from `gradle/libs.versions.toml` — no `mcp-server-base` module exists in ai-platform at HEAD. Either to be authored in ai-platform first, or vendored locally during Stage 1.3.

## Library / pattern references

- **ai-platform `EXAMPLES.md` §1a + §1b** — Ktor base installer + canonical `Application.kt`. Strictly copy the shape; under 45 lines.
- **ai-platform `EXAMPLES.md` §2a** — `buildJsonObject` for `call.respond`, never `mapOf`.
- **ai-platform `gap-v1` plan Gap 1 — "Consumer-side configuration"** — the GitHub Packages `settings.gradle.kts` snippet.
- **ai-platform `gradle/libs.versions.toml`** — version catalog template.
- **Koog 0.8.0** at `~/Dev/view-only/koog/` — not used in this stage, but `libs.versions.toml` declares `koog = "0.8.0"` so Stage 2.1 spike has it ready. Module path: `ai.koog:koog-agents`.
- **Kotlin MCP SDK 0.12.0** at `~/Dev/view-only/kotlin-mcp-sdk/` — not used in this stage; declared in catalog so Stage 1.2 has it. Module path: `io.modelcontextprotocol:kotlin-sdk`.

## Out of scope for Stage 1.1

- Capabilities proto schema / capabilities-mcp service — Stage 1.2.
- Python + TS proto codegen — added in Stages 1.3 and beyond as consumers materialise.
- K8s manifests — Stage 1.4.
- `local-infra-up` recipe and Postgres/Wiremock fixtures — added later if a stage needs them; Phase 1 does not require persistence.
