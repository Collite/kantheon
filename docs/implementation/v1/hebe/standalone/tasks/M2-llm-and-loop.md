# M2 — LLM + agent loop

OpenAI-compat client wrapped behind `LlmProvider`, koog wrapped, dispatcher state machine, hooks, `ChatDelegate`, loop detector, cost guard, compaction ladder, agent facade.

**Done when:** a mock-LLM end-to-end test runs a multi-tool turn through the dispatcher, with receipts written and memory appended.

References: [`../v1-architecture.md`](../v1-architecture.md) §§3, 5, 9, 10, 13a, 20, 22.

---

## M2.T1 — `OpenAiCompatProvider` (Ktor + streaming + tool use)

**Status**: pending  
**Size**: L  
**Depends on**: M0.T5, M0.T9  
**Blocks**: M2.T3, M1.T7 (shares Ktor client setup), M2.T11

### Goal

A concrete `LlmProvider` backed by an OpenAI-compatible HTTP endpoint. Streams `StreamEvent` correctly for both text deltas and tool calls. BYO key from secrets; baseUrl + model from config.

### Files to create

- `modules/providers/openai-compat/build.gradle.kts` (edit)
- `modules/providers/openai-compat/src/main/kotlin/com/hebe/providers/openai/OpenAiCompatProvider.kt` (new)
- `modules/providers/openai-compat/src/main/kotlin/com/hebe/providers/openai/Wire.kt` (new — request/response DTOs)
- `modules/providers/openai-compat/src/main/kotlin/com/hebe/providers/openai/SseParser.kt` (new — server-sent-events stream parser)
- `modules/providers/openai-compat/src/main/kotlin/com/hebe/providers/openai/HttpClientFactory.kt` (new)
- Tests with recorded fixtures (uses cross-cutting X.T1)

### Detailed work

1. Deps:

   ```kotlin
   dependencies {
       api(project(":modules:api"))
       implementation(project(":modules:observability"))
       implementation(libs.bundles.ktor.client)
       implementation(libs.kotlinx.serialization.json)
   }
   ```

2. `Wire.kt` — Chat Completions request/response shapes:

   ```kotlin
   @Serializable data class ChatCompletionRequest(
       val model: String,
       val messages: List<WireMessage>,
       val tools: List<WireTool>? = null,
       @SerialName("tool_choice") val toolChoice: JsonElement? = null,
       val temperature: Double = 0.7,
       @SerialName("max_tokens") val maxTokens: Int? = null,
       val stream: Boolean = true,
   )
   @Serializable data class WireMessage(val role: String, val content: JsonElement?, val toolCalls: List<WireToolCall>? = null, val toolCallId: String? = null)
   @Serializable data class WireTool(val type: String = "function", val function: WireFunction)
   @Serializable data class WireFunction(val name: String, val description: String, val parameters: JsonObject)
   @Serializable data class WireToolCall(val id: String, val type: String = "function", val function: WireToolCallFn)
   @Serializable data class WireToolCallFn(val name: String, val arguments: String)  // JSON-encoded string
   // streaming chunk:
   @Serializable data class ChatCompletionChunk(val id: String, val choices: List<ChunkChoice>, val usage: Usage? = null)
   @Serializable data class ChunkChoice(val index: Int, val delta: ChunkDelta, @SerialName("finish_reason") val finishReason: String? = null)
   @Serializable data class ChunkDelta(val role: String? = null, val content: String? = null, @SerialName("tool_calls") val toolCalls: List<ChunkToolCall>? = null)
   @Serializable data class ChunkToolCall(val index: Int, val id: String? = null, val type: String? = null, val function: ChunkToolCallFn? = null)
   @Serializable data class ChunkToolCallFn(val name: String? = null, val arguments: String? = null)
   @Serializable data class Usage(@SerialName("prompt_tokens") val prompt: Int, @SerialName("completion_tokens") val completion: Int, @SerialName("prompt_tokens_details") val promptDetails: PromptDetails? = null)
   @Serializable data class PromptDetails(@SerialName("cached_tokens") val cached: Int = 0)
   ```

3. `OpenAiCompatProvider`:

   ```kotlin
   class OpenAiCompatProvider(
       private val baseUrl: String,
       private val apiKey: String,
       private val defaultModel: String,
       private val httpClient: HttpClient,
       private val maxContextTokens: Int = 128_000,
   ) : LlmProvider {
       override suspend fun chat(req: ChatRequest): Flow<StreamEvent> = flow { … }
       override fun capabilities() = ProviderCapabilities(
           streaming = true, toolUse = true, multimodal = false,
           maxContextTokens = maxContextTokens, supportsPromptCaching = true,
       )
   }
   ```

4. **Streaming SSE parsing**: the response is `text/event-stream`. Parse line by line: `data: {...}` chunks separated by blank lines. Terminator: `data: [DONE]`.
   - For each chunk, accumulate the per-`tool_call.index` `function.name` and `function.arguments` deltas (the wire format streams partial JSON arguments as chunks with the same `index`).
   - When `finish_reason == "tool_calls"`, emit one `StreamEvent.ToolCall(ParsedToolCall(...))` per accumulated tool call, then `StreamEvent.Done`.
   - On a streamed `usage` block (some endpoints emit it on the final chunk), emit `StreamEvent.TokenUsage`.
   - Errors → `StreamEvent.Error` with `retriable = (status in 500..599 || status == 429 || isTransport)`.

