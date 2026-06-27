# Architecture

## Layered view

```
┌──────────────────────────────────────────────────────────────────────────┐
│  L7  Channel adapters   CLI · REPL · Gateway(web) · HTTP · Signal · WASM │
│      (channels-src/* WASM guests, src/channels/* native impls)           │
├──────────────────────────────────────────────────────────────────────────┤
│  L6  ChannelManager     merges N MessageStream → 1, owns inject mpsc     │
├──────────────────────────────────────────────────────────────────────────┤
│  L5  Agent / SubmissionParser / Router                                   │
│      session → thread → turn state machine                               │
├──────────────────────────────────────────────────────────────────────────┤
│  L4  Hooks (BeforeInbound, BeforeToolCall, BeforeOutbound, …)            │
│      Skill selector (deterministic) → SkillRegistry → attenuator         │
├──────────────────────────────────────────────────────────────────────────┤
│  L3  Agentic loop (LoopDelegate trait)                                   │
│      ChatDelegate · JobDelegate · ContainerDelegate                      │
│      run_agentic_loop()  — call_llm → handle_text or execute_tool_calls  │
├──────────────────────────────────────────────────────────────────────────┤
│  L2  ToolDispatcher · ToolRegistry                                       │
│      builtin/  WASM(wasmtime)  MCP(JSON-RPC)  approvals · sensitive_params│
├──────────────────────────────────────────────────────────────────────────┤
│  L1  SafetyLayer (sanitize · validate · policy · leak detect)            │
├──────────────────────────────────────────────────────────────────────────┤
│  L0  LlmProvider chain · Workspace · Database · SecretsStore             │
│      rig-core providers · Postgres/libSQL · AES-256-GCM · OS keychain    │
└──────────────────────────────────────────────────────────────────────────┘
```

## Inbound message data flow (v1, chat path)

```
Channel.start() → MessageStream                         (src/channels/channel.rs:864-930)
   │
   ▼ merged by ChannelManager (futures::stream::select_all)   (src/channels/manager.rs:99-133)
IncomingMessage  (id, channel, user_id, sender_id, content, attachments, metadata, …)
   │
   ▼ transcription middleware (audio → text)     (src/agent/agent_loop.rs:1163-1167)
   ▼ document_extraction middleware (PDF/DOCX → text)  (1170-1176)
   ▼ store_extracted_documents() into workspace   (1175)
   │
Agent.handle_message:
   1. SubmissionParser.parse(content)            (src/agent/submission.rs)
        ─ /undo /redo /interrupt /compact /clear /heartbeat /summarize /suggest
        ─ /expected /new /thread /resume /status /cancel /quit
        ─ approval keywords (yes/no/always)
        ─ JSON ExecApproval{...} from web gateway
        ─ /help /version /tools /skills /ping /debug /model  (SystemCommand bypasses thread state)
        ─ everything else → UserInput
   2. SystemCommand: bypass session lock & turn creation; reply directly
      Quit:         break run loop
      AuthMode:     intercept *before* turn creation (pending_auth credential)
      Approval:     resolve PendingApproval, resume LoopDelegate
      Submission*:  handlers in thread_ops.rs / commands.rs
      UserInput:    process_user_input()
            │
            ▼
      Hook: BeforeInbound  (HookOutcome::Continue { modified } | Reject)   (src/hooks/)
            │
            ▼
      SessionManager.resolve_session/thread  (DCL: read-lock fast path → write-lock w/ recheck)
            │  (src/agent/session_manager.rs)
            ▼
      ChatDelegate ──▶ run_agentic_loop()
                            │
                            ▼ ReasoningContext built from:
                              identity files (system prompt) + thread history +
                              skill prompts (selector) + tool defs (filtered by attenuation)
                            │
                            ▼ delegate.call_llm()  → RespondOutput
                            │   ├── Text → handle_text_response → return Response or Continue
                            │   └── ToolCalls → execute_tool_calls()
                            │           │
                            │           ▼ for each call: ToolDispatcher.dispatch()
                            │                ├── tool.is_approval_required → return NeedApproval
                            │                ├── execute_tool_with_safety()
                            │                │     validate → timeout → execute → serialize
                            │                ├── process_tool_result()
                            │                │     SafetyLayer.sanitize → leak_detect → wrap
                            │                └── append assistant+tool ChatMessages to ctx
                            │
                            ▼ until LoopOutcome::Response | NeedApproval | AuthPending
                                       | Stopped | MaxIterations | Failure
            ▼
      LoopOutcome → HandleOutcome (Respond/NoResponse/Pending/Shutdown)
   3. Hook: BeforeOutbound (Continue { modified } | Reject)   (1179-1219)
   4. ChannelManager.respond() → channel.respond() → "Done" status
```

The Submission cheat-sheet is in `src/agent/CLAUDE.md:135-167`.

## Tool dispatch surface

`ToolDispatcher` (`src/tools/dispatch.rs`) is the single mutation funnel. Five inputs go through it:

1. LLM-initiated tool calls (the agentic loop)
2. Web gateway button clicks (extension activate, settings change, file write)
3. CLI subcommands (`ironclaw tool …`)
4. Routine engine (cron / event triggers)
5. WASM channels invoking sub-tools via `tool-invoke` (if granted; `wit/tool.wit:82-93`)

