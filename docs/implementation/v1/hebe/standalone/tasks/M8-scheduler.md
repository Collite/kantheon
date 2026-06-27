# M8 — Scheduler + heartbeat

Cron parser, job loop, routines engine, scheduled-maintenance jobs (transcript summarisation, fact extraction, daily digest, embedding refresh, stuck-job detection), heartbeat.

**Done when:** a 24-hour soak fires the heartbeat 4 times, summarises transcripts twice, generates a daily digest, and refreshes embeddings on demand.

References: [`../v1-architecture.md`](../v1-architecture.md) §§5, 18; [`../v1-specs.md`](../v1-specs.md) §2.4.

---

## M8.T1 — Cron parser + next-fire calculator

**Status**: pending  
**Size**: S  
**Depends on**: —  
**Blocks**: M8.T3

### Goal

Parse standard 5-field cron expressions plus shortcuts (`@hourly`, `@daily`, `@every <duration>`). Compute the next fire time given a `now: Instant`.

### Files to create

- `modules/scheduler/build.gradle.kts` (edit)
- `modules/scheduler/src/main/kotlin/com/hebe/scheduler/cron/Cron.kt` (new)
- `modules/scheduler/src/main/kotlin/com/hebe/scheduler/cron/CronParser.kt` (new)
- Tests with property + golden cases

### Detailed work

1. Deps:

   ```kotlin
   dependencies {
       api(project(":modules:api"))
       implementation(project(":modules:memory"))
       implementation(project(":modules:core"))                  // for JobDelegate
       implementation(libs.kotlinx.datetime)
   }
   ```

2. `CronParser.parse(expr): Cron` accepts:
   - 5 fields: minute hour dom month dow.
   - Field syntax: `*`, integer, `*/n`, range `a-b`, list `a,b,c`. No `L`/`#` extensions in v1.
   - Shortcuts: `@hourly` → `0 * * * *`; `@daily` → `0 0 * * *`; `@every 30m` → custom `Cron.Every(30.minutes)`.

3. `Cron.nextFire(now: Instant, tz: TimeZone = TimeZone.UTC): Instant`:
   - For 5-field cron: increment minute by minute until all fields match.
   - For `@every`: `lastFire + duration`.

4. Property tests with random valid expressions; golden tests for boundary cases (DST transitions in the configured timezone — at minimum document if v1 stays UTC-only).

### Tests / verification

- `0 * * * *` from `12:30` → next is `13:00`.
- `@every 5m` from `12:30:00` → `12:35:00`.
- Invalid input → clear `IllegalArgumentException`.

### Acceptance criteria

- ✅ Standard 5-field syntax + shortcuts.
- ✅ Property tests pass.
- ✅ Timezone documented (UTC-only in v1; bump in v2).

### References

- `v1-architecture.md` §18

---

## M8.T2 — Job loop

**Status**: pending  
**Size**: M  
**Depends on**: M1.T2 (`jobs` table), M2.T12 (`JobDelegate`)  
**Blocks**: M8.T3 onward

### Goal

A single coroutine reads `jobs WHERE status = 'pending' AND trigger_at <= now()`, marks `running`, runs the body via `JobDelegate`, marks `done`/`failed`. At-least-once execution; idempotency required for retryable kinds.

### Files to create

- `modules/scheduler/src/main/kotlin/com/hebe/scheduler/JobLoop.kt` (new)
- `modules/scheduler/src/main/kotlin/com/hebe/scheduler/JobRepo.kt` (new — `jobs` CRUD)
- `modules/scheduler/src/main/kotlin/com/hebe/scheduler/JobRunner.kt` (new — kind dispatch)
- Tests

### Detailed work

1. `JobLoop.run(scope)`:
   - Tick every 5 seconds (configurable).
   - `JobRepo.claimPending(maxN = 1)` — pessimistic claim via `UPDATE jobs SET status='running', started_at=? WHERE id=(SELECT id FROM jobs WHERE status='pending' AND trigger_at<=? ORDER BY trigger_at LIMIT 1)`.
   - Pass to `JobRunner.run(job)`.
   - On exception: `status='failed'`, `result_json={error: ...}`.
   - On success: `status='done'`, `ended_at=now`, `result_json=...`.

2. `JobRunner.run(job)` dispatches by `job.kind`:
   - `routine` → resolve to a `routines` row → execute body via `JobDelegate`.
   - `maintenance` → call the registered maintenance handler (M8.T4–T7).
   - `adhoc` → run a `JobDelegate` with `payload_json` as a free-form prompt.
   - `heartbeat` → handled in M8.T9.

3. **Idempotency**: maintenance jobs should be safe to run multiple times. We mark each run by `id`; if a partial result was committed and the job retries, the operation should converge to the same final state.

4. **Cancellation**: jobs polled for `status='cancelled'` between tool calls; on cancel, exit cleanly and append `result_json={cancelled: true}`.

### Tests / verification

