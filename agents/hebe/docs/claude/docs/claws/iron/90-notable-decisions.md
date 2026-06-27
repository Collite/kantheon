# Notable design choices

Things to study, things worth stealing, and things to avoid.

## Worth stealing

1. **One mutation funnel: `ToolDispatcher::dispatch`.** Web clicks, CLI commands, routines, the LLM, and WASM channels all call the same dispatcher. Consequences: every mutation has audit trail (`ActionRecord`), uniform safety pipeline, channels become interchangeable. The pre-commit hook (`scripts/pre-commit-safety.sh`) enforces it by flagging direct `state.{store, workspace, …}` access in handler files. **Lesson**: pick this rule on day one and lint for it; retrofitting is the painful path their migration is on.

2. **Deterministic skill selection.** `prefilter_skills` is pure logic — no LLM call, no embeddings, no model choice. Selection of "what context the LLM sees" cannot itself be LLM-driven, otherwise a malicious or stale skill could manipulate which skills get loaded (`crates/ironclaw_skills/src/selector.rs:1-11`). Steal the principle: any prompt-augmentation step that decides what enters context must not itself be an LLM call.

3. **Capabilities default-deny + host-side credential injection.** WASM tools never see secrets. The host resolves them from `SecretsStore`, matches host/path patterns, injects via `CredentialMapping` (Bearer / BasicAuth / Header / QueryParam), and runs a `LeakDetector` on the response (`src/tools/wasm/wrapper.rs`). The `secret-exists` host import lets WASM check existence without reading. **Lesson**: plan the auth boundary in your sandbox interface from day one. "We'll just pass credentials in" turns into a leak.

4. **Identity files always read from primary scope.** Multi-scope reads in the workspace are a feature, but reading `AGENTS.md` / `SOUL.md` / `USER.md` from a secondary scope would let one user silently inherit another's identity if their own copy is missing (`src/workspace/README.md:94-114`). This is a correctness bug masquerading as a feature.

5. **Two thread-id types: trusted vs untrusted.** `ExternalThreadId::new` (validates) vs `from_trusted` (skips validation). The choice between them is the audit trail (`src/channels/channel.rs:174-217`, `.claude/rules/types.md`). Apply this pattern to anything crossing a trust boundary.

6. **`HandleOutcome::Pending` ≠ `NoResponse`.** The agent loop must distinguish "turn done, no text" from "turn paused, awaiting user". The web UI's missing-response safety net would otherwise close the turn early (`src/agent/agent_loop.rs:46-79`, issue #2079).

7. **Duplicate-tool-call detector.** ~200 LOC defends against LLM thrash loops: hash batch fingerprint + all-failed flag, escalate at 3 (warn) and 5 (force text-only) consecutive identical failures (`src/agent/agentic_loop.rs:140-207`). Cheap, generic, prevents real-world loops.

8. **The compaction ladder, with refusal to truncate on summarization failure.** Three strategies chosen by usage% (80/85/95), and if the summarization LLM call fails the error propagates rather than dropping data (`src/agent/CLAUDE.md:80-93`). Quality choice.

9. **Module-owned init.** Module factories live in the owning module; `app.rs`/`main.rs` only orchestrates. Feature-flag `#[cfg(feature = ...)]` branching stays inside the module. `AGENTS.md:36-41`. This keeps the build for `--no-default-features` sane.

10. **`CostGuard` with explicit before/after contract.** No auto-recording; the caller must `check_allowed()` then `record_llm_call()`. Trivial to mock in tests; impossible to "forget to record" because both sides are visible at the call site.

11. **Self-repair with notification dedup.** `notified_manual: HashSet<Uuid>` prevents spamming the user when a permanently-failing job is re-detected every cycle. `Retry` results suppress notifications entirely. Small touch, big UX win.

12. **`is_agent_broadcast` and `triggering_mission_id` recursion guards.** When a Slack/Discord adapter re-delivers the agent's own outbound text as an inbound event, missions whose patterns match their own output would loop forever. The flags break that. Plan for echo loops in any "events trigger work" architecture.

