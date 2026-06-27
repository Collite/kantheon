# hebe — specifikace v1 (scope)

Toto je **kontrakt rozsahu (scope contract)** pro hebe v1. Slouží jako referenční dokument při rozhodování, zda daná funkce patří do aktuální verze či nikoliv ("is this in or out?").

Doprovodná dokumentace:

- [`v1-architecture.md`](v1-architecture.md) — konkrétní kontrakty, schémata, životní cyklus (lifecycle).
- [`v1-tasks.md`](v1-tasks.md) — seřazený seznam úkolů s ohledem na závislosti.

Zdroje pro upstream rozhodnutí: [`hebe-brainstorming-responses.md`](hebe-brainstorming-responses.md), [`hebe-architecture.md`](hebe-architecture.md), [`hebe-features.md`](hebe-features.md).

---

## 1. Téze v1 (definice úspěchu)

Jeden uživatel může spustit příkaz `hebe run` a získá **osobního autonomního agenta**, který:

1. Komunikuje prostřednictvím **CLI, self-hosted webové konzole nebo Telegramu**.
2. Provádí úvahy (reasoning) skrze uživatelův **LLM Gateway** (nebo jakýkoliv OpenAI-kompatibilní endpoint).
3. Čte a zapisuje do **markdown workspace**, který slouží jako perzistentní paměť agenta.
4. Volá **vybranou sadu vestavěných nástrojů** (souborový systém, shell, HTTP, web search, git, kubectl, paměť, plánování) pod přísnou politikou autonomie a schvalování.
5. Generuje **tamper-evident logy (receipts log)** o všech provedených akcích.
6. Je rozšiřitelný pomocí **PF4J pluginů stahovaných z container registry** bez nutnosti rekompilace hebe.
7. Podporuje **MCP** jako server i klient, což umožňuje propojení s externími nástroji a IDE.
8. Běží nepřetržitě se **systémem naplánovaných rutin a úloh pro údržbu paměti** bez nutnosti manuálního zásahu.

Pokud může uživatel používat hebe denně po dobu dvou týdnů, aniž by bylo nutné restartovat JVM, ladit proces nebo ručně upravovat databázi — verze v1 je hotová.

## 2. V rozsahu (In scope - musí být dodáno)

### 2.1 Runtime + agent loop

- Jeden proces agenta na uživatele; single-user architektura bez `TenantScope`.
- koog zapouzdřený za fasádou `LlmProvider` / `HebeAgent`.
- Jednotný mutační trychtýř `ToolDispatcher.dispatch`, vynucený pomocí lintu (pravidlo Detekt).
- Parser typu `Submission` před dispatcherem (slash-příkazy, schvalování, surový vstup, zadávání přihlašovacích údajů v auth-módu).
- Rozdělení strategií `LoopDelegate`: **`ChatDelegate` v1**, minimální `JobDelegate` (pro scheduler), `WorkerDelegate` odložen na později.
- Hooky: `BeforeInbound`, `BeforeToolCall`, `BeforeOutbound`, `OnSessionStart/End`. Fail-open sémantika.
- Detektor smyček (varování po 3 opakováních / vynucený text po 5 opakováních u identických fingerprintů volání nástrojů).
- Hlídání nákladů (limity tokenů na jeden turn a na den).
- Kompakční žebříček: workspace-promote → sumarizace → odmítnutí trunkace. Výchozí práh spuštění na 60 % kontextu (konfigurovatelné).
- Preemptivní prořezávání historie (trimování před přetečením).
- End-to-end streaming: provider → channel `updateDraft` (kde je podporováno) → finální odpověď při stavu `Done`.
- Pozastavení uprostřed streamu pro volání nástroje → validace → vyvolání → pokračování.
- Max-iteration guard (výchozí 10 na jeden turn).
- Rozlišení stavu `HandleOutcome::Pending` od `NoResponse`.
- Intercepce v auth-módu (zadávání přihlašovacích údajů se nikdy neukládá do historie chatu).

### 2.2 LLM provider

- Implementace jednoho `LlmProvider`: **OpenAI-kompatibilní klient** (BYOK).
- Konfigurace pomocí `base_url` + `api_key` + `default_model`; funguje proti uživatelskému LLM Gateway, OpenAI, Ollama, OpenRouter nebo Groq.
- Podpora pro streaming, tool use a kontrolu schopností (`streaming?`, `tool_use?`, `multimodal?`).
- Počítání tokenů a záznam nákladů za každé volání do tabulky `llm_calls`.

### 2.3 Paměť (Memory)

