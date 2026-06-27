# Architecture

## Layered view

```
┌────────────────────────────────────────────────────────────────────┐
│  L7  Edges     Channels (40+) · Gateway (REST/WS) · ACP (JSON-RPC) │
├────────────────────────────────────────────────────────────────────┤
│  L6  Inbound   Channel adapters · pair/allow checks · dedup        │
│                Inbound envelope (canonical)                         │
├────────────────────────────────────────────────────────────────────┤
│  L5  Triggers  SOP engine · Cron · Routines · Heartbeat · Hooks    │
├────────────────────────────────────────────────────────────────────┤
│  L4  Agent     classifier · memory_loader · prompt · history       │
│                loop_ · loop_detector · cost guard · model_switch    │
├────────────────────────────────────────────────────────────────────┤
│  L3  Security  AutonomyLevel · workspace boundary · command policy │
│                prompt_guard · leak_detector · pairing · OTP · estop │
│                tool_receipts (chained Ed25519)                     │
├────────────────────────────────────────────────────────────────────┤
│  L2  Tools     ToolDispatcher · builtin · Extism WASM · MCP         │
│                OS sandbox (Landlock/Bubblewrap/Firejail/Seatbelt/   │
│                Docker/AppContainer)                                 │
├────────────────────────────────────────────────────────────────────┤
│  L1  Providers Provider trait · streaming · router · fallback      │
│                ToolCall parsing (XML + native)                      │
├────────────────────────────────────────────────────────────────────┤
│  L0  Memory    SQLite · Postgres · Qdrant                          │
│                decay · importance · consolidation · KG · RAG        │
│                response_cache · snapshot                            │
└────────────────────────────────────────────────────────────────────┘
```

## Public ABI: `zeroclaw-api`

Three traits define the kernel boundary. Everything else in the workspace is feature-flagged and loadable behind these:

```rust
trait Provider {
    async fn chat(&self, req: ChatRequest) -> ProviderResult<Box<dyn Stream<Item = StreamEvent>>>;
    fn capabilities(&self) -> ProviderCapabilities;  // streaming?, tool_use?, multimodal?
    // ...
}

enum StreamEvent { TextDelta(String), ToolCall(ToolCall), Done, Error(...), ... }

trait Channel {
    async fn deliver_message(&self, env: InboundEnvelope) -> ChannelResult<()>;
    async fn reply(&self, ctx: ReplyContext, msg: OutboundMessage) -> ChannelResult<()>;
    fn supports_draft_updates(&self) -> bool;
    // pairing, allowed_users, IAM hook, etc.
}

trait Tool {
    fn spec(&self) -> ToolSpec;     // name, description, JSON schema for LLM
    async fn invoke(&self, args: serde_json::Value, ctx: ToolContext) -> ToolResult;
    fn risk(&self) -> RiskLevel;    // Low | Medium | High
}
```

The kernel does not know concrete types; factories register implementations at startup. Compile-time feature flags decide which implementations ship in a binary.

