# M10 — Hardening + docs

README, quickstart, plugin protocol spec, MCP integration guide, security model doc, Telegram setup guide, soak test, security review checklist, RFC scaffold, acceptance run-through.

**Done when:** all `v1-specs.md` §5 acceptance items pass and a soak run completes without intervention.

References: [`../v1-specs.md`](../v1-specs.md) §5; [`../v1-architecture.md`](../v1-architecture.md) §22.

---

## M10.T1 — README.md (install + minimal config)

**Status**: done  
**Size**: S  
**Depends on**: M9.T4 (onboarding)  
**Blocks**: M10.T2

### Goal

A 10-line quickstart at the top of the repo's `README.md` that takes a new user from zero to a working chat in CLI.

### Files to create / modify

- `README.md` (edit; replace the placeholder)

### Detailed work

1. Sections:
   - **What is hebe?** — one paragraph (autonomous JVM agent, single-user, BYOK).
   - **Install**:
     ```
     curl -sSL https://example.com/hebe/install.sh | bash
     # or
     ./gradlew shadowJar && cp build/libs/hebe.jar /usr/local/lib/hebe/
     ```
   - **First run**:
     ```
     hebe onboard          # interactive setup
     hebe doctor           # verify
     hebe run              # start the agent (CLI mode)
     ```
   - **Pointer to the docs/plan/v1-* and quickstart** for more.

2. Keep terse. The aspirational target: a reader can paste the install + first-run blocks and have a working agent in 10 min.

### Tests / verification

- Manual run on a fresh machine.

### Acceptance criteria

- ✅ 10-line install + first-run block works.
- ✅ Links to quickstart + architecture.

### References

- `v1-specs.md` §2.13

---

## M10.T2 — Quickstart guide (10-minute happy path)

**Status**: done  
**Size**: M  
**Depends on**: M10.T1  
**Blocks**: nothing

### Goal

A longer step-by-step guide: clone → build → onboard → first chat → first plugin install → run as service. New user reaches first chat in < 10 min.

### Files to create

- `docs/quickstart.md` (new)
- Screenshots / sample output captured into `docs/img/`

### Detailed work

1. Sections:
   - Prerequisites (JDK 21, optional Docker for OCI registry tests).
   - Build from source (`./gradlew shadowJar`).
   - Onboarding walkthrough (with sample inputs).
   - First chat in CLI.
   - First chat in the web console (via `hebe run`, then opening `localhost:8765`).
   - Adding the Telegram channel.
   - Installing a plugin from the local OCI registry.
   - Running as a service.

2. Each step ends with a verification (`./hebe status`, doctor, etc.).

3. Length: aim for ~5 pages of markdown; include sample command output to show the "right" look.

### Tests / verification

- One reviewer who hasn't seen hebe before follows it on a fresh machine and reports time-to-first-chat.

### Acceptance criteria

- ✅ Time to first chat < 10 min on a clean machine.
- ✅ Each step has a verification command.

### References

- `v1-specs.md` §2.13

---

## M10.T3 — Plugin protocol spec doc

**Status**: done  
**Size**: M  
**Depends on**: M6.T11, M6.T13  
**Blocks**: nothing

### Goal

A formal document covering the plugin authoring contract: manifest, classloader rules, capability gates, lifecycle, OCI publish/pull flow.

### Files to create

- `docs/plugin-protocol.md` (new)

### Detailed work

1. Sections:
   - **Structure** (PF4J `plugin.properties` + `plugin.toml`).
   - **Capabilities + Permissions** (the v1 set + reserved names).
   - **PluginHost surface** — what the host exposes; what it doesn't.
   - **ABI versioning** (semver-ish range syntax).
   - **Signing** (Ed25519 generation, signature_mode interaction).
   - **Distribution** (OCI artifact format, media types, ACR auth).
   - **Lifecycle** (created → resolved → started → stopped → unloaded).
   - **Restrictions** (no `Class.forName("com.hebe.core.*")`, no bundled `hebe-api`/`plugin-api` in `implementation`, no use of `Runtime.exec` from the plugin sandbox surface — but a frank acknowledgement that nothing prevents it).
   - **Trust posture**: bold callout that JVM plugins are *not* sandboxed; trust whom you install from.