- Trait `MemoryStore` + SQLite backend (`~/.hebe/hebe.db`).
- FTS5 + sqlite-vec; Reciprocal Rank Fusion při `k₀ = 60`.
- Markdown workspace (`~/.hebe/workspace/`) se soubory `MEMORY.md`, `IDENTITY.md`, `HEARTBEAT.md`, `daily/YYYY-MM-DD.md`.
- Nástroje pro workspace: `memory_search`, `memory_write`, `memory_read`, `memory_tree`, `wiki_read`, `wiki_write`.
- Abstrakce poskytovatele embeddingů. v1 obsahuje: OpenAI-kompatibilní embeddingy (přes stejný gateway) + mock provider pro testy. Lokální Ollama embeddingy jsou považovány za OpenAI-kompatibilní.
- Chunking: 800 slov, 15% překryv, minimum 50.
- LRU cache pro odpovědi.
- Hygiene scanner (sanitizace příchozích zápisů proti vzorům prompt-injection).
- Detekce skupinových chatů (vyloučení `MEMORY.md` ze systémového promptu pro non-1:1 kontexty — relevantní po přidání podpory pro Telegram groups).
- Enum pro kategorie paměti (`Conversation | Fact | Preference | Skill | Document`).
- Dokumentováno pět úrovní (Live / Transcript / Curated / Derived / Retrieval).

### 2.4 Naplánovaná interní správa (paměť + operace)

Implementováno jako řádky `Routine` vlastněné schedulerem. v1 obsahuje:

- Sumarizace transkriptů (rolling window).
- Extrakce faktů a preferencí do `MEMORY.md`.
- Daily digest (`workspace/daily/YYYY-MM-DD.md`).
- Čištění zastaralých úloh.
- Refresh a reindexace embeddingů.
- Detekce selhaných jobů a nástrojů.
- Heartbeat (řízený přes `HEARTBEAT.md`; tichý režim při stavu OK).

### 2.5 Kanály (Channels)

**Sada kanálů pro v1: Web Console + CLI + Telegram. Nic jiného.**

- **CLI** — lokální interaktivní REPL postavený nad traitem `Channel`. Slash-příkazy (`/quit`, `/compact`, `/approve`, `/help`).
- **Web Console** — Ktor server s SSE pro streaming, základní HTML/HTMX nebo malá Svelte SPA. Routy:
    - `GET /` — chat UI
    - `POST /api/messages` — odeslání zprávy
    - `GET /api/sessions/{id}/events` — SSE stream
    - `POST /api/approval/{id}` — vyřízení schválení
    - `GET /api/memory/search?q=…`
    - `GET /api/receipts?since=…`
    - `POST /api/webhooks/<channel>/<endpoint>` — webhook ingress pro kanály
    - HTTP Basic auth přes TLS, jedno heslo.
- **Telegram** — knihovna TelegramBots. Podpora pro webhook i long-poll. Allowlist pro jednoho operátora (akceptováno pouze ID Telegramu konfigurovaného operátora). Aktualizace draftů přes `editMessageText`.
- `ChannelManager` merging + `injectChannel` (kapacita 64) pro background producenty (heartbeat, scheduler, MCP server).
- Channel `healthCheck()` dostupný přes `hebe doctor`.
- Ochrana proti rekurzi (`isAgentBroadcast`).

### 2.6 Vestavěné nástroje (Built-in tools)

| Nástroj | Riziko | Poznámky |
|---|---|---|
| `file_system` (read/write/list/glob) | Nízké | Vázáno na workspace. Podpora pro markdown/json/yaml/html. |
| `shell` | Vysoké + vždy vyžaduje schválení | Allow/deny listy; validátor před spuštěním. Ve v1 bez subprocess sandboxu. |
| `http` (RESTful APIs) | Střední | Allowlist domén; SSRF-safe. |
| `web_search` | Nízké | Trait `WebSearchProvider`. v1 providery: **Brave** (výchozí při existenci API klíče) + **DuckDuckGo** (free fallback). |
| `memory_search` / `memory_read` / `memory_write` / `memory_tree` | Nízké / Střední u zápisu | Workspace jako souborový systém. |
| `wiki_read` / `wiki_write` | Nízké / Střední | Konvence postavená nad `file_system`. |
| `git` | Střední u čtení / Vysoké u zápisu | JGit v procesu pro read/diff/clone; volání shellu pro push + credential helpers. |
| `github` | Střední / Vysoké | API klient; autentizace přes PAT v secrets store. |
| `kubectl` | Vysoké + vždy vyžaduje schválení pro mutační příkazy | Volání shellu. Read-only příkazy (`get`, `describe`, `logs`, `top`, `events`, `version`) jsou Střední. Mutační příkazy (`apply`, `delete`, `exec`, `scale`, `patch`, `replace`, `port-forward`, `rollout`, `cordon`, `drain`, `uncordon`, `taint`, `label`) jsou Vysoké a vyžadují schválení. |
| `ask_user` | Nízké | Doplňující otázka přes původní kanál. |
| `schedule` | Střední | Vytváří záznamy rutin (routines). |
| `job_create` / `job_status` / `job_cancel` | Nízké | Ovládání úloh na pozadí. |

