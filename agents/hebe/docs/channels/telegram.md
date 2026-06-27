# Telegram Channel Setup

hebe supports Telegram as a first-class channel. It accepts messages from a single configured operator (you) and rejects all others at the adapter — messages from non-operator Telegram users never reach the agent.

---

## Step 1: Create a bot with BotFather

1. Open Telegram and start a conversation with `@BotFather`.
2. Send `/newbot`.
3. Follow the prompts: give the bot a display name (e.g. `My Hebe Agent`) and a username (e.g. `myhebebot`). The username must end in `bot`.
4. BotFather will reply with the bot token:
   ```
   Use this token to access the HTTP API:
   1234567890:AAGxJyX...yourtoken...
   ```
   Copy this token. Do not share it.

### Recommended bot settings

While still talking to `@BotFather`, configure two extra settings:

**Disable group messaging** (hebe v1 does not support groups):
```
/setjoingroups → Disable
```

**Privacy mode** (don't receive all messages in groups if added to one):
```
/setprivacy → Disable
```

---

## Step 2: Find your Telegram user id

hebe needs your numeric Telegram user id, not your username. The easiest way to find it:

1. DM `@userinfobot` on Telegram.
2. It will reply with your `Id: 123456789`.

Alternatively, run `hebe onboard` and it will auto-detect your id when you send the bot a first message.

---

## Step 3: Configure hebe

### Option A: Use `hebe onboard`

```bash
hebe onboard --telegram
```

The wizard prompts for the bot token and your user id, stores the token in `secrets.db`, and updates `config.toml`.

### Option B: Manual configuration

Store the token in the secrets store:

```bash
hebe secret set telegram.bot_token "1234567890:AAGxJyX...yourtoken..."
```

Edit `~/.hebe/config.toml`:

```toml
[channels.telegram]
enabled                = true
bot_token_secret       = "telegram.bot_token"
operator_telegram_id   = 123456789          # your numeric Telegram id
```

---

## Step 4: Choose a transport: long-poll vs webhook

### Long-poll (default, simpler)

hebe periodically calls Telegram's `getUpdates` API. No public URL required.

```toml
[channels.telegram]
enabled = true
# no webhook config needed
```

Start hebe (`hebe run` or via the service) and send your bot a message. It will respond.

Long-poll is the right choice for:
- Development and personal deployments on a home server without a static IP.
- Machines behind a NAT that can't receive inbound connections.

### Webhook (lower latency, requires a public HTTPS URL)

Telegram sends updates directly to a URL you register. You need:
- A publicly reachable server with a valid TLS certificate.
- Port 443, 80, 88, or 8443 open.

#### Using Caddy (recommended reverse proxy)

```caddy
hebe.example.com {
    reverse_proxy 127.0.0.1:8765
}
```

Caddy automatically provisions a Let's Encrypt certificate. Reload Caddy, then register the webhook with Telegram:

```bash
curl "https://api.telegram.org/bot<YOUR_TOKEN>/setWebhook" \
  -d "url=https://hebe.example.com/api/webhooks/telegram"
```

#### Using nginx

```nginx
server {
    listen 443 ssl;
    server_name hebe.example.com;

    ssl_certificate     /etc/letsencrypt/live/hebe.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/hebe.example.com/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:8765;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
}
```

After reloading nginx, register the webhook as above.

Verify the webhook is active:
```bash
curl "https://api.telegram.org/bot<YOUR_TOKEN>/getWebhookInfo" | jq .
```

---

## Step 5: Verify

Start (or restart) hebe:

```bash
hebe run
# or
sudo systemctl restart hebe
```

Check the Telegram channel health:

```bash
hebe doctor
# telegram: PASS  (long-poll connected / webhook registered)
```

Send your bot a message from your Telegram account. You should receive a reply.

Send a message from a different Telegram account. hebe should silently drop it (the non-operator message never reaches the agent; it is logged at INFO level).

---

## Sample config block (complete)

```toml
[channels.telegram]
enabled                = true
bot_token_secret       = "telegram.bot_token"
operator_telegram_id   = 123456789
```

---

## Common errors

| Error | Cause | Fix |
|---|---|---|
| `telegram: FAIL — connection timeout` | Bot token incorrect or network blocked | Re-run `hebe onboard --telegram`; verify the token with `curl https://api.telegram.org/bot<TOKEN>/getMe` |
| `operator gate: message rejected` | Message from non-operator user | Expected behaviour; verify you are messaging from the account whose id is in config |
| `rate limit: 429` | Sending too many `editMessageText` updates | Draft updates are throttled automatically (800 ms / 80 chars); reduce message frequency if you are scripting |
| Webhook not receiving updates | Public URL unreachable or TLS error | Verify with `curl https://api.telegram.org/bot<TOKEN>/getWebhookInfo`; check nginx/Caddy logs |
| `webhook: signature validation failed` | Telegram's `X-Telegram-Bot-Api-Secret-Token` header missing or wrong | Re-register the webhook; hebe generates the shared secret automatically |

---

## Approvals in Telegram

In v1, hebe does not send inline approve/deny buttons in Telegram. To approve a pending tool call, either:

- Open the web console at `http://localhost:8765` and click the approval prompt.
- Reply to hebe in Telegram with the slash command: `/approve <id>` (the id is shown in hebe's Telegram reply).
- In the CLI channel: `/approve <id>`.

Inline approval buttons are a v2 deliverable.
