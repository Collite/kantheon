# Agent loop and orchestration

Source: `src/agent/`. Spec: `src/agent/CLAUDE.md`.

## Session / thread / turn

```
Session  (per user, lifecycle managed by SessionManager)
└── Thread  (per conversation; switchable)
    └── Turn  (request/response pair, append-only)
        ├── user_input
        ├── response
        ├── tool_calls
        └── state: TurnState (Pending | Running | Complete | Failed)
```

`ThreadState`: `Idle | Processing | AwaitingApproval | Completed | Interrupted`.

`SessionManager` (`src/agent/session_manager.rs`):
- Maps `(user_id, channel, external_thread_id) → internal UUID`.
- Double-checked locking on session creation: read-lock fast path, then write-lock with re-check (`src/agent/CLAUDE.md:127`).
- Prunes stale sessions every 10 min; warns at 1000 sessions.
- Owns `UndoManager` per thread (max 20 checkpoints, oldest dropped). Checkpoints store the message list, not full thread snapshots.

## `Submission` parser (pre-router)

`src/agent/submission.rs` parses every user message into a typed `Submission` enum **before** any other logic runs. This is the gate for control commands.

Cheat sheet (`src/agent/CLAUDE.md:135-167`):

| Input | Variant |
|---|---|
| `/undo`, `/redo`, `/interrupt`, `/stop`, `/compact`, `/clear`, `/heartbeat`, `/summarize`, `/suggest` | direct variants |
| `/expected <desc>` | self-improvement signal with conversation context |
| `/new`, `/thread new`, `/thread <uuid>`, `/resume <uuid>` | session navigation |
| `/status [id]`, `/progress [id]`, `/list`, `/cancel <id>` | scheduler control |
| `/quit`, `/exit`, `/shutdown` | break run loop |
| `yes`/`y`/`approve`/`ok`, `always`/`a`, `no`/`n`/`deny`/`reject`/`cancel` | `ApprovalResponse` |
| JSON `ExecApproval{...}` | from web gateway |
| `/help`, `/version`, `/tools`, `/skills`, `/ping`, `/debug`, `/model` | `SystemCommand` (bypasses thread state checks) |
| anything else | `UserInput` (starts a new turn) |

Adding a new submission: add enum variant → parse case → handler in `agent_loop.rs::handle_message`'s match → implement in `thread_ops.rs` (session) or `commands.rs` (system).

## Auth-mode interception

If a thread has `pending_auth` set (extension auth flow paused for token submission), the next user message is intercepted **before** any turn creation, logging, or safety validation, and routed directly to the credential store. Any control submission (undo, interrupt) cancels auth mode (`src/agent/CLAUDE.md:46-47`). This special case must not be folded into normal chat history.

## Router

`src/agent/router.rs`. Handles only `/commands` that `SubmissionParser` didn't claim — basically the job-related ones. Maps to `MessageIntent::{CreateJob, CheckJobStatus, CancelJob, ListJobs, HelpJob, Command}`. **Natural language bypasses the router entirely** and goes straight to the dispatcher.

## Main `run()` loop

`src/agent/agent_loop.rs:741-1308`. Steps:

1. **Eager engine v2 init** if `engine_v2` flag set (so gateway endpoints can serve project/mission/thread data before the first message; `1144-1148`).
2. **Start all channels** → merged `MessageStream`.
3. **Spawn self-repair task** every `repair_check_interval`: detect stuck jobs / broken tools, attempt recovery (see "Self-repair" below).
4. **Spawn session pruner** every 10 min.
5. **Spawn heartbeat** if enabled (single-tenant or `multi_tenant`).
6. **Spawn routine engine** if enabled — cron ticker + event matcher; lightweight runs inline, full jobs go to `Scheduler`.
7. **Spawn TUI sidebar refresher** for engine v2 thread list.
8. **Main `tokio::select!` loop** (biased):
   - Ctrl-C → break.
   - `message_stream.next()` → process the message.
9. **Per message**:
   - Apply transcription middleware (audio attachments → text).
   - Apply document-extraction middleware (PDF/DOCX → text).
   - Persist extracted document text into workspace.
   - `handle_message(msg) → HandleOutcome`:
     - `Respond(s)` → `BeforeOutbound` hook → `respond_then_done`.
     - `NoResponse` → `send_done` (turn done; UI knows to close).
     - `Pending` → suppress `Done` (paused for approval/auth).
     - `Shutdown` → break.
     - `Err` → respond with error text + `Done`.
   - Refresh engine v2 thread list in TUI sidebar.
10. **Cleanup**: abort spawned handles, `scheduler.stop_all()`, `channels.shutdown_all()`.

The `HandleOutcome` enum (`agent_loop.rs:46-79`) makes the "no response, turn over" vs "no response, turn paused" distinction explicit so the run loop knows whether to emit the terminal `Done` status. Sending `Done` after a pause would trip the web UI's missing-response safety net (issue #2079 referenced inline).

## Agentic loop

