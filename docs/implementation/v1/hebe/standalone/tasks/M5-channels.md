# M5 — Channels

`ChannelManager`, CLI REPL, Web Console (Ktor + SSE + chat UI + memory browser + receipts viewer), Telegram (long-poll + webhook), `healthCheck()`.

**Done when:** a chat works end-to-end through CLI, web, and Telegram; an approval prompt round-trips on each.

References: [`../v1-architecture.md`](../v1-architecture.md) §§10, 14, 16, 17.

---

## M5.T1 — `channels/channel-manager`: `ChannelManagerImpl`, `injectChannel`, recursion guards

> **Note (implemented)**: module renamed from `channels/api` → `channels/channel-manager` to avoid ambiguity with `:modules:api`. `ChannelRegistry` was merged into `ChannelManagerImpl`.

**Status**: done  
**Size**: M  
**Depends on**: M0.T5  
**Blocks**: every concrete channel

### Goal

The seam between channels and the agent. Merges N channel flows into one; provides an `injectChannel` for background producers (heartbeat, scheduler, MCP server) to push messages without being a full `Channel`.

### Files created

- `modules/channels/channel-manager/build.gradle.kts`
- `modules/channels/channel-manager/src/main/kotlin/com/hebe/channels/ChannelManagerImpl.kt`
- `modules/channels/channel-manager/src/main/kotlin/com/hebe/channels/InjectChannel.kt`
- Tests

### Detailed work

1. `ChannelManager`:

   ```kotlin
   class ChannelManager(private val agent: HebeAgent, private val observer: Observer) {
       private val channels = mutableMapOf<String, Channel>()
       private val inject = InjectChannel(capacity = 64)
       fun register(channel: Channel) = …
       fun unregister(name: String) = …
       fun injectChannel(): InjectChannel = inject
       fun start(scope: CoroutineScope): Job = …
       suspend fun shutdown() = …
   }
   ```

2. `start` merges:
   - Each `channel.start(scope)` flow.
   - The `inject.flow`.

   Into one flow. For each `IncomingMessage`, launch a child coroutine that calls `agent.handleMessage(msg)` and routes the resulting `OutboundMessage` back to the originating channel via `channel.reply(ctx, msg)`.

3. **Recursion guards**: drop messages with `metadata.is_agent_broadcast = true` early. Track `triggeringMissionId`; if a message tries to spawn the same mission as a recent one, log and drop.

4. `InjectChannel` is a `Channel<IncomingMessage>` (kotlinx-coroutines) with capacity 64 and overflow strategy `DROP_OLDEST` (with a warn log).

### Tests / verification

- Two mock channels register; messages from each route through the agent.
- `injectChannel.send(msg)` reaches the agent.
- A message with `is_agent_broadcast=true` is dropped.

### Acceptance criteria

- ✅ Merging works.
- ✅ Recursion guards firing.
- ✅ Capacity-bounded inject channel.

### References

- `v1-architecture.md` §6 (data flow)
- `v1-architecture.md` §10 (channel model)

---

## M5.T2 — CLI channel + REPL

**Status**: pending  
**Size**: M  
**Depends on**: M2.T13, M5.T1  
**Blocks**: developers debugging the agent

### Goal

A blocking REPL that implements `Channel`. Slash-commands (`/quit`, `/compact`, `/approve`, `/help`). `Ctrl-C` cancels turn; double `Ctrl-C` exits.

### Files to create

- `modules/channels/cli/build.gradle.kts` (edit)
- `modules/channels/cli/src/main/kotlin/com/hebe/channels/cli/CliChannel.kt` (new)
- `modules/channels/cli/src/main/kotlin/com/hebe/channels/cli/Repl.kt` (new)
- Tests with an in-memory `BufferedReader`

### Detailed work

1. Deps:

   ```kotlin
   implementation(project(":modules:api"))
   implementation(project(":modules:channels:api"))
   implementation(project(":modules:core"))
   implementation("org.jline:jline:3.27.0")  // for line editing + Ctrl-C handling
   ```