Všechny nástroje procházejí skrze `ToolDispatcher`. Citlivé parametry (`api_key`, `token`, `password`, `secret` atd.) jsou automaticky redigovány v receipts i v UI.

### 2.7 MCP

- **MCP klient**: konzumace externích MCP serverů jako zdroje nástrojů. Nástroje importovány jako `mcp_<server>_<tool>`.
- **MCP server**: vystavení vestavěných nástrojů hebe pro ostatní agenty.
- Transporty: stdio (výchozí pro oba směry), SSE + WebSocket přes Ktor.
- Filtrační skupiny nástrojů: `Always` (inzerováno bezpodmínečně) + `Dynamic` (inzerováno pouze pokud zpráva uživatele obsahuje klíčové slovo).
- Injekce přihlašovacích údajů pro konkrétní servery na hranici (boundary); plugin/MCP server nikdy nevidí surové tajemství (secret).

### 2.8 Hostitel pluginů (PF4J + ACR)

PF4J spike je součástí v1 — nikoliv předehrou. Prvním výstupem je "hello-world Tool plugin načtený z lokálního JARu"; druhý krok rozšiřuje podporu na OCI pull z ACR s verifikací podpisu.

- PF4J `PluginManager` řídící wrapper `hebe` PluginManageru.
- Modul `plugin-api` vystavující `HebePlugin` + `PluginHost` + rozhraní schopností. `koog`, `slack-bolt` (při přidání), JDBC atd. nejsou pro pluginy viditelné — pouze `api` + `plugin-api`.
- Layout pluginu: adresář nebo fat-JAR; `plugin.properties` (PF4J) + `plugin.toml` (hebe manifest).
- Schopnosti v1 dostupné skrze `PluginHost`:
    - `http_client` — Ktor klient s allowlistem domén.
    - `env_read` — vybraná podmnožina env proměnných (filtrováno proti vzorům `*_TOKEN | *_SECRET | *_KEY`).
    - `secrets:<name>` — injektováno hostitelem; plugin vidí handle, nikoliv hodnotu.
- Ed25519 verifikace podpisu s výchozím nastavením `signature_mode = optional` (varování u nepodepsaných, ale načtení).
- Distribuce: OCI pull z container registry (primárně Azure Container Registry; OCI klient je generický, funguje jakákoliv registry). Autentizace: Azure `DefaultAzureCredential` řetězec.
- Příkazy `hebe plugin install <oci-ref>`, `hebe plugin install <path>` (sideload), `hebe plugin list` a `hebe plugin remove <name>`.
- Namespacing pluginů pro skilly: `plugin:<plugin>/<skill>`.
- ABI verze fixována v manifestu (`hebe_api_version`); nekompatibilní pluginy se odmítnou načíst s jasnou chybou.

### 2.9 Skilly

- Markdown balíčky ve formátu agentskills.io pod `~/.hebe/skills/`.
- Frontmatter (název, popis, aktivační klíčová slova/vzory/tagy, max_context_tokens) + tělo.
- Deterministický prefilter (bez nutnosti LLM) — port IronClaw.
- Trust ceiling skillů (Installed < User < Bundled) omezující seznam nástrojů.
- Bundled sada ve v1: 3–5 startovacích skillů (bude určeno; např. `daily-briefing`, `code-review-prep`, `wiki-organiser`).

### 2.10 Bezpečnost (Security)

