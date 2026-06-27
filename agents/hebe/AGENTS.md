# Agent Instructions & Repository Context

This is a repository where we develop a Kotlin based autonomous agent.
We are developing according to the Plan. The Plan can be found in `docs/plan` folder.
You MUST read the following files before implementing:
- `docs/plan/v1-architecture.md`
- `docs/plan/v1-tasks.md`
These will give you the context.

Detailed task lists are then found in `docs/plan/tasks/Mx-*.md`. We develop by the Mx phases. After each phase you must pause and ask for a review. 
We keep all development for the v1.0 (M0 - M10 phases) in one branch, but we review after each phase.


## 1. Build
**Do not infer build steps.** Follow the strict conventions below.

- **Root Build Tool:** Gradle 9 (Kotlin DSL) + `just` (Command Runner)
- **Primary Language:** Kotlin 2.3.0 (JVM 21)
- **Infrastructure:** Kubernetes (K3s Local, Azure AKS Prod), ArgoCD

## 2. Directory Structure
- `gradle/libs.versions.toml`: Central Version Catalog. **All dependency versions must be defined here.**
- `docs` : Documentation. `docs/plan` - the architecture and specs, `docs/plan/tasks` - task lists for each phase.
- `generated`: folder for generated code.
- `modules`: Kotlin modules for the agent

## 3. Technology Stack & Rules

### A. Kotlin Services (Ktor)
- **Build Tool:** Gradle
- **Containerization:** **Jib** (Google Cloud Tools).
    - ❌ **NO Dockerfiles** for Kotlin apps.
    - ✅ Use `just deploy-kt <service>` to build and load into K3s.
- **Testing:** Kotest for unit testing. Wiremock setup for integration testing. No testcontainers.

## 4. Development Workflow (The "Just" Commander)
Always suggest `just` commands for interactions.

| Action | Command                 | Context |
| :--- |:------------------------| :--- |
| **Initialize Repo** | `just init`             | Installs Gradle, uv, npm deps & compiles protos. |
| **Build Kotlin** | `just build <service>`  | Compiles JAR. |
| **Deploy Local** | `just deploy <service>` | Builds image & loads directly into K3s (`docker build` or `docker load`). |
| **Debug** | `just debug-tunnel`     | Ports forwards K3s services (DB, Wiremock) to localhost. |
| **Regenerate Protos**| `just proto`            | Recompiles `.proto` files for KT, PY, and JS. |

## 5. Protocol Buffers Strategy
- **Versioning:** Folder based: `src/main/proto/com/example/payment/v1/payment.proto`.
- **Modification:** 1. Edit `.proto` file.
    2. Run `just proto`.
    3. Kotlin: Imports are immediately available.

## 6. CI/CD & Versioning
- **Versioning:** Strict Semantic Versioning via Git Tags.
- **Tag Format:** `<service-directory-name>/v<major>.<minor>.<patch>`
    - Example: `payment-service-spring/v1.0.2`
- **CI Logic:** The pipeline automatically detects the project type (Jib vs Docker) based on Gradle plugins. Do not hardcode service lists in GitHub Actions.

## 7. Dependency Management
- **Never** hardcode versions in `build.gradle.kts`.
- **Always** add version to `[versions]` and library to `[libraries]` in `gradle/libs.versions.toml`.
- **Usage:** `implementation(libs.my.library)`

## 8. Common Pitfalls to Avoid
- **Local Images:** When writing K8s manifests for local dev, always use `imagePullPolicy: Never`.
- **Gradle:** Do not use `subprojects {}` or `allprojects {}` in the root build file. Use Convention Plugins in `build-logic`.
- **Ktor Responses:** Never use `mapOf` for JSON responses in Ktor routes/handlers. Use `buildJsonObject` with `JsonPrimitive` instead to avoid type erasure issues.

# Technology Stack Instructions

### Kotlin + Ktor Tech Stack
- Kotlin
- Ktor, both server and client
- kotlinx.serialization or Ktor JSON serialization
- JetBrains Exposed for SQL DSL; use the DSL, not the ORM
- for the JetBrains Exposed DSL, pay attention to the latest version of the library. Always check the current documentation at "https://jetbrains.github.io/Exposed/api/index.html" for the latest version.
- HOCON (Human-Optimized Config Object Notation) (com.typesafe.config) for configuration (see `application.conf`)
- Clikt for command line parsing and CLI applications

