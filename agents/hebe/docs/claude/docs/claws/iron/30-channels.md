# Channels

## Abstraction

`src/channels/channel.rs:864-930`. The `Channel` async trait:

```rust
#[async_trait]
pub trait Channel: Send + Sync {
    fn name(&self) -> &str;
    async fn start(&self) -> Result<MessageStream, ChannelError>;
    async fn respond(&self, msg: &IncomingMessage, resp: OutgoingResponse) -> Result<(), ChannelError>;
    async fn send_status(&self, status: StatusUpdate, metadata: &Value) -> Result<(), ChannelError>;
    async fn broadcast(&self, user_id: &str, resp: OutgoingResponse) -> Result<(), ChannelError>;
    async fn health_check(&self) -> Result<(), ChannelError>;
    fn conversation_context(&self, metadata: &Value) -> HashMap<String, String>;
    async fn shutdown(&self) -> Result<(), ChannelError>;
}
```

`MessageStream = Pin<Box<dyn Stream<Item = IncomingMessage> + Send>>` — a hot stream owned by the channel. The channel handles its own reconnection/error recovery.

`ChannelSecretUpdater` (separate trait) lets a channel hot-swap credentials on SIGHUP without restart (`channel.rs:937-948`).

## Normalized message type

`IncomingMessage` (`src/channels/channel.rs:67-129`) — every external event becomes one of these:

```rust
struct IncomingMessage {
    id: Uuid,
    channel: String,                          // "cli", "slack", "telegram", ...
    user_id: String,                          // resolved owner identity
    sender_id: String,                        // raw channel-side actor id
    user_name: Option<String>,
    content: String,
    structured_submission: Option<Submission>, // sideband for internal callers
    thread_id: Option<ExternalThreadId>,       // typed; not the engine ThreadId UUID
    conversation_scope_id: Option<String>,
    received_at: DateTime<Utc>,
    metadata: serde_json::Value,               // free-form, channel-specific
    timezone: Option<String>,                  // IANA tz from client
    attachments: Vec<IncomingAttachment>,
    is_internal: bool,                         // pkg-private; channels can't spoof
    is_agent_broadcast: bool,                  // echo guard for slack/discord
    triggering_mission_id: Option<String>,     // chain-recursion guard
}
```

`IncomingAttachment` (`channel.rs:39-65`) covers audio/image/document with `mime_type`, optional `source_url`, `storage_key`, `local_path`, `extracted_text` (transcripts/OCR/PDF text), inline bytes for small files, `duration_secs`. `AttachmentKind::from_mime_type` is the authority on Audio/Image/Document classification.

### `ExternalThreadId`

A typed newtype (`crates/ironclaw_common/`) wrapping the channel-supplied id (Telegram chat id, Slack `thread_ts`, web UUID string). Two constructors enforce the trust split (`channel.rs:174-217`):

- `from_trusted` — `with_thread()` builder, no validation
- `new` — `try_with_thread()`, returns `ExternalThreadIdError` for empty/oversized/NUL

The convention is documented in `.claude/rules/types.md`: pick `new` at system boundaries (HTTP webhooks, raw payloads), `from_trusted` for typed/internal sources.

The conversion to the engine's internal UUID happens in `SessionManager::resolve_thread`.

### Routing target

`routing_target_from_metadata` (`channel.rs:303-317`) extracts the proactive-reply target from metadata, in priority order:

```
signal_target  (Signal phone/group)
chat_id        (Telegram)
channel_id     (Slack channel/DM, set by channel-relay)
target         (generic)
```

Tests in `channel.rs:1132-1175` are explicit about the precedence.

## `ChannelManager`

`src/channels/manager.rs:16-200`.

- Owns `Arc<RwLock<HashMap<String, Arc<dyn Channel>>>>` keyed by `name()`.
- Owns an `mpsc::Sender<IncomingMessage>` (capacity 64) — the **inject channel** — so background tasks (heartbeat, routine engine, job monitor) can push into the same merged stream without being a full `Channel`.
- `start_all()` — calls each channel's `start()`, merges with `futures::stream::select_all`, appends the inject receiver. Continues if individual channels fail; only errors if zero start successfully.
- `hot_add(channel)` — for runtime addition (e.g. user installs Slack mid-session). Shuts down any existing channel with the same name first; spawns a forwarder task that pumps the new stream into `inject_tx`.
- `respond` / `send_status` / `broadcast` / `broadcast_all` look up by name; status sends are silently no-op if the channel is missing.

## Built-in channels (in-process)