- Insert a `pending` job → loop picks it up → status progresses.
- Insert a job with `trigger_at` in the future → not picked up until time passes.
- Cancel a running job → status `cancelled` after the next tool boundary.

### Acceptance criteria

- ✅ At-least-once.
- ✅ Pessimistic claim prevents double-pickup.
- ✅ Cancellation cooperative.

### Pitfalls

- SQLite locks: long-running tool calls inside a job hold no DB lock between them; only the claim and final update are inside transactions.

### References

- `v1-architecture.md` §18

---

## M8.T3 — Routines engine

**Status**: pending  
**Size**: M  
**Depends on**: M8.T1, M8.T2  
**Blocks**: M8.T9 (heartbeat is a routine)

### Goal

Watches `routines WHERE enabled=1`; for each, computes `next_run_at` from the cron and inserts a `pending` `jobs` row when due.

### Files to create

- `modules/scheduler/src/main/kotlin/com/hebe/scheduler/RoutinesEngine.kt` (new)
- Tests

### Detailed work

1. `RoutinesEngine.tick()`:
   - Read all enabled routines.
   - For each:
     - If `next_run_at <= now`:
       - Insert a `jobs(kind='routine', payload_json={routine_id: <id>}, trigger_at=now, status='pending')`.
       - Update `last_run_at = now`, `next_run_at = cron.nextFire(now)`.

2. Catchup: missed runs while hebe was down.
   - Per `v1-tasks.md` X.T8 cut line, **catchup is v2** in the original plan, but lightweight catchup is cheap here: if `last_run_at + cron interval < now`, schedule one catchup invocation (`payload_json.catchup = true`) but don't insert N missed runs. Document the behaviour.