- Úrovně autonomie: `ReadOnly | Supervised | Full` (+ předvolba `YOLO`, výrazně označená).
- Workspace boundary (cesty mimo workspace jsou ve výchozím stavu blokovány; `forbidden_paths` jsou blokovány vždy).
- Command policy (allow/deny + validátor vzorů před shell příkazem).
- Domain matcher pro odchozí HTTP.
- Tool receipts: Ed25519-signed, hash-chained, append-only na disku v `~/.hebe/receipts/YYYY-MM.log`.
- Leak detector na výstupu (blokování při nálezu).
- Ochrana proti prompt-injection na výstupu modelu před dispečinkem nástroje.
- Redakce citlivých parametrů v logách/UI.
- Nouzové zastavení (`hebe estop`).
- Secrets at rest: AES-256-GCM v `secrets.db`, hlavní klíč v OS keychain (macOS / secret-service / Windows Cred Mgr); fallback na klíč odvozený z hesla (passphrase).
- Třívrstvé oddělení: bootstrap config / DB settings / šifrované secrets — nikdy se neslučují.
- Invariant LLM dat: nic se proaktivně nemaže.

### 2.11 Operace

- CLI sub-příkazy: `run`, `onboard`, `service install/start/stop/uninstall`, `doctor`, `tool list`, `plugin install/list/remove`, `mcp serve`, `memory search/tree/show`, `pairing` (single-operator pairing), `estop`, `status`, `completion`.
- Kontroly `hebe doctor`: validita konfigurace, dostupnost LLM endpointu, zdraví kanálů, manifesty pluginů, sandbox (ve v1 no-op, detekce přítomnosti), přístup ke keychain.
- `hebe service install` generuje a instaluje systemd unit / launchctl plist / Windows-Service definici. Daemon mód + PID soubor.
- Onboarding průvodce: výběr LLM endpointu, vložení API klíče, konfigurace Telegramu (volitelné), vygenerování výchozí konfigurace.
- OpenTelemetry exportéry skrze koog.
- Strukturované JSON logy přes kotlin-logging + logback.
- Korektní vypnutí (SIGTERM, Ctrl-C, `/quit`).

### 2.12 Distribuce + dev experience

- Single fat JAR přes Gradle Shadow (`hebe.jar`), shell wrapper `./hebe`.
- Detekt + ktlint baseline + vlastní Detekt pravidlo pro disciplínu `// dispatch-exempt:`.
- Mock LLM provider (založený na replay), mock paměť, mock kanál pro testy.
- HTTP record/replay pro trace testy (analogie k IronClaw `HttpInterceptor`).
- Integrace Testcontainers pro integrační testy (Postgres odložen, ale Telegram bot + simulovaný MCP server sem patří).

### 2.13 Dokumentace (v1)

- `README.md` — instalace + minimální konfigurace na 10 řádků.
- Quickstart (10minutová cesta k úspěchu).
- `v1-specs.md` (tento dokument), `v1-architecture.md`, `v1-tasks.md`.
- Průvodce nastavením pro každý kanál (Telegram).
- Specifikace protokolu JVM pluginů (PF4J + manifest + ACR publish/pull flow).
- Průvodce integrací MCP (server + klient).
- Dokument k bezpečnostnímu modelu.

## 3. Mimo rozsah (Explicitně vyloučeno)

Tyto položky budou dodány ve verzi v2 nebo později. Jsou zde uvedeny, aby nedošlo k jejich nechtěnému zahrnutí do v1.

- **Kanály**: Slack, Email, WhatsApp, Discord, Matrix, Signal, iMessage, Microsoft Teams, IRC.
- **LLM**: nativní adaptéry pro Anthropic / Bedrock / Gemini / Azure / OpenRouter. Provider router. Fallback chain.
- **Paměť**: PostgreSQL backend. Decay (stárnutí dat). Konsolidace. Detekce konfliktů. Snapshoty. Knowledge graph. Multi-scope čtení. Externí provideři (mem0/honcho/supermemory).
- **Pluginy**: hot-reload. Veřejný marketplace. Schopnosti `channel`/`memory`/`observer` (rezervováno). Oprávnění `file_read`/`file_write`/`memory_read`/`memory_write`. Verzování pluginů + rollback.
- **SOP (Standard Operating Procedures)** kompletně. (Rutiny ano, SOP ne.)
- **OS sandbox** (`firejail`/`bwrap`/Docker) pro shell/browser/kubectl. Subprocess wrapper odložen.
- **OTP gating**, **WebAuthn**, párování zařízení pro kanály.
- **Multi-tenant / multi-user**. `TenantScope`. Párování pro více uživatelů.
- **ACP** (integrace IDE přes JSON-RPC přes stdio). Pokryto přesahem MCP.
- **Skillforge** (agent se sám učí nové skilly). Sebeevoluce ve stylu Hermes.
- **Native-image** distribuce (GraalVM). **Docker** image. Helm chart.
- **Správa tunelů** (cloudflared/ngrok/tailscale).
- **Webová konzole**: editor nastavení, UI pro správu pluginů, UI pro správu cronů/rutin, panel stavu kanálů/providerů/secretů.
- **Hardware** (trait `Peripheral`, GPIO/I2C/SPI/USB).
- **Path-scoped concurrent tools**, **dvouvrstvý kontext**, **transkripce/TTS middleware**.
- **Inline tlačítka pro schvalování** v Telegramu.