13. **`inject_tx` mpsc inside the `ChannelManager`.** Heartbeat / routines / job monitors push messages without being a full `Channel`. Same merged stream the channels feed into. Cleaner than two parallel pipelines.

14. **WIT/component model + WASI Preview 2 + persistent compilation cache.** Wasmtime serializes compiled native code under `~/.cache/wasmtime`; subsequent startups deserialize 10–50× faster (`src/tools/wasm/runtime.rs:32-72`). Per-engine subdir on Windows to avoid `ERROR_LOCK_VIOLATION` on memory-mapped files (`#448`).

15. **Engine v2's primitives.** Five primitives (Thread, Step, Capability, MemoryDoc, Project) replacing ten v1 abstractions. Even if you don't copy v2 directly, the **collapse** is the right direction: a thread is a job is a routine is a sub-agent. Capability is a tool is a skill is a hook is an extension. Memory is a doc is a chunk.

16. **Engine v2 capability leases.** Time-limited, use-limited, scoped grants instead of static permissions. The policy engine is deterministic: `Deny > RequireApproval > Allow`. Effects (ReadLocal, WriteExternal, CredentialedNetwork, …) are declared per action and matched against policy. This is more principled than `requires_approval` booleans.

17. **The ironclaw_engine crate has zero dependency on the host crate.** Pure types + traits + execution loop. Host adapts existing `LlmProvider`/`Database`/`ToolRegistry` to engine traits in `src/bridge/`. This boundary is what makes v2 testable in isolation. Mirror it for koklyp.

18. **Pre-resolved `ResolvedHostCredential` has hand-rolled redacting `Debug`.** No `#[derive(Debug)]` because an accidental `{:?}` in a future log line could leak secrets (`src/tools/wasm/wrapper.rs:88-130`). Same kind of care should appear in any secrets-bearing struct.

## Subtle gotchas

- **`#[derive(Ord)]` is load-bearing for `SkillTrust`.** `Installed=0 < Trusted=1` ordering is what makes `min(...)` give the lowest trust; reordering variants would silently break attenuation. Comment on the type tells you so explicitly (`crates/ironclaw_skills/src/types.rs:43-50`).

- **Empty `Capabilities` for newly-built tools.** When the auto-tool-builder produces a new WASM tool, capabilities are empty. There is currently no UX to grant them (`CLAUDE.md:325-326`). The build pipeline outpaces the permission UX.

- **MCP transports are all request-response.** No streaming yet (`CLAUDE.md:329`). If your Kotlin port plans to use MCP for streaming tool outputs, prepare to add it.

- **`tracing::info!` corrupts the TUI.** Background tasks (reflection, trace analysis) must use `debug!` only. `info!` is reserved for user-facing status the REPL renders (`CLAUDE.md:28`). If you build a TUI, decide your logging contract early.

- **`unwrap()`/`expect()` are forbidden in production.** Lint config plus convention. Tests are fine. Production exceptions need a safety comment.

- **Auto-broadcast loop danger.** Heartbeat notifications use `broadcast` first (configured channel), fallback to `broadcast_all`. In multi-tenant mode, the per-user loop overrides `notify_target` from response metadata (`agent_loop.rs:914-963`) — there's a subtle invariant that the response producer must put `owner_id` in metadata for the multi-tenant path.

## Things to avoid in the Kotlin port

1. **Don't try to clone the WASM host wholesale.** Wasmtime + WIT bindgen is a Rust-shaped stack. JVM equivalents (Chicory, Wasmer-Java) lack the component model. Options:
   - Use **GraalVM polyglot** (Truffle-based; supports Python/JS/WASM, has a similar capability story via Polyglot Access).
   - Use **Wasmtime via JNI** (works but you carry a native dep).
   - Drop in-process untrusted-code execution and **lean harder on MCP** for plugin-style integrations. MCP-everywhere is simpler for v1.
   The capability model itself (allowlist, host-side credential injection, leak scan, rate limit) is what's load-bearing — port that, not Wasmtime.

