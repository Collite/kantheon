# v1 Acceptance Record

Manual verification of every numbered acceptance item from `v1-specs.md` §5, run against the complete system on a clean machine before declaring v1 shippable.

See `docs/operations/security-review.md` for the security-specific checklist (which feeds items 3, 4, 5, 12 here).

---

## Environment

| Field | Value |
|---|---|
| Verified by | |
| Date | |
| hebe version / git SHA | |
| OS / JVM | |
| LLM endpoint | |
| Clean machine? (no prior hebe install) | [ ] Yes  [ ] No (explain) |

---

## Acceptance items

### 1 — Onboarding wizard

> `./hebe onboard` walks me through an LLM endpoint + Telegram setup and produces a working `~/.hebe/config.toml`.

**Verification steps**:
1. On a fresh machine with no `~/.hebe/` directory, run `hebe onboard`.
2. Provide a real LLM endpoint and API key.
3. Configure Telegram with a real bot token and your operator id.
4. Verify `~/.hebe/config.toml` exists and contains the configured values.
5. Verify `~/.hebe/secrets.db` exists and is readable by `hebe doctor`.

**Result**: [ ] PASS  [ ] FAIL  
**Notes**:

---

### 2 — Doctor green after boot

> `./hebe run` boots; `./hebe doctor` reports green for config / LLM / channels / keychain / plugins.

**Verification steps**:
1. Run `hebe run` in the background or a separate terminal.
2. Run `hebe doctor` in another terminal.
3. All checks should report PASS (or WARN for optional/unconfigured items).

**Result**: [ ] PASS  [ ] FAIL  
**Notes**:

---

### 3 — CLI multi-turn chat with tools and receipts

> From the CLI, I can have a multi-turn chat that calls `file_system.read`, `web_search`, and `http` tools, with receipts written to disk and visible via `./hebe status --recent`.

**Verification steps**:
1. `hebe run` (CLI mode).
2. Ask the agent to read a file in the workspace (triggers `file_system.read`).
3. Ask the agent to search the web for something (triggers `web_search`).
4. Ask the agent to call a whitelisted HTTP endpoint (triggers `http`).
5. Run `hebe status --recent`; verify all three tool calls appear with timestamps.
6. Run `hebe memory show receipts/$(date +%Y-%m).log --verify`; expect OK.

**Result**: [ ] PASS  [ ] FAIL  
**Notes**:

---

### 4 — Web console streaming, approval, resolution

> From the web console, the same chat works with streaming over SSE, an approval prompt appears for `shell`, and I can resolve it from the UI.

**Verification steps**:
1. Open `http://localhost:8765` (or your configured address).
2. Log in with the admin password.
3. Send a message that triggers a streaming reply — verify text appears character-by-character.
4. Ask the agent to run a shell command (e.g. `ls -la ~`).
5. Verify an approval prompt appears in the UI with an Approve/Deny button.
6. Click Approve.
7. Verify the shell command result appears in the chat.

**Result**: [ ] PASS  [ ] FAIL  
**Notes**:

---

### 5 — Telegram operator gate

> From Telegram, the same chat works as the configured operator; messages from any other Telegram user are rejected.

**Verification steps**:
1. Send a message from your configured operator Telegram account. Verify a reply.
2. Send a message from a different Telegram account. Verify silence (no reply).
3. Check hebe's logs — the non-operator message should appear at INFO with "operator gate: rejected".

**Result**: [ ] PASS  [ ] FAIL  
**Notes**:

---

### 6 — Memory: system prompt, explicit write, retrieval

> `MEMORY.md` is loaded into the system prompt; an explicit "remember that I prefer X" produces a write; a follow-up question retrieves the fact via hybrid search.

**Verification steps**:
1. Start a fresh CLI session.
2. Say: "Remember that I prefer dark mode in all tools."
3. Verify hebe acknowledges and writes to `MEMORY.md`.
4. Start a new session (quit and re-run `hebe run`).
5. Ask: "What display preferences do I have?"
6. Verify the agent recalls "dark mode" via memory retrieval (check that it used `memory_search`).

