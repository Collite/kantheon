# Skills, plugins and extensibility

ZeroClaw has two distinct extension surfaces:

1. **Skills** (markdown only) — agentskills.io-format prompt bundles.
2. **Plugins** (WASM via Extism) — third-party tools (and, planned, channels/memory/observers).

Plus the in-tree extension paths: native channels, native tools, native providers, native peripherals — added by writing Rust against the `zeroclaw-api` traits and feature-flagging into the build.

## Skills (markdown bundles)

Skills follow the [agentskills.io](https://agentskills.io) format — a community standard for portable, prompt-only agent skills. A skill is a directory:

```
my-skill/
  SKILL.md            # YAML frontmatter + markdown body (required)
  scripts/            # optional helper scripts
  references/         # optional reference material
```

`SKILL.md` frontmatter must include `name` and `description`. The runtime rejects bundles missing either at *discovery* time, not first-invocation. This means broken skills surface immediately rather than mid-conversation.

### Where skills come from

- **User-authored** — local files under the workspace's `skills/` directory.
- **Plugin-bundled** — a plugin with `capabilities = ["skill"]` ships skills under its `skills/` subdir; they register under namespaced IDs `plugin:<plugin-name>/<skill-name>`. This avoids collisions across plugins and with user skills.
- **Skillforge** — runtime-generated. `crates/zeroclaw-runtime/src/skillforge/` is a pipeline: `scout` (find candidates from completed traces) → `evaluate` (score by quality and relevance) → `integrate` (commit as a skill). The agent learns its own skills from successful runs.

### Skill modules in the runtime

`crates/zeroclaw-runtime/src/skills/`:

| File | Role |
|---|---|
| `mod.rs` | `Skill` type, registry plumbing |
| `creator.rs` | Programmatic skill creation API |
| `improver.rs` | Skill refinement from feedback |
| `audit.rs` | Skill change log |
| `skill_http.rs` | HTTP-style skill invocations |
| `skill_tool.rs` | Skill exposed as a tool |
| `testing.rs`, `symlink_tests.rs` | Test scaffolding |

### Skill activation in the agent

`Agent` (`agent/agent.rs:23-55`) carries:

- `skills: Vec<Skill>`
- `skills_prompt_mode: SkillsPromptInjectionMode`

Mode determines how skills are injected into the system prompt. The selector is **not as deterministic-by-design** as IronClaw's: ZeroClaw appears to inject skills based on the configured mode + the runtime's classifier, not a strict score-and-budget pipeline. This is one of the clearest places where the two designs diverge.

## Plugin protocol (Extism)

ZeroClaw uses **Extism** (`extism-pdk` for guests, `extism` host) rather than IronClaw's bespoke Wasmtime + WIT component-model stack. Trade-offs:

| Dimension | Extism | Wasmtime + component model |
|---|---|---|
| Maturity in 2026 | Stable, Java/Kotlin SDK exists | Stable but more invasive |
| Component model | No (single function exports) | Yes (rich types via WIT) |
| Type contracts | JSON-string in/out | WIT-typed |
| Host functions | Permission-gated, simple | Capability-typed |
| Sandboxing | WASI Preview 1 | WASI Preview 2 |
| Plugin size | Smaller | Larger (component overhead) |
| JVM/Kotlin port | Trivial via `extism` Java SDK | Hard (no JVM component-model host) |

**For koklyp this is good news**: Extism has a first-class JVM SDK. A Kotlin port can adopt ZeroClaw's plugin model nearly verbatim and skip the component-model engineering.

### Plugin layout

```
my-plugin/
  manifest.toml      # metadata + permissions + signature
  plugin.wasm        # compiled module (cdylib)
  skills/            # optional, only if capabilities includes "skill"
    foo/SKILL.md
```

`manifest.toml`:

```toml
name = "my-plugin"
version = "0.1.0"
description = "..."
author = "..."
wasm_path = "plugin.wasm"
capabilities = ["tool"]                # tool | channel | memory | observer | skill
permissions  = ["http_client"]         # http_client | env_read | (file/memory: future)
signature = "base64url..."             # Ed25519 over plugin.wasm (optional)
publisher_key = "hex..."               # corresponding public key (optional)
```

Discovery path: `~/.zeroclaw/plugins/` (or `plugins.plugins_dir` in config).

### Required guest exports

```rust
#[plugin_fn]
pub fn tool_metadata(_input: String) -> FnResult<String> {
    // returns JSON: { name, description, parameters_schema (JSON Schema) }
}

#[plugin_fn]
pub fn execute(input: String) -> FnResult<String> {
    // input: JSON matching parameters_schema
    // output: JSON { success: bool, output: string, error: ?string }
}
```

`tool_metadata` is called once at load to produce the `ToolSpec` shown to the LLM. `execute` is called per invocation.

### Host functions (importable into the guest)

| Function | Permission | Behavior |
|---|---|---|
| `zc_http_request` | `http_client` | HTTP method/url/headers/body in, status/body/headers out. Methods: GET POST PUT DELETE PATCH HEAD. Timeout 120s |
| `zc_env_read` | `env_read` | Reads env var by name; errors if unset |
| `zc_file_read`, `zc_file_write`, `zc_memory_read`, `zc_memory_write` | (planned) | Listed in docs but not implemented |

Calling without the declared permission returns an error to the guest. Permissions are declared in `manifest.toml` and checked host-side at every host-function call.

### Signing

`signature.rs` verifies an Ed25519 signature over `plugin.wasm` using `publisher_key`. Three modes (`signature_mode` in config):

- `disabled` — accept all
- `optional` — verify if present, skip if absent
- `required` — reject unsigned plugins

(Exact mode names are best-confirmed against `zeroclaw-config` schema; the doc surface establishes the three-mode shape.)

### Capabilities other than `tool`

Per `developing/plugin-protocol.md`, the manifest accepts `channel`, `memory`, `observer`, `skill` capabilities. **Only `tool` and `skill` are implemented today.** A plugin with `capabilities = ["channel"]` will not produce a working channel; the type is reserved.

This is the key extensibility caveat for the Kotlin port: don't promise more capabilities than the plugin host can deliver. ZeroClaw advertises a future-proof manifest schema but ships only two capabilities.

### Building a plugin

```bash
rustup target add wasm32-wasip1
cargo build --target wasm32-wasip1 --release
cp target/wasm32-wasip1/release/<crate>.wasm ./plugin.wasm
zeroclaw plugin install ./my-plugin/   # or: cp -r my-plugin/ ~/.zeroclaw/plugins/
```

Compile-time gate: feature flag `plugins-wasm` must be enabled (it is in the default `ci-all` set).

## Native extension paths

Adding behavior in-tree means writing Rust against `zeroclaw-api`:

| Adding... | Crate | Pattern |
|---|---|---|
| New channel | `zeroclaw-channels` | New file `<name>.rs`, impl `Channel`, register via factory |
| New provider | `zeroclaw-providers` | New file under `providers/`, impl `Provider`, register via the router |
| New tool | `zeroclaw-tools` | New file, impl `Tool`, list in registry |
| New peripheral | `zeroclaw-hardware` | Impl `Peripheral` for the board |

All three traits are documented in `docs/book/src/architecture/overview.md` and `docs/book/src/developing/plugin-protocol.md`. Compile-time feature flags decide which ship.

## MCP (Model Context Protocol)

ZeroClaw supports MCP servers as an additional tool source. Tools imported from MCP appear in the `tool_specs` list with names prefixed `mcp_<server>_<tool>`. The runtime applies `filter_tool_specs_for_turn` (`agent/loop_.rs`) to gate MCP tools on group membership: `Always` (always advertised) or `Dynamic` (advertised only when the user message contains a keyword from `keywords`).

This is a deliberate optimization: MCP servers can advertise hundreds of tools; loading them all into every turn balloons the prompt. The keyword-gated `Dynamic` mode keeps the prompt slim until relevant.

Spec doc: `docs/book/src/tools/mcp.md`.

## Take-aways for the Kotlin port

- Adopting **Extism** for plugin sandbox is the practical choice for JVM. The SDK exists, signing is straightforward, the protocol is JSON-string in/out (trivially serializable with kotlinx.serialization).
- The **agentskills.io markdown skill format** is portable and worth following. Plugin-namespaced skill IDs (`plugin:<plugin>/<skill>`) deserve adoption verbatim — collision avoidance was clearly hard-won.
- `filter_tool_specs_for_turn` with `Always`/`Dynamic` MCP groups is a nice pattern for keeping prompts small. Steal it.
- Don't advertise capabilities you haven't built. ZeroClaw's `channel`/`memory`/`observer` plugin capabilities are a cautionary tale: they exist in docs but not in code, and that's confusing to users.
- Skillforge (runtime skill creation from successful traces) is ambitious; v1 koklyp can skip it. But the *concept* — agent learns its own playbook — is worth a place on the long-term roadmap.
- IronClaw's deterministic skill selector is more principled than ZeroClaw's mode-based injection. If you want one of these, lean toward IronClaw's design but adopt ZeroClaw's bundle format.
