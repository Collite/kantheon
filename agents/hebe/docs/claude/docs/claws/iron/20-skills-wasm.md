# Skills and the WASM host

This is IronClaw's most distinctive layer and the one most worth porting carefully.

## Two related but separate sandboxes

| Concern | "Skills" | "WASM tools" / "WASM channels" |
|---|---|---|
| What lives in it | Markdown prompts (no code) | Compiled WASM components (Rust→cdylib→WIT) |
| Trust model | Trusted (user-placed) vs Installed (registry, read-only tools) | Capability-driven; default deny |
| Selection | Deterministic prefilter on user message | Always loaded once granted |
| Effect | Adds prompt text + filters tools | Provides callable tool / inbound channel |
| Crate | `crates/ironclaw_skills/` | `src/tools/wasm/` and `src/channels/wasm/` |

Both are called "extensions" in user-facing UX (`ExtensionManager`); the registry is shared (`registry/`).

## Skill model

A skill is a `SKILL.md` (YAML frontmatter + markdown body) with:

```yaml
---
name: coding
version: "1.0.0"
description: ...
activation:
  keywords:        [up to 20, min 3 chars each]
  exclude_keywords: [veto: any match → score 0]
  patterns:        [up to 5 regexes]
  tags:            [up to 10]
  max_context_tokens: 1500
  setup_marker: ".developer-setup-complete"  # one-shot setups skip after marker exists
---

# Coding Best Practices
...prompt body...
```

`crates/ironclaw_skills/src/types.rs:13-156` enforces the per-skill caps to prevent scoring manipulation. Real example: `skills/coding/SKILL.md`.

### Trust

`SkillTrust` is `Installed=0 < Trusted=1` (`crates/ironclaw_skills/src/types.rs:43-50` — variant ordering is **load-bearing** for security; the comment explicitly forbids reordering). `SkillSource` distinguishes `Workspace`, `User` (`~/.ironclaw/skills/`), `Installed` (`~/.ironclaw/installed_skills/`), `Bundled`.

`src/skills/attenuation.rs::attenuate_tools` filters the tool list based on the lowest trust among active skills: Installed skills can only see read-only tools.

### Selection pipeline (deterministic, LLM-free)

`crates/ironclaw_skills/src/selector.rs:prefilter_skills` runs each turn:

1. **Gating** (load-time) — `gating::check_requirements` verifies bin/env/config requirements.
2. **Scoring** per skill, given user message:
   - Keyword exact match: 10 pts (cap 30)
   - Keyword substring: 5 pts (cap 30)
   - Tag match: 3 pts (cap 15)
   - Regex pattern match: 20 pts (cap 40)
   - `exclude_keywords` match: hard zero
3. **Budget** — sort descending, fit within `SKILLS_MAX_TOKENS` (default 4000, `selector.rs:16`). `try_select` returns `BudgetFull` / `MarkerSatisfied` / `CandidateLimit` and the outcome reports human-readable notes.
4. **Attenuation** — `attenuate_tools` filters tool definitions by trust ceiling.

The deterministic property is critical: a loaded skill cannot manipulate which other skills get loaded (`selector.rs:1-11`). Notes from `SelectionOutcome` flow into the `SkillActivated` `StatusUpdate` (`src/channels/channel.rs:565-569`) so the UI can show a sub-bullet "chain-loaded from code-review" or "budget exhausted".

### Skill credentials

Skills can declare credentials in frontmatter (`SkillCredentialSpec`, `SkillCredentialLocation`). At skill registration the host converts these to `CredentialMapping`s (`src/skills/mod.rs:46-60`) and registers them with `SharedCredentialRegistry`. OAuth descriptors are upserted via `crate::auth::upsert_auth_descriptor`.

### v2 placement

In engine v2 the selector logic is **not** in Rust — it lives in the Python orchestrator (`orchestrator/default.py:score_skill`). The Rust crate keeps types/parser/v2 metadata; selector/gating/registry/catalog are v1-only. See `crates/ironclaw_skills/src/lib.rs:1-50`.

## WASM host (Wasmtime + WIT component model)