**Result**: [ ] PASS  [ ] FAIL  
**Notes**:

---

### 7 — Plugin: OCI install, tool call, signature verification

> I can publish the in-tree `hello-world` plugin to a local OCI registry, then `hebe plugin install <ref>`, then call its tool from a chat turn. Same plugin loaded with `signature_mode = required` + a valid Ed25519 signature works; loaded without a signature it refuses.

**Verification steps**:
1. Start a local OCI registry (`docker run -d -p 5000:5000 registry:2`).
2. Build and push: `cd plugin-template && ./gradlew publishPlugin -Pregistry=localhost:5000`.
3. `hebe plugin install localhost:5000/hello-world:0.1.0`.
4. `hebe plugin list` — verify status is `loaded`.
5. In a chat: ask the agent to call the `say_hello` tool. Verify output.
6. Set `plugin_signature_mode = "required"` and remove/reinstall the unsigned plugin. Verify rejection.
7. Sign the plugin (see `plugin-protocol.md §5`), reinstall. Verify it loads successfully.

**Result**: [ ] PASS  [ ] FAIL  
**Notes**:

---

### 8 — MCP server: hebe tools callable from Claude Desktop

> `hebe mcp serve --stdio` from Claude Desktop / Cursor lets that client call hebe's `file_system` tool.

**Verification steps**:
1. Add the hebe stdio MCP server to Claude Desktop's config (see `docs/mcp.md`).
2. Restart Claude Desktop.
3. Open a new conversation in Claude Desktop and ask it to read a file in your hebe workspace.
4. Verify Claude Desktop calls the hebe `file_system` tool and returns the file content.

**Result**: [ ] PASS  [ ] FAIL  
**Notes**:

---

### 9 — MCP client: external server tools available in hebe

> Configuring an external MCP server (e.g. a stdio echo server) makes its tools available with `mcp_<server>_<tool>` names.

**Verification steps**:
1. Add a `[[mcp.client.servers]]` entry for a stdio MCP server (e.g. `@modelcontextprotocol/server-filesystem`).
2. Restart hebe.
3. In a chat, ask hebe to use one of the MCP server's tools.
4. Verify the tool is invoked with the `mcp_<server>_<tool>` prefix name.
5. Verify a receipt is written for the MCP tool call.

**Result**: [ ] PASS  [ ] FAIL  
**Notes**:

---

### 10 — Routines: cron fire, ask_user, daily log

> A cron-defined routine fires at the scheduled time, runs an `ask_user` round-trip, and writes its output to `daily/YYYY-MM-DD.md`.

**Verification steps**:
1. Configure a test routine with a cron expression that fires within the next 5 minutes.
2. The routine body should: call `ask_user` with a question, then write the response to `daily/`.
3. Wait for the routine to fire.
4. Verify the question appears in the CLI/web console.
5. Answer the question.
6. Verify `daily/YYYY-MM-DD.md` is updated with the routine's output.

**Result**: [ ] PASS  [ ] FAIL  
**Notes**:

---

### 11 — Heartbeat: silence-on-OK, notify on non-OK

> `HEARTBEAT.md` content drives a periodic turn; silence-on-OK is observed.

**Verification steps**:
1. Set `heartbeat_cron` to fire every 2 minutes for this test.
2. Ensure `HEARTBEAT.md` contains a checklist whose status should evaluate to OK.
3. Wait for 2 heartbeat fires. Verify no notification is sent to the channel.
4. Modify `HEARTBEAT.md` to contain a non-OK item (e.g. "[ ] critical service is down").
5. Wait for the next heartbeat. Verify a notification IS sent to the configured `notify_channel`.
6. Restore `HEARTBEAT.md` to OK; verify silence resumes.

**Result**: [ ] PASS  [ ] FAIL  
**Notes**:

---

### 12 — Receipts: every tool call, chain verifies