2. `CliChannel(scope: CoroutineScope) : Channel`:
   - `start(scope)` returns a `Flow<IncomingMessage>` backed by a `Channel<IncomingMessage>` populated by the REPL goroutine.
   - `reply(ctx, msg)` prints the assistant text to stdout (with optional ANSI color).
   - `supportsDraftUpdates() = true`. `updateDraft(ctx, partial)` rewrites the current line via `\r` or jline's redraw (whichever is robust enough for v1; jline preferred).
   - `healthCheck() = ChannelHealth.Up` (always — it's local).
   - `shutdown()` exits the REPL.

3. `Repl.run()`:
   - Prompt: `hebe> `.
   - Read line from jline's `LineReader`.
   - On line, push as `IncomingMessage(channel = "cli", userId = "operator", senderId = "tty", content, …)`.
   - On `Ctrl-C`, set a per-turn cancel flag. Double-tap within 2 s → break the REPL.
   - When the agent replies, jline writes above the prompt.

4. Slash-commands handled by `SubmissionParser` (M2.T4); the CLI doesn't need to special-case them — they round-trip through the agent, which produces a deterministic reply.

### Tests / verification

- Scripted input: "hi" + scripted mock LLM → assistant text printed.
- `/help` → help text printed.
- Ctrl-C cancellation cancels the in-flight tool call.

### Acceptance criteria

- ✅ Blocking REPL.
- ✅ Slash-commands work via the agent.
- ✅ Streaming via `updateDraft`.
- ✅ Ctrl-C semantics: single = cancel turn, double = exit.

### Pitfalls

- jline's `LineReader` interaction with streaming `updateDraft` requires careful redraw — test on Linux/macOS terminals.

### References

- `v1-architecture.md` §17 (CLI contract)

---

## M5.T3 — Web `gateway` skeleton (Ktor + HTTP Basic)

**Status**: pending  
**Size**: M  
**Depends on**: M0.T8  
**Blocks**: M5.T4–T9

### Goal

A Ktor server with HTTP Basic auth using a single password from `secrets.db`. Serves static UI + the JSON API routes registered by later tasks.

### Files to create

- `modules/gateway/build.gradle.kts` (edit)
- `modules/gateway/src/main/kotlin/com/hebe/gateway/Gateway.kt` (new)
- `modules/gateway/src/main/kotlin/com/hebe/gateway/auth/BasicAuthPlugin.kt` (new)
- Tests

### Detailed work

1. Deps:

   ```kotlin
   implementation(libs.bundles.ktor.server)
   implementation(project(":modules:api"))
   implementation(project(":modules:config"))
   ```

2. `Gateway.start(config, agent, secretStore, ...): EmbeddedServer`:

   ```kotlin
   embeddedServer(Netty, host = config.channels.web.bind, port = config.channels.web.port) {
       install(ContentNegotiation) { json() }
       install(SSE)
       install(WebSockets)
       install(Authentication) {
           basic("admin") { realm = "hebe"; validate { … } }
       }
       routing {
           authenticate("admin") {
               // routes registered by later tasks
           }
       }
   }
   ```

3. Basic auth validates against `secretStore.get("web.password")` (SHA-256 of the actual password is stored; comparison is timing-safe).

4. TLS: v1 supports HTTP only by default; the user is expected to put a reverse proxy in front for TLS termination. Document the recommended nginx/Caddy snippet in M10.T6.

### Tests / verification

- GET `/health` (no auth required) returns 200.
- GET `/api/status` without basic auth → 401.
- With correct credentials → 200.

### Acceptance criteria

- ✅ Ktor server starts on configured bind/port.
- ✅ Basic auth gates `/api/*`.
- ✅ Origin checks for browsers.

### Pitfalls

- Don't use plain-text password comparison; use `MessageDigest.isEqual` after hashing.

### References

- `v1-architecture.md` §15 (web console)

---

## M5.T4 — Web SSE `/api/sessions/{id}/events`

**Status**: pending  
**Size**: M  
**Depends on**: M5.T3, M2.T13  
**Blocks**: M5.T5

### Goal

A `Channel`-implementing `WebChannel` whose outbound replies stream over SSE. Each session id gets its own SSE stream.

### Files to create

- `modules/channels/web/build.gradle.kts` (edit)
- `modules/channels/web/src/main/kotlin/com/hebe/channels/web/WebChannel.kt` (new)
- `modules/channels/web/src/main/kotlin/com/hebe/channels/web/Routes.kt` (new — registers routes on `Application`)
- Tests with Ktor's TestApplication

### Detailed work

1. `WebChannel`:
   - `start(scope)` returns a `Flow<IncomingMessage>` populated by the `POST /api/messages` route.
   - `reply(ctx, msg)` writes the final text to that session's SSE stream as `event: done\ndata: {...}`.
   - `supportsDraftUpdates() = true`. `updateDraft(ctx, partial)` writes `event: text_delta\ndata: {"text": …}`.

2. Routes (registered in `Routes.kt`):
   - `POST /api/messages` body `{content, attachments}`; creates an `IncomingMessage`, returns `{sessionId, turnId}`.
   - `GET /api/sessions/{id}/events` opens an SSE channel; subscribes to events for that session.

3. Per-session subscription: a `MutableSharedFlow<SseEvent>` per session id, written by `WebChannel.reply/updateDraft`. The SSE handler reads from the flow.

4. Reconnection: support `Last-Event-ID` header → resume from the next event. v1: just keep a small ring buffer per session (last 32 events).

### Tests / verification

- Post a message, open SSE, receive `text_delta` + `done`.
- Reconnect mid-stream with `Last-Event-ID` → catch-up works.

### Acceptance criteria

- ✅ SSE frames match the format in arch §14.
- ✅ Per-session flows.
- ✅ Reconnection ring buffer.

### References

- `v1-architecture.md` §14 (SSE format)

---

## M5.T5 — Web chat UI

**Status**: pending  
**Size**: L  
**Depends on**: M5.T4  
**Blocks**: M5.T6, M5.T7

### Goal

Single-file HTML + light JS (HTMX preferred; Svelte if necessary). Sends + receives messages, resolves approvals, renders streaming text.

### Files to create

- `modules/gateway/src/main/resources/static/index.html` (new)
- `modules/gateway/src/main/resources/static/app.js` (new)
- `modules/gateway/src/main/resources/static/style.css` (new)
- `modules/gateway/src/main/kotlin/com/hebe/gateway/StaticRoutes.kt` (new — serves `/`)
- Tests via headless browser are out of scope for v1; manual happy-path

### Detailed work

1. UI layout (3 areas):
   - Left panel: session list.
   - Center: active conversation; streaming responses; input box at bottom.
   - Right panel: collapsible — memory browser (M5.T6) + receipts viewer (M5.T7) appear here in later tasks.

2. JavaScript:
   - Open `EventSource('/api/sessions/{id}/events')` for the active session.
   - On `text_delta` → append to current assistant bubble.
   - On `done` → finalise bubble.
   - On `approval_requested` → modal with `[Approve]` / `[Deny]` buttons; POST to `/api/approval/{id}`.
   - On `error` → red banner.

3. POST `/api/messages` from the input box on enter. Spinner while waiting.

4. HTMX-only path: server returns HTML fragments. Easier to implement, harder to do streaming UI updates. **Recommended**: minimal vanilla JS for the EventSource handling (≤300 lines), no framework.

### Tests / verification

- Manual: open browser, log in, send message, receive streamed reply, see approval modal for a `shell` call, resolve.

### Acceptance criteria

- ✅ Chat works end-to-end against a mock LLM.
- ✅ Approval modal appears + resolves.
- ✅ Streaming visible.

### Pitfalls

- Don't depend on external CDNs for libs; bundle everything (or skip libs).

### References

- `v1-architecture.md` §15

---

## M5.T6 — Web memory browser

**Status**: pending  
**Size**: M  
**Depends on**: M5.T3, M1.T10, M4.T5  
**Blocks**: nothing

### Goal

UI surface to search workspace + view docs read-only.

### Files to create

- `modules/gateway/src/main/kotlin/com/hebe/gateway/MemoryRoutes.kt` (new)
- `modules/gateway/src/main/resources/static/memory.js` (new)
- Tests with Ktor's TestApplication

### Detailed work

1. Routes:
   - `GET /api/memory/search?q=…&k=…` → calls `MemoryStore.search`; returns hits.
   - `GET /api/memory/tree?prefix=…` → calls `WorkspaceFs.list`; returns nested tree.
   - `GET /api/memory/doc?path=…` → calls `MemoryStore.readDoc`; returns content + metadata.

2. UI: search box + tree view + read-only doc panel.

3. v1 read-only — no editing from the browser.

### Tests / verification

- Search returns hits; doc opens; tree expands.

### Acceptance criteria

- ✅ Three endpoints.
- ✅ Read-only UI.

### References

- `v1-architecture.md` §14

---

## M5.T7 — Web receipts viewer

**Status**: pending  
**Size**: M  
**Depends on**: M5.T3, M3.T8, M3.T9  
**Blocks**: nothing

### Goal

Tabular view of recent receipts + a "Verify" button that runs `Receipts.verify` and shows the result.

### Files to create

- `modules/gateway/src/main/kotlin/com/hebe/gateway/ReceiptsRoutes.kt` (new)
- `modules/gateway/src/main/resources/static/receipts.js` (new)
- Tests

### Detailed work

1. Routes:
   - `GET /api/receipts?since=<ISO ts>&limit=…` → reads NDJSON files newest-to-oldest; returns parsed records (capped at `limit`, default 100).
   - `GET /api/receipts/verify?file=<filename>` → runs verifier, returns `{ok, lastSeq, errors?}`.

2. UI: paginated table with columns `seq, ts, tool, ok, duration_ms`, expandable row to show `args_redacted` + `result_hash`.

3. "Verify" button at the top runs verify on the current month; status indicator turns green/red.

### Tests / verification

- 100 synthetic receipts → table displays them.
- Tampered file → verify returns `Failed`.

### Acceptance criteria

- ✅ Listing + filter by `since`.
- ✅ Verify endpoint works.
- ✅ UI shows verified-OK badge.

### References

- `v1-architecture.md` §13 (receipts)

---

## M5.T8 — Telegram channel (long-poll + draft updates + operator gate)

**Status**: pending  
**Size**: L  
**Depends on**: M5.T1, M0.T9  
**Blocks**: M5.T9

### Goal

Telegram bot via `org.telegram:telegrambots`. Bot token from secrets. Operator gate: only the configured `operator_telegram_id` can talk to the bot. Draft updates via `editMessageText` throttled to 1/800 ms or 80 chars.

### Files to create

- `modules/channels/telegram/build.gradle.kts` (edit)
- `modules/channels/telegram/src/main/kotlin/com/hebe/channels/telegram/TelegramChannel.kt` (new)
- `modules/channels/telegram/src/main/kotlin/com/hebe/channels/telegram/UpdatePoller.kt` (new — long-poll loop)
- `modules/channels/telegram/src/main/kotlin/com/hebe/channels/telegram/DraftThrottler.kt` (new)
- Tests with `MockTelegramApi`

### Detailed work

1. Deps:

   ```kotlin
   implementation(libs.telegrambots)
   ```

2. `TelegramChannel(config, secretStore, observer) : Channel`:
   - `start(scope)`: launches `UpdatePoller` which long-polls `getUpdates`. Each update mapped to `IncomingMessage`. Reject any update where `from.id != config.channels.telegram.operator_telegram_id` — log + drop.
   - `reply(ctx, msg)`: `sendMessage(chatId, msg.text)` (Markdown formatting). Stores returned `messageId` on the `ReplyContext`'s metadata for `updateDraft`.
   - `supportsDraftUpdates() = true`. `updateDraft` calls `editMessageText` via `DraftThrottler`.
   - `healthCheck()`: pings `getMe`; `Up` if 200.

3. `DraftThrottler`: per-`messageId` rate-limited via a coalescing queue. Rule: emit if `>= 80 chars new` OR `>= 800 ms since last edit`. Cancels the timer on `done`.

4. Webhook variant lives in M5.T9.

### Tests / verification

- Mock bot API: scripted update → `IncomingMessage` emitted → reply via `sendMessage`.
- Wrong `from.id` → dropped, no further calls.
- DraftThrottler under high-frequency calls: emits ~ once per 800 ms.

### Acceptance criteria

- ✅ Long-poll works.
- ✅ Operator gate enforced.
- ✅ Draft updates throttled.

### Pitfalls

- Telegram rate limits: bot can't `sendMessage` more than 30 messages/sec globally; `editMessageText` is rate-limited per chat (~1/sec). The throttler protects us.

### References

- `v1-architecture.md` §16 (Telegram contract)

---

## M5.T9 — Telegram webhook variant

**Status**: pending  
**Size**: M  
**Depends on**: M5.T8, M5.T3  
**Blocks**: nothing

### Goal

Receive updates via webhook instead of long-poll. Webhook URL registered with Telegram via `setWebhook`. Signature validation via the bot token's secret path.

### Files to create

- `modules/channels/telegram/src/main/kotlin/com/hebe/channels/telegram/TelegramWebhookRoute.kt` (new)
- Edit `TelegramChannel` to support `mode: "polling" | "webhook"` from config.
- Tests with Ktor's TestApplication

### Detailed work

1. Route registered on the gateway:

   ```kotlin
   post("/api/webhooks/telegram/$secretPath") {
       val update = call.receive<Update>()
       channel.handleUpdate(update)
       call.respond(HttpStatusCode.OK)
   }
   ```

   `secretPath` is a random 32-char string stored in `secrets.db` under `telegram.webhook_secret_path`. Telegram's `setWebhook(url=https://.../api/webhooks/telegram/<secretPath>)` ensures only Telegram can hit it (as long as the secret stays secret).

2. `setWebhook` registration is part of `hebe onboard` (M9.T4).

### Tests / verification

- POST a fake update → `IncomingMessage` emitted.
- POST to wrong secret path → 404.

### Acceptance criteria

- ✅ Webhook receive works.
- ✅ Mode toggle from config.
- ✅ Path-based secret validates.

### References

- `v1-architecture.md` §16

---

## M5.T10 — Channel `healthCheck()` exposed in `/api/status` and `hebe doctor`

**Status**: pending  
**Size**: S  
**Depends on**: M5.T2, M5.T5, M5.T8  
**Blocks**: M9.T1

### Goal

Aggregate per-channel health into a single status surface used by both the web `/api/status` route and the `hebe doctor` CLI subcommand.

### Files to create

- `modules/gateway/src/main/kotlin/com/hebe/gateway/StatusRoute.kt` (new)
- `modules/cli-app/src/main/kotlin/com/hebe/cli/commands/Doctor.kt` (edit; full impl in M9.T1, this task wires the channel section)
- Tests

### Detailed work

1. `GET /api/status` returns:

   ```json
   {
     "uptimeMs": 3600000,
     "channels": [
       { "name": "cli", "health": "Up" },
       { "name": "web", "health": "Up" },
       { "name": "telegram", "health": "Up" }
     ],
     "llm": { "endpoint": "https://gateway…", "reachable": true },
     "plugins": [ … ]
   }
   ```

2. `hebe doctor` calls the same aggregator (the function lives in `gateway` or a small `status` module; both surfaces consume it).

### Tests / verification

- All-up status returns `Up` everywhere.
- Mock a Telegram channel returning `Down` → reflected in both surfaces.

### Acceptance criteria

- ✅ Single aggregator.
- ✅ Used by `/api/status` and `doctor`.

### References

- `v1-architecture.md` §14
