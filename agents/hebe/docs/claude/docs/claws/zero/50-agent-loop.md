# Agent loop and orchestration

Source: `crates/zeroclaw-runtime/src/agent/`. The crate-level layout is:

```
agent.rs                ← Agent + AgentBuilder
loop_.rs                ← the actual run loop, streaming, tool dispatch
dispatcher.rs           ← ToolDispatcher (NativeToolDispatcher, XmlToolDispatcher, ParsedToolCall)
classifier.rs           ← optional classifier: routes message → hint → model
context_analyzer.rs     ← analyses request context for classifier signals
context_compressor.rs   ← compress old context when window pressure
cost.rs                 ← per-turn cost guard
eval.rs                 ← AutoClassifyExt + evaluators
history.rs              ← history management (load/save/trim)
history_pruner.rs       ← prune by token estimate
loop_detector.rs        ← runaway-tool-loop guard
memory_loader.rs        ← MemoryLoader trait + DefaultMemoryLoader
personality.rs          ← per-agent persona overrides
prompt.rs               ← PromptContext
system_prompt.rs        ← SystemPromptBuilder
thinking.rs             ← chain-of-thought helpers
tool_execution.rs       ← tool exec pipeline
tool_receipts.rs        ← receipt chain
tests.rs                ← in-tree tests
```

## The `Agent` struct

`agent/agent.rs:18-71`. Holds:

```rust
struct Agent {
    provider: Box<dyn Provider>,
    tools: Vec<Box<dyn Tool>>,
    tool_specs: Vec<ToolSpec>,
    memory: Arc<dyn Memory>,
    observer: Arc<dyn Observer>,
    prompt_builder: SystemPromptBuilder,
    tool_dispatcher: Box<dyn ToolDispatcher>,    // Native or Xml
    memory_loader: Box<dyn MemoryLoader>,
    config: AgentConfig,
    model_name: String,
    temperature: f64,
    workspace_dir: PathBuf,
    identity_config: IdentityConfig,
    skills: Vec<Skill>,
    skills_prompt_mode: SkillsPromptInjectionMode,
    auto_save: bool,
    memory_session_id: Option<String>,
    history: Vec<ConversationMessage>,
    classification_config: QueryClassificationConfig,
    available_hints: Vec<String>,
    route_model_by_hint: HashMap<String, String>,
    allowed_tools: Option<Vec<String>>,
    response_cache: Option<Arc<ResponseCache>>,
    security_summary: Option<String>,
    autonomy_level: AutonomyLevel,
    activated_tools: Option<Arc<Mutex<ActivatedToolSet>>>,  // MCP deferred loading
    hook_runner: Option<Arc<HookRunner>>,
}
```

Built via `AgentBuilder` (~30 setters). The `provider` is the LLM client; `tool_specs` is the LLM-facing tool catalog; `tools` is the execution side.

## The run loop (per turn)

`agent/loop_.rs` + `agent/agent.rs`. Each user message goes through:

1. **History load** — `memory_loader.load_context(session_id)` returns prior `ConversationMessage`s.
2. **Classification (optional)** — `classifier` analyses the message → hint string. Maps via `route_model_by_hint` to a specific model. Used to send simple questions to a cheap model and reasoning to a heavy one.
3. **Tool spec filtering** — `filter_tool_specs_for_turn(tool_specs, groups, user_msg)` (`loop_.rs:106-145`):
   - Built-in tools (names not starting with `mcp_`) always pass through.
   - MCP tools gated by `ToolFilterGroup` mode:
     - `Always` group: included if any pattern matches the tool name.
     - `Dynamic` group: included if any pattern matches AND `keywords` overlaps the user message (case-insensitive substring).
   - Empty groups → all tools pass through (backward compat).