5. `HttpClientFactory.create()` builds a Ktor client with: `CIO` engine, `ContentNegotiation` + Json, `HttpRequestRetry` plugin (retry on 429/5xx with capped exponential backoff, max 3 attempts), `HttpTimeout` (30 s connect, 5 min read), `Logging` plugin gated on log level.

6. **Auth**: `Authorization: Bearer ${apiKey}` header on every request. The `apiKey` is resolved from `SecretStore` at construction; do not log it.

7. **System messages**: hebe's `ChatRequest.systemPrompt` becomes a `WireMessage(role="system", content=...)` prepended to `messages`.

### Tests / verification

- Recorded-fixture test: feed a recorded SSE stream through `chat(...)`; assert `Flow<StreamEvent>` emits `[TextDelta×N, ToolCall, Done]`.
- Negative: 401 → `StreamEvent.Error(retriable = false)`. 429 → `Error(retriable = true)`.
- Tool-call accumulation: streams with multi-chunk `arguments` reassemble correctly.

### Acceptance criteria

- ✅ Streaming text + tool calls work against a recorded fixture.
- ✅ Capability check returns sensible defaults.
- ✅ API key never logged.
- ✅ Retries on 5xx/429 capped at 3.

### Pitfalls

- The SSE `data:` line can contain JSON with embedded `data:` prefixes (rare). Parse the line, don't substring on `data:`.
- Some OpenAI-compat endpoints (Ollama, some gateways) don't stream `usage` until the very end and may include `usage` only when `stream_options: {include_usage: true}` is sent. Default to sending it.
- `tool_choice` shape: string `"auto"`, `"none"`, `"required"`, OR object `{type:"function",function:{name:"..."}}`. Cover all four mappings from `ToolChoice`.

### References

- `v1-architecture.md` §3 (`LlmProvider`, `StreamEvent`)
- `v1-architecture.md` §13a (provider config)

---

## M2.T2 — Mock `LlmProvider` (replay-based)

**Status**: pending  
**Size**: M  
**Depends on**: M0.T5  
**Blocks**: M2.T11 tests, every loop test

### Goal

A test-only `LlmProvider` that replays a recorded (or programmatically-built) sequence of `StreamEvent`s. Used by every test that exercises the loop without a live provider.

### Files to create

- `modules/providers/openai-compat/src/main/kotlin/com/hebe/providers/openai/MockLlmProvider.kt` (new — or place in a `:modules:test-fixtures` module)
- Builders: `MockLlmProvider.builder().textDelta("Hello").toolCall(...).done().build()`
- Tests of the mock itself

### Detailed work

1. Place this in the `openai-compat` module's `src/main/kotlin` (so other test modules can use it without depending on test sources). Marked `@VisibleForTesting`-equivalent in docs.

2. API:

   ```kotlin
   class MockLlmProvider private constructor(private val script: List<List<StreamEvent>>, private val caps: ProviderCapabilities) : LlmProvider {
       private val turn = AtomicInteger()
       override suspend fun chat(req: ChatRequest): Flow<StreamEvent> = flow {
           val t = turn.getAndIncrement()
           script[t].forEach { emit(it) }
       }
       override fun capabilities() = caps

       class Builder { … }
   }
   ```

3. `Builder` lets tests script multi-turn behaviour:

   ```kotlin
   val provider = MockLlmProvider.builder()
       .turn { textDelta("Hello, "); textDelta("world"); done() }
       .turn { toolCall("file_system_read", json { put("path", "README.md") }); done() }
       .build()
   ```

### Tests / verification

- Unit test: scripted 2-turn exchange replays in order; third turn throws (out of script).

### Acceptance criteria

- ✅ Builder DSL matches the example.
- ✅ Multi-turn scripts work.
- ✅ Available to other modules without `testImplementation` gymnastics.

### References

- `v1-specs.md` §2.12 (mock LLM provider)

---

## M2.T3 — `KoogLlmProvider` adapter

**Status**: pending  
**Size**: L  
**Depends on**: M2.T1  
**Blocks**: M2.T11 (ChatDelegate uses koog under the hood)

### Goal

A second `LlmProvider` impl that delegates to koog. The **only** file in the repo that imports `ai.koog.*` from a public type. Acts as the swappable seam — if we drop koog later, this is the file we replace.

### Files to create

- `modules/core/build.gradle.kts` (edit)
- `modules/core/src/main/kotlin/com/hebe/core/llm/KoogLlmProvider.kt` (new)
- Tests using koog's mock fixtures or the `MockLlmProvider` of M2.T2

### Detailed work

1. Deps:

   ```kotlin
   dependencies {
       api(project(":modules:api"))
       implementation(project(":modules:providers:openai-compat"))
       implementation(libs.koog.core)
   }
   ```