## 4. Nefunkční požadavky (NFR)

| NFR | Cíl | Verifikace |
|---|---|---|
| Studený start | < 5 s na vývojářském notebooku (warm JVM cache) | Stopky v CI na referenčním stroji Mac mini |
| Latence na turn | < 250 ms na straně hebe (bez LLM) | OTel span budgety |
| Paměťová stopa (idle) | < 400 MB RSS | `ps` snapshot v `hebe doctor` |
| Paměťová stopa (aktivní turn) | < 800 MB RSS | Stejné |
| Růst SQLite | Přijatelný pro neustálé přidávání; bez proaktivního mazání | Manuální revize na konci v1 po 30denním provozu |
| Testovací pokrytí | > 70 % řádků pro moduly `core`, `memory`, `security`, `plugins` | jacoco gate v CI |
| Detekt | Nula varování na větvi `main` | CI gate |
| Načtení pluginu (lokální) | < 500 ms | Benchmark v `hebe plugin install --dry-run` |
| Stažení pluginu (ACR cold) | < 10 s pro 5 MB artefakt | Manuální benchmark |
| Zápis tool receipt | Append v řádu milisekund; fsync každých N volání | Benchmark |
| Integrita workspace | Žádné poškozené zápisy po kill -9 uprostřed relace | Stress test |

## 5. Akceptační kritéria (Definition of Done)

Verze v1 je připravena k vydání, pokud na čistém stroji projde vše z následujícího:

1. `./hebe onboard` provede uživatele nastavením LLM endpointu + Telegramu a vytvoří funkční `~/.hebe/config.toml`.
2. `./hebe run` se spustí; `./hebe doctor` hlásí zelenou pro config / LLM / kanály / keychain / pluginy.
3. Z **CLI** lze vést multi-turn chat, který volá nástroje `file_system.read`, `web_search` a `http`, přičemž potvrzení (receipts) se zapisují na disk a jsou viditelná přes `./hebe status --recent`.
4. Z **webové konzole** funguje stejný chat se streamingem přes SSE, pro `shell` se zobrazí výzva ke schválení a lze ji vyřešit z UI.
5. Z **Telegramu** funguje stejný chat jako konfigurovaný operátor; zprávy od jakéhokoliv jiného uživatele Telegramu jsou odmítnuty.
6. Paměť: `MEMORY.md` je načten do systémového promptu; explicitní příkaz "zapamatuj si, že preferuji X" vytvoří záznam; následná otázka načte fakt přes hybridní vyhledávání.
7. **Plugin**: lze publikovat in-tree `hello-world` plugin do lokální OCI registry, poté provést `hebe plugin install <ref>` a zavolat jeho nástroj z chatu. Stejný plugin načtený s `signature_mode = required` + platným Ed25519 podpisem funguje; bez podpisu se odmítne.
8. **MCP server**: `hebe mcp serve --stdio` z Claude Desktop / Cursor umožní tomuto klientovi volat nástroj `file_system` hebe.
9. **MCP klient**: konfigurace externího MCP serveru (např. stdio echo server) zpřístupní jeho nástroje pod názvy `mcp_<server>_<tool>`.
10. **Rutiny**: rutina definovaná přes cron se spustí v naplánovaný čas, provede round-trip `ask_user` a zapíše výstup do `daily/YYYY-MM-DD.md`.
11. **Heartbeat**: obsah `HEARTBEAT.md` řídí periodický turn; je dodržen tichý režim (silence-on-OK).
12. **Potvrzení (Receipts)**: každé volání nástroje je v `~/.hebe/receipts/YYYY-MM.log`, hash řetězec sedí a `hebe memory show receipts/2026-04.log --verify` vrací OK.
13. **Služba**: `./hebe service install --systemd` vygeneruje unit; unit spustí hebe; `systemctl restart` korektně zastaví a obnoví proces (běžící turny skončí čistě, čekající schválení jsou obnovena).
14. **Estop**: `./hebe estop` uprostřed volání nástroje zastaví smyčku, nezanechá žádné zombie procesy a log potvrzení zaznamená přerušení.
15. Zátěžový test: 7denní nepřetržitý běh s alespoň jednou rutinou denně, bez manuálního zásahu, bez pádu JVM, bez poškození DB.