| Channel | File | Notes |
|---|---|---|
| CLI / TUI | `src/channels/tui.rs` (full Ratatui), `cli/` directory | Used by `tui` feature |
| REPL | `src/channels/repl.rs` | Simple line-based; `single_message_mode` flag for one-shot |
| HTTP webhook | `src/channels/http.rs` | axum, secret validation |
| Web gateway | `src/channels/web/` | Browser UI, SSE for live status, WebSocket optional. See `src/channels/web/CLAUDE.md` |
| Signal | `src/channels/signal.rs` | Talks to a `signal-cli` HTTP daemon |
| Relay | `src/channels/relay/` | Compatibility shim for legacy server-mediated platforms |
| Webhook server | `src/channels/webhook_server.rs` | Unified axum server composing routes from every channel that registers HTTP endpoints |

## WASM-sandboxed channels

`channels-src/{slack,telegram,discord,whatsapp,feishu}/` build to WASM with `wit-bindgen 0.36`, target `wasm32-wasip2`. Layout per channel:

```
channels-src/slack/
├── Cargo.toml             # crate-type = ["cdylib"]
├── src/lib.rs             # implements Guest trait from wit/channel.wit
├── slack.capabilities.json
└── build.sh
```

The build artifact `<name>.wasm` + `<name>.capabilities.json` is deployed to `~/.ironclaw/channels/`. The host wraps it via `WasmChannel` (`src/channels/wasm/wrapper.rs`) which implements `Channel`. The architecture diagram in that file (`wrapper.rs:7-29`) shows `WasmChannel → execute_callback (fresh instance) → ChannelStoreData (limiter + ChannelHostState)`.

Inbound flow:
1. HTTP request lands at the unified `WebhookServer`.
2. `WasmChannelRouter` (`src/channels/wasm/router.rs`) matches path/method, validates the per-endpoint webhook secret if `require_secret: true`.
3. Calls the channel's `on-http-request` in a fresh WASM instance.
4. The guest emits zero or more `EmittedMessage`s via `channel-host.emit-message` (queued on the host side; rate-limited to 100/exec, 64KB each).
5. After the callback completes, the host translates `EmittedMessage → IncomingMessage` and pushes to `inject_tx`. Attachments stored via `store-attachment-data` are retrieved here.

Polling flow: same shape but driven by the host's interval scheduler invoking `on-poll`.

Outbound:
- `respond` / `broadcast` / `send_status` translate to `on-respond` / `on-broadcast` / `on-status` calls in fresh instances.
- Long-poll-style channels (Telegram getUpdates) pass an explicit `timeout-ms` to `http-request`, capped at the channel's `callback_timeout` (`wit/channel.wit:96-104`).

## Pairing (DM admission)

WASM channels can interact with the pairing system via three host imports (`wit/channel.wit:197-222`):

- `pairing-upsert-request(channel, id, meta-json) → (code, created)` — when an unknown sender DMs the bot, the channel registers a pending pair and gets a code to send back.
- `pairing-resolve-identity(channel, external-id) → option<owner-id>` — converts the channel-side identifier to the owner identity.
- `pairing-read-allow-from(channel) → list<external-id>` — legacy compat for `allowFrom`-based admission.

The store is `src/pairing/`; the CLI subcommand `ironclaw pairing` administers it.

## Status updates

`StatusUpdate` (`channel.rs:404-579`) is a giant enum the agent emits during a turn. Examples: `Thinking`, `ToolStarted`, `ToolCompleted` (auto-redacts `sensitive_params`, `channel.rs:708-732`), `ApprovalNeeded`, `AuthRequired`, `JobStarted`, `RoutineUpdate`, `ContextPressure`, `CostGuard`, `SkillActivated { skill_names, feedback }`, `ThreadList` for resume picker.

`ChatApprovalPrompt` (`channel.rs:614-857`) renders approvals as plain-text or markdown for chat-style channels, with vocabulary based on `allow_always` (`yes/no/always` vs `yes/no`).

## Adding a new channel (per `CLAUDE.md:262-266`)

1. Create `src/channels/my_channel.rs`.
2. Implement `Channel`.
3. Add config in `src/config/channels.rs`.
4. Wire up in `src/app.rs` channel setup section.

For WASM channels: build the guest in `channels-src/<name>/`, deploy artifact + capabilities JSON, the loader picks it up automatically.

## Take-aways for the Kotlin port

- The `IncomingMessage` shape with `metadata: JsonValue` + `routing_target_from_metadata` priority list is a clean way to keep the agent core channel-agnostic while letting per-channel quirks ride along. Easy to mirror.
- The `inject_tx` mpsc inside the manager is the key to making background tasks (heartbeat, scheduler completion) feel like channels without forcing them to be one. Steal this.
- Distinguish `sender_id` vs `user_id` (raw vs resolved owner). The pairing flow makes this load-bearing; conflating them is a security bug.
- `ExternalThreadId::new` vs `from_trusted` is a nice pattern — make the trusted-vs-untrusted distinction visible in the type system.
- `is_agent_broadcast` and `triggering_mission_id` exist purely to break recursion when channel-relayed bot messages re-enter as inbound events. Plan for this in any "events trigger jobs" model from day one.