2. Include a working sample (link to `plugin-template/`).

### Acceptance criteria

- ✅ Covers all plugin-author concerns.
- ✅ Trust posture loud and clear.
- ✅ Links to `plugin-template/`.

### References

- `v1-architecture.md` §§4, 8, 11, 12

---

## M10.T4 — MCP integration guide

**Status**: done  
**Size**: M  
**Depends on**: M7.T6  
**Blocks**: nothing

### Goal

How to: (a) point Claude Desktop / Cursor / Windsurf at `hebe mcp serve`; (b) connect hebe as a client to external MCP servers.

### Files to create

- `docs/mcp.md` (new)

### Detailed work

1. **Server side**:
   - stdio config example for Claude Desktop:
     ```json
     {
       "mcpServers": {
         "hebe": {
           "command": "/usr/local/bin/hebe",
           "args": ["mcp", "serve"]
         }
       }
     }
     ```
   - SSE setup (URL + Basic auth headers).
   - WebSocket setup.
   - Which tools are advertised; how to expose `High`-risk tools via `expose_high_risk = true`.

2. **Client side**:
   - Sample `[[mcp.client.servers]]` for `@modelcontextprotocol/server-filesystem`, with `secrets` and `dynamic_keywords`.
   - Filter group strategy: when to use `Always` vs `Dynamic`.

3. Common errors + remediation (auth failure, transport disconnect, tool name collision).

### Acceptance criteria

- ✅ Both directions covered with copy-pasteable configs.

### References

- `v1-architecture.md` §15

---

## M10.T5 — Security model doc

**Status**: done  
**Size**: M  
**Depends on**: M3.T11  
**Blocks**: M10.T8

### Goal

Single document covering autonomy levels, sandbox posture, receipts, plugin trust posture, leak detector, prompt-injection guard.

### Files to create

- `docs/security.md` (new)

### Detailed work

1. Sections:
   - **Threat model** — what we defend against, what we don't.
   - **Autonomy levels** — what each one allows.
   - **Workspace boundary + command policy + domain matcher**.
   - **Subprocess sandbox** — explicitly v2; v1 relies on the validators.
   - **Plugin trust posture** — JVM modules are not sandboxed; classloader isolation is dep-version insulation, not security; signature_mode default is `optional` for v1; production should set `required`.
   - **Receipts** — Ed25519 chain, NDJSON, verify command.
   - **Secrets** — AES-256-GCM, OS keychain, passphrase fallback.
   - **Hygiene scanner + leak detector + prompt-injection guard**.
   - **Operator responsibilities** — patch the host, don't run as root, don't share `secrets.db`, set `signature_mode = required` for production.

2. Include a "what to do if you suspect compromise" runbook.

### Acceptance criteria

- ✅ All security mechanisms documented in one place.
- ✅ Trust posture explicit.
- ✅ Operator runbook present.

### References

- `v1-architecture.md` §11, §22

---

## M10.T6 — Per-channel setup guide — Telegram

**Status**: done  
**Size**: S  
**Depends on**: M5.T9  
**Blocks**: nothing

### Goal

Step-by-step Telegram setup: BotFather → token → operator id → wired up via `hebe onboard` (or manually).

### Files to create

- `docs/channels/telegram.md` (new)

### Detailed work

