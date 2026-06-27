# M3 — Security

Autonomy levels, workspace boundary, command policy, leak detector, prompt-injection guard, sensitive-param redaction, Ed25519 receipts, verifier, policy chain wiring, estop.

**Done when:** running a tool that violates each policy returns a structured `ToolResult.Err`; a known-malicious LLM output is blocked; receipts verify clean on a 100-call sample.

References: [`../v1-architecture.md`](../v1-architecture.md) §§11, 13, 20, 22.

---

## M3.T1 — `AutonomyLevel` enum + per-tool risk validator

**Status**: pending  
**Size**: S  
**Depends on**: M2.T6  
**Blocks**: M3.T10, every tool that needs the gate

### Goal

`AutonomyValidator` implements `Validator` and matches `tool.risk` against `config.autonomy.level`. `ReadOnly` blocks any side-effect tool; `Supervised` requires approval for `Medium`/`High`; `Full` allows `Low`/`Medium`, requires approval for `High`; `YOLO` allows everything.

### Files to create

- `modules/security/src/main/kotlin/com/hebe/security/policy/AutonomyValidator.kt` (new)
- `modules/security/src/main/kotlin/com/hebe/security/policy/AutonomyLevel.kt` (new — or in `api`)
- Tests with golden cases

### Detailed work