Output of every dispatch becomes an `ActionRecord` for audit. The pre-commit hook flags new lines in handler files that mutate `state.{store, workspace, extension_manager, skill_registry, session_manager}` directly without `// dispatch-exempt: <reason>`.

## Hook points (`src/hooks/`)

Six lifecycle points; each hook is a trait implementor:

- `BeforeInbound` — run on every user message before turn creation. Can modify or reject.
- `BeforeToolCall` — pre-validate parameters; can reject.
- `BeforeOutbound` — modify or suppress agent reply.
- `OnSessionStart`, `OnSessionEnd`.
- `TransformResponse` — last-mile response shaping.

Hook errors are **fail-open** (logged but don't block). See `src/agent/CLAUDE.md:131-132`.

## Engine v1 vs v2

v1 abstractions (~10 of them): Session, Job, Routine, Channel, Tool, Skill, Hook, Observer, Extension, LoopDelegate.

v2 collapses them into 5 (`crates/ironclaw_engine/CLAUDE.md:9-17`):

| Primitive | Replaces |
|---|---|
| Thread | Session + Job + Routine + Sub-agent |
| Step | Agentic-loop iteration + tool calls |
| Capability | Tool + Skill + Hook + Extension |
| MemoryDoc | Workspace memory blob |
| Project | Flat workspace namespace |

v2 keeps the LLM/Store/EffectExecutor as injected traits (`crates/ironclaw_engine/CLAUDE.md:99-107`); the host crate adapts existing `LlmProvider`/`Database`/`ToolRegistry` to those traits in `src/bridge/effect_adapter.rs`. v2 has Tier 0 (structured tool calls) and Tier 1 (CodeAct via embedded Python in the Monty interpreter — `executor/scripting.rs`); Tier 1 uses the RLM pattern (context-as-variables, `llm_query()` recursion, compact metadata between steps).

For the Kotlin port: study v2's primitives but recognize that v1 is still the production driver. The "extracted crate" boundary (`ironclaw_engine` knows nothing about `ironclaw`) is a reusable pattern.

## Key abstractions and their files

| Abstraction | Trait/Type | File |
|---|---|---|
| Channel input | `Channel` trait, `IncomingMessage`, `OutgoingResponse`, `StatusUpdate` | `src/channels/channel.rs` |
| Channel registry | `ChannelManager` | `src/channels/manager.rs` |
| Tool | `Tool` trait, `ToolOutput`, `ToolError`, `ToolDispatcher` | `src/tools/tool.rs`, `src/tools/dispatch.rs` |
| LLM provider | `LlmProvider`, `RespondOutput`, `Reasoning`, `ReasoningContext` | `src/llm/provider.rs` |
| Database | `Database`, `SettingsStore`, `UserStore` | `src/db/` |
| Embeddings | `EmbeddingProvider` | `src/workspace/embeddings.rs` |
| Memory | `Workspace`, `MemoryDocument`, `MemoryChunk` | `src/workspace/mod.rs`, `src/workspace/document.rs` |
| Skills | `LoadedSkill`, `SkillManifest`, `SkillRegistry`, `prefilter_skills` | `crates/ironclaw_skills/` |
| Safety | `SafetyLayer`, `Sanitizer`, `LeakDetector`, `Validator`, `PolicyEngine` | `crates/ironclaw_safety/` |
| Loop driver | `LoopDelegate` trait, `LoopOutcome`, `LoopSignal`, `run_agentic_loop` | `src/agent/agentic_loop.rs:21-220` |
| Hook | `Hook`, `HookEvent`, `HookOutcome`, `HookRegistry` | `src/hooks/` |
| Observer | `Observer` (metrics/event recording) | `src/observability/` |
| Sandbox | `SandboxConfig`, `SandboxPolicy`, `ContainerRunner` | `src/sandbox/` |
| Tunnel | `Tunnel` | `src/tunnel/mod.rs` |
| Extension manager | `ExtensionManager`, `RegistryEntry` | `src/extensions/` |
| Engine v2 | `Thread`, `Step`, `Capability`, `MemoryDoc`, `Project`, `ExecutionLoop`, `MissionManager` | `crates/ironclaw_engine/src/` |

These match the trait list in `CLAUDE.md:73`: `Database, Channel, Tool, LlmProvider, SuccessEvaluator, EmbeddingProvider, NetworkPolicyDecider, Hook, Observer, Tunnel`.

## Cross-cutting policies

- **CostGuard** (`src/agent/cost_guard.rs`) — daily budget (cents) + hourly call rate. Caller must `check_allowed()` *before* the LLM call and `record_llm_call()` *after*. Lives in `AgentDeps` (`src/agent/agent_loop.rs:227`).
- **TenantScope / SystemScope** (`src/tenant.rs`) — wraps `Database` to auto-bind `user_id` on every operation, plus per-user rate limiter. The chat flow uses `TenantScope`; system tasks (heartbeat, routines that span users) use `SystemScope`.
- **NetworkPolicyDecider** — pluggable decider for outbound HTTP from sandboxed tools.
- **HttpInterceptor** (`src/llm/recording.rs`) — replay/record gate for trace tests.