`src/agent/agentic_loop.rs:213-…`. The shared driver used by all three execution paths.

```
run_agentic_loop(delegate, reasoning, reason_ctx, config) -> LoopOutcome
```

Iteration body (paraphrased, lines `1-220`):

1. `delegate.check_signals()` → `LoopSignal::{Continue | Stop | InjectMessage(s)}`.
2. `delegate.before_llm_call(reason_ctx, iter)` → optional early-out `LoopOutcome`.
3. `delegate.call_llm(reasoning, reason_ctx, iter)` → `RespondOutput`.
4. If `Text(content)`: `delegate.handle_text_response(...)` → `TextAction::Return | Continue`.
5. If `ToolCalls(calls)`: `delegate.execute_tool_calls(calls, content, reason_ctx)` → optional early-out (e.g. approval).
6. `delegate.after_iteration(iter)`.
7. Repeat until `LoopOutcome` returned or `max_iterations` (default 50) hit.

`LoopOutcome` variants:
- `Response(String)` — final text.
- `Stopped` — graceful stop signal.
- `MaxIterations` — hit the cap.
- `Failure(String)` — clear failure reason.
- `NeedApproval(Box<PendingApproval>)` — chat delegate only.
- `AuthPending(String)` — auth flow initiated, suppress text response.

## `LoopDelegate` trait

`agentic_loop.rs:86-138`. Three implementations:

| Delegate | File | Purpose |
|---|---|---|
| `ChatDelegate<'a>` | `src/agent/dispatcher.rs` | Conversational turns; tool approval; skill context injection; holds session lock; tracks turns. **Borrows references** (`'a`) — therefore can't be spawned into detached tasks |
| `JobDelegate` | `src/worker/job.rs` | Background scheduler jobs from `CreateJob`/`/job`. Owns its `Arc`s. Has planning support (`use_planning` flag). Independent of session |
| `ContainerDelegate` | `src/worker/container.rs` | Docker-container worker. Sequential tool exec; HTTP event streaming back to host |

Trait requires `Send + Sync` so the loop can take `&dyn LoopDelegate`. Borrowed delegates must keep all borrowed fields `Send + Sync`. This is **load-bearing**: detached tasks must use `Arc`-based ownership.

## Duplicate-tool-call escalation

`agentic_loop.rs:140-207`. Defends against an LLM stuck in a "call same failing tool, get same error, repeat" loop:

- After **3** consecutive identical failing tool batches → inject `DUPLICATE_TOOL_CALL_WARNING` ("try a different approach...").
- After **5** → force text-only mode for the next turn.

Fingerprint = hash of `(tool_name, canonicalized_args)` over the whole batch. Resets when LLM produces text or any tool succeeds.

## Tool execution pipeline

`src/tools/execute.rs`:

- `execute_tool_with_safety()` — validate → timeout → execute → serialize.
- `process_tool_result()` — sanitize → wrap → `ChatMessage`.

Used by all three delegates. `SafetyLayer` (in `ironclaw_safety`) runs the chain: sanitizer → validator → policy → leak detector.

Approval: tools flagged `requires_approval` (or `ApprovalRequirement::Always` for "always require") pause the loop. `ChatDelegate` returns `LoopOutcome::NeedApproval(pending)`. The web gateway stores the `PendingApproval` in session state and emits an `approval_needed` SSE event. User's approve/deny resumes the loop. Cf. `ChatApprovalPrompt` rendering for plain-text/markdown channels.

## Scheduler

`src/agent/scheduler.rs`. Two `Arc<RwLock<HashMap>>`s:

- `jobs: HashMap<Uuid, ScheduledJob>` — full LLM-driven jobs. Each has a `JoinHandle` and an `mpsc::Sender<WorkerMessage>` (variants: `Start`, `Stop`, `Ping`, `UserMessage`).
- `subtasks: HashMap<Uuid, ScheduledSubtask>` — `ToolExec` or `Background`.

Preferred entry: `dispatch_job()` — creates context, persists to DB (so foreign keys from `job_actions`/`llm_calls` work), then `schedule()`. Don't call `schedule()` directly without persisting first (`src/agent/CLAUDE.md:99-105`).

Concurrency rules (CLAUDE.md:127-128):
- Single check-insert under one write lock to prevent TOCTOU.
- A cleanup task polls every 1s for completion and removes entries.
- `Scheduler.schedule()` holds the write lock for the entire check-insert; **don't hold any other locks when calling**.
- `spawn_subtask()` returns `oneshot::Receiver` — caller must await for result.
- `spawn_batch()` runs concurrently; results in input order.

## Self-repair

`src/agent/self_repair.rs`. `DefaultSelfRepair` runs on `repair_check_interval`:

1. `ContextManager::find_stuck_jobs()` (or `detect_stuck_jobs` for time-based detection that auto-transitions `InProgress → Stuck`).
2. `attempt_recovery()` — back to `InProgress`.
3. If `repair_attempts >= max_repair_attempts` → `ManualRequired`.
4. Detect broken tools via `store.get_broken_tools(5)` (5 failure threshold). Requires `with_store()`; empty otherwise.
5. Attempt rebuild via `SoftwareBuilder` (requires `with_builder()`; otherwise `ManualRequired`).