> Every tool call is in `~/.hebe/receipts/YYYY-MM.log`, the chain hash verifies, and `hebe memory show receipts/2026-05.log --verify` returns OK.

**Verification steps**:
1. Run several tool calls across multiple sessions.
2. Run `hebe memory show receipts/$(date +%Y-%m).log --verify`.
3. Verify output: `OK: N records verified; chain intact`.
4. Manually corrupt one line in the receipts file (change a character).
5. Re-run `--verify`. Verify it reports the first divergence at the correct line.
6. Restore the file from backup.

**Result**: [ ] PASS  [ ] FAIL  
**Notes**:

---

### 13 — Service: install, start, restart

> `./hebe service install --systemd` generates a unit; the unit starts hebe; `systemctl restart` cleanly stops and resumes (in-flight turns terminate cleanly, pending approvals are restored).

**Verification steps**:
1. Run `hebe service install --systemd` (or `--launchd` on macOS).
2. `sudo systemctl start hebe` (or `launchctl start org.tatrman.kantheon.hebe.agent`).
3. `hebe doctor` — verify all checks PASS.
4. Start a chat session with a pending approval (trigger a shell tool call).
5. `sudo systemctl restart hebe`.
6. Verify the agent comes back up; verify the pending approval is visible (restored from DB).
7. Approve it; verify the turn resumes cleanly.

**Result**: [ ] PASS  [ ] FAIL  
**Notes**:

---

### 14 — Estop: halt mid-call, no zombies, receipt records abort

> `./hebe estop` mid-tool-call halts the loop, leaves no zombie processes, and the receipts log records the abort.

**Verification steps**:
1. Start a long-running shell command (e.g. `sleep 60`) via the `shell` tool (approve it).
2. While the command is running, in another terminal: `hebe estop`.
3. Verify the current turn stops; verify no zombie `sleep 60` process remains (`ps aux | grep sleep`).
4. Check receipts: the aborted tool call should have `"aborted": true` in the log.

**Result**: [ ] PASS  [ ] FAIL  
**Notes**:

---

### 15 — Soak test: 7-day continuous run

> A 7-day continuous run with at least one routine firing daily, no manual intervention, no JVM crash, no DB corruption.

**Verification steps**: See `docs/operations/soak-test.md` for methodology and the monitoring script.

**This item is verified separately and cross-referenced here.**

- Soak test start date:
- Soak test end date:
- Routine fires (target: ≥ 7): 
- Heartbeat fires (target: ≥ 28):
- JVM crashes: 
- DB integrity (final check):
- Receipts chain (final check):

**Result**: [ ] PASS  [ ] FAIL  
**Notes**:

---

## Final sign-off

| Item | Result |
|---|---|
| 1. Onboarding | [ ] PASS  [ ] FAIL |
| 2. Doctor green | [ ] PASS  [ ] FAIL |
| 3. CLI chat + receipts | [ ] PASS  [ ] FAIL |
| 4. Web console + SSE + approval | [ ] PASS  [ ] FAIL |
| 5. Telegram operator gate | [ ] PASS  [ ] FAIL |
| 6. Memory | [ ] PASS  [ ] FAIL |
| 7. Plugin OCI + signature | [ ] PASS  [ ] FAIL |
| 8. MCP server | [ ] PASS  [ ] FAIL |
| 9. MCP client | [ ] PASS  [ ] FAIL |
| 10. Routines | [ ] PASS  [ ] FAIL |
| 11. Heartbeat | [ ] PASS  [ ] FAIL |
| 12. Receipts | [ ] PASS  [ ] FAIL |
| 13. Service | [ ] PASS  [ ] FAIL |
| 14. Estop | [ ] PASS  [ ] FAIL |
| 15. Soak test | [ ] PASS  [ ] FAIL |

**Overall verdict**: [ ] ALL PASS — v1 shippable  [ ] FAIL — see items above

Reviewed by: ___________________________  
Date: ___________________________  
Signature: ___________________________
