# hebe Usage Examples

Real-world scenarios that show what you can do with hebe once it is running. Each example includes the relevant config, a sample conversation, and notes on what makes it work.

---

## 1. Telegram Butler

Turn hebe into a personal assistant that you can message from anywhere via Telegram.

### Setup

Follow [docs/channels/telegram.md](../channels/telegram.md) to create a bot and configure the channel. Then set an appropriate autonomy level in `~/.hebe/config.toml`:

```toml
[autonomy]
level = "Supervised"   # hebe asks for approval on risky operations
```

### Example conversation (in Telegram)

```
You: What's on my agenda today?
hebe: [reads ~/workspace/context/agenda.md] Here's what you have today:
      - 10:00 Team sync
      - 14:00 Code review with Alice
      - 16:30 Write the weekly update

You: Remind me to send the weekly update email by 16:00
hebe: Got it. I've added a note to your MEMORY.md and scheduled a reminder 
      routine for 15:45 today.

You: Search the web for the latest Python 3.13 release notes
hebe: [calls web_search] Python 3.13 was released on October 7, 2024. 
      Key highlights: …
```

### What makes this work

- The `telegram` channel is enabled with your `operator_telegram_id` set.
- `memory_write` stores the reminder; the scheduler picks it up.
- `web_search` is `Low` risk and runs automatically.
- All tool calls are receipted in `~/.hebe/receipts/`.

---

## 2. Daily Read Summariser

Have hebe fetch articles from Hacker News and Medium each morning and email you a summary by the time you wake up.

### Setup

Configure a daily routine in `~/.hebe/config.toml`:

```toml
[scheduler]
daily_digest_cron = "5 0 * * *"   # 00:05 UTC every day
```

Add a custom routine (via chat or directly in the DB):

```
hebe> schedule a routine called "morning-reads" to run at 4:00 AM every day:
      1. Fetch the top 10 Hacker News stories from the HN API
      2. For each story, fetch the article and summarise it in 2 sentences
      3. Write the summaries to daily/YYYY-MM-DD-reads.md in my workspace
```

### Required config

```toml
[security]
http_allowlist_domains = [
    "hacker-news.firebaseio.com",   # HN API
    "medium.com",
    "api.brave.com",                # web_search
]
```

### Sample output (in `~/.hebe/workspace/daily/2026-05-17-reads.md`)

```markdown
# Morning reads — 2026-05-17

## 1. Ask HN: What's your favourite static analysis tool?
*Source: news.ycombinator.com*
A community thread on static analysis tools, with strong mentions of Semgrep,
Clippy (Rust), and Pyright. Commenters favour tools that catch real bugs over
style linters.

## 2. Why I switched from React to Svelte
*Source: medium.com*
A developer's account of migrating a mid-sized SPA to Svelte, citing bundle
size reduction of 40% and significantly less boilerplate.
```

### Notes

- `http` tool is `Medium` risk; under `Supervised` autonomy it needs one-time approval per turn. Set `Full` for unattended routines.
- Use `always_approve_tools = ["http"]` in the routine config to skip the approval gate for this specific routine (v2 feature — in v1, set autonomy to `Full` for the routine session).
- The resulting file is indexed by the memory system and searchable via `hebe memory search`.

---

## 3. Email Categoriser

Have hebe connect to your email (via an MCP server or custom plugin), flag important messages, and write a daily summary to your workspace.

### Setup (using an MCP-based email server)

```toml
[[mcp.client.servers]]
name      = "email"
transport = "stdio"
command   = ["npx", "@example/mcp-email-server"]
secrets   = { EMAIL_PASSWORD = "email.password" }
always_tools = ["list_emails", "read_email", "flag_email"]
```

### Example conversation

```
You: Check my inbox and flag anything from my team
hebe: [calls mcp_email_list_emails, mcp_email_read_email ×3]
      Found 12 new emails. Flagged 3 from your team:
      - "Re: API design review" — Alice — needs your input
      - "PR ready for review" — Bob — PR #247
      - "Weekly metrics" — Carol — attached report
      
      Moved 6 promotional emails to the Promotions folder.
      Left 3 newsletters unflagged for your review.

You: Summarise the PR review email and add it to today's daily log
hebe: [calls mcp_email_read_email, memory_write]
      Added to your daily log. Bob's PR #247 refactors the authentication 
      middleware to use the new token rotation logic from last sprint.
      Estimated review time: 30 minutes.
```

### Notes

- The MCP server handles email protocol details; hebe just calls its tools.
- The `secrets` injection means your email password is never in `config.toml`.
- `flag_email` and `list_emails` should be `Low` or `Medium` risk in the MCP server's risk declaration.

---

## 4. Secure File Operations

Use hebe to read, transform, and write files within your workspace, with a full tamper-evident receipt chain.

### Example: batch rename files by content

