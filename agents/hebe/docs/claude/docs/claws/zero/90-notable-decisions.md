# Notable design choices

Things to study, things worth stealing, and things to avoid. Compared with IronClaw where directly relevant.

## Worth stealing

1. **Three-trait kernel ABI (`Provider`, `Channel`, `Tool`).** Defined once in `zeroclaw-api`. Everything else feature-flagged. This is the load-bearing decision the microkernel transition is built on. The koklyp Kotlin port should pick its kernel surface day one and stick to it.

2. **Extism for plugin sandboxing.** Lighter than Wasmtime + component model, has a JVM SDK, JSON-string-based protocol that serialises trivially with kotlinx.serialization. For a JVM/Kotlin agent, this is the practical sandbox choice — accept the lower expressivity in exchange for a working, well-supported host.

3. **OS-level sandboxes per-tool, auto-detected.** Landlock / Bubblewrap / Firejail / Seatbelt / Docker / AppContainer chosen at runtime. The agent never has to know which is in use. JVM equivalent: process-level sandbox (Docker/`firejail`/`bwrap` invoked as a child process) since Kotlin can't drive Landlock directly. The pattern still applies.

4. **Tool receipts as a chained Ed25519 log.** Each receipt links to the previous via hash. Tamper-evident audit. Independent of the memory DB. Greppable on disk. For koklyp: a single append-only file under `~/.koklyp/receipts/YYYY-MM.log` is enough.

5. **agentskills.io skill format with plugin namespacing.** `plugin:<plugin>/<skill>` IDs avoid collisions across plugins and with user skills. Adopt the format verbatim — interoperability with other agents costs nothing and gains a lot.

6. **`filter_tool_specs_for_turn` with `Always`/`Dynamic` MCP groups.** MCP servers can advertise hundreds of tools; gating by keyword keeps the prompt slim. This is a small, reusable piece of code (~50 LOC) with outsized impact on token budget.

7. **Channel-side `tools_allow` allowlist.** Per-channel tool restriction. The model never sees tools it can't use, so it can't try and fail. Mirror it.

8. **SOPs as a separate execution path.** Predictable, deterministic, step-driven, with per-step approval. Separate from the open-ended chat loop. They share security/sandbox infrastructure but not control flow. For a small-team koklyp this is a great way to encode runbooks ("deploy to staging", "rotate API keys") without trusting the model to invent the steps each time.

9. **`StreamEvent` enum + provider streaming + draft updates.** End-to-end streaming, with channels that support edit-in-place getting per-token UX. The runtime pauses the stream on tool calls and resumes after — clean, doesn't require any provider-specific glue.

10. **Provider router with fallback chain and capability check.** Multiple `[providers.models.<name>]` blocks, hint-based routing (cheap model for chat, heavy for reasoning), automatic failover. The capability check (`streaming?`, `tool_use?`, `multimodal?`) is what keeps the agent from sending requests a provider can't fulfil.

11. **AutonomyLevel as a coarse knob.** Three settings (`ReadOnly` / `Supervised` / `Full`) plus a `YOLO` preset. Each tool's `risk()` is matched against the level. This is a simpler mental model than ironclaw's per-tool `requires_approval` flags.

12. **Pair-check + allowed_users *at the channel adapter*, before the runtime sees the event.** Stops out-of-policy traffic before any LLM cost or risk is incurred. Mirror this even for v1 koklyp.

13. **Plugin-host capability + permission separation.** `capabilities` (what the plugin provides) is a different axis from `permissions` (what the plugin needs from the host). The host gates each host-fn call against the declared permissions. JVM Extism preserves this.

14. **Skillforge: agent-generated skills from successful traces.** Scout → evaluate → integrate. Defer for v1, but it's a research-quality idea worth keeping on the long-term roadmap.

15. **`auto_save` with a min-char threshold.** Below 20 chars, user messages skip auto-save (`AUTOSAVE_MIN_MESSAGE_CHARS`). Cheap noise filter for the memory DB.

16. **`zeroclaw doctor` health-check subcommand.** Inspects config, providers, channels, plugins, sandbox availability, and reports issues. For a self-hosted tool this is worth far more than it costs — most user-facing bugs are environment misconfigurations, and a doctor command makes them self-diagnosable.

17. **`zeroclaw service install/start/stop`.** First-class systemd / launchctl / Windows-Service registration. Don't make the user write unit files.

## Subtle gotchas

- **Plugin capabilities `channel`/`memory`/`observer` are reserved but not implemented** as of the doc date (`docs/book/src/developing/plugin-protocol.md`). The manifest accepts them; the host doesn't act on them. Don't promise capabilities you haven't built.

- **Plugin host functions `file_read`/`file_write`/`memory_read`/`memory_write` are listed but not implemented.** Same caveat.

