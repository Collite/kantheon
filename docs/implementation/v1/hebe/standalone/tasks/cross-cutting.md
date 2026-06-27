# Cross-cutting tasks

Infrastructure that doesn't belong to a single milestone. Pick up opportunistically; most of these are claim-anytime once their dependencies are in.

References: [`../v1-tasks.md`](../v1-tasks.md) "Cross-cutting" section.

---

## X.T1 — HTTP record/replay infrastructure

**Status**: pending  
**Size**: M  
**Depends on**: M0.T2  
**Blocks**: M2.T1 (provider tests), M4.T9 (`github` tests), M4.T4 (`web_search` tests)

### Goal

A small, reusable HTTP record/replay layer for tests that target real HTTP services. Records once against a live endpoint; replays from disk thereafter.

### Files to create

- `modules/test-fixtures/build.gradle.kts` (new — a new module just for cross-test fixtures, OR put under `:modules:providers:openai-compat:src/testFixtures`)
- `modules/test-fixtures/src/main/kotlin/com/hebe/testing/HttpRecorder.kt` (new)
- `modules/test-fixtures/src/main/kotlin/com/hebe/testing/HttpReplayer.kt` (new)
- `modules/test-fixtures/src/main/resources/recordings/.gitkeep` (new)
- Tests of the recorder/replayer themselves

### Detailed work

1. Two modes:
   - **Record**: when env `KOKLYP_HTTP_RECORD=1`, the wrapper around Ktor's `HttpClient` fires the real request, saves `(method, url, body, headers, status, response_body, response_headers)` as JSON to `recordings/<test-name>.json`.
   - **Replay** (default): looks up the recording by test name; matches on `(method, url, body)`; returns the recorded response. Cache miss → test fails with "no recording, run with KOKLYP_HTTP_RECORD=1".

2. Recordings are committed to git per test, so CI runs in pure replay mode and is reproducible.

3. Sensitive headers (e.g. `Authorization`) are replaced with `[REDACTED]` before saving; replay matches modulo that header.

### Tests / verification

- Round-trip: record a real call (against a public endpoint), then replay; assert response matches.

### Acceptance criteria

- ✅ Record + replay modes.
- ✅ Sensitive headers redacted.
- ✅ Used by at least one milestone test (the OpenAI-compat provider).

### Pitfalls

- Live tests that record will fail in CI unless somehow already recorded; gate the live mode strictly on the env var.

### References

- `v1-specs.md` §2.12

---

## X.T2 — Mock channel for e2e tests

**Status**: pending  
**Size**: S  
**Depends on**: M5.T1  
**Blocks**: nothing direct; used by integration tests

### Goal

A scriptable test `Channel` that submits programmed `IncomingMessage`s and captures `OutboundMessage`s.

### Files to create

- `modules/test-fixtures/src/main/kotlin/com/hebe/testing/MockChannel.kt` (new)
- Tests

### Detailed work

1. API:

   ```kotlin
   class MockChannel(override val name: String = "mock") : Channel {
       private val incoming = MutableSharedFlow<IncomingMessage>(replay = 0)
       private val captured = mutableListOf<Captured>()

       override suspend fun start(scope: CoroutineScope): Flow<IncomingMessage> = incoming
       override suspend fun reply(ctx: ReplyContext, msg: OutboundMessage) { captured += Captured.Reply(ctx, msg) }
       override suspend fun updateDraft(ctx: ReplyContext, partial: String) { captured += Captured.Draft(ctx, partial) }
       override fun supportsDraftUpdates() = true
       override suspend fun healthCheck() = ChannelHealth.Up
       override suspend fun shutdown() {}

       suspend fun send(content: String, userId: String = "operator") = incoming.emit(IncomingMessage(...))
       fun captured(): List<Captured> = captured
   }
   sealed interface Captured {
       data class Reply(val ctx: ReplyContext, val msg: OutboundMessage) : Captured
       data class Draft(val ctx: ReplyContext, val partial: String) : Captured
   }
   ```

2. Used by integration tests in M2 (loop), M5 (channels), M6 (plugin tool callable), M7 (MCP).

### Acceptance criteria

- ✅ Programmable + captures.
- ✅ Available via `testFixtures` to all modules.

### References

- `v1-specs.md` §2.12

---

## X.T3 — Mock memory backend (in-memory)

**Status**: pending  
**Size**: S  
**Depends on**: M0.T5  
**Blocks**: nothing direct

### Goal

An in-memory `MemoryStore` for unit tests that don't need SQLite. Drops vector + FTS sophistication; substring match for `search`.

### Files to create

- `modules/test-fixtures/src/main/kotlin/com/hebe/testing/InMemoryMemoryStore.kt` (new)
- Tests

### Detailed work

1. Implement the full `MemoryStore` interface backed by `ConcurrentHashMap`s.

2. `search`: linear scan, substring match, score = position + length penalty. Returns `MemoryHit` with `source = Fts`.

3. `systemPrompt()`: simple concatenation of identity files.

4. Used wherever a test exercises memory behaviour without needing real retrieval semantics.

### Acceptance criteria

- ✅ All `MemoryStore` methods implemented.
- ✅ Unit-test-friendly (no DB setup).

### References

- `v1-specs.md` §2.12

---

## X.T4 — jacoco coverage gate ≥ 70%

**Status**: pending  
**Size**: S  
**Depends on**: M0.T4  
**Blocks**: every PR after this one

### Goal

CI fails if line coverage on `core`, `memory`, `security`, `plugins` modules drops below 70%.

### Files to create / modify

