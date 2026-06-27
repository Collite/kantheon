# hebe v1 — task briefs (per milestone)

Detailed, hand-off-ready task descriptions for each milestone in [`../v1-tasks.md`](../v1-tasks.md). Each file in this folder contains the briefs for one milestone, written so a coding agent can pick up a task and execute it without further questions.

## Files

| File | Tasks | Topic |
|---|---|---|
| [`M0-foundations.md`](M0-foundations.md) | 11 | Gradle multi-module skeleton, kernel + plugin ABI, observability, config, secrets, Detekt rules, CI |
| [`M1-memory.md`](M1-memory.md) | 16 | SQLite + Flyway, sqlite-vec, WorkspaceFs, chunker, embeddings, indexer, RRF retrieval, hygiene, response cache |
| [`M2-llm-and-loop.md`](M2-llm-and-loop.md) | 15 | OpenAI-compat client, koog wrap, dispatcher, ChatDelegate, hooks, loop detector, cost guard, compaction, agent facade |
| [`M3-security.md`](M3-security.md) | 11 | Autonomy levels, workspace boundary, command policy, leak detector, prompt-injection guard, Ed25519 receipts, estop |
| [`M4-builtin-tools.md`](M4-builtin-tools.md) | 13 | All v1 built-in tools |
| [`M5-channels.md`](M5-channels.md) | 10 | CLI, Web Console (Ktor + SSE + UI), Telegram |
| [`M6-plugins.md`](M6-plugins.md) | 13 | PF4J spike, manifest, PluginHost gates, signature verification, OCI/ACR distribution |
| [`M7-mcp.md`](M7-mcp.md) | 6 | MCP server + client + transports + filter groups |
| [`M8-scheduler.md`](M8-scheduler.md) | 9 | Cron, routines, maintenance jobs, heartbeat |
| [`M9-operations.md`](M9-operations.md) | 8 | doctor, service, daemon, onboard, OTel, Shadow, completions, status |
| [`M10-hardening-and-docs.md`](M10-hardening-and-docs.md) | 10 | README, quickstart, protocol specs, security model, soak test, acceptance |
| [`cross-cutting.md`](cross-cutting.md) | 8 | Test infra, mocks, coverage gates, starter skills, hook installer |

## How to read a task brief

Every task in these files follows a uniform structure:

```
### M{n}.T{n} — Title

**Status**:    pending
**Size**:      S | M | L | XL  (S ≤ 0.5 d, M 0.5–1 d, L 1–3 d, XL > 3 d)
**Depends on**: list of task IDs
**Blocks**:    list of task IDs (informational, can be omitted if obvious)

#### Goal
One- or two-sentence statement of what this task delivers.

#### Files to create / modify
Bullet list of file paths under the repo root, marked (new) or (edit).

#### Detailed work
Numbered, ordered steps. Each step is small enough that a coding agent can execute it in a single file edit. Where shape matters, include a code skeleton.

#### Tests / verification
Concrete commands and expected outcomes, e.g. `./gradlew :modules:api:test`.

#### Acceptance criteria
Bullets, each independently verifiable.

#### Pitfalls (optional)
Known gotchas — version mismatches, classloader subtleties, Kotlin/Java interop quirks, platform differences.

#### References
Pointers into `v1-architecture.md` and `v1-specs.md`.
```

## Conventions assumed across all tasks

These are repo-wide rules; tasks reference them rather than restate them.

### Identifiers and naming

- The project name in code is **`hebe`** (matching `v1-architecture.md`). The agreed rename to **Hebe** is deferred — see `../README.md` §10. Module names (`hebe-api`, `hebe-plugin-api`, etc.), package paths (`com.hebe.*`), the binary name (`hebe run`), the data dir (`~/.hebe/`), and class names (`HebeAgent`, `HebePlugin`, `HebeException`, `HebeConfig`) all stay `hebe`. The rename will be one coordinated sweep at the end.
- Module Gradle paths: `:modules:api`, `:modules:plugin-api`, `:modules:core`, etc.
- Package paths follow module structure: `com.hebe.api`, `com.hebe.plugin`, `com.hebe.core`, etc.
- Test packages mirror main: tests for `com.hebe.api.X` live in `modules/api/src/test/kotlin/com/hebe/api/XTest.kt`.

### Code style

