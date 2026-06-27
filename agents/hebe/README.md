# hebe

A personal autonomous agent that lives on your machine *or* on the Kantheon platform, talks to you via CLI, a self-hosted web console, or Telegram, and reasons via your own LLM endpoint (local profile) or the platform's llm-gateway (k8s profile).

> **Kantheon module note (2026-06-12).** Hebe moved into `kantheon/agents/hebe`. The integration arc (profiles, Postgres, Keycloak, capabilities registration, iris-bff client) is documented at [`/docs/architecture/hebe/`](../../docs/architecture/hebe/) and planned at [`/docs/implementation/v1/hebe/plan.md`](../../docs/implementation/v1/hebe/plan.md).
>
> **Build merged into the kantheon root (P1, 2026-06-25).** The standalone Hebe build is retired. The 21 modules build from the kantheon root (`:agents:hebe:modules:*`) and the package root is now **`org.tatrman.kantheon.hebe.*`** (was `com.hebe.*`). Commands below that invoke `./gradlew` from `agents/hebe/` no longer apply — use the root recipes instead: `just hebe-build` (the `hebe.jar` shadowJar), `just hebe-test`, `just hebe-run-local`.

## Install

```bash
# Build from source (requires JDK 21+)
./gradlew shadowJar
sudo cp build/libs/hebe-all.jar /usr/local/lib/hebe/hebe.jar
sudo cp hebe.sh /usr/local/bin/hebe && chmod +x /usr/local/bin/hebe
```

## First run

```bash
hebe onboard          # interactive setup: LLM endpoint, Telegram (optional), admin password
hebe doctor           # verify config, LLM reachability, channel health, keychain
hebe run              # start the agent in CLI mode
```

Open `http://localhost:8765` in your browser for the web console.

## Documentation

- [Quickstart guide](docs/quickstart.md) — 10-minute happy path from clone to first chat
- [Security model](docs/security.md) — autonomy levels, receipts, plugin trust posture
- [Plugin protocol spec](docs/plugin-protocol.md) — authoring and distributing plugins
- [MCP integration guide](docs/mcp.md) — using hebe as an MCP server or client
- [Telegram setup](docs/channels/telegram.md) — BotFather to first message
- [Usage examples](docs/examples/Hebe%20Examples.md) — real-world scenarios
- [Architecture (standalone v1)](../../docs/architecture/hebe/standalone-v1-architecture.md) — wiring diagram, contracts, schemas
- [Specs (standalone v1)](../../docs/design/hebe/v1-specs.md) — scope contract and acceptance criteria
- [Kantheon integration arc](../../docs/architecture/hebe/architecture.md) — profiles, Postgres, constellation wiring