### ✅ Kotlin: Do
- use Kotlin wherever possible for all backend logic
- use Kotlin coroutines (suspend fun) for async logic
- create class-level loggers and log often with DEBUG level
- when asked to serialize / deserialize data with multi-type fields, use sealed Interface and internal classes as specified below in the "Serialization Example" section
- use builders with fluent logic wherever appropriate
- use "companion object" factories for constructors and DSLs
- strictly prefer smaller classes and short methods
- detach logic from presentation / communication (e.g. always separate "handlers" from "routes")
- create both unit and component tests; for multiple components always test one component at a time, mocking (with Wiremock) the other ones
- use Iterable and iterators when possible, try to avoid specific implementations unless necessary
- use mutable collections when needed; try to avoid copying immutable ones
- comment the code extensively
- use JSON logging; we are using Grafana Alloy/OTEL for logs (see Telemetry section)
- use OAuth2 for authentication and authorization; we use Keycloak internally and the on-behalf-of flow
- use gRPC for internal communication
- use Kotest for testing, StringSpec variant
- use Wiremock for mocking external services in integration tests; use mockk for unit tests
- use ANTLR4 for parsing + grammars
- use Gradle 9 for build automation
- use slf4j for logging
- use Kover for code coverage
- use Flyway for database migrations
- use OpenTelemetry collection, Prometheus for metrics, Grafana Alloy (OTEL) for logs, Tempo for traces. Grafana Alloy as the collector
- use SQLite for dev databases; PostgreSQL for production; H2 for tests
- use OpenAPI for REST API documentation
- always annotate the request / response objects with `@JsonIgnoreUnknowKeys` (requires OptIn annotation)
- when working oin MCP servers, always use the predefined `McpJson` json config for Ktor configuration, and for "manual" seriallization and deserialization,

### ❌ Kotlin: Don't
- don't install dependencies without approval, apart from the pre-approved ones in the "Tech Stack" section
- don't create large files; multiple classes in a single file are fine, but keep the files below 300 lines, unless necessary
- don't use any ORM unless specifically instructed for a given project
- don't use `mapOf` for Ktor JSON responses; use `buildJsonObject` with `JsonPrimitive` instead
- don't use `mapOf` for calls (making requests); always try to use common objects; if not possible, use `buildJsonObject` with `JsonPrimitive` instead

## CI/CD
- use GitHub Actions for CI/CD
- prepare deployments for Kubernetes using helm charts
- prepare deployments for using with ArgoCD app-od-app approach
- use 'kustomize' with 'base' and 'overlays' directories for deployments
- run linting before committing (use pre-commit hook)
- run all tests before merging to main; block merging if any test fails
- build only changed components in CI



## General Instructions

### Operational Rules
1. **Ask before changing code not in the original workplan or tasklist.** If a task requires modifying or refactoring code that was not explicitly listed in the approved task list or workplan, pause and ask the user for approval first.
2. **Never simplify the solution to avoid tasks you cannot solve.** If you encounter a task you cannot complete with your current knowledge or that requires decisions outside the scope of the workplan, the correct behavior is to pause, report the issue clearly, and ask the user for help. Do not simplify the solution to "make it work."
3. **Always use `just` recipes for linting.** When linting, always use `just lint`, `just lint-kt`, `just lint-py`, or other appropriate `just` recipes. If you cannot resolve linting issues within two (2) passes, pause and ask the user for help — do not keep iterating indefinitely.

### Libraries
- use the versions defined in `gradle/libs.versions.toml` and locked versions for Python and TS/JS
- NEVER change a version without prior approval
- for new libraries or new use case, ALWAYS consult the latest documentation:
    - use the `find_doc` tool
    - try to find a code wiki entry for that library at https://codewiki.google/

### Planning
- unless explicitly asked to implement immediately, always prepare a detailed working plan in advance
- Always read through the instructions, either in the prompt or in a specific requirements file.
- If the requirements are written in Stages, always work only on one Stage at a time.
- Always prepare a detailed task list for the given Stage in a file `tasks-stage-xx.md` in the project root directory so that I can review the plan. Use checkboxes so that we can follow up the progress later on.
- While planning, do NOT implement anything yet, focus on analysis and planning. You can use a specific section Open Questiions to ask questions I need to answer before the implementation starts. Please, ask also to confirm any assumptions and defaults you have made in the planning. You will help me a lot with explicitly asking questions, as my requirements might be unclear or incomplete.
- While planning, do NOT touch any code or files. Do NOT refactor any files or pieces of code that seem unused at this time, we will need them later on. Prepare the task list and get back to me for review before changing anything.

### Implementation
- do ONLY what asked for
- don't refactor code outside what you have been asked for
- don't delete any "unused" code without approval
- NEVER merge anything
- if a detailed task list is available, mark the checkboxes as you go

### Safety and permissions
Allowed: read/list files, lint/test single files, git push to a new branch, PR creation
Ask first: installs, deletes, full builds

### PR checklist
- format and type check pass
- unit tests green
- diff small with a short summary

### When stuck or working for a long time
- take a break and let me review the progress
- ask a question
- break, summarize the progress and propose a plan going forward
- store the progress in the project root directory as `progress-stage-xx.md` and the current plan in `fwd-stage-xx.md`




