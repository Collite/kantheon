# Channels

ZeroClaw's channel layer is its most expansive surface — 40+ in-tree adapters covering chat platforms, social, email, voice, IRC, federated platforms, and webhooks. Compared to IronClaw (10 native + 5 WASM), it's roughly 4× the breadth.

## The `Channel` trait

Defined in `zeroclaw-api/src/channel.rs`. Approximate shape (read the file for ground truth):

```rust
trait Channel: Send + Sync {
    fn name(&self) -> &str;
    async fn deliver_message(&self, env: InboundEnvelope) -> ChannelResult<()>;
    async fn reply(&self, ctx: ReplyContext, msg: OutboundMessage) -> ChannelResult<()>;
    fn supports_draft_updates(&self) -> bool;
    async fn update_draft(&self, ctx: ReplyContext, partial: &str) -> ChannelResult<()>;
    // pairing, allowed_users, IAM, dedup, health
}
```

The trait is the kernel ABI; concrete adapters live in `zeroclaw-channels`. Each adapter handles its own:

- Connection / reconnection
- Platform-native event decoding
- Deduplication (replay guard for restarts)
- Pair-check and `allowed_users` enforcement *before* the runtime sees the event
- IP allowlist for webhook-style adapters
- Outbound posting, with `update_draft` for streaming-capable platforms

## InboundEnvelope (canonical message)

Adapters convert platform-native events into a uniform shape (the exact name and fields are best-confirmed against `zeroclaw-api`; from docs):

- `channel: String` — adapter name
- `conversation_id` — channel-side thread/room/DM identifier
- `sender_id` / `sender_name`
- `text` / `attachments`
- `received_at`
- `metadata: serde_json::Value` — channel-specific extras

This mirrors IronClaw's `IncomingMessage` in spirit but with a slightly tighter shape (no separate "engine thread id" type — the channel-side conversation_id is what's carried).

## Channel inventory

`crates/zeroclaw-channels/src/`:

### Chat platforms

| Channel | File | Notes |
|---|---|---|
| Slack | `slack.rs` | Webhooks + Web API, draft-update capable |
| Discord | `discord.rs` (+ `discord_history.rs`) | Gateway WS, draft-update capable, separate history scraper |
| Telegram | `telegram.rs` | Long-poll + webhook; draft-update via `editMessageText` |
| Matrix | `matrix.rs` | Federated; spec lifts include E2EE caveats |
| Mattermost | `mattermost.rs` | Self-hosted Slack-alike |
| Signal | `signal.rs` | Likely talks to a `signal-cli` daemon (same shape as IronClaw) |
| iMessage | `imessage.rs` | Likely macOS-only via AppleScript bridge |
| WhatsApp | `whatsapp.rs` (+ `whatsapp_storage.rs`, `whatsapp_web.rs`) | Multiple variants; storage = chat history persistence; web = Web API integration |
| LINE | `line.rs` | Asia-Pacific chat platform |
| WeChat | `wechat.rs` | + `wecom.rs` (WeChat Work) |
| QQ | `qq.rs` | Tencent IM |
| DingTalk | `dingtalk.rs`, MoChat `mochat.rs`, Lark `lark.rs`, Linq `linq.rs`, Wati `wati.rs`, Clawdtalk `clawdtalk.rs` | Various enterprise / regional chat |
| Nextcloud Talk | `nextcloud_talk.rs` | Self-hosted |
| IRC | `irc.rs` | Classic |
| Notion | `notion.rs` | Database/page interaction |

### Social media

| Channel | File | Notes |
|---|---|---|
| Bluesky | `bluesky.rs` | AT Protocol |
| Nostr | `nostr.rs` | Decentralized social |
| Twitter | `twitter.rs` | API v2 |
| Reddit | `reddit.rs` | Mentions, DMs |

### Voice / telephony

| Channel | File | Notes |
|---|---|---|
| Voice call | `voice_call.rs` | Telephony provider integration |
| Voice wake | `voice_wake.rs` | Wake-word detection |