4. **Per-channel allowlist** — `filter_by_allowed_tools(specs, allowed_tools)` further trims.
5. **System prompt build** — `SystemPromptBuilder` assembles identity + persona + skills prompt fragment + security summary + tool descriptions.
6. **Cost-guard pre-check** — `check_tool_loop_budget()` against the per-turn budget.
7. **Provider chat call** — `provider.chat(ChatRequest{system, history, user, tools})` returns a `Stream<StreamEvent>`.
8. **Stream consumption loop**:
   - `TextDelta(s)` — buffered; if channel supports drafts and `STREAM_CHUNK_MIN_CHARS` (80) accumulated, push update.
   - `ToolCall(call)` — pause the stream:
     - Parse via `ToolDispatcher` (native or XML, by provider preference).
     - **Loop detector** (`loop_detector.rs`) — refuses if same tool+args repeats too many times.
     - **Security validator** (`security/policy.rs::validate_tool_call`) — workspace boundary, command policy, autonomy level, OTP check, prompt-guard.
     - **Approval gate** (if Supervised + Medium risk) — `approval_manager.request(...)` → operator response via the originating channel.
     - **Tool exec** (`tool_execution.rs`) — `tool.invoke(args, ctx)` inside the OS sandbox if available.
     - **Receipt** — `tool_receipts.append(...)` (Ed25519, chained).
     - **Memory** — `memory.append(call, result)`.
     - **Hooks** — `hook_runner.run_post_tool(...)`.
     - Resume stream with the tool result fed back into the chat request.
   - `Done` — final reply text.
9. **Cost-guard record** — `record_tool_loop_cost_usage()`.
10. **Channel reply** — `channel.reply(ctx, OutboundMessage{ text })`.
11. **Memory persist** — `memory.append(...)` for the final assistant message.
12. **Leak detector** (`security/leak_detector.rs`) — scans outbound; blocks on hit.

## Default and tunable limits

Constants from `agent/loop_.rs:48-72`:

- `STREAM_CHUNK_MIN_CHARS = 80` — minimum batch for draft updates.
- `STREAM_TOOL_MARKER_WINDOW_CHARS = 512` — rolling window for detecting tool-call markers in streamed text (XML-style provers only).
- `DEFAULT_MAX_TOOL_ITERATIONS = 10` — cap on tool-call iterations per user message. Configurable via `agent.max_tool_iterations`.
- `AUTOSAVE_MIN_MESSAGE_CHARS = 20` — below this, user messages skip auto-save.

## Tool dispatchers

Two implementations in `agent/dispatcher.rs`:

- **`NativeToolDispatcher`** — for providers that emit structured tool-call payloads (Anthropic, OpenAI, etc.). Parses provider-native function calls.
- **`XmlToolDispatcher`** — for providers that emit XML-tagged tool calls in the text stream (some open models). Watches the stream for `<tool>` markers via `STREAM_TOOL_MARKER_WINDOW_CHARS`.

Both produce `ParsedToolCall` for downstream execution.

`zeroclaw-tool-call-parser` is a separate crate, used both here and in providers. It exposes:
- `parse_tool_calls(text)` → `Vec<ParsedToolCall>`
- `canonicalize_json_for_tool_signature` — for loop-detector fingerprinting
- `strip_think_tags`, `strip_tool_result_blocks` — chat history hygiene

## Loop detector

`agent/loop_detector.rs`. Defends against runaway loops in two ways:

- **Iteration cap** — `DEFAULT_MAX_TOOL_ITERATIONS = 10` per user message (configurable).
- **Repetition fingerprint** — hash `(tool_name, canonicalize_json(args))` per call; if the same fingerprint repeats N times in a row, escalate (warn the model, force text-only, or stop).

Compared to ironclaw's "3 warns, 5 force" two-tier escalation, zeroclaw's surface in this file is similar but the exact thresholds need confirmation against the source.

## Security validator

`crates/zeroclaw-runtime/src/security/policy.rs` + adjacent files:

| File | Role |
|---|---|
| `policy.rs` | Top-level `SecurityPolicy` — combines workspace, command, autonomy |
| `iam_policy.rs` | Per-channel/per-user IAM rules |
| `workspace_boundary.rs` | Path validation; `forbidden_paths` |
| `domain_matcher.rs` | URL host allow/deny |
| `prompt_guard.rs` | Prompt-injection pattern detection |
| `leak_detector.rs` | Outbound secret scanner |
| `pairing.rs` | Channel device pairing |
| `otp.rs` | One-time-code gating per action |
| `webauthn.rs` | WebAuthn for high-risk approvals |
| `estop.rs` | Emergency stop |
| `secrets.rs` | Encrypted secrets store |
| `audit.rs` | Security audit log |
| `playbook.rs` | Reusable security playbooks |
| `vulnerability.rs` | Vulnerability scanner integration |
| `nevis.rs` | (purpose unclear from name) |
| Sandbox impls | `bubblewrap.rs`, `firejail.rs`, `landlock.rs`, `seatbelt.rs`, `docker.rs` |
| `detect.rs` | Auto-detect available sandbox at runtime |