2. `KoogLlmProvider(koogAgent: ai.koog.agents.Agent, …) : LlmProvider`:
   - `chat(req: ChatRequest)` translates `req` → koog's request shape, calls koog, translates koog's stream → `Flow<StreamEvent>`.
   - History compression, retries, agent persistence, OTel — all handled by koog underneath.

3. **The translation layer**: keep all `ai.koog.*` references inside this file. Internal helpers in this file may reference koog types; nothing else in `core/` should.

4. Whether koog is configured to wrap our `OpenAiCompatProvider` or call its own HTTP layer is a design decision for this task — verify which gives streaming + tool-use semantics that match the rest of the loop. **Recommended**: configure koog with our `OpenAiCompatProvider` as its underlying transport (if koog's plugin model supports it); otherwise wrap koog's transport at this seam.

### Tests / verification

- A turn through `KoogLlmProvider` produces the same `Flow<StreamEvent>` shape as `MockLlmProvider`.
- Tool calls emitted by koog round-trip through the adapter.

### Acceptance criteria

- ✅ `KoogLlmProvider` is the only file importing `ai.koog.*` from a public type (Detekt rule or manual review).
- ✅ Shape parity with `OpenAiCompatProvider` and `MockLlmProvider`.

### Pitfalls

- koog v1.0 may not have shipped — confirm the API surface at PR time.
- If koog forces a particular history-compression model that conflicts with our compaction ladder (M2.T9), the adapter may need to disable koog's compression.

### References

- `v1-architecture.md` §5 (agent loop)
- `v1-architecture.md` §1 (koog facade rule)

---

## M2.T4 — `SubmissionParser`

**Status**: pending  
**Size**: M  
**Depends on**: M0.T5  
**Blocks**: M2.T13 (agent facade dispatches on Submission), M5.T2 (CLI slash-commands)

### Goal

Parse an `IncomingMessage`'s content into a `Submission` sealed type before the dispatcher sees it. Slash-commands, approvals, auth-mode, and raw user input are all distinguishable.

### Files to create

- `modules/core/src/main/kotlin/com/hebe/core/submission/SubmissionParser.kt` (new)
- `modules/core/src/main/kotlin/com/hebe/core/submission/SlashCommandParser.kt` (new)
- Tests with property + golden cases

### Detailed work

1. Submission types from arch §3 are:
   - `UserInput(msg)`
   - `SystemCommand(msg, command: SlashCommand)` — `/compact`, `/status`, `/help`, `/skills`
   - `Approval(msg, approvalId, approved)` — text shape `/approve <id>` or `/deny <id>`
   - `AuthMode(msg, purpose, secret)` — when the agent has previously asked for a credential and the channel is in auth-mode for that conversation
   - `QuitCommand(msg)` — `/quit`, `/exit`

2. Parser order:
   1. If `msg.metadata["authMode"] == true` → `AuthMode`. Auth-mode is a per-conversation flag set by `ApprovalGate` (M2.T5) when the agent calls `ask_user(purpose=credential)`.
   2. Else if content starts with `/`:
      - `/quit|/exit` → `QuitCommand`.
      - `/approve <id>` or `/deny <id>` → `Approval`.
      - `/compact|/status|/help|/skills(?:\s+<filter>)?` → `SystemCommand`.
      - Anything else starting with `/` → unknown slash; fall through to `UserInput` (some chat platforms allow `/` in normal text).
   3. Else → `UserInput`.

3. `SlashCommand` is a sealed interface with the v1 commands. Add new ones in later milestones (M3.T11 adds `/estop`).

### Tests / verification

- Property: every input parses to exactly one `Submission`.
- Golden: `/compact`, `/approve 8a4f`, `/quit`, plain text, auth-mode-flagged input.

### Acceptance criteria

- ✅ Sealed parser covers all four variants.
- ✅ Auth-mode interception happens **before** any other branch.
- ✅ Unknown slash commands fall back to `UserInput` rather than throwing.

### References

- `v1-architecture.md` §3 (`Submission`)

---

## M2.T5 — `ApprovalGate`

**Status**: pending  
**Size**: M  
**Depends on**: M1.T2  
**Blocks**: M2.T6 (dispatcher), M5.T5 (web approval UI)

### Goal

Manage `pending_approvals` rows + an in-memory wait map so the dispatcher can suspend a turn pending a human's `Approval` submission.

### Files to create

- `modules/security/build.gradle.kts` (edit)
- `modules/security/src/main/kotlin/com/hebe/security/approval/ApprovalGate.kt` (new)
- `modules/security/src/main/kotlin/com/hebe/security/approval/PendingApprovalsRepo.kt` (new)
- Tests

### Detailed work

1. Deps:

   ```kotlin
   dependencies {
       api(project(":modules:api"))
       implementation(project(":modules:memory"))           // reuse Db for the table
       implementation(project(":modules:observability"))
   }
   ```

2. `PendingApprovalsRepo` wraps `pending_approvals` table CRUD.

3. `ApprovalGate.requestIfNeeded(toolName, args, ctx): ApprovalDecision`:
   - If the security policy returns `Allow` → return `Allow` immediately.
   - If `RequireApproval(prompt)` → insert a row, send the prompt via `ctx.requestor.reply` with an `ApprovalRequest`, then `suspend` until either:
     - The corresponding `Submission.Approval` arrives → `Allow` or `Deny` according to the response.
     - The approval expires (default TTL 24 h) → `Deny`.
     - `hebe estop` → `Deny`.

4. Suspension uses a `Channel<ApprovalDecision>` per pending id, stored in a `ConcurrentHashMap`. The dispatcher calls `gate.await(approvalId)` which suspends on that channel.

5. **Resumption** of an in-flight approval after hebe restarts: on boot, scan `pending_approvals WHERE resolved_at IS NULL` and:
   - If `expires_at < now`, mark expired.
   - Otherwise, expose them via `hebe status` so the operator sees there's a stuck turn. The original turn is gone (process restart); resolution after restart marks the row resolved but doesn't resume the turn.

### Tests / verification

- `requestIfNeeded` with `Allow` returns immediately.
- `requestIfNeeded` with `RequireApproval` blocks until `resolve(id, true)` or `resolve(id, false)`.
- TTL expiry fires after the configured window.

### Acceptance criteria

- ✅ Persists rows in `pending_approvals`.
- ✅ Suspends and resumes correctly.
- ✅ Expiry wakes the suspended caller.
- ✅ Restart-time scan documents the limitation.

### Pitfalls

- The wait map is in-memory; if the process dies mid-turn, the suspended caller is gone — that's the documented behaviour. Receipts must be written *before* suspension so the audit trail captures the request even if the resume doesn't happen.

### References

- `v1-architecture.md` §5 (table), §9 (dispatcher), §22 (security sequencing)

---

## M2.T6 — `ToolDispatcher` skeleton + state machine

**Status**: pending  
**Size**: L  
**Depends on**: M2.T4, M2.T5, M0.T7  
**Blocks**: every tool, every channel

### Goal

The single mutation funnel. Implements the state machine in `v1-architecture.md` §9. Pluggable validators + leak detector + receipts hooks.

### Files to create

- `modules/tools/dispatch/build.gradle.kts` (edit)
- `modules/tools/dispatch/src/main/kotlin/com/hebe/tools/dispatch/ToolDispatcher.kt` (new)
- `modules/tools/dispatch/src/main/kotlin/com/hebe/tools/dispatch/ToolRegistry.kt` (new)
- `modules/tools/dispatch/src/main/kotlin/com/hebe/tools/dispatch/DispatchOutcome.kt` (new — sealed)
- `modules/tools/dispatch/src/main/kotlin/com/hebe/tools/dispatch/Validator.kt` (new — pluggable interface)
- Tests

### Detailed work

1. Deps:

   ```kotlin
   dependencies {
       api(project(":modules:api"))
       implementation(project(":modules:security"))
       implementation(project(":modules:memory"))
       implementation(project(":modules:observability"))
   }
   ```

2. `ToolRegistry`:

   ```kotlin
   class ToolRegistry {
       fun register(tool: Tool) = …
       fun unregister(name: String) = …
       fun get(name: String): Tool? = …
       fun list(): List<Tool> = …
   }
   ```

3. `Validator` interface (security checks plug in here):

   ```kotlin
   interface Validator {
       suspend fun validate(call: ParsedToolCall, tool: Tool, ctx: ToolContext): ValidationResult
   }
   sealed interface ValidationResult {
       data object Allow : ValidationResult
       data class RequireApproval(val prompt: String) : ValidationResult
       data class Deny(val reason: String) : ValidationResult
   }
   ```

   Concrete validators (registered in M3): `AutonomyValidator`, `WorkspaceBoundaryValidator`, `CommandPolicyValidator`, `DomainAllowlistValidator`, `PromptInjectionValidator`. Each runs in order; first non-`Allow` wins.

4. `ToolDispatcher.dispatch(call, ctx): DispatchOutcome`:

   ```kotlin
   suspend fun dispatch(call: ParsedToolCall, ctx: ToolContext): DispatchOutcome {
       val tool = registry.get(call.name) ?: return DispatchOutcome.Result(ToolResult.Err("unknown tool: ${call.name}"))
       loopDetector.fingerprint(ctx.turnId, call)
       observer.span("dispatch.${call.name}").use { span ->
           // 1. validators
           val v = validators.fold(ValidationResult.Allow as ValidationResult) { acc, v ->
               if (acc is ValidationResult.Allow) v.validate(call, tool, ctx) else acc
           }
           when (v) {
               is ValidationResult.Deny -> return resultErr("policy: ${v.reason}", call, ctx)
               is ValidationResult.RequireApproval -> approvalGate.requestIfNeeded(call, v.prompt, ctx).let { if (it is Decision.Deny) return resultErr("denied", call, ctx) }
               ValidationResult.Allow -> Unit
           }
           // 2. invoke
           val raw = runCatching { tool.invoke(call.args, ctx) }
               .getOrElse { ToolResult.Err("tool exception: ${it.message}") }
           // 3. leak scan
           val scanned = leakDetector.scan(raw)
           // 4. receipts append
           val seq = receipts.append(buildReceipt(call, scanned, ctx))
           // 5. memory append
           memory.appendMessage(ctx.sessionId, ConversationMessage(role = ChatRole.Tool, content = scanned.json(), …))
           // 6. observer event
           observer.event(ObserverEvent.ToolDispatched(ctx.turnId, call.name, span.duration, ok = scanned is ToolResult.Ok))
           // 7. hooks
           hooks.afterToolCall(ctx, call, scanned)
           return DispatchOutcome.Result(scanned)
       }
   }
   ```

5. `DispatchOutcome`:

   ```kotlin
   sealed interface DispatchOutcome {
       data class Result(val result: ToolResult) : DispatchOutcome
       data class Pending(val approvalId: String) : DispatchOutcome
   }
   ```

6. **Annotate every mutation with `// dispatch-exempt: …`** since the M0.T10 rule fires here. (The rule's protected-name list will eventually include `state.workspace`, `state.memory` etc.; this dispatcher's own writes are the canonical exempt-path.)

7. Wire-up: every tool, channel, scheduler, hook must call `ToolDispatcher.dispatch` rather than touching state directly. The M0.T10 rule enforces it.

### Tests / verification

- Mock tool registered; dispatch returns `Result.Ok` with receipts appended.
- Validator denying → `Result(Err("policy: …"))`; no receipts written? Decision: **always write a receipt** (even for denied calls) so the audit trail captures attempts. Update test accordingly.
- Validator requiring approval → `DispatchOutcome.Pending(id)`; resolving the approval later runs the tool.

### Acceptance criteria

- ✅ State machine matches arch §9.
- ✅ Receipts written for every dispatch (allow/deny/error).
- ✅ Memory appended for every successful or errored result.
- ✅ Leak scan runs on every tool output.
- ✅ Detekt mutation-funnel rule still passes — no new violations elsewhere.

### Pitfalls

- The order in §9 matters: `loopDetector → policy → approval → invoke → leak → receipts → memory → observer → hooks`. Don't re-order without thinking through edge cases (e.g. running leak before invoke is impossible).
- Tool exceptions must be caught; never let a tool crash the dispatcher.

### References

- `v1-architecture.md` §9 (state machine)
- `v1-architecture.md` §22 (security check sequencing)

---

## M2.T7 — Loop detector

**Status**: pending  
**Size**: M  
**Depends on**: M2.T6  
**Blocks**: nothing direct (built into dispatcher)

### Goal

Flag and ultimately break runaway tool-call loops. 3 warns / 5 force-text on duplicate fingerprints in a turn.

### Files to create

- `modules/tools/dispatch/src/main/kotlin/com/hebe/tools/dispatch/LoopDetector.kt` (new)
- Tests

### Detailed work

1. Per-turn state: `Map<String /*fingerprint*/, Int /*count*/>`.

2. Fingerprint: `sha256(tool.name + canonicalJson(args))`.

3. `LoopDetector.fingerprint(turnId, call)` increments the count.

4. `LoopDetector.shouldWarn(turnId, call): Boolean` (count == 3).

5. `LoopDetector.shouldForceText(turnId, call): Boolean` (count >= 5).

6. The dispatcher consults `shouldForceText` after invoke; if true, it injects a synthetic assistant text "[Loop detector] Repeated identical call; switching to text mode" and aborts the loop.

7. State cleared at end of turn.

### Tests / verification

- Five identical calls → 4th is fine, 5th triggers.
- Different args / different name → not flagged.

### Acceptance criteria

- ✅ Per-turn isolated.
- ✅ Forces text after 5 identical calls.

### References

- `v1-architecture.md` §3 (loop detector pattern)

---

## M2.T8 — Cost guard (per-turn + daily budget)

**Status**: pending  
**Size**: M  
**Depends on**: M1.T2, M2.T1  
**Blocks**: nothing direct (built into ChatDelegate)

### Goal

Reads `llm_calls` to enforce per-turn token cap + daily $-budget. Blocks new turns past daily cap; emits structured budget events.

### Files to create

- `modules/tools/dispatch/src/main/kotlin/com/hebe/tools/dispatch/CostGuard.kt` (new — actually lives wherever feels right; suggested in `core` since it's loop-scoped)
- Move to `modules/core/src/main/kotlin/com/hebe/core/cost/CostGuard.kt`
- Tests

### Detailed work

1. Config (from `HebeConfig`): `dailyUsdCap`, `perTurnTokenCap`. Defaults: $5/day, 100k tokens/turn.

2. `CostGuard.checkAllowed(turnId): CheckResult` queries `llm_calls` for today's spend (cost_micros_usd / 1_000_000) and current turn's tokens. Returns `Allow | DenyDaily(spentUsd) | DenyPerTurn(tokens)`.

3. `CostGuard.recordCall(turnId, tokensIn, tokensOut, costMicrosUsd?)` inserts an `llm_calls` row.

4. Wired by `ChatDelegate` (M2.T11): before each `LlmProvider.chat` call, `checkAllowed`; after each `Done` with `TokenUsage`, `recordCall`.

### Tests / verification

- Spend $4.99 → next call OK; spend $5.01 → denied.
- Per-turn cap: 99k tokens used → next call OK; 101k → denied.

### Acceptance criteria

- ✅ Reads from `llm_calls`.
- ✅ Two cap dimensions (per-turn + daily).
- ✅ Emits `ObserverEvent.LlmCall` on every record.

### Pitfalls

- Some gateways don't return cost; `cost_micros_usd` is nullable. Treat null as "unknown — count as 0 for billing but warn that the cap is unenforceable".

### References

- `v1-architecture.md` §5 (`llm_calls`)

---

## M2.T9 — Compaction ladder

**Status**: pending  
**Size**: L  
**Depends on**: M1.T11, M2.T1  
**Blocks**: M2.T11 (ChatDelegate triggers compaction)

### Goal

Three-step ladder when context approaches the limit: workspace-promote → summarise → refuse-to-truncate. Threshold default 60% (configurable per-channel).

### Files to create

- `modules/core/src/main/kotlin/com/hebe/core/compaction/Compactor.kt` (new)
- `modules/core/src/main/kotlin/com/hebe/core/compaction/CompactionStrategy.kt` (new)
- Tests

### Detailed work

1. `Compactor.maybeCompact(history: List<ChatMessage>, ctx: CompactionCtx): CompactedHistory`:
   - If `tokenCount(history) < threshold * maxContext`, return as-is.
   - Else step 1: **workspace-promote**. Identify large content blobs (e.g. tool results > 4 KB) and move them to `~/.hebe/workspace/context/<turn>-<seq>.md`, replacing them in history with a 1-line reference (`see workspace/context/2026-05-04-1.md`).
   - Re-check token count.
   - Step 2: **summarise**. Call the LLM with a summarisation prompt over the oldest N messages (those past a "keep window" of ~10 turns), replace them with the summary.
   - Step 3: **refuse-to-truncate**. If summarisation fails (e.g. summarisation call errors), do NOT silently drop messages; throw `HebeException.Memory("compaction failed; refusing to truncate")` so the loop returns `Failure` and the operator can intervene.

2. `tokenCount` uses a heuristic: word count × 1.3 (rough English-text token ratio). Better estimators land in v2.

3. `threshold = config.compactionThreshold ?: 0.6`.

### Tests / verification

- History under threshold → unchanged.
- History over threshold with a 5 KB tool result → result moved to workspace, history shrinks.
- Summarisation call fails → exception, not silent loss.

### Acceptance criteria

- ✅ Three-step ladder implemented.
- ✅ Refuse-to-truncate on summarisation failure.
- ✅ Workspace-promote leaves a reference in history.

### References

- `v1-architecture.md` §5 (compaction)

---

## M2.T10 — Preemptive history pruning

**Status**: pending  
**Size**: S  
**Depends on**: M2.T9  
**Blocks**: nothing direct

### Goal

Trim history *before* it overflows. Compactor runs at the start of each turn, not just on overflow.

### Files to create

- `modules/core/src/main/kotlin/com/hebe/core/compaction/PreemptivePruner.kt` (new)
- Tests

### Detailed work

1. `PreemptivePruner.prune(history, ctx)` is called by `ChatDelegate.beforeLlmCall` every iteration.

2. It just delegates to `Compactor.maybeCompact` — the "preemptive" name is documentation; the mechanism is the same as M2.T9. The behavioural difference: M2.T10 ensures compaction runs even on the first iteration of a fresh turn (where history might be loaded from disk and already large).

### Tests / verification

- Loaded-from-disk history exceeding threshold compacts on the first call, not after a failure.

### Acceptance criteria

- ✅ Compactor invoked from `beforeLlmCall`.
- ✅ Threshold respected.

### References

- `v1-architecture.md` §13 (preemptive pruning)

---

## M2.T11 — `ChatDelegate` (implements `LoopDelegate`)

**Status**: pending  
**Size**: L  
**Depends on**: M2.T3, M2.T6, M2.T7, M2.T8, M2.T9  
**Blocks**: M2.T13

### Goal

The v1 `LoopDelegate` for foreground chat turns. Holds the session lock, drives the streaming loop, talks to the dispatcher, channel, memory.

### Files to create

- `modules/core/src/main/kotlin/com/hebe/core/delegate/ChatDelegate.kt` (new)
- `modules/core/src/main/kotlin/com/hebe/core/loop/LoopDriver.kt` (new — `runAgenticLoop`)
- Tests against `MockLlmProvider`

### Detailed work

1. `LoopDriver.runAgenticLoop(delegate, reasoning, ctx, config): LoopOutcome` — concrete implementation of arch §10. Iterates:

   ```
   for iter in 0 until config.maxIterations:
       if (delegate.checkSignals() != Continue) return Stopped
       delegate.beforeLlmCall(ctx, iter)?.let { return it }
       val out = delegate.callLlm(reasoning, ctx)
       when (out):
         is RespondOutput.TextOnly -> when (delegate.handleTextResponse(out.text)):
              FinishWith(text) -> return Response(text)
              ContinueLoop -> continue
         is RespondOutput.WithToolCalls -> delegate.executeToolCalls(out.calls, ctx)?.let { return it }
       delegate.afterIteration(iter)
   return MaxIterations
   ```

2. `ChatDelegate(session, channel, memory, dispatcher, llmProvider, costGuard, compactor, hooks, observer)`:
   - `checkSignals()`: estop + cancel.
   - `beforeLlmCall(ctx, iter)`: cost-guard check; preemptive prune (calls compactor).
   - `callLlm(reasoning, ctx)`: builds `ChatRequest` from `MemoryStore.systemPrompt() + history + tool defs + active skills`, calls `llmProvider.chat`, accumulates the stream into either `TextOnly(text)` or `WithToolCalls(calls)`. While streaming, call `channel.updateDraft` (if supported) batched at every 80 chars.
   - `handleTextResponse(text)`: append assistant message to memory; return `FinishWith(text)`.
   - `executeToolCalls(calls)`: for each call, `dispatcher.dispatch(call, ctx)`; on `Pending`, return `LoopOutcome.NeedApproval` with the request; on `Result`, append to history and continue.
   - `afterIteration(iter)`: cost-guard `recordCall`.

3. Per-session lock: `Mutex` keyed by `sessionId` so concurrent inbound messages on the same session serialise.

### Tests / verification

- Single-turn text response: scripted `MockLlmProvider` → assistant text emitted, memory appended, no tool calls.
- Two-turn tool exchange: turn 1 emits a tool call, dispatcher invokes a mock tool, turn 2 emits text. Receipts written, history appended.
- Approval-required tool: dispatcher returns `Pending`, loop returns `NeedApproval`, channel sees the prompt.
- Max iterations: scripted to call a tool 11 times → returns `MaxIterations`.

### Acceptance criteria

- ✅ Drives the loop per arch §10.
- ✅ Streams to `channel.updateDraft` when supported.
- ✅ All paths produce a deterministic `LoopOutcome`.
- ✅ Per-session mutex prevents racing inbound messages.

### References

- `v1-architecture.md` §10 (loop driver)
- `v1-architecture.md` §3 (`Submission`, `HandleOutcome`)

---

## M2.T12 — `JobDelegate` (minimal, scheduler-side)

**Status**: pending  
**Size**: M  
**Depends on**: M2.T11  
**Blocks**: M8.T3 (routines engine)

### Goal

A sibling `LoopDelegate` for the scheduler. Same loop, **no draft updates**, sequential tools, no session lock (each job is its own scope).

### Files to create

- `modules/core/src/main/kotlin/com/hebe/core/delegate/JobDelegate.kt` (new)
- Tests

### Detailed work

1. `JobDelegate(jobId, memory, dispatcher, llmProvider, hooks, observer)`:
   - `checkSignals()`: estop + cancel.
   - `beforeLlmCall`: same as ChatDelegate.
   - `callLlm`: streams into a buffer (no `updateDraft`); returns the full assistant message at `Done`.
   - `executeToolCalls`: sequential via dispatcher (no parallelism in v1).
   - On completion, write the result back to `jobs.result_json`.

2. Output path: the routine body's text is written to a file (or `MEMORY.md`-appended) per the routine's spec. M8 specifies which.

### Tests / verification

- Scripted single-turn job: assistant text → `jobs.result_json` populated, `jobs.status = done`.
- Tool-using job: tool ran, result captured.

### Acceptance criteria

- ✅ Reuses `runAgenticLoop`.
- ✅ No `updateDraft` calls (background; nothing to update).

### References

- `v1-architecture.md` §10

---

## M2.T13 — `HebeAgent` facade

**Status**: pending  
**Size**: M  
**Depends on**: M2.T11  
**Blocks**: M5.T2 (CLI), M5.T8 (Telegram), M2.T14, M2.T15

### Goal

The single entry point used by channels: `agent.handleMessage(IncomingMessage): HandleOutcome`. Wraps Submission parsing + session resolution + delegate selection.

### Files to create

- `modules/core/src/main/kotlin/com/hebe/core/agent/HebeAgent.kt` (new)
- `modules/core/src/main/kotlin/com/hebe/core/agent/SessionManager.kt` (new)
- Tests

### Detailed work

1. `SessionManager`:
   - In-memory map `sessionId → Mutex` (the per-session lock).
   - Resolves an `IncomingMessage` to a `Session` row (creating if missing).
   - Closes idle sessions after a configurable idle timeout (cleanup is best-effort; cron job handles real cleanup).

2. `HebeAgent.handleMessage(msg)`:
   1. Apply `BeforeInbound` hooks.
   2. `submissionParser.parse(msg)` → `Submission`.
   3. Branch on `Submission`:
      - `SystemCommand`: handle without locking the session (`/help`, `/status`, `/skills`); `/compact` runs `Compactor.maybeCompact` synchronously and replies.
      - `QuitCommand`: signal shutdown of the channel's REPL (CLI only).
      - `Approval`: `approvalGate.resolve(id, approved)`. Reply with confirmation.
      - `AuthMode`: store in `SecretStore`; reply with confirmation.
      - `UserInput`: lock the session, run `ChatDelegate.run(...)`, returning a `HandleOutcome`.
   4. Apply `BeforeOutbound` hooks to the resulting reply.
   5. Return `HandleOutcome`.

3. The agent **does not** call `channel.reply` itself — that's the caller's responsibility. The agent returns the `OutboundMessage`; the channel adapter sends it. This keeps the channel-loop decision out of the agent.

### Tests / verification

- Each `Submission` variant produces the right outcome.
- Per-session lock serialises two concurrent `UserInput`s.
- `BeforeInbound`/`BeforeOutbound` hooks observed.

### Acceptance criteria

- ✅ Single entry point.
- ✅ Submission branching matches arch §6.
- ✅ Per-session lock works.
- ✅ Returns `HandleOutcome` (Done/Pending/NoResponse/Failed).

### References

- `v1-architecture.md` §6 (data flow)
- `v1-architecture.md` §3 (`HandleOutcome`)

---

## M2.T14 — Hooks (`BeforeInbound`, `BeforeToolCall`, `BeforeOutbound`, `OnSessionStart/End`)

**Status**: pending  
**Size**: M  
**Depends on**: M2.T13  
**Blocks**: nothing direct

### Goal

Fail-open lifecycle hook framework. Hooks can mutate or suppress messages; exceptions in hooks are logged but never crash the turn.

### Files to create

- `modules/core/src/main/kotlin/com/hebe/core/hooks/Hook.kt` (new — interface)
- `modules/core/src/main/kotlin/com/hebe/core/hooks/HookRunner.kt` (new)
- Tests

### Detailed work

1. Hook interfaces:

   ```kotlin
   fun interface BeforeInbound { suspend fun apply(msg: IncomingMessage): IncomingMessage? /* null = suppress */ }
   fun interface BeforeOutbound { suspend fun apply(msg: OutboundMessage): OutboundMessage? }
   fun interface BeforeToolCall { suspend fun apply(call: ParsedToolCall, ctx: ToolContext): ParsedToolCall? }
   fun interface OnSessionStart { suspend fun apply(sessionId: String) }
   fun interface OnSessionEnd { suspend fun apply(sessionId: String, outcome: String) }
   ```

2. `HookRunner` holds lists of each. `run` invokes them in registration order; a `null` return short-circuits (suppression). Exceptions are caught, logged, and treated as "no-op".

3. Wire into `HebeAgent` and `ToolDispatcher`.

### Tests / verification

- Hook returning `null` suppresses the message.
- Hook throwing → logged, treated as no-op, turn continues.
- Multiple hooks applied in order; outputs chained.

### Acceptance criteria

- ✅ Five hook points.
- ✅ Fail-open behaviour verified.
- ✅ Registration order respected.

### References

- `v1-specs.md` §2.1 (hooks)

---

## M2.T15 — Auth-mode interception

**Status**: pending  
**Size**: M  
**Depends on**: M2.T4, M2.T13  
**Blocks**: tools that need creds (M4.T9 `github`, future)

### Goal

When the agent (or a tool) requests a credential via `ask_user`, subsequent messages on that conversation are intercepted and routed to `SecretStore`, never reaching `messages` table or the LLM.

### Files to create

- `modules/core/src/main/kotlin/com/hebe/core/auth/AuthMode.kt` (new)
- Tests

### Detailed work

1. State: `Map<conversationId, AuthRequest>` in memory, plus a row in a small `auth_pending` table (V7 migration; alternatively, reuse `pending_approvals`).

2. When a tool calls `ask_user(purpose="credential", secretName="github_pat")`, the dispatcher returns `Pending`, and the agent enters auth-mode for this conversation.

3. The next inbound message from the operator on this conversation is parsed by `SubmissionParser` as `AuthMode(msg, purpose, secret)`. `HebeAgent` handles it:
   - `secretStore.put(secretName, secret)`.
   - Resume the suspended turn (similar to ApprovalGate resume).
   - **The raw secret never enters the `messages` table** — only a redacted "[credential entered]" placeholder.

4. If the operator types something that doesn't look like a credential (e.g. `/help`) while in auth-mode, exit auth-mode and treat as a normal `Submission`.

### Tests / verification

- Auth-mode entry, credential capture, secret stored, resume happens.
- Verify `messages` table contains no row with the raw credential.
- Cancel: `/cancel` exits auth-mode without storing.

### Acceptance criteria

- ✅ Credentials captured securely.
- ✅ Redacted placeholder in transcripts.
- ✅ Cancel path works.

### Pitfalls

- The redacted placeholder is the only audit trail of "user entered a credential here"; that's intentional. Document.

### References

- `v1-architecture.md` §3 (`Submission.AuthMode`)
- `v1-specs.md` §2.10 (security)