2. **Don't ship two engines simultaneously.** The v1/v2 coexistence is a migration artifact; the bridge code (`src/bridge/`) is non-trivial overhead. Pick one model upfront — v2's primitives are more principled.

3. **Don't conflate `extension_name` with `credential_name`.** This is one of two `# Extension/Auth Invariants` rules in `CLAUDE.md:36-65`. Examples:
   - Telegram: `credential_name = telegram_bot_token`, `extension_name = telegram`
   - Gmail: `credential_name = google_oauth_token`, `extension_name = gmail`
   Resolve `extension_name` once in shared backend logic and carry it through the wire contract; do not re-derive in multiple layers.

4. **Don't fold v1 `pending_auth` compatibility into v2.** The web auth-prompt path has two modes: with `request_id` (v2 `/api/chat/gate/resolve`) and without (legacy `pending_auth`). Keep them isolated; do not add features to the legacy mode (`CLAUDE.md:66-71`). For koklyp, just don't ship the legacy mode.

5. **Don't make settings persistence transactional with secrets.** Three layers: bootstrap config (env/TOML), DB-backed settings (`settings` table), encrypted secrets (AES-GCM in DB, master key in OS keychain). Collapsing them creates schema/encryption coupling that bites later (`AGENTS.md:50-56`).

6. **Don't put prompt templates in source code.** Multi-line prompts (mission goals, system prompts, CodeAct preambles) live in `crates/ironclaw_engine/prompts/*.md` and are loaded via `include_str!()`. Inline string constants are unreviewable (`CLAUDE.md:27`). For Kotlin: load from `resources/prompts/*.md` via classloader.

7. **Don't add a `pub use` re-export shim across crates.** `src/safety/mod.rs` and `src/skills/mod.rs` used to glob-re-export the extracted-crate types. Now they don't, and consumers `use ironclaw_safety::SafetyLayer` directly (`CLAUDE.md:79-81`). Keeps imports honest about which crate owns what.

8. **Don't hold any other lock when calling `Scheduler::schedule()`.** Schedule holds the write lock for the entire check-insert (TOCTOU prevention). Calling it under another lock is a deadlock recipe (`src/agent/CLAUDE.md:128`).

9. **Don't trust Docker containers or external services.** Even your own sandbox container. The orchestrator's per-job bearer tokens, the proxy's domain allowlist, the credential injection at the proxy — all of these treat the sandbox as untrusted (`AGENTS.md:62-63`).

10. **Don't proactively delete LLM data.** Reasoning, tool calls, messages — never strip, never truncate, never delete. Mark with timestamps and filter, but retain everything (`CLAUDE.md:74-77`).

## Open questions / uncertainty

- **WIT bindgen extraction for tool schemas is partial** (`CLAUDE.md:325`). The `extract_wasm_metadata` flow (`src/tools/wasm/runtime.rs:265-299`) briefly instantiates the component to call `description()` and `schema()`, with fallbacks. The "auto-extract from WASM" claim in the README is more aspirational than complete. If you want strong-typed tool schemas at compile time in Kotlin, plan to author them by hand.

- **The `engine_v2` flag's coverage** is ambiguous from the Rust side; the bridge code has many paths that branch on it, and a complete inventory of "what runs in v2 vs v1 today" would require runtime tracing. The CLAUDE.md docs document v2 as the future, but the production chat flow appears to still be v1.

- **Multi-tenant heartbeat invariants.** The "owner_id from response metadata overrides notify_target" path (`agent_loop.rs:914-963`) was clearly added later; the contract for what producers must put in metadata isn't fully spelled out. For multi-tenant koklyp, plan to define this explicitly.

- **The Docker sandbox's NDJSON daemon protocol** isn't formally specified anywhere I could find — it's defined by the Rust types in `src/sandbox/` and the daemon binary in `src/bin/`. If you copy the per-project sandbox model, plan to write the protocol spec.