- **Workspace publish blocked.** `[package] publish = false` on every workspace member, with a comment that the multi-crate publish topology is mid-design (RFC #5579). If you depend on the runtime crate, vendor or use git refs.

- **AppContainer (Windows sandbox) is experimental.** On Windows, expect to fall back to "no sandbox" or Docker.

- **`nodes/` (multi-node coordination) is early.** Don't assume horizontal scaling from this codebase.

- **AGENTS.md / CLAUDE.md** in the zeroclaw repo are AI-collaboration norms, not architectural specs. Architecture lives in `docs/book/src/`. Don't mistake one for the other.

- **40+ channel adapters means 40+ vendor-specific bug surfaces.** Many are community contributions. Don't assume parity of reliability across them. Pick the ones you care about and verify each.

- **The classifier + hint routing has its own failure modes.** A wrong hint sends a question to the wrong model (e.g. cheap model gets a reasoning task it fails). Failure is silent unless you observe the routing decisions.

- **Model-switch via global mutable state.** `MODEL_SWITCH_REQUEST: LazyLock<Arc<Mutex<Option<...>>>>` (`agent/loop_.rs:67-73`) — a process-global. Works for single-tenant zeroclaw; would need rethinking for multi-tenant.

## Things to avoid in the Kotlin port

1. **Don't ship 40+ channels.** Pick the ones the brief calls for (Slack, WhatsApp, Telegram, email) and add others on demand. Each channel is a long-tail of platform-specific bugs.

2. **Don't bundle Qdrant or Postgres for v1.** SQLite + a vector extension is plenty for a self-hosted small-team agent. Add other backends when usage justifies them.

3. **Don't replicate the full memory subsystem** (decay + importance + consolidation + conflict + KG + lucid + audit + hygiene + ...). Pick a subset: `Memory` trait + SQLite backend + hygiene + response cache. Defer the rest.

4. **Don't advertise plugin capabilities you haven't implemented.** ZeroClaw lists `channel`/`memory`/`observer` plugin capabilities and corresponding host-function permissions; only `tool` and `skill` plus `http_client`/`env_read` are real. This is confusing for users. Ship what works; document what's coming separately.

5. **Don't conflate SOPs with routines.** Both are "scheduled or triggered work" but SOPs have step structure + per-step approval; routines are fire-and-forget. Keeping them as two types in the API surface (and possibly two engines) makes both simpler.

6. **Don't put the agent loop and SOP engine on the same `tokio::select!`.** They have different shutdown semantics, different failure recovery, and different observability needs.

7. **Don't try to write your own WASM host.** Use Extism (or whatever the JVM equivalent stabilises into). Plugin host engineering is a multi-quarter project that does not differentiate koklyp.

8. **Don't pick "feature parity with both claws" as a goal.** They make different bets. IronClaw's WIT-typed sandbox + skill selector + workspace-as-memory is one coherent shape; ZeroClaw's microkernel + Extism + SOPs + 40 channels is another. Pick a target, and accept the other tradeoffs explicitly.

9. **Don't expose `YOLO` as the default.** It exists to be obviously named and explicitly chosen. Default to `Supervised`.

10. **Don't try to ship a Tauri desktop app for v1.** ZeroClaw's `apps/tauri/` is a separate effort; it's not what makes the project useful. A web console (Ktor + a small SPA) is a far better v1 surface.

## Open questions / uncertainty

- **`lucid.rs` in `zeroclaw-memory`** — purpose not documented in the surface I read. The name suggests background reflection / "lucid dreaming" — async memory consolidation while the agent is idle? Worth grep'ing the file before relying on the concept.

- **`nevis.rs` in `security/`** — name unclear; possibly a vulnerability scanner ("Nevis" is a Greek mythological figure but no obvious connection). Read the file to confirm.

- **The exact RRF/fusion algorithm in `retrieval.rs`.** Both ironclaw (RRF k=60) and zeroclaw (likely similar) do hybrid search; zeroclaw's exact tuning is undocumented in the surface I read.

- **The `engine_v2`-equivalent in zeroclaw.** ZeroClaw is mid-microkernel-transition (RFC #5574); how far that has progressed in the actual codebase needs auditing. The crate split is real; the kernel-shrinkage may be partial.

- **SOP cookbook examples vs. real-world usage.** The SOP syntax is documented; what's missing is a sense of which SOPs people actually run. Without that signal, designing for SOP-as-first-class might be over-engineering for koklyp.

- **Whether the hook runner has all six event points (BeforeInbound/Outbound/etc.) like ironclaw, or just pre/post-tool-call.** From the surface I read, it's pre/post-tool-call; ironclaw's broader hook surface is more useful for things like message-rewriting middleware.