3. The engine itself runs as a job loop tick at 30 s cadence (separate from the job loop's 5 s). Or piggy-back on the same loop — pick one and document.

### Tests / verification

- Routine due → job inserted; `next_run_at` advanced.
- Routine not due → nothing happens.
- Disabled routine → ignored.
- Down for an hour with `@hourly` → one catchup job inserted on resume.

### Acceptance criteria

- ✅ Routines fire on schedule.
- ✅ Catchup behaviour as documented.
- ✅ Updates `last_run_at` + `next_run_at`.

### References

- `v1-architecture.md` §5 (`routines`)

---

## M8.T4 — Maintenance: transcript summarisation

**Status**: pending  
**Size**: M  
**Depends on**: M2.T9 (compactor), M8.T2  
**Blocks**: nothing direct (the routines configure it)

### Goal

Rolling-window transcript summarisation appended to `MEMORY.md` when the window crosses a token threshold.

### Files to create

- `modules/scheduler/src/main/kotlin/com/hebe/scheduler/maintenance/Summariser.kt` (new)
- Tests

### Detailed work

1. Cron-driven (default `*/30 * * * *`). On fire:
   - Pull recent `messages` (e.g. last 24 h) for each conversation.
   - For conversations whose unsummarised window crosses the threshold (e.g. 8000 tokens), call the LLM with a summarisation prompt over the oldest portion.
   - Append the summary to `MEMORY.md` under a `## Summary YYYY-MM-DD HH:MM` section.
   - Mark the summarised messages with a `summary_id` column (V7 migration; or a separate `message_summaries` table) so we don't re-summarise them.

2. Failure: if the LLM call fails, log + skip; the next tick retries.

### Tests / verification

- Inject 9000-token transcript → summarisation runs → summary appended → next tick is no-op.

### Acceptance criteria

- ✅ Threshold-driven.
- ✅ Summarised window marked to avoid re-work.

### References

- `v1-architecture.md` §18

---

## M8.T5 — Maintenance: fact / preference extraction

**Status**: pending  
**Size**: M  
**Depends on**: M8.T2, M2.T1  
**Blocks**: nothing

### Goal

Run an LLM pass over recent assistant outputs looking for "remember X" / "the user prefers Y" patterns; promote high-confidence facts to `MEMORY.md`.

### Files to create

- `modules/scheduler/src/main/kotlin/com/hebe/scheduler/maintenance/FactExtractor.kt` (new)
- Tests

### Detailed work

1. Cron-driven (default `10 * * * *`). On fire:
   - Pull last hour of `messages` where `role = assistant`.
   - Prompt the LLM to extract durable facts as JSON with confidence scores.
   - For each fact with `confidence >= 0.85`, append to `MEMORY.md` under `## Facts` with a provenance line (`<!-- source: turn 8a4f, 2026-05-04 -->`).
   - Skip duplicates (cosine match against recent fact entries; threshold 0.9).

2. The hygiene scanner (M1.T12) still applies; an injection sample even via fact extraction is rejected.

### Tests / verification

- Mock LLM extracts a fact; it lands in `MEMORY.md`.
- Duplicate fact in next run → skipped.
- Hygiene blocks an attempted injection-extraction.

### Acceptance criteria

- ✅ Hourly cadence.
- ✅ Provenance line per fact.
- ✅ Duplicate suppression.

### References

- `v1-architecture.md` §18

---

## M8.T6 — Maintenance: daily digest

**Status**: pending  
**Size**: M  
**Depends on**: M8.T2, M3.T8 (receipts), M2.T1  
**Blocks**: nothing

### Goal

End-of-day routine: generates `daily/YYYY-MM-DD.md` summarising the day's transcript + receipts.

### Files to create

- `modules/scheduler/src/main/kotlin/com/hebe/scheduler/maintenance/DailyDigest.kt` (new)
- Tests

### Detailed work

1. Cron-driven (default `5 0 * * *` = 00:05 UTC).

2. Inputs:
   - All `messages` from the previous calendar day.
   - All receipts from the previous day (read NDJSON).
   - All `jobs` from the previous day.

3. LLM prompt produces a markdown digest with sections: "Conversations", "Tools called", "Facts learned", "Issues encountered". Saved as `workspace/daily/2026-05-04.md`.

4. Quiet on no-activity days: if zero messages + zero non-heartbeat jobs, skip.

### Tests / verification

- Mock day with messages + receipts → digest file created with all sections populated.
- Empty day → no file.

### Acceptance criteria

- ✅ One file per day.
- ✅ Sections present.
- ✅ Skip on empty days.

### References

- `v1-architecture.md` §18

---

## M8.T7 — Maintenance: embedding refresh

**Status**: pending  
**Size**: S  
**Depends on**: M1.T9 (indexer)  
**Blocks**: nothing

### Goal

Find chunks with NULL embeddings and batch-index them. Useful when the embedding provider was unavailable during initial indexing.

### Files to create

- `modules/scheduler/src/main/kotlin/com/hebe/scheduler/maintenance/EmbeddingRefresh.kt` (new)
- Tests

### Detailed work

1. Cron-driven (default `*/15 * * * *`).

2. Query: `SELECT doc_path, chunk_idx, content FROM memory_chunks WHERE embedding IS NULL LIMIT 100`.

3. Embed via the provider (batched 32 at a time).

4. UPDATE rows with the new BLOB; insert into `memory_chunks_vec`.

5. Cap: 100 chunks per tick to avoid hammering the provider.

### Tests / verification

- Insert chunks with NULL embeddings → tick → embeddings populated.

### Acceptance criteria

- ✅ Batched 32.
- ✅ 100/tick cap.

### References

- `v1-architecture.md` §18

---

## M8.T8 — Maintenance: stuck-job detection + retry-once

**Status**: pending  
**Size**: S  
**Depends on**: M8.T2  
**Blocks**: nothing

### Goal

Detect jobs stuck in `running` past their deadline; mark `stuck`; retry once if the kind is retryable.

### Files to create

- `modules/scheduler/src/main/kotlin/com/hebe/scheduler/maintenance/StuckJobDetector.kt` (new)
- Tests

### Detailed work

1. Cron-driven (default `*/5 * * * *`).

2. Find jobs where `status='running' AND started_at < now - timeout`. Default timeout 30 min.

3. For each: append a `result_json={stuck: true}` note, mark `status='stuck'`. If `payload_json.retryable = true` and `attempt < 1`, insert a fresh `pending` job with `attempt = 1`.

### Tests / verification

- Synthetic stuck job → marked stuck.
- Retryable stuck job → fresh pending job inserted with `attempt=1`.

### Acceptance criteria

- ✅ Timeout-driven.
- ✅ Retry-once when allowed.

### References

- `v1-architecture.md` §18

---

## M8.T9 — Heartbeat routine (HEARTBEAT.md → run a turn → silence-on-OK)

**Status**: pending  
**Size**: M  
**Depends on**: M8.T3, M2.T13  
**Blocks**: nothing

### Goal

Periodic agentic turn driven by `HEARTBEAT.md`. On non-OK output, deliver to the configured `notify_channel`; on OK output, stay silent.

### Files to create

- `modules/scheduler/src/main/kotlin/com/hebe/scheduler/maintenance/Heartbeat.kt` (new)
- Tests

### Detailed work

1. Cron-driven (default `0 */6 * * *`).

2. On fire:
   - Read `HEARTBEAT.md` content.
   - Run a turn through `JobDelegate` with system prompt: "You are hebe's heartbeat. Read HEARTBEAT.md (provided below). For each item, perform the check. If everything is OK, reply with literally `OK`. Otherwise reply with a short summary of what needs attention."
   - Output: if response is exactly `"OK"` (after trim), silent. Else send via the configured `notify_channel` (default: web console; configurable to Telegram).

3. Receipts capture every tool call as usual.

### Tests / verification

- Mock heartbeat with `OK` → no channel reply.
- Mock heartbeat with non-OK → reply delivered.

### Acceptance criteria

- ✅ Cron-driven.
- ✅ Silence-on-OK.
- ✅ Notification channel configurable.

### References

- `v1-architecture.md` §18