Result variants: `Success`, `Retry` (no notification, prevents spam), `Failed`, `ManualRequired`. Notification dedup via `notified_manual: HashSet<Uuid>` so a permanently-failing job notifies once.

## Heartbeat / routines (proactive)

- **Heartbeat** — see Memory doc; periodic `HEARTBEAT.md` scan, single- or multi-tenant.
- **Routines** (`src/agent/routine.rs`, `routine_engine.rs`) — `Trigger::{Cron(spec) | Event(name) | SystemEvent(...) | Manual}` × `RoutineAction::{Lightweight | FullJob}` × `RoutineGuardrails`. Cron ticker + event matcher fire routines when triggers match. Lightweight runs inline; FullJob dispatches to `Scheduler`. Tools `routine_create`/`routine_list`/`routine_run`/`routine_delete` are registered if routines enabled.

## Cost guard

`src/agent/cost_guard.rs`. Daily budget (cents) + hourly call rate. Lives in `AgentDeps`. The contract is explicit: caller must `check_allowed()` **before** the LLM call and `record_llm_call()` **after**. The guard does not auto-record (CLAUDE.md:130).

## Job monitor

`src/agent/job_monitor.rs`. Subscribes to the SSE broadcast and injects Claude-Code (container) output back into the agent loop as `IncomingMessage` (with `is_internal=true`). This is how the parent agent gets to "see" what its sandboxed sub-agent is doing in real time.

## Hooks

Six points; each `Hook` impl is registered in `HookRegistry` (`src/hooks/`):

- `BeforeInbound`, `BeforeToolCall`, `BeforeOutbound`, `OnSessionStart`, `OnSessionEnd`, `TransformResponse`.

Hook errors are **fail-open** (logged, processing continues). `BeforeInbound` and `BeforeOutbound` run for every user message and agent response respectively (CLAUDE.md:131-132).

## Engine v2 execution loop (`crates/ironclaw_engine/`)

`ExecutionLoop::run` (`crates/ironclaw_engine/CLAUDE.md:109-145`):

1. Check signals (`Stop`, `InjectMessage`) on `mpsc::Receiver`.
2. Build context: messages + callable actions from active **leases** + capability metadata (including blocked managed integrations as "Activatable Integrations").
3. `LlmBackend.complete()`.
4. Branch on `LlmResponse`:
   - `Text` — tool intent nudge, return if final.
   - `ActionCalls` (Tier 0) — for each call: lookup lease → policy check → consume use → `EffectExecutor` → record.
   - `Code` (Tier 1) — execute Python via Monty with `context`/`goal`/`previous_results` as variables. `llm_query()` is a recursive subagent call (suspends VM, single-shot LLM, returns string). Compact metadata in chat between iterations.
5. Record `Step`, emit `ThreadEvent`s.
6. Repeat until text response, stop, max iterations, or approval.

Capability **leases** are scoped, time-limited, use-limited grants — not static permissions:

```rust
CapabilityLease {
    thread_id, capability_name, granted_actions,
    expires_at: Option<DateTime>,
    max_uses: Option<u32>,
    revoked: bool,
}
```

`PolicyEngine` is deterministic: `Deny > RequireApproval > Allow`.

Effect types (`crates/ironclaw_engine/src/types/capability.rs`): `ReadLocal`, `ReadExternal`, `WriteLocal`, `WriteExternal`, `CredentialedNetwork`, `Compute`, `Financial`. Used for allow/deny.

Learning missions fire automatically (CLAUDE.md:84-93):
- **Error diagnosis** when a thread completes with trace issues.
- **Skill repair** when a completed thread used a stale/incomplete skill.
- **Skill extraction** when a thread succeeds with 5+ steps and 3+ tool actions.
- **Conversation insights** every 5 completed threads in a project.

## Take-aways for the Kotlin port

- The single shared agentic-loop function with a strategy delegate (`LoopDelegate`) is a clean way to share behavior between chat / job / sandboxed-worker. Keep it.
- The `Submission` enum is the right entry shape: parse user input into a sealed type **before** any other logic. Don't fold "is this a slash command?" checks into the dispatcher.
- The duplicate-tool-call detector is a small piece of code (~200 LOC) with high real-world impact. Port it; tune thresholds.
- `HandleOutcome::Pending` (turn paused, do not emit `Done`) is a subtle but correct distinction. Plan for it.
- v2's lease + effect-type policy model is more principled than v1's `requires_approval` flag. If you're starting fresh, prefer v2's shape: capabilities advertise effects, policies match against effects, leases bind threads to capabilities.
- Cost guard's "explicit before/after" contract (vs. auto-recording) makes test fixtures simpler. Steal it.
- Auth-mode interception **before** turn creation is essential — credentials must not enter chat history. Build this into your submission layer.