1. Sections:
   - Create the bot via `@BotFather`; copy the token.
   - Disable group messaging on the bot (we don't support groups in v1).
   - Set bot privacy mode (`/setprivacy` → `Disable`).
   - Operator id: how to find yours (DM `@userinfobot` or run `hebe onboard`'s auto-detect).
   - Webhook vs long-poll trade-offs.
   - Sample `config.toml` block.
   - Common errors (rate limits, missing operator id).

2. Recommended **nginx/Caddy** snippet for terminating TLS in front of the gateway when using webhooks. Caddy:

   ```
   hebe.example.com {
       reverse_proxy 127.0.0.1:8765
   }
   ```

### Acceptance criteria

- ✅ Setup walkthrough.
- ✅ Webhook + long-poll covered.
- ✅ TLS reverse-proxy hint included.

### References

- `v1-architecture.md` §16

---

## M10.T7 — Soak test (7-day continuous run) - SKIP THIS, THE USER WILL DO THIS

**Status**: pending  
**Size**: L  
**Depends on**: M9.T2 (service), M8.T9 (heartbeat)  
**Blocks**: M10.T10

### Goal

Run hebe continuously for 7 days under systemd; verify no JVM crash, no DB corruption, at least one routine fires daily, heartbeat 4×/day.

### Files to create

- `docs/operations/soak-test.md` (new — methodology + log)
- `scripts/soak-monitor.sh` (new — checks PID, DB integrity, receipts chain)

### Detailed work

1. Set up: a small VM (DigitalOcean/Linode/Hetzner) or a home server. systemd service installed.

2. Configure routines:
   - Heartbeat every 6 h.
   - Daily digest 00:05 UTC.
   - One synthetic adhoc routine per day to exercise the full loop.

3. `soak-monitor.sh` runs every hour via cron:
   - Check PID file present.
   - `hebe doctor --json | jq '.checks[].status'` — alert on any `FAIL`.
   - SQLite integrity: `pragma integrity_check;`.
   - Receipts verify: `hebe memory show receipts/$(date +%Y-%m).log --verify`.
   - Disk usage trend (DB shouldn't grow unbounded).

4. Document any incidents in `soak-test.md`; resolve before declaring v1 ready.

### Tests / verification

- 7 consecutive days green.

### Acceptance criteria

- ✅ 7-day uptime.
- ✅ Daily routines fired.
- ✅ Heartbeats fired (28 over 7 days).
- ✅ Receipts chain intact.
- ✅ DB integrity OK.

### Cut-line note

Per `v1-tasks.md` cut lines, soak duration can shrink to 48 h if schedule slips.

### References

- `v1-specs.md` §5 (acceptance criterion 15)

---

## M10.T8 — Security review checklist

**Status**: done  
**Size**: M  
**Depends on**: M10.T5  
**Blocks**: M10.T10

### Goal

A self-audit checklist covering every item in `v1-architecture.md` §22, verified against the running system.

### Files to create

- `docs/operations/security-review.md` (new)

### Detailed work

1. Each row: check name, expected behaviour, **how to verify**, status.

2. Checklist items (sample — full list mirrors arch §22):
   - INBOUND.1: dedup by external message id (verify by sending the same Telegram update twice).
   - INBOUND.2: operator allowlist (send a message from a non-operator id; assert dropped).
   - INBOUND.3: `is_agent_broadcast` recursion guard.
   - TOOL.1: loop detector fingerprint.
   - TOOL.2: tool exists + within active skill's attenuated list.
   - TOOL.3: autonomy level vs risk (each cell of the matrix).
   - TOOL.4: workspace boundary (try a traversal-attack path).
   - TOOL.5: command policy (try `rm -rf /`).
   - TOOL.6: domain matcher (try a non-allowlisted URL).
   - TOOL.7: prompt-injection guard.
   - TOOL.8: approval gate (Supervised + Medium tool).
   - TOOL.9: invoke (positive case).
   - TOOL.10: leak detector (output containing a synthetic GitHub PAT).
   - TOOL.11: receipts append + memory append + observer event.

3. Sign-off line at the bottom: "Reviewed by <name> on <date>; all items PASS."

### Acceptance criteria

- ✅ Every item in arch §22 has a verification.
- ✅ Reviewer signs off before declaring v1 ready.

### References

- `v1-architecture.md` §22

---

## M10.T9 — RFC process scaffold

**Status**: done  
**Size**: S  
**Depends on**: M10.T1  
**Blocks**: nothing direct

### Goal

A template + README for proposing substantive changes once contributors arrive (out of v1's single-engineer scope, but the seam is cheap to set up now).

### Files to create

- `docs/rfcs/0000-template.md` (new)
- `docs/rfcs/README.md` (new)

### Detailed work

1. **Template**: title, summary, motivation, design, alternatives considered, drawbacks, prior art, unresolved questions.

2. **README**: when an RFC is needed (e.g. changes to kernel ABI, new permission, new channel category, security model changes); how to propose one (open a PR adding a new RFC file).

3. Number assignment: the next sequential digit (0001, 0002, …).

### Acceptance criteria

- ✅ Template committed.
- ✅ Process documented.

### References

- `v1-specs.md` §2.13

---

## M10.T10 — Plugin Developer's Guide

**Status**: done  
**Size**: S  
**Depends on**: M10.T1  
**Blocks**: nothing direct

### Goal

A template + Guide for plugin developers, covering best practices, API documentation, and contributing guidelines. How to develop and test your plugin.

### Files to create

- `docs/plugins/0000-template.md` (new)
- `docs/plugins/Developer Guide.md` (new)

### Detailed work

1. **Template**: title, summary, motivation, design, alternatives considered, drawbacks, prior art, unresolved questions.

2. **Guide**: a complete guide to develop the Hebe plugin. Setup, start, development, testing, deployment.

3. Number assignment: the next sequential digit (0001, 0002, …).

### Acceptance criteria

- ✅ Template committed.
- ✅ Process documented.

### References

- `v1-specs.md` §2.13

---

## M10.T11 — Hebe Usage Examples

**Status**: done  
**Size**: S  
**Depends on**: M10.T1  
**Blocks**: nothing direct

### Goal

A document with a set of example use cases how to use Hebe for specific tasks.

### Files to create

- `docs/examples/Hebe Examples.md` (new)

### Detailed work

1. **Hebe Examples**: title, summary, motivation. Samples of the following use case:
    - **Secure File Transfer**: How to securely transfer files between two systems using Hebe's secure channels.
    - **Data Encryption**: How to encrypt sensitive data using Hebe's cryptographic primitives.
    - **Secure Communication**: How to establish secure communication channels between applications using Hebe's secure messaging protocol.
    - **Daily Read Summarizer**: How to use Hebe for summarizing the daily reads: internet article source (like "Medium", "Hacker News", etc.), daily sumamriZation of new articles at 4 A.M
    - **Email Categorizer**: How to use Hebe to flag and categorize your inbox
    - **Telegram**: How to set Hebe up as your Telegram conversation butler

2. **README**: when an RFC is needed (e.g. changes to kernel ABI, new permission, new channel category, security model changes); how to propose one (open a PR adding a new RFC file).

3. Number assignment: the next sequential digit (0001, 0002, …).

### Acceptance criteria

- ✅ Template committed.
- ✅ Process documented.

### References

- `v1-specs.md` §2.13

---

## M10.T12 — Internal acceptance run-through against `v1-specs.md` §5

**Status**: done  
**Size**: L  
**Depends on**: every other M* task  
**Blocks**: shipping v1

### Goal

Manually verify every numbered acceptance item in `v1-specs.md` §5 against the running system. Sign off in writing.

### Files to create

- `docs/operations/v1-acceptance-record.md` (new)

### Detailed work

1. For each of the 15 acceptance items, run the verification, capture the output (terminal session, screenshots), and check or annotate.

2. Items requiring multi-day verification (#15 soak) cross-link to `M10.T7`.

3. Final summary: PASS / FAIL per item; if any FAIL, decide cut-line vs hold-back.

### Tests / verification

- Each item verified.

### Acceptance criteria

- ✅ All 15 items recorded.
- ✅ Reviewer signature on the doc.

### References

- `v1-specs.md` §5
