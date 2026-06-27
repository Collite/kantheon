# Quickstart — hebe in 10 minutes

This guide takes you from a fresh clone to a working chat with your personal autonomous agent.

---

## Prerequisites

- **JDK 21+** — `java -version` should print `21` or higher.
- **An OpenAI-compatible LLM endpoint** — OpenAI proper, Ollama, Groq, OpenRouter, or your own LLM gateway. You need a `base_url` and an API key.
- Docker (optional, only needed for the local OCI registry demo in step 6).

---

## Step 1: Build from source

```bash
git clone https://github.com/yourorg/hebe.git
cd hebe
./gradlew shadowJar
```

The output is `build/libs/hebe-all.jar`. The `./hebe` wrapper script in the repo root points at it.

**Verify:**

```bash
./hebe --help
# prints: usage: hebe [run|onboard|doctor|service|plugin|mcp|memory|estop|status|completion|tool] …
```

---

## Step 2: Onboard

The onboarding wizard creates `~/.hebe/config.toml` and stores your secrets in the encrypted `~/.hebe/secrets.db`.

```bash
./hebe onboard
```

Sample session:

```
? LLM base URL (e.g. https://api.openai.com/v1): https://api.openai.com/v1
? API key: sk-...
? Default model: gpt-4o-mini
? Embedding model (leave blank to skip): text-embedding-3-small
? Enable Telegram? (y/N): n
? Web console admin password: ••••••••
✔ Config written to ~/.hebe/config.toml
✔ Secrets stored in ~/.hebe/secrets.db
✔ Workspace seeded at ~/.hebe/workspace/
```

**Verify:**

```bash
./hebe doctor
# every check should show PASS
```

---

## Step 3: First chat in CLI

```bash
./hebe run
```

```
hebe> hi
agent> Hi! I'm hebe, your personal autonomous agent. What would you like to do?
hebe> summarise the README.md in the current directory
agent> [streams a summary, using file_system.read internally]
hebe> /quit
```

Useful slash commands: `/approve <id>`, `/compact`, `/help`, `/quit`.  
`Ctrl-C` once cancels the current turn; twice exits.

**Verify receipts were written:**

```bash
./hebe status --recent
# shows the last few tool calls with timestamps
```

---

## Step 4: First chat in the web console

Start hebe with the web channel enabled (it is by default):

```bash
./hebe run &
```

Open `http://localhost:8765` in your browser. Log in with the password you set during onboarding.

You can:
- Send messages and watch streaming replies.
- Approve `shell` tool calls from the approval prompt.
- Browse `Memory` and `Receipts` from the top navigation.

**Verify streaming:**
Send a message that requires a tool call (e.g. "search my workspace for anything about shopping"). Watch the text stream in real time.

---

## Step 5: Add the Telegram channel

See [docs/channels/telegram.md](channels/telegram.md) for the full walkthrough.

Short version:
1. Create a bot with `@BotFather`; copy the token.
2. Find your own Telegram user id (DM `@userinfobot`).
3. Re-run onboarding or manually edit `~/.hebe/config.toml`:

```toml
[channels.telegram]
enabled = true
bot_token_secret = "telegram.bot_token"
operator_telegram_id = 123456789   # your id
```

Then store the token:

```bash
./hebe onboard --telegram
```

Restart hebe and send it a message from your Telegram account.

---

## Step 6: Install a plugin (optional)

Sideload the bundled hello-world plugin:

```bash
./hebe plugin install plugin-template/build/libs/hello-world.jar
./hebe plugin list
# hello-world  v0.1.0  loaded  capabilities=[tool]
```

Then from a chat:

```
hebe> say hello
agent> Hello from the hello-world plugin!
```

To use the OCI flow with a local registry:

```bash
# Start a local OCI registry
docker run -d -p 5000:5000 registry:2

# Build and push the plugin
cd plugin-template
./gradlew publishPlugin -Pregistry=localhost:5000

# Install from the registry
./hebe plugin install localhost:5000/hello-world:0.1.0
```

---

## Step 7: Run as a system service

```bash
# Install as a systemd service (Linux)
./hebe service install --systemd

# Or as a launchctl plist (macOS)
./hebe service install --launchd

sudo systemctl start hebe      # or: launchctl start org.tatrman.kantheon.hebe.agent
sudo systemctl enable hebe     # start on boot
```

**Verify:**

```bash
./hebe doctor
sudo systemctl status hebe
```

After a host reboot, `./hebe doctor` should still report green.

---

## Next steps

- Set up memory-maintenance routines in `~/.hebe/config.toml` (`[scheduler]` block).
- Add MCP servers to give hebe access to your local filesystem, databases, or other tools. See [docs/mcp.md](mcp.md).
- Write skills in `~/.hebe/skills/` to give hebe domain-specific instructions for recurring tasks.
- Review the [security model](security.md) to choose the right autonomy level (`ReadOnly`, `Supervised`, or `Full`).