- `build-logic/src/main/kotlin/hebe.coverage.gradle.kts` (new — convention plugin)
- Each of the four modules' `build.gradle.kts` (edit — apply the convention)
- Tests via CI

### Detailed work

1. Convention plugin applies jacoco, configures the report task, and adds a `verifyCoverage` task with `minimum = 0.70` line.

2. CI's `./gradlew check` includes `verifyCoverage` for the four target modules.

3. Failures show which classes are below threshold.

### Acceptance criteria

- ✅ 70% gate on the four modules.
- ✅ CI fails below threshold.

### Pitfalls

- jacoco's report-aggregation across modules requires the `jacoco-report-aggregation` Gradle plugin if you want a unified report. Per-module enforcement is simpler.

### References

- `v1-specs.md` §4 (NFR)

---

## X.T5 — Bundled starter skills (3–5 markdown files)

**Status**: pending  
**Size**: M  
**Depends on**: M1.T11 (skills loader; not on the v1 critical path until skill loading is added — note: skills loading was implied by the architecture but not landed as a specific task above; if needed, fold a quick "skill registry" task into M1 after M1.T11. For now, create the markdown only.)  
**Blocks**: nothing

### Goal

Three to five bundled markdown skills shipped under `modules/cli-app/src/main/resources/skills/`. Loaded by the skill registry on boot. Used by everyday operator workflows.

### Files to create

- `modules/cli-app/src/main/resources/skills/daily-briefing/SKILL.md` (new)
- `modules/cli-app/src/main/resources/skills/code-review-prep/SKILL.md` (new)
- `modules/cli-app/src/main/resources/skills/wiki-organiser/SKILL.md` (new)
- (Optional) two more, depending on use case.

### Detailed work

1. agentskills.io frontmatter:

   ```yaml
   ---
   name: daily-briefing
   version: "1.0.0"
   description: "Read the day's heartbeat and summarise."
   activation:
     keywords: ["briefing", "morning", "daily"]
     tags: ["routine"]
     max_context_tokens: 1500
   ---
   ```

2. Body: prompt + steps + tools used. Each skill names the tools it expects (`memory_read`, `web_search`, etc.).

3. Test each skill end-to-end in a manual run before stamping done.

### Acceptance criteria

- ✅ Three skills shipped, frontmatter valid, deterministic prefilter selects them on relevant queries.

### References

- `v1-specs.md` §2.9

---

## X.T6 — `MockLlmProvider` recording tool

**Status**: pending  
**Size**: M  
**Depends on**: X.T1, M2.T1  
**Blocks**: nothing direct; speeds up authoring loop tests

### Goal

A small Gradle task `./gradlew :tests:recordTrace --prompt="..."` that captures a real LLM trace into a fixture file usable by `MockLlmProvider`.

### Files to create

- `modules/test-fixtures/src/main/kotlin/com/hebe/testing/TraceRecorder.kt` (new)
- `modules/test-fixtures/build.gradle.kts` (edit — add the task)
- Tests

### Detailed work

1. Task hits the configured LLM endpoint (BYO key), captures the streamed response as a list of `StreamEvent`s, serialises to JSON.

2. Used by test authors to seed `MockLlmProvider` with realistic traces for regression tests.

### Acceptance criteria

- ✅ Task wired.
- ✅ Output JSON loadable by `MockLlmProvider.fromTrace(file)`.

### References

- `v1-specs.md` §2.12

---

## X.T7 — Pre-commit hook installer

**Status**: pending  
**Size**: S  
**Depends on**: M0.T3  
**Blocks**: nothing direct

### Goal

`./scripts/install-hooks.sh` installs a pre-commit hook that runs `./gradlew detekt ktlintCheck` (with the `--quiet` flag so the developer experience is fast).

### Files to create

- `scripts/install-hooks.sh` (new)
- `scripts/git-hooks/pre-commit` (new — the hook itself)

### Detailed work

1. Hook logic: runs detekt + ktlint on **changed Kotlin files only** (parses `git diff --cached --name-only`). Full-tree runs are CI's job.

2. `install-hooks.sh` symlinks `.git/hooks/pre-commit` → `scripts/git-hooks/pre-commit`. Idempotent.

3. Optional `--bypass` flag (`SKIP_HOOKS=1 git commit ...`) for emergencies.

### Acceptance criteria

- ✅ Hook fires on commit.
- ✅ Only checks changed files.
- ✅ Bypass available.

### References

- `v1-specs.md` §2.12

---

## X.T8 — Migration immutability check

**Status**: pending  
**Size**: S  
**Depends on**: M1.T2  
**Blocks**: every later migration PR

### Goal

CI fails if a previously-shipped Flyway migration's content changes. Once V1 ships in `main`, V1.sql is frozen — schema changes go in V6+.

### Files to create

- `scripts/check-migrations.sh` (new)
- `.github/workflows/ci.yml` (edit — add the check)

### Detailed work

1. Script:
   - Compute SHA-256 of every `V*.sql` in `modules/memory/src/main/resources/db/migration/`.
   - Compare against a committed `migrations.lock` file in the repo root.
   - For new migrations (not in `migrations.lock`), append; for existing, fail if the hash changed.

2. CI step runs the script; failure means the developer changed a shipped migration.

3. Process: when adding a new migration, run `./scripts/check-migrations.sh --update` to regenerate `migrations.lock`, commit both.

### Acceptance criteria

- ✅ CI gate.
- ✅ Lock file committed.
- ✅ `--update` regenerates without complaint for new migrations.

### Pitfalls

- The lock file format is just `<filename>=<sha256>` per line, sorted; easy to merge.

### References

- `v1-architecture.md` §5 (migration immutability invariant)
- `v1-tasks.md` X.T8