The validator is the single chokepoint between LLM intent and tool execution.

## Approval flow

`runtime/src/approval/`:
- `ApprovalManager` — owns pending approvals, dispatches to channels.
- `ApprovalRequest` — what the operator sees: tool name, args summary, risk level.
- `ApprovalResponse` — operator's choice: approve, deny, "always allow this exact call".

The originating channel renders the prompt; for chat platforms, this is a normal message ("Approve `shell {cmd: \"...\"}` ? Reply yes/no/always."). For ACP, it's a JSON-RPC request to the IDE.

## Tool receipts

`agent/tool_receipts.rs` + `security/audit.rs`. Each tool invocation produces a signed receipt:

```
receipt {
  index: u64,
  prev_hash: [u8; 32],
  tool: String,
  args_hash: [u8; 32],
  result_hash: [u8; 32],
  status: Executed | Blocked | RequiredApproval,
  approver: Option<String>,
  timestamp: DateTime<Utc>,
  signature: Ed25519,
}
```

Each receipt's `prev_hash` chains to the prior, so tampering with any receipt invalidates the rest. The receipt log is greppable, durable, and the source of truth for "what did the agent do".

## Hook runner

`runtime/src/hooks/`. Pre/post-tool-call hooks for audit and lifecycle side effects (issue #5462). Hook order: `pre_tool_call → tool exec → post_tool_call`. Hooks can:
- Reject the call (becomes `Blocked` in receipts)
- Add audit metadata
- Trigger downstream effects (notify, log, snapshot)

This is much lighter than IronClaw's six-point hook surface; ZeroClaw's design is more focused.

## SOPs vs the chat loop

SOPs are an entirely separate execution path:
- Triggered by external events (webhook, MQTT, cron, peripheral) or `sop_execute` tool from inside chat.
- Run by `SopEngine` (`runtime/src/sop/engine.rs`), not the agent loop.
- Steps are deterministic: parsed from `SOP.md`, executed in order, with optional `requires_confirmation` per step.
- Use the same security stack (validator, sandbox, receipts) but a different orchestrator.

This split is deliberate. SOPs are for predictable, audited workflows. The chat loop is for open-ended tasks. They share infrastructure but not control flow.

## Cron and routines

- **Cron** (`runtime/src/cron/`) — time-driven trigger source for SOPs and small "ping the agent at 9am" tasks. Standard cron syntax, 5/6/7-field tolerated.
- **Routines** (`runtime/src/routines/`) — lightweight scheduled actions. Distinct from SOPs in that they don't have step-by-step structure or approval gates. Think "every hour, run skill X with input Y".

## Heartbeat and self-repair

- **Heartbeat** (`runtime/src/heartbeat/`) — periodic agent self-check. Compared to ironclaw's `HEARTBEAT.md`-driven prompt, zeroclaw's appears more health-focused (liveness/readiness signals); the file contents would need confirmation.
- No explicit self-repair module like ironclaw's `self_repair.rs`. Failed SOPs surface in the audit log; recovery is the operator's job.

## Take-aways for the Kotlin port

- The single `Agent` struct + `AgentBuilder` shape is clean. ~30 fields in zeroclaw is on the heavy side; a Kotlin port can collapse some (autonomy + security_summary into a `SecurityProfile`, classification config + hints into a `Routing` config).
- Streaming with mid-stream tool calls is the right design. Plan for it.
- Splitting **chat-loop** (open-ended) from **SOP-engine** (deterministic) is a worthwhile distinction. Most personal-agent ports lump these together; ZeroClaw keeps them separate, and the SOP engine becomes much simpler as a result.
- Tool receipts as a chained Ed25519 log is overkill for v1 single-user koklyp but easy to bolt on. Don't store them in the same DB as memory; keep the receipt chain on disk as an append-only file.
- **Loop detector + cost guard + max iterations** — three independent guards. All of them are cheap and worth shipping. Don't rely on any one alone.
- The classifier (route message → hint → model) is a worthwhile feature for a self-hosted agent that wants to use a cheap model for chat and a heavy model for reasoning. Optional in v1; design the provider router so the classifier slots in later.