```
You: Find all markdown files in projects/alpha/ that mention "deprecated" 
     in the first line, and rename them to have a "-deprecated" suffix

hebe: [calls file_system.glob, file_system.read ×4, file_system.write ×2]
      Found 4 files. Of these, 2 have "deprecated" in the first line:
      - projects/alpha/auth-v1.md → renamed to auth-v1-deprecated.md
      - projects/alpha/billing-old.md → renamed to billing-old-deprecated.md
      
      The other 2 files were not modified.
```

### Example: encrypt a sensitive note before storing

hebe does not directly provide encryption tools in v1. However, you can use a plugin or the `shell` tool (with approval) to call `openssl` or `gpg`:

```
You: Encrypt the file projects/alpha/api-keys.md with my GPG key 
     and delete the plaintext

hebe: I need approval to run a shell command. I'll run:
      gpg --encrypt --recipient your@email.com projects/alpha/api-keys.md
      and then: rm projects/alpha/api-keys.md
      [shows approval prompt]
      
You: /approve a3f2
hebe: Done. projects/alpha/api-keys.md.gpg created; plaintext deleted.
```

### Security notes

- File writes within the workspace are automatically receipted.
- `shell` tool calls require explicit approval under `Supervised` or `Full` autonomy.
- Workspace boundary enforcement prevents writes outside `~/.hebe/workspace/` unless explicitly configured.

---

## 5. Code Review Prep

Use the bundled `code-review-prep` skill to have hebe prepare a summary of a PR before you sit down to review it.

### Activate the skill

```
You: /skill code-review-prep
```

### Example session

```
You: Prepare a review for PR #247 in the hebe repo
hebe: [calls github.get_pr, github.list_pr_files, file_system.read ×8]
      
      ## PR #247 — Refactor auth middleware (Bob, 2026-05-16)
      
      **Changed files**: 8 files, +312 / -187 lines
      
      **Key changes**:
      - New `TokenRotator` class in `modules/security/`
      - Existing `AuthMiddleware` now delegates rotation to `TokenRotator`
      - Tests updated: 4 existing tests modified, 6 new tests added
      
      **Potential concerns**:
      - `TokenRotator` opens a database connection in the constructor — 
        consider lazy init or injection
      - No integration test covers the rotation-on-expiry path
      
      **Suggested review focus**: the rotation logic in `TokenRotator.rotate()` 
      and the expiry calculation in `AuthMiddleware.checkExpiry()`.
```

### Notes

- Requires `github` tool with a PAT stored in secrets: `hebe secret set github.token ghp_...`.
- `github` read operations are `Medium` risk; the skill uses the attenuated tool list.
- The prepared summary is written to the workspace and available for future reference via `memory_search`.

---

## 6. Data Encryption (via plugin)

A custom plugin can expose domain-specific encryption tools beyond what the built-in `shell` tool provides. This example shows the pattern; the actual plugin is not shipped in v1.

### Example plugin: AES-256-GCM file vault

A plugin could expose tools like:
- `vault:encrypt_file` — encrypts a file using a key from secrets
- `vault:decrypt_file` — decrypts to a temp path; temp file is deleted after the turn
- `vault:list_encrypted` — lists encrypted files in the vault directory

```
You: Encrypt my API credentials file and store it in the vault
hebe: [calls vault:encrypt_file]
      Encrypted: projects/alpha/api-keys.md → vault/api-keys.md.enc
      The plaintext file has been deleted.
      Key reference: vault.api-keys (stored in secrets.db)
```

### Building such a plugin

See the [Plugin Developer's Guide](../plugins/Developer%20Guide.md) for how to expose custom tools. For crypto operations, depend on Bouncy Castle (already in hebe's classpath) or any standard JCE provider.

---

## 7. Secure Communication Setup

hebe's web console exposes an HTTPS endpoint for the web UI and API. Here's how to set it up securely for LAN or remote access.

### TLS via Caddy (simplest)

```caddy
hebe.yourdomain.com {
    reverse_proxy 127.0.0.1:8765
}
```

Caddy provisions a Let's Encrypt cert automatically. Your web console is then at `https://hebe.yourdomain.com`.

### hebe config

```toml
[channels.web]
enabled               = true
bind                  = "127.0.0.1"   # bind to loopback; Caddy proxies to it
port                  = 8765
admin_password_secret = "web.password"
```

### MCP over TLS (for IDE integration)

Once Caddy is running:

```json
{
  "mcpServers": {
    "hebe": {
      "url": "https://hebe.yourdomain.com/mcp/sse"
    }
  }
}
```

Set the `Authorization: Basic admin:<password>` header in your MCP client if it supports custom headers. For Claude Desktop (which does not yet support custom headers on SSE), use the stdio transport instead.

### Notes

- The web console uses HTTP Basic auth. Choose a strong password during `hebe onboard`.
- For purely local use, `bind = "127.0.0.1"` is sufficient; no TLS needed.
- Never expose `hebe` directly on a public IP without TLS.
