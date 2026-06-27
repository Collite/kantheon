# hebe Security Model

This document covers every security mechanism in hebe v1. Read it before running hebe with `autonomy.level = "Full"` or installing third-party plugins.

---

## Contents

1. [Threat model](#1-threat-model)
2. [Autonomy levels](#2-autonomy-levels)
3. [Workspace boundary, command policy, domain matcher](#3-workspace-boundary-command-policy-domain-matcher)
4. [Subprocess sandbox](#4-subprocess-sandbox)
5. [Plugin trust posture](#5-plugin-trust-posture)
6. [Receipts (tamper-evident log)](#6-receipts-tamper-evident-log)
7. [Secrets at rest](#7-secrets-at-rest)
8. [Hygiene scanner, leak detector, prompt-injection guard](#8-hygiene-scanner-leak-detector-prompt-injection-guard)
9. [Operator responsibilities](#9-operator-responsibilities)
10. [Incident runbook](#10-incident-runbook)

---

## 1. Threat model

### What we defend against

- **Prompt injection via untrusted content** — a webpage, file, or memory doc that attempts to hijack the agent's tool calls.
- **Exfiltration of secrets** — the LLM or a plugin outputting an API key, token, or credential.
- **Accidental destruction** — the agent running `rm -rf` or `kubectl delete` without explicit human approval.
- **Scope creep** — tools reading or writing outside the declared workspace.
- **Supply-chain substitution** — a tampered plugin JAR pushed to an OCI registry.
- **Outbound SSRF** — the agent reaching internal network services via the `http` tool.

### What we do NOT defend against (v1)

- **A malicious plugin** — JVM plugins run in-process; they can bypass all hebe-level controls. Only install plugins you trust.
- **A compromised LLM response** — if the model itself is backdoored, hebe can only catch output patterns via the leak detector and prompt-injection guard.
- **Kernel-level exploits** — no OS sandbox in v1 (`firejail`, `bwrap`, seccomp). That is a v2 deliverable.
- **Physical access** — `secrets.db` is encrypted, but the master key is in the OS keychain. Physical access to the machine is outside scope.
- **Denial of service** — hebe is single-user; rate limiting is not a design goal.

---

## 2. Autonomy levels

Set in `~/.hebe/config.toml`:

```toml
[autonomy]
level = "Supervised"   # ReadOnly | Supervised | Full | YOLO
```

| Level | What the agent can do without approval |
|---|---|
| `ReadOnly` | Only `Low`-risk tools: `file_system` (read/list), `memory_search`, `memory_read`, `web_search`, `http` (read). No writes, no shell. |
| `Supervised` | `Low` tools automatically; `Medium`-risk tools require a one-time per-turn approval; `High`-risk tools require explicit approval every time. |
| `Full` | `Low` and `Medium` tools automatically; `High`-risk tools (shell, git push, kubectl mutating) require approval. |
| `YOLO` | All tools, no approval prompts. **Not recommended for production.** The name is intentional. |

Tool risk levels:

| Risk | Examples |
|---|---|
| Low | `file_system` read, `memory_search`, `web_search`, `ask_user`, `job_status` |
| Medium | `file_system` write, `http`, `git` read ops, `memory_write`, `schedule`, `github` read |
| High | `shell`, `git push`, `github` write ops, `kubectl` mutating verbs |

Tools tagged `requiresApproval = true` (e.g. `shell`) always require an explicit approval regardless of autonomy level, unless the level is `YOLO`.

---

## 3. Workspace boundary, command policy, domain matcher

### Workspace boundary

The workspace is `~/.hebe/workspace/`. File tools (`file_system`, `memory_*`, `wiki_*`) are restricted to this tree by default.

`forbidden_paths` in config are always blocked, regardless of workspace settings:

```toml
[security]
forbidden_paths = ["/etc", "~/.ssh", "~/.aws", "~/.azure"]
```

A path traversal attempt (e.g. `../../etc/passwd`) throws `HebeException.Security` and is logged + receipted.

### Command policy

Shell commands (`shell` tool) are matched against allow and deny glob lists before execution:

```toml
[security]
allowed_command_globs  = ["git *", "kubectl get *", "ls *", "cat *"]
forbidden_command_globs = ["rm -rf /*", "kubectl delete *", "shutdown *"]
```

Rules:
- Explicit `forbidden_command_globs` override `allowed_command_globs` (deny wins).
- If `allowed_command_globs` is non-empty, any command not matching an allow glob is denied.
- If `allowed_command_globs` is empty, any command is allowed unless it matches a deny glob.
- Evaluation is pre-exec: the command string is matched before any subprocess is spawned.

### Domain matcher

The `http` tool enforces an allowlist of domains:

```toml
[security]
http_allowlist_domains = ["api.brave.com", "api.duckduckgo.com", "api.linear.app"]
```

SSRF-safe IP checks are applied: private RFC 1918 ranges, loopback, link-local, and cloud-provider metadata IPs (`169.254.169.254`, etc.) are always blocked, even if you add them to the allowlist.

---

## 4. Subprocess sandbox

**v1 does not sandbox subprocesses.** The `shell` tool uses `ProcessBuilder` with the command policy validator as the only gate. A command that passes the allowlist/denylist runs with the same OS user and permissions as hebe itself.

Mitigation:
- Run hebe as a dedicated unprivileged user (not root).
- Keep `allowed_command_globs` tight.
- Use `Supervised` or `Full` autonomy (never `YOLO`) for production.

Full OS-level sandbox (`firejail`, `bwrap`, seccomp profiles) is a v2 deliverable.

---

## 5. Plugin trust posture

JVM plugins run **in the same process** as hebe. The classloader provides dependency isolation, not security isolation. A plugin can call `Runtime.exec()`, open raw sockets, or read arbitrary files — none of these are blocked at the JVM level.

What is enforced:
- Plugins can only access `api` and `plugin-api` module classes via normal imports (classloader boundary).
- Declared capabilities are checked at call time: calling `host.http()` without `http_client = true` in `plugin.toml` throws `PluginCapabilityException`.
- HTTP calls through `GatedHttpClient` are domain-allowlist checked.
- Secrets are handled as opaque `SecretHandle` objects; the raw value is never passed to the plugin.

What is NOT enforced:
- A plugin that bypasses `host.http()` and opens a raw `java.net.Socket` is not blocked.
- Reflection into hebe internals is not prevented.

**Recommendation**: set `plugin_signature_mode = "required"` and add only keys from publishers you control.

```toml
[plugins]
plugin_signature_mode = "required"
publisher_keys = ["<hex-ed25519-public-key>"]
```

See [plugin-protocol.md](plugin-protocol.md) for key generation and signing.

---

## 6. Receipts (tamper-evident log)

Every tool invocation produces a signed, hash-chained receipt appended to `~/.hebe/receipts/YYYY-MM.log`. This is an append-only NDJSON file.

### Format

```json
{
  "seq": 12345,
  "ts": "2026-05-04T08:42:13Z",
  "session_id": "...", "turn_id": "...",
  "tool": "shell",
  "args_redacted": {"cmd": "git status"},
  "risk": "Medium",
  "approval": {"required": false},
  "duration_ms": 23,
  "ok": true,
  "result_hash": "sha256:abc123…",
  "prev_hash": "sha256:def456…",
  "self_hash": "sha256:789…",
  "sig": "ed25519:base64url…"
}
```

- `prev_hash` = `self_hash` of the previous record. First record uses the all-zero hash.
- `self_hash` = SHA-256 over the canonical record (minus `self_hash` and `sig`).
- `sig` = Ed25519 over `self_hash` using the agent's signing key from `secrets.db`.

### Verification

```bash
hebe memory show receipts/2026-05.log --verify
# OK: 12345 records verified; chain intact
```

If the chain is broken or a signature is invalid, the command reports the first divergence.

### Sensitive-param redaction

Arguments containing sensitive keys (`api_key`, `token`, `password`, `secret`, `auth`, `bearer`, `cookie`, etc.) are masked in `args_redacted`. The original values are never written to the receipts log or any structured log.

---

## 7. Secrets at rest

```
~/.hebe/secrets.db    ← SQLite, encrypted with AES-256-GCM
master key            ← OS keychain (Keychain / secret-service / Windows Credential Manager)
                         fallback: passphrase-derived (PBKDF2-HMAC-SHA256, 600k rounds)
                                   stored in chmod 600 file at ~/.hebe/.key
```

API keys, bot tokens, and the receipts signing key are all stored in `secrets.db`. Config files reference secret names (e.g. `api_key_secret = "llm.api_key"`), never raw values.

Three-layer separation: bootstrap config (`config.toml`) / DB settings (`settings` table) / encrypted secrets (`secrets.db`). These are never collapsed.

LLM-data retention invariant: nothing in `messages`, `llm_calls`, or `tool_calls` is proactively deleted.

---

## 8. Hygiene scanner, leak detector, prompt-injection guard

### Hygiene scanner (inbound writes)

Applied when the agent or a tool attempts to write to the workspace. Scans content for known prompt-injection patterns (instruction overrides, jailbreak fragments, separator injection). High-severity matches reject the write; lower severity logs a warning.

### Leak detector (outbound results)

Applied to every tool result before it is forwarded to the LLM. Detects:

- AWS access keys (`AKIA...`)
- OpenAI API keys (`sk-...`)
- GitHub PATs (`ghp_...`, `github_pat_...`)
- Stripe secret keys (`sk_live_...`)
- Generic high-entropy tokens (>32 chars matching a base64/hex pattern)

On a hit, the tool result is replaced with `ToolResult.Err("output blocked: leak detector")` and the detection is logged at WARN with severity.

### Prompt-injection guard

Applied once at the start of each turn, over the raw LLM output, before tool dispatch. Uses the same pattern set as the hygiene scanner. Results are cached for the turn. A high-severity hit causes the dispatcher to return `ToolResult.Err` instead of invoking the tool.

---

## 9. Operator responsibilities

1. **Do not run hebe as root.** Use a dedicated unprivileged user.
2. **Do not share `secrets.db`.** It contains every credential hebe uses. If it is compromised, rotate all secrets.
3. **Set `plugin_signature_mode = "required"` in production.** The default `optional` is a development convenience.
4. **Keep the host OS and JVM patched.** hebe inherits the host's security posture.
5. **Review `allowed_command_globs` before enabling `shell` in Full autonomy.** A permissive allowlist under Full autonomy is effectively `YOLO`.
6. **Back up `~/.hebe/` periodically.** The workspace and `hebe.db` are the agent's memory. They are not replicated.
7. **Rotate the receipts signing key annually** (or on suspected compromise). The key is in `secrets.db` under `receipts.signing_key`; rotation breaks the old chain — archive the old receipts file first.

---

## 10. Incident runbook

If you suspect the agent has been compromised or has acted outside its intended scope:

1. **Immediate stop:**
   ```bash
   hebe estop
   # or from web console: POST /api/estop
   ```
   This halts the current loop; in-flight tool calls are interrupted; the receipts log records `{aborted: true}`.

2. **Review recent receipts:**
   ```bash
   hebe status --recent
   hebe memory show receipts/$(date +%Y-%m).log --verify
   ```

3. **Check for workspace writes:**
   ```bash
   hebe memory tree
   # look for unexpected files or modifications
   ```

4. **Examine logs:**
   ```bash
   # JSON logs at the configured log path (default: ~/.hebe/logs/)
   cat ~/.hebe/logs/hebe.log | jq 'select(.level == "WARN" or .level == "ERROR")'
   ```

5. **Rotate compromised secrets:**
   ```bash
   hebe onboard --reset-secrets   # re-enters only the secrets portion of onboarding
   ```

6. **If a plugin is suspected**, remove it and restart:
   ```bash
   hebe plugin remove <name>
   sudo systemctl restart hebe
   ```

7. **If the DB is suspected corrupt:**
   ```bash
   sqlite3 ~/.hebe/hebe.db "pragma integrity_check;"
   ```
   If integrity check reports errors, restore from backup. Do not attempt manual DB surgery while hebe is running.

8. **Report the incident** in the issue tracker with the receipts verification output and relevant log lines (redact secrets before pasting).