Two parallel hosts:

- `src/tools/wasm/` — sandboxed **tools** (callable functions)
- `src/channels/wasm/` — sandboxed **channels** (inbound message sources)

Both use Wasmtime 43 with the component model and WASI Preview 2. WIT files in `wit/` are the contract.

### `wit/tool.wit` highlights

```wit
world sandboxed-tool { import host; export tool; }

interface tool {
    execute: func(req: request) -> response;
    schema:  func() -> string;
    description: func() -> string;
}

interface host {
    log, now-millis, workspace-read,         // base
    http-request,                            // capability-gated
    tool-invoke,                             // capability-gated
    secret-exists,                           // never exposes values
}
```

### `wit/channel.wit` highlights

```wit
world sandboxed-channel { import channel-host; export channel; }

interface channel {
    on-start(config-json) -> channel-config  // declares HTTP endpoints + poll config
    on-http-request(req)  -> response
    on-poll()
    on-respond(agent-response) -> result
    on-status(update)
    on-broadcast(user-id, response) -> result
    on-shutdown()
}
```

The host runs a single event loop (HTTP router + polling scheduler + timer scheduler) and dispatches to `on-*` callbacks. Channels return `EmittedMessage`s that flow into the agent (`wit/channel.wit:43-186`).

### Runtime

`src/tools/wasm/runtime.rs:32-310` and `src/channels/wasm/runtime.rs` (parallel) handle Wasmtime engine setup:

- Component model + WASI Preview 2 enabled, threads disabled.
- Fuel metering for CPU limit (`FuelConfig`, default 500M for channels, less for tools).
- Epoch interruption for hard timeouts. A dedicated background thread `wasm-epoch-ticker` increments the engine's epoch every 500ms (`runtime.rs:213-227`); without it `epoch_deadline_trap` never fires.
- Persistent compilation cache (Wasmtime serializes compiled native code to disk under `~/.cache/wasmtime` or per-engine on Windows to avoid `ERROR_LOCK_VIOLATION` `#448`, `runtime.rs:32-72`).
- `PreparedModule` caches compiled `Component` keyed by name; instantiation creates a **fresh instance per execution** ("compile once, instantiate fresh" — explicit NEAR-blockchain pattern, `runtime.rs:1-3`). No mutable state survives across calls.

### Capabilities (`src/tools/wasm/capabilities.rs:21-78`)

`Capabilities` is the per-tool grant set, default-empty:

```rust
struct Capabilities {
    workspace_read: Option<WorkspaceCapability>,   // allowed_prefixes
    http: Option<HttpCapability>,                  // allowlist + credentials + rate limits
    tool_invoke: Option<ToolInvokeCapability>,     // alias map (indirection layer)
    secrets: Option<SecretsCapability>,            // allowed_names (existence check only)
    webhook, websocket: ...
}
```

Loaded from `<tool>.capabilities.json` adjacent to the WASM artifact. Patterns from `validate_path_pattern` (skills crate) are reused.

### Credential injection

Defined in `src/tools/wasm/credential_injector.rs`. The flow:

1. Tool loads with `HttpCapability { credentials: HashMap<secret_name, CredentialMapping> }`.
2. Each `CredentialMapping` declares `host_patterns`, `path_patterns`, and `location` (Bearer / BasicAuth / Header { name, prefix } / QueryParam { name }).
3. Before each WASM call, `resolve_host_credentials` decrypts secrets from `SecretsStore`, builds a `ResolvedHostCredential` (no `Debug` derive — has hand-rolled redacting `Debug` impl, `wrapper.rs:88-130`).
4. When the WASM module calls `host.http-request(...)`, the host:
   - validates URL host/path against the allowlist
   - applies the matching `ResolvedHostCredential` (host pattern + path prefix specificity tiebreak)
   - sets up `ssrf_safe_client_builder` (rejects private IPs, with optional override for tests)
   - executes the request
   - scans the response for leaked secret material (`LeakDetector`)
   - returns sanitized response or `Err`