1. Place `AutonomyLevel` in `api` (it's part of the Tool contract semantically):

   ```kotlin
   enum class AutonomyLevel { ReadOnly, Supervised, Full, YOLO }
   ```

2. `AutonomyValidator(level: AutonomyLevel)`:

   ```
   level | risk Low | risk Medium | risk High
   -------------------------------------------
   ReadOnly  | Allow if read-only | Deny  | Deny
   Supervised| Allow      | RequireApproval | RequireApproval
   Full      | Allow      | Allow      | RequireApproval if requiresApproval else Allow
   YOLO      | Allow      | Allow      | Allow (with loud warning)
   ```

   The "read-only" check for `ReadOnly` uses a `Tool.isReadOnly` property — add as `val readOnly: Boolean get() = false` on `Tool` interface (default false; Low-risk tools opt in). For v1, mark `file_system.read`, `file_system.list`, `memory_search`, `memory_read`, `memory_tree`, `web_search`, `git status`, `kubectl get`, `kubectl describe`, `http GET`, `ask_user` as read-only.

   Note: the read-only flag is *informational* per-tool; a tool with multiple verbs (`file_system`) can't easily be one or the other. v1 simplification: either the whole tool is read-only or it isn't. Tools with mixed semantics (e.g. `file_system` doing both read + write) should split into `file_system_read` + `file_system_write` — see M4.T1.

3. Tests for every cell of the table.

### Acceptance criteria

- ✅ All 12 cells of the matrix tested.
- ✅ Tool's `requiresApproval = true` always wins (forces approval even at `Full`).

### References

- `v1-architecture.md` §11 (autonomy levels)
- `v1-specs.md` §2.10 (security)

---

## M3.T2 — Workspace boundary validator

**Status**: pending  
**Size**: S  
**Depends on**: M1.T4  
**Blocks**: M3.T10, M4.T1 (file_system)

### Goal

`WorkspaceBoundaryValidator` rejects tool calls that target paths outside the workspace or in `forbidden_paths`.

### Files to create

- `modules/security/src/main/kotlin/com/hebe/security/policy/WorkspaceBoundaryValidator.kt` (new)
- Tests

### Detailed work

1. Reads `config.security.forbidden_paths` (defaults: `/etc`, `~/.ssh`, `~/.aws`, `~/.azure`).

2. Inspects `args` for keys named `path`, `file`, `target`, `dest`, `cwd`. For each, validate:
   - Resolves to a real path.
   - Is **inside** the workspace OR is in a per-tool allowlist (e.g. `git` may need to operate outside on a configured `~/repos/`).
   - Is **not** a prefix of any `forbidden_paths` entry.

3. Per-tool allowlists: extend `ToolSpec` with `pathScope: PathScope`:

   ```kotlin
   enum class PathScope { WorkspaceOnly, ConfiguredRoots, Anywhere }
   ```

   v1 defaults: `WorkspaceOnly` for `file_system`, `wiki_*`, `memory_*`; `ConfiguredRoots` for `git`, `github`; `Anywhere` for `kubectl` (relies on kubeconfig path).

4. Tests cover: workspace-bound rejection of `/etc/passwd`, allowed for in-workspace path, forbidden paths globally blocked even if otherwise allowed.

### Acceptance criteria

- ✅ Three path-scope modes implemented.
- ✅ `forbidden_paths` always wins.

### References

- `v1-architecture.md` §11 (workspace boundary)
- `v1-architecture.md` §22 (security check ordering)

---

## M3.T3 — Command-policy validator

**Status**: pending  
**Size**: M  
**Depends on**: M0.T8  
**Blocks**: M3.T10, M4.T2 (shell)

### Goal

Pre-execution validator for shell-style tools: matches `args.cmd` (or equivalent) against `allowed_command_globs` + `forbidden_command_globs` and runs a pattern-based safety check.

### Files to create

- `modules/security/src/main/kotlin/com/hebe/security/policy/CommandPolicyValidator.kt` (new)
- `modules/security/src/main/kotlin/com/hebe/security/policy/CommandPatternChecker.kt` (new)
- Tests with golden footgun samples

### Detailed work

1. Inputs: `allowed_command_globs`, `forbidden_command_globs` from config; the tool name + args.

2. Glob matching: simple `*`-pattern matcher, anchored at start. Examples:
   - `git *` matches `git status`, `git push`.
   - `kubectl get *` matches `kubectl get pods`.
   - `rm -rf /*` matches `rm -rf /` and `rm -rf /var`.

3. Order of evaluation: forbidden first (deny if match), then allowed (allow if match), else `Deny("not in allowlist")`.

4. **Pattern-based safety checks** — independent of the allow/deny lists, always block:
   - Command-substitution: `` ` ... ` `` or `$(...)` containing `rm`, `curl ... | sh`, `wget ... | sh`.
   - Pipe-to-shell: `... | bash`, `... | sh`, `... | python -c`.
   - Network exfil with sensitive paths: `cat /etc/passwd | curl ...`.
   - Variable substitution that hides intent: `$IFS$9` or whitespace tricks.

5. Returns `Allow | RequireApproval(prompt) | Deny(reason)`. For globs that match an allowlist but contain pattern-flagged content, prefer `RequireApproval` (the operator can choose to let it through).

### Tests / verification

- Golden suite of 20+ command strings, half "should pass", half "should be blocked".
- Property test: random commands containing `rm -rf` (without the slashes) are flagged.

### Acceptance criteria

- ✅ Allow/deny lists work.
- ✅ Pattern checks block known footguns.
- ✅ Approval-prompt path documented.

### Pitfalls

- Glob matching with `*` is not regex; don't accidentally use `*` to mean ".*" — implement a small matcher or use Java's `PathMatcher` with `glob:` syntax.
- Whitespace tricks: normalise multiple spaces before matching.

### References

- `v1-architecture.md` §11 (command policy)
- `v1-specs.md` §2.10 (security)

---

## M3.T4 — Domain matcher for outbound HTTP

**Status**: pending  
**Size**: S  
**Depends on**: M0.T8  
**Blocks**: M3.T10, M4.T3 (`http` tool), M6.T4 (`PluginHost.http()`)

### Goal

`DomainAllowlistValidator` checks the `url` arg of HTTP-using tools against `config.security.http_allowlist_domains` and rejects SSRF candidates.

### Files to create

- `modules/security/src/main/kotlin/com/hebe/security/policy/DomainAllowlistValidator.kt` (new)
- `modules/security/src/main/kotlin/com/hebe/security/policy/SsrfGuard.kt` (new)
- Tests

### Detailed work

1. Allowlist matching: domain or `*.domain.tld` (subdomain wildcard).

2. SSRF guard rejects URLs whose hostname resolves to:
   - Loopback (`127.0.0.0/8`, `::1`).
   - Link-local (`169.254.0.0/16`, `fe80::/10`).
   - Private (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`).
   - Unique-local IPv6 (`fc00::/7`).
   - The metadata services (`169.254.169.254` Azure/AWS, `metadata.google.internal`).

   **Exception**: explicit allowlist for the user's LLM Gateway IP/hostname. If the gateway is on `localhost:8080`, that's a legitimate config; users opt in via `security.http_allow_loopback_for: ["llm-gateway.example.com"]`.

3. Resolve hostnames once and cache the answer for 60 s — avoids DNS rebinding attacks where the first lookup is public and the second is private.

4. Returns `Allow | Deny(reason)`.

### Tests / verification

- `https://api.github.com/...` allowed when `*.github.com` in allowlist.
- `http://localhost/...` blocked.
- `http://169.254.169.254/...` blocked.
- `https://gateway.example.com/...` allowed when explicitly opted-in.

### Acceptance criteria

- ✅ Allowlist + SSRF guard.
- ✅ Loopback exception is opt-in.
- ✅ DNS-rebind mitigation present.

### References

- `v1-architecture.md` §11 (domain matcher)
- `v1-architecture.md` §22

---

## M3.T5 — Prompt-injection guard

**Status**: pending  
**Size**: M  
**Depends on**: M0.T7  
**Blocks**: M3.T10

### Goal

Pattern-scan the assistant's generated output (the model's text + tool-call args) before any tool dispatch. If the assistant is being clearly steered (e.g. tool-call args contain `<system>` or "ignore your instructions"), block the call.

### Files to create

- `modules/security/src/main/kotlin/com/hebe/security/policy/PromptInjectionValidator.kt` (new)
- `modules/security/src/main/kotlin/com/hebe/security/policy/PromptInjectionRules.kt` (new)
- Tests

### Detailed work

1. Reuses the **same regex set** as the hygiene scanner (M1.T12) but applied at **dispatch-time** to tool-call args (and optionally the model's text response in `BeforeOutbound`).

2. Sources: extract injectable strings from `args`. Common keys to scan: `content`, `text`, `body`, `query`, `prompt`, `cmd`.

3. Rule severity ladder:
   - `High` → `Deny`.
   - `Medium` → `RequireApproval`.
   - `Low` → log + allow.

4. The scan runs **once per turn** at first model response; cache the verdict for the rest of the turn (so we don't re-scan every iteration).

### Tests / verification

- Args containing `"ignore the previous instructions"` → `Deny`.
- Args with `<system>` tag → `Deny`.
- Benign args → `Allow`.

### Acceptance criteria

- ✅ Reuses hygiene rules.
- ✅ Per-turn caching.
- ✅ Severity ladder respected.

### References

- `v1-architecture.md` §11 (prompt-injection guard)
- `v1-architecture.md` §22

---

## M3.T6 — Leak detector

**Status**: pending  
**Size**: M  
**Depends on**: M0.T7  
**Blocks**: M2.T6 (dispatcher uses it post-invoke), M3.T10

### Goal

Scan tool *output* for known secret patterns. On hit, replace the output with a `ToolResult.Err("output blocked: leak detector")` and log a high-severity event.

### Files to create

- `modules/security/src/main/kotlin/com/hebe/security/policy/LeakDetector.kt` (new)
- `modules/security/src/main/kotlin/com/hebe/security/policy/SecretPatterns.kt` (new)
- Tests

### Detailed work

1. Patterns (regex; verify the latest formats at PR time):

   | Provider | Pattern |
   |---|---|
   | AWS access key | `AKIA[0-9A-Z]{16}` |
   | AWS secret | `(?i)aws_secret_access_key.*[0-9a-z+/]{40}` |
   | OpenAI | `sk-[A-Za-z0-9]{20,}` |
   | Anthropic | `sk-ant-[A-Za-z0-9-_]{20,}` |
   | GitHub PAT | `ghp_[A-Za-z0-9]{36}` or `github_pat_[A-Za-z0-9_]{82,}` |
   | Stripe | `sk_live_[A-Za-z0-9]{24}` |
   | Slack | `xox[baprs]-[A-Za-z0-9-]{10,}` |
   | Generic high-entropy | `[A-Za-z0-9_-]{32,}` with Shannon entropy > 4.5 |

2. `LeakDetector.scan(result: ToolResult): ToolResult`:
   - Serialise result to a string.
   - For each pattern, check; if any match, return `Err("output blocked: matched <rule>")` and emit an observer event `LeakDetected(tool, rule, severity=High)`.

3. Make rules configurable from `config.toml` so users can add patterns.

### Tests / verification

- Synthetic outputs for each pattern match.
- Benign output passes through unchanged.

### Acceptance criteria

- ✅ At least 8 default patterns.
- ✅ Configurable.
- ✅ High-severity event on match.

### Pitfalls

- High-entropy generic match has false-positive risk (UUIDs, Git SHAs); whitelist common shapes (`[0-9a-f]{40}` Git SHA) before the entropy check.
- Leak detector runs on output; the *input* (args) is handled by the prompt-injection guard.

### References

- `v1-architecture.md` §11 (leak detector)
- `v1-specs.md` §2.10

---

## M3.T7 — Sensitive-param redaction

**Status**: pending  
**Size**: S  
**Depends on**: M0.T7, M2.T6  
**Blocks**: M3.T8 (receipts use the redactor)

### Goal

Redact sensitive parameter *names* (not values matched by patterns) before writing receipts, logs, or surfacing args to the UI. Default denylist + configurable extension.

### Files to create

- `modules/security/src/main/kotlin/com/hebe/security/policy/ArgsRedactor.kt` (new)
- Tests

### Detailed work

1. Default denylist (case-insensitive, substring match): `api_key`, `apikey`, `token`, `secret`, `password`, `auth`, `bearer`, `signature`, `cookie`, `email`, `phone`. Replace value with `"[REDACTED]"`.

2. `ArgsRedactor.redact(args: JsonObject): JsonObject` walks the object tree.

3. Used by:
   - `ToolDispatcher` when populating `tool_calls.args_redacted`.
   - `Receipts.append` for the `args_redacted` field.
   - `LogbackObserver` when logging `ToolDispatched` events.
   - Web UI when showing a tool call's args.

### Tests / verification

- `{"api_key": "sk-...", "url": "..."}` → `{"api_key": "[REDACTED]", "url": "..."}`.
- Nested: `{"auth": {"bearer": "..."}}` → `{"auth": "[REDACTED]"}` (top-level match wins).
- Configurable: add `"my_secret_param"` via config; verify redaction.

### Acceptance criteria

- ✅ Default denylist applied.
- ✅ Recursive walk.
- ✅ Configurable extension.
- ✅ Used by receipts + logs + UI.

### References

- `v1-architecture.md` §11, §21

---

## M3.T8 — Ed25519 receipts log writer

**Status**: pending  
**Size**: L  
**Depends on**: M1.T2, M0.T9  
**Blocks**: M2.T6 (dispatcher appends), M3.T9 (verifier), M5.T7 (web receipts viewer)

### Goal

Append-only NDJSON receipts log per `v1-architecture.md` §13. One file per month at `~/.hebe/receipts/YYYY-MM.log`. Each record is hash-chained + Ed25519-signed.

### Files to create

- `modules/security/src/main/kotlin/com/hebe/security/receipts/Receipts.kt` (new)
- `modules/security/src/main/kotlin/com/hebe/security/receipts/Receipt.kt` (new — data class)
- `modules/security/src/main/kotlin/com/hebe/security/receipts/SigningKey.kt` (new — generation + storage)
- `modules/security/src/main/kotlin/com/hebe/security/receipts/CanonicalJson.kt` (new — deterministic serialiser)
- Tests

### Detailed work

1. **Signing key**:
   - On first boot, `SigningKey.bootstrap(secretStore)`: generates an Ed25519 keypair (Bouncy Castle). Stores the **private key** in `secrets.db` under `receipts.signing_key`. The **public key** is stored as plaintext under `~/.hebe/receipts/public.key` for verification.
   - Subsequent boots: load private key from secrets store.

2. **Receipt** data class (matches arch §13):

   ```kotlin
   @Serializable
   data class Receipt(
       val seq: Long,
       val ts: String,                // ISO-8601 UTC
       val sessionId: String,
       val turnId: String,
       val tool: String,
       val argsRedacted: JsonObject,
       val risk: String,              // "Low" | "Medium" | "High"
       val approval: ApprovalRecord,
       val durationMs: Long,
       val ok: Boolean,
       val resultHash: String,        // sha256: of full result before redaction
       val prevHash: String,          // sha256: of previous record's selfHash
       val selfHash: String,          // sha256: of canonical(self minus selfHash + sig)
       val sig: String,               // ed25519:base64url over selfHash bytes
   )
   ```

3. **Receipts class**:

   ```kotlin
   class Receipts(private val dir: Path, private val signing: Ed25519PrivateKey) {
       suspend fun append(partial: PartialReceipt): Long { … }   // returns seq
       suspend fun verify(file: Path, publicKey: Ed25519PublicKey): VerifyResult { … }
   }
   ```

4. **append**:
   - Serialise the partial receipt (without `selfHash` + `sig`) using `CanonicalJson.serialize` (sorted keys, no whitespace).
   - Compute `selfHash = sha256(canonical)`.
   - Sign `selfHash` bytes with Ed25519 → `sig`.
   - Look up `prevHash` from the last record in the current month's file (cached in memory after first read; first record has `prevHash = "sha256:0".repeat(64)`).
   - Append the full record as a single JSON line.
   - `fsync` per N appends (configurable; default 16) for durability without per-call cost.

5. **CanonicalJson** ensures keys are sorted lexicographically (so signatures are reproducible) and uses minimal separators.

6. Per-file rollover at month change. The `prevHash` for the first record of a new file is the `selfHash` of the last record of the previous file (so the chain spans months).

### Tests / verification

- 100 receipts appended → file has 100 NDJSON lines.
- Each record's `selfHash` matches `sha256(canonical(self_without_hash_and_sig))`.
- `prevHash` of record N == `selfHash` of record N-1.
- Each `sig` verifies against the public key.
- Cross-month rollover: month boundary preserves the chain.

### Acceptance criteria

- ✅ NDJSON format.
- ✅ Hash chain valid across all records.
- ✅ Ed25519 signatures verifiable.
- ✅ Append latency sub-millisecond on a dev SSD (per `v1-specs.md` §4 NFR).

### Pitfalls

- Bouncy Castle's Ed25519 API has a few different entry points; use `Ed25519PrivateKeyParameters` + `Ed25519Signer`.
- `fsync` per append crushes throughput; batch every 16 appends, sync on shutdown.

### References

- `v1-architecture.md` §13 (receipts format)

---

## M3.T9 — Receipts verifier

**Status**: pending  
**Size**: M  
**Depends on**: M3.T8  
**Blocks**: M5.T7 (web verify endpoint), M9.T8 (`hebe status` shows receipts)

### Goal

`hebe memory show receipts/<file> --verify` walks the file, verifies the hash chain + signatures, reports the first divergence.

### Files to create

- `modules/security/src/main/kotlin/com/hebe/security/receipts/Verifier.kt` (new)
- `modules/cli-app/src/main/kotlin/com/hebe/cli/commands/Memory.kt` (edit — wire `show --verify`)
- Tests

### Detailed work

1. `Verifier.verify(file: Path, publicKey: Ed25519PublicKey, expectedFirstPrevHash: String): VerifyResult`:

   ```kotlin
   sealed interface VerifyResult {
       data class Ok(val records: Int, val lastSelfHash: String) : VerifyResult
       data class Failed(val recordSeq: Long, val reason: String) : VerifyResult
   }
   ```

2. Walks line-by-line, recomputes `selfHash`, verifies `sig` against the public key, checks `prevHash` against the previous record's `selfHash`. First mismatch returns `Failed`.

3. CLI: `hebe memory show receipts/2026-04.log --verify`. Output:

   ```
   Verified 12,345 receipts in receipts/2026-04.log
   Last hash: sha256:abc123…
   ```

   Or:

   ```
   FAILED at seq 12,346: prev_hash mismatch (expected sha256:abc, got sha256:def)
   ```

4. Cross-file verification: `hebe memory show receipts/ --verify` walks all files in lexicographic order, threading `lastSelfHash` between them.

### Tests / verification

- Clean log → `Ok(N, hash)`.
- Tampered record → `Failed(seq, reason)` at the right seq.
- Truncated file → `Failed` at the missing record.

### Acceptance criteria

- ✅ Returns sealed result.
- ✅ CLI outputs match the format above.
- ✅ Cross-file walking works.

### References

- `v1-architecture.md` §13

---

## M3.T10 — Wire policy chain into `ToolDispatcher`

**Status**: pending  
**Size**: M  
**Depends on**: M3.T1–T6, M2.T6  
**Blocks**: every tool

### Goal

Register all validators in the right order and run them as the dispatcher's `validators` list. The order is fixed per `v1-architecture.md` §22.

### Files to create / modify

- `modules/cli-app/src/main/kotlin/com/hebe/cli/AppComponents.kt` (new — wires everything; full content lands in M9.T2 or earlier; this task adds the validator wiring)
- `modules/security/src/main/kotlin/com/hebe/security/policy/PolicyChain.kt` (new — composes validators)
- Tests

### Detailed work

1. `PolicyChain.standard(config, leak, redactor): List<Validator>`:

   ```kotlin
   listOf(
       AutonomyValidator(config.autonomy.level),
       WorkspaceBoundaryValidator(config.security.forbiddenPaths, …),
       CommandPolicyValidator(config.security.allowedCommandGlobs, config.security.forbiddenCommandGlobs),
       DomainAllowlistValidator(config.security.httpAllowlistDomains, …),
       PromptInjectionValidator(),
   )
   ```

   The leak detector runs **after** invoke (in the dispatcher's pipeline), not as a Validator.

2. The dispatcher composes results: first non-`Allow` decides. `Deny` short-circuits; `RequireApproval` advances to the gate.

3. Golden integration test: a synthetic tool call passes through every validator; mismatched config cells produce the right `Deny`/`RequireApproval`.

### Tests / verification

- Validator chain integration test exercising each policy.

### Acceptance criteria

- ✅ Order matches arch §22 (channel-side checks happen at the channel adapter; tool-side checks here).
- ✅ All five validators run.

### References

- `v1-architecture.md` §22

---

## M3.T11 — Emergency stop (`hebe estop`)

**Status**: pending  
**Size**: M  
**Depends on**: M2.T13  
**Blocks**: M9.T8

### Goal

`hebe estop` aborts the current in-flight tool call and any pending approvals. Receipts log records the abort. Process keeps running.

### Files to create

- `modules/security/src/main/kotlin/com/hebe/security/estop/EmergencyStop.kt` (new)
- `modules/security/src/main/kotlin/com/hebe/security/estop/EstopIpc.kt` (new — Unix-domain socket or named pipe at `~/.hebe/.estop.sock`)
- `modules/cli-app/src/main/kotlin/com/hebe/cli/commands/Estop.kt` (edit — implement)
- Tests

### Detailed work

1. **Server side** (lives in the running hebe process): `EmergencyStop` exposes:
   - `volatile var stopFlag: AtomicBoolean`.
   - `awaitStop(): Channel<Unit>` for `LoopDelegate.checkSignals` to suspend on.
   - On stop: cancel the agent's per-session coroutine scope; mark all `pending_approvals` as `resolved=false, approved=null` with reason "estop"; write a synthetic receipt `{tool:"_estop", ok:false}`.

2. **IPC**: a Unix-domain socket at `~/.hebe/.estop.sock`. `hebe estop` connects + sends `STOP\n`. Process responds `OK\n`. On Windows, use a named pipe at `\\.\pipe\hebe-estop`.

3. CLI:

   ```
   $ hebe estop
   Sending estop to local hebe instance…
   Acknowledged. In-flight tool calls cancelled. Pending approvals expired.
   ```

4. After estop, the dispatcher continues to accept new turns (the process isn't killed). Optionally: `hebe estop --quiesce` puts the agent into "no new turns" mode until `hebe estop --resume`.

### Tests / verification

- Long-running mock tool gets cancelled within 100 ms of estop.
- Pending approvals marked expired.
- Synthetic receipt appended.

### Acceptance criteria

- ✅ IPC works on macOS + Linux (Unix-domain socket).
- ✅ Windows path documented (named pipe; can be a follow-up).
- ✅ Receipts capture the abort.

### Pitfalls

- Cancelling a coroutine doesn't kill subprocess children; `shell` tool's children need explicit `Process.destroyForcibly()`. Add to the `shell` tool's `invoke` to register subprocesses for cancellation.

### References

- `v1-architecture.md` §11 (emergency stop)
- `v1-specs.md` §2.10