This is the load-bearing design: the microkernel transition (RFC #5574) is fundamentally about pulling concrete implementations behind these three traits.

## Inbound message data flow

```
External event (webhook/push/poll/WS)
   │
   ▼ channel adapter (e.g. discord.rs)
      • decode platform-native payload
      • dedup (replay guard)
      • pair-check + allowed_users + IAM
   │
   ▼ InboundEnvelope (canonical)
   │
   ▼ runtime.deliver_message(env)
   │
   ▼ Agent.handle (in agent/loop_.rs)
      1. memory_loader.load_context(conversation_id)
      2. classifier (optional): map message to a hint → route to model
      3. system_prompt = prompt_builder.build(identity + skills + security_summary)
      4. tool_specs = filter_tool_specs_for_turn(all_specs, groups, user_msg)
                      ∩ filter_by_allowed_tools(allowed_tools)
      5. provider.chat(ChatRequest { system, history, user_msg, tools }) → Stream
      6. for each StreamEvent:
            TextDelta → channel.draft_update or buffer
            ToolCall  → pause stream
                        ├── parse + dispatch via ToolDispatcher
                        ├── security.validate_tool_call(name, args, risk)
                        │     ├── workspace boundary
                        │     ├── command policy (allow/deny + validator)
                        │     ├── autonomy level (ReadOnly/Supervised/Full)
                        │     ├── OTP gate?
                        │     ├── operator approval (via channel)
                        │     └── prompt_guard scan
                        ├── tools.invoke (within OS sandbox if available)
                        ├── tool_receipts.append (Ed25519, chained)
                        ├── memory.append(call, result)
                        └── feed result back → resume stream
            Done → final reply text → channel.reply
   │
   ▼ memory.persist(conversation)
   ▼ leak_detector.scan(outbound)  → block on hit
```

Source: `docs/book/src/architecture/request-lifecycle.md`, `crates/zeroclaw-runtime/src/agent/loop_.rs`, `crates/zeroclaw-runtime/src/security/`.

## SOP engine (parallel path, not chat-driven)

```
Trigger event (manual/webhook/MQTT/cron/peripheral)
   │
   ▼ SopEngine.match_triggers(event)
      • condition eval (JSONPath, fail-closed)
      • cooldown / max_concurrent guards
   │
   ▼ for each matched SOP:
      load SOP.toml + SOP.md
      execute steps in order:
         • execution_mode: auto | supervised | step_by_step | priority_based
         • per-step requires_confirmation → operator approval
         • suggested_tools from `- tools:` markdown
         • audit log per step (sop/audit.rs)
      emit metrics (sop/metrics.rs)
```

Source: `crates/zeroclaw-runtime/src/sop/{engine,dispatch,types,condition,audit,metrics}.rs`. SOPs live as markdown bundles under `<workspace>/sops/<name>/SOP.{toml,md}`.

## Provider router and fallback

`zeroclaw-providers` wraps individual provider implementations with:

- **Router** — multiple `[providers.models.<name>]` blocks; agent picks one per turn based on hint (from classifier) or explicit selection. Hints come from `available_hints` and `route_model_by_hint` on `Agent`.
- **Fallback chain** — primary → secondary → tertiary; failover on transport errors, rate limits, or capability mismatch.
- **Capability check** — `ProviderCapabilities` advertises `streaming`, `tool_use`, `multimodal`; runtime degrades or errors if a request needs unsupported features.

Streaming uses `StreamEvent`; tool calls are parsed by `zeroclaw-tool-call-parser` (XML for non-native models, native function-call format for OpenAI/Anthropic-shaped APIs).

## Plugin host (Extism)

`crates/zeroclaw-plugins/`:

- `host.rs` — Extism plugin host wrapper.
- `runtime.rs` — plugin lifecycle.
- `wasm_tool.rs` — adapts a WASM plugin to the `Tool` trait.
- `wasm_channel.rs` — placeholder; channel capability is "not yet implemented" per docs.
- `signature.rs` — Ed25519 signature verification on the WASM bytes (publisher_key + signature in manifest.toml).

Plugins implement two exports: `tool_metadata` (returns JSON ToolSpec) and `execute` (input JSON args → output JSON `{success, output, error}`). Host functions: `zc_http_request` (gated by `http_client` permission), `zc_env_read` (gated by `env_read`). See `30-skills-extensibility.md` for the deep dive.

## Cross-cutting

- **AutonomyLevel** (`security/policy.rs`) — `ReadOnly | Supervised | Full`. Each tool's `risk()` is matched against the level. `Supervised` + `Medium` → operator approval. `Supervised` + `High` → block.
- **Hooks** (`runtime/src/hooks/`) — pre/post-tool-call audit + lifecycle side effects (issue #5462).
- **Loop detector** (`agent/loop_detector.rs`) — protect against runaway tool-call loops.
- **Cost guard** (`agent/cost.rs` + `runtime/src/cost/`) — per-turn budget; `check_tool_loop_budget` and `record_tool_loop_cost_usage`.
- **Model-switch tool** — runtime can switch models mid-conversation via a `model_switch` tool that flips a global request, picked up by the agent loop next turn.
- **Hooks for tool filtering**: `filter_tool_specs_for_turn` (built-ins always on; MCP tools gated by groups: `Always` or `Dynamic` keyword-based) + `filter_by_allowed_tools` (per-channel allowlist). Built-ins always pass through.

## Key traits and where they live

| Trait | Crate | File |
|---|---|---|
| `Provider` | `zeroclaw-api` | `provider.rs` |
| `Channel` | `zeroclaw-api` | `channel.rs` |
| `Tool` | `zeroclaw-api` | `tool.rs` |
| `Memory` | `zeroclaw-memory` | `traits.rs` |
| `Observer` | `zeroclaw-runtime` | `observability/` |
| `Peripheral` (HW) | `zeroclaw-hardware` | `lib.rs` |
| `SopEngine` (concrete) | `zeroclaw-runtime` | `sop/engine.rs` |

## Engine pattern: shrinking kernel + stable ABI

The microkernel direction is the single architectural bet to study. Where IronClaw collapses ten primitives into five (engine v2's Thread/Step/Capability/MemoryDoc/Project), ZeroClaw reduces the kernel surface to **three traits** (Provider/Channel/Tool) and pushes everything else behind feature flags. Different shape, similar spirit: a small core with a stable ABI is the load-bearing constraint.

For koklyp: pick a target kernel up front. Either a small set of primitives (ironclaw v2 style) or a small set of traits (zeroclaw style). Don't ship both.