WASM never sees the raw token. OAuth refresh is transparent: `OAuthRefreshConfig` (`src/tools/wasm/wrapper.rs:51-81`) carries `token_url`, `client_id`, optional `client_secret`, optional `exchange_proxy_url` (a hosted OAuth proxy at e.g. `host.docker.internal:8080`), and `extra_refresh_params`. Pre-call hook `resolve_host_credentials` refreshes expired tokens before injection.

### Workspace writes from WASM channels

`channel-host.workspace-write` automatically prefixes paths with `channels/<name>/` (`wit/channel.wit:187-195`) and rejects `..` traversal. Same prefix logic for reads. Hard 20MB / 50MB caps for `store-attachment-data` (`wit/channel.wit:149-158`).

### Limits and security knobs (`wit/channel.wit:34-42`)

- WASM channels are untrusted — fresh instance per callback, no shared mutable state.
- Capabilities opt-in; default deny.
- Secrets never exposed; injected at host boundary.
- Workspace writes prefix-namespaced.
- Message emission rate-limited (per-execution: 100 messages, 64KB content; per-channel: configurable global rate limiter).
- `log` rate-limited to 1000 entries/exec, 4KB/message.

### Loader and registry

`src/tools/wasm/loader.rs` walks `~/.ironclaw/tools/` and bundled paths. `src/registry/` defines `ExtensionManifest`, `BundleDefinition`, `ArtifactSpec`, `RegistryCatalog` (loads from filesystem + embedded JSON), and `RegistryInstaller` (downloads, verifies SHA-256, installs).

Artifact host allowlist (`src/registry/installer.rs:14-46`): only `github.com`, `objects.githubusercontent.com`, `*.githubusercontent.com`, `raw.githubusercontent.com`. New trusted hosts must be added explicitly. Fallback rule: a checksum mismatch on `releases/latest/` falls back to source build (latest is a moving target); a mismatch on a version-pinned URL is a hard block.

Signed channels: `src/channels/wasm/signature.rs` validates `ed25519-dalek` signatures over the WASM bytes before loading. Runtime config keys are restricted (`src/channels/wasm/runtime_config_keys.rs`) to limit what host-side config the guest can introspect.

### Lifecycle

Channel:
1. Discover artifact + capabilities (`channels/wasm/loader.rs`)
2. `prepare()` compiles the component, caches the `PreparedChannelModule`.
3. `register_channel()` (`channels/wasm/setup.rs:241+`) calls `on-start` to get `ChannelConfig`; registers HTTP endpoints with the unified `WebhookServer`; registers signing/HMAC keys; loads credentials.
4. `WasmChannel` (the `Channel` impl in `wrapper.rs`) is added to `ChannelManager`. Each inbound HTTP request → `on-http-request` callback in a fresh instance; emitted messages go through `inject_tx` of the manager.
5. On SIGHUP reload, secrets are hot-swapped via `ChannelSecretUpdater` trait (`channels/channel.rs:937-948`).

Tool: similar but no HTTP endpoints; tool is registered into `ToolRegistry` and invoked by the dispatcher.

## Take-aways for the Kotlin port

Skills as deterministic prompt extensions are easy to port; the cap arithmetic and trust ceiling are pure logic. The WASM sandbox is the hard part:

- Wasmtime has a Java-friendly cousin? Not really — JVM hosts typically use Chicory (interpreter, no component model yet) or Wasmer-Java (limited). The component model + WIT bindgen is a Rust-leaning stack.
- A Kotlin port likely substitutes WASM with **GraalVM polyglot** (Python/JS/WASM via Truffle) or reuses Wasmtime via the C API + JNI. Both are non-trivial.
- Alternatively, port the **capability model** (allowlist, default-deny, host-side credential injection, leak scan, rate limit) to whatever sandbox you choose — the host-boundary interface is what's load-bearing, not Wasmtime specifically.
- The MCP tool path (`src/tools/mcp/`) is a sandbox-free escape hatch for "external server integrations that belong outside the main binary" (`AGENTS.md:69-72`). For the Kotlin port, MCP-everywhere may be a saner default; reserve WASM for cases where you must run third-party code in-process.