- Kotlin 2.2.x (bump to 2.3.x when GA per `v1-architecture.md` §2). JVM target 21.
- All public types in `api` and `plugin-api` are stable; never break source compatibility within `0.x`.
- `kotlinx-serialization` for all data classes that cross a module boundary.
- Coroutines: `Dispatchers.IO` for I/O, `Dispatchers.Default` for CPU. Tools default to `IO`.
- `Flow<StreamEvent>` for streaming protocols, never callbacks.
- No nullable returns from public APIs unless absence is meaningful — use sealed types or `Result<T, E>`-style returns where richer error information is needed.
- Detekt + ktlint must pass with zero warnings. The custom `mutation-funnel` Detekt rule (M0.T10) flags any direct mutation of agent state outside `// dispatch-exempt: <reason>` lines.

### Testing

- Test framework: **JUnit 5** with **Kotest assertions** (`assertSoftly`, `shouldBe`, etc.) and **MockK** for mocking. **Testcontainers** where a live external resource is needed (rare in v1; SQLite in-process suffices).
- Unit-test naming: `should <expected behaviour> when <condition>`. Backtick form: `` `should reject when args are missing`() ``.
- Property tests for pure functions where useful (chunker, dispatcher fingerprint, cron parser): use Kotest's `forAll`.
- Coverage gate: ≥ 70% line on `core`, `memory`, `security`, `plugins`. Enforced by jacoco in CI (cross-cutting task X.T4).
- Every milestone has at least one **integration test** that wires the milestone's deliverable end-to-end. These integration tests run by default in CI; mark with `@Tag("integration")` if they're slow.

### Build commands

The following commands appear repeatedly and should always work after each task:

| Command | Expectation |
|---|---|
| `./gradlew build` | Whole tree compiles + tests pass |
| `./gradlew :modules:<name>:test` | Single module's tests pass |
| `./gradlew detekt` | Static analysis clean |
| `./gradlew ktlintCheck` | Format clean |
| `./gradlew shadowJar` | Fat JAR produced (after M9.T6) |
| `./gradlew :modules:cli-app:run --args="..."` | Run hebe from source |

### Module dependency rules (recap)

`api` depends only on `kotlinx-serialization-core/json`, `kotlinx-coroutines-core`, `kotlinx-datetime`. `plugin-api` depends on `api` + PF4J only. Modules depend strictly downward per `v1-architecture.md` §1. Violations should fail the build via a custom check; for now, reviewers enforce.

`core` is the only module allowed to import `ai.koog.*`, and only inside `KoogLlmProvider`.

### Commit hygiene

- Conventional Commits: `feat(memory): add chunker`, `fix(dispatcher): leak detector mis-redacts`, `chore: bump Kotlin`, etc.
- One task = one PR (or a tight series). PR description references `M{n}.T{n}` and the acceptance criteria copied from the task brief.
- Squash-merge to `main`.

### Glossary (used across milestones)

| Term | Meaning |
|---|---|
| **Kernel ABI** | The five interfaces in `modules/api`: `LlmProvider`, `Channel`, `Tool`, `MemoryStore`, `Observer`. Plus supporting types. |
| **Plugin ABI** | `HebePlugin`, `PluginHost`, `Capability`, `Permission` in `modules/plugin-api`. |
| **Dispatcher / mutation funnel** | `ToolDispatcher.dispatch`. The single entry point through which any side-effect happens. |
| **Loop driver** | `runAgenticLoop(delegate, …)` — the shared per-turn driver. |
| **Receipts** | NDJSON Ed25519-chained log at `~/.hebe/receipts/YYYY-MM.log`. |
| **Workspace** | `~/.hebe/workspace/` — markdown filesystem. |
| **Routine** | Cron-driven entry that fires a tool or skill. |
| **Maintenance job** | Built-in routine (summarisation, fact extraction, etc.). |
| **Capability** | What a plugin contributes (`tool`, `skill`). |
| **Permission** | What a plugin needs from the host (`http_client`, `env_read`, `secrets:<name>`). |
| **PF4J** | The plugin framework we use for classloader isolation + lifecycle. |
| **OCI/ACR** | Plugin distribution format (OCI artifacts) and concrete registry (Azure Container Registry). |

## How to claim a task (suggestion)

1. Pick a task whose `Depends on` list is fully `completed`.
2. Reread the task brief end-to-end.
3. Skim the referenced `v1-architecture.md` sections.
4. If something is ambiguous, raise it before starting — don't invent.
5. Implement; run the verification commands locally.
6. Open a PR titled `M{n}.T{n}: <Title>`; description copies the brief's "Acceptance criteria" as a checklist.
7. On merge, mark the task `completed` in `v1-tasks.md` (or wherever the project tracker is by then).