### Email + ingress

| Channel | File | Notes |
|---|---|---|
| Email | `email_channel.rs` | SMTP/IMAP |
| Gmail push | `gmail_push.rs` | Push notifications via Gmail API |
| Webhook | `webhook.rs` | Generic incoming HTTP webhook |

### CLI

| Channel | File | Notes |
|---|---|---|
| CLI | `cli.rs` | Interactive terminal channel; injected via `register_cli_channel_fn` (`agent/loop_.rs:3-15`) |

### Cross-cutting modules

| Module | File | Role |
|---|---|---|
| Transcription | `transcription.rs` | Audio attachment → text middleware |
| TTS | `tts.rs` | Text-to-speech for voice channels |
| Link enricher | `link_enricher.rs` | URL preview/expansion |
| Orchestrator | `orchestrator/` | Routes messages between channels & runtime |
| Util | `util.rs` | Shared helpers |

## Streaming + draft updates

Channels that override `supports_draft_updates() = true` get streaming UX: as the LLM produces tokens, the runtime calls `update_draft` to edit a sent message in place. Discord, Slack, Telegram support this. Email/SMS/IRC do not — the runtime buffers and sends a single complete message at end-of-stream.

The agent loop uses `STREAM_CHUNK_MIN_CHARS = 80` (`agent/loop_.rs:54-55`) as the minimum batch size for draft updates — smaller batches would spam the platform's API rate limits.

## Pairing and access control

Each channel adapter carries pairing/allowlist logic. Defaults are conservative: a new sender DMing the bot is *not* automatically allowed.

Per `security/overview.md`:
- `allowed_users` — explicit list of channel-side identifiers
- `allowed_chats` — explicit list of conversation/room ids
- IP allowlist — for webhooks
- Pairing flow — device pairing for credential auth, prevents stolen credentials from working on a new device

`security/pairing.rs` and `security/webauthn.rs` cover the auth side. Each channel that supports DM admission documents its own pairing UX.

## ACP (Agent Client Protocol)

`docs/book/src/channels/acp.md`. JSON-RPC 2.0 over stdio — the agent speaks ACP to an IDE or editor that wants to embed it like a language server. Methods cover `chat`, `tool_invoke`, `approval_response`, etc. This is a unique surface among open-source agents; treat it as evidence that "channels are not just chat platforms" is a real design stance.

## Tool gating per channel

Channel config can include `tools_allow = [...]` — a list of tool names exposed to the model when this channel is the source. Tools outside the list are simply not advertised. The runtime never sends a hidden tool to the model and then refuses on invocation; the model never sees what it can't use.

This is implemented through `filter_by_allowed_tools` at the agent-loop level (`agent/loop_.rs`).

## Adding a new channel

1. Create `crates/zeroclaw-channels/src/<name>.rs`.
2. Implement `Channel` from `zeroclaw-api`.
3. Add a feature flag in the channel crate's Cargo.toml.
4. Register a factory at startup; the runtime picks it up if the feature is enabled in the build.
5. Document in `docs/book/src/channels/<name>.md`.

The pattern mirrors providers and tools — the kernel doesn't care about concrete types; it only sees the trait.

## Take-aways for the Kotlin port

- The koklyp scope (Slack, WhatsApp, Telegram, email) is a small subset of zeroclaw's surface. Don't try to match coverage; pick the ones that matter.
- The pattern of "pair-check + allowlist *before* the runtime sees the event" is correct; enforce at the adapter boundary.
- Channel-side `tools_allow` is a clean way to constrain what the agent can do per surface. Mirror it.
- `supports_draft_updates()` + an `update_draft` method is the right shape for streaming UX. Plan for it from day one even if your first channel doesn't support it (CLI, email don't; Slack/Telegram do).
- ACP is a worthy long-term goal for IDE integration but completely optional for v1.
- `transcription` and `tts` as cross-cutting middleware modules (rather than per-channel features) is a cleaner organization than embedding them in each adapter.
