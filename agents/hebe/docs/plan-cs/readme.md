Zde je český překlad souboru `README.md`**README.md** (původně označeného jako _Hebe — brainstorming record_). Tento dokument slouží jako retrospektivní záznam rozhodovacího procesu a „metodika“ toho, jak projekt dospěl k aktuálnímu plánu verze v1.

### **README.md (Czech Translation)**

# Hebe — záznam brainstormingu (shrnuti diskuse)

Chronologický záznam konverzace, která vyústila v aktuální plán verze v1. Účelem tohoto dokumentu je uchovat informaci o tom, **jak jsme se sem dostali** — tedy zdůvodnění, zvažované alternativy a momenty, kdy vstup uživatele změnil směřování projektu — nikoliv pouze finální rozhodnutí.

Pro samotná finální rozhodnutí viz:

- `v1-specs.md`[v1-specs.md](https://www.google.com/search?q=v1-specs.md) — kontrakt rozsahu (scope contract).

- `v1-architecture.md`[v1-architecture.md](https://www.google.com/search?q=v1-architecture.md) — schéma zapojení + schémata.

- `v1-tasks.md`[v1-tasks.md](https://www.google.com/search?q=v1-tasks.md) — seznam úkolů.

- `hebe-brainstorming-responses.md`[hebe-brainstorming-responses.md](https://www.google.com/search?q=hebe-brainstorming-responses.md) — odpovědi uživatele na otevřené otázky.


Toto je vrstva „zápisu z jednání“ a logu rozhodnutí nad výše uvedenými dokumenty.

## 1. Kde jsme začali

Repozitář byl inicializován souborem `req.md` deklarujícím záměr („Kotlin verze agenta *Claw, autonomní agent schopný přijímat instrukce různými kanály a provádět akce“) a čtyřmi paralelními pokusy o úvodní plán, které vytvořili čtyři různí AI agenti:

- `docs/claude/` — Claude Code: nejdelší, nejvíce názorový, s podrobnou analýzou architektur IronClaw / ZeroClaw a rešerší ekosystému JVM.

- `docs/gpt/` — GPT-5.x přes Copilot: dobře organizovaný, opatrnější, přispěl nejčistším rámcovým vymezením (5úrovňová paměť, koncept sidecar boundary).

- `docs/gemini/` — Gemini: stručné náčrty, přispěl dvěma originálními nápady (trusted scripting přes JSR-223, sebeevoluce agenta psaním `.kts`).

- `docs/minimax/` — MiniMax: komplexní seznam funkcí, doporučil Kotlin Native (proti proudu), zavedl pravidlo „vše jde přes nástroje“ a preemptivní prořezávání historie.


Každý agent vytvořil vlastní soubory `hebe-architecture.md`, `hebe-features.md` a `hebe-brainstorming.md` na základě kódových bází IronClaw/ZeroClaw a inspirací jako OpenClaw či Hermes Agent.

Prvním pokynem uživatele byla **syntéza** těchto návrhů do jednoho koherentního plánu, s využitím návrhu od Claude jako páteře, a vytvoření samostatného dokumentu (diff), který ukáže přínos každého agenta a v čem se lišili.

## 2. První syntéza (`docs/plan/`)

Byly vytvořeny čtyři dokumenty:

- `hebe-architecture.md`[hebe-architecture.md](https://www.google.com/search?q=hebe-architecture.md) — propojil GPT pojmenování 5úrovňové paměti a rámec „MCP-as-sidecar“ s páteří od Claude; převzal od MiniMax preemptivní prořezávání, časovou degradaci (time decay), `tool_search`, catchup execution a tunelování; převzal od Gemini výběr implementací JGit/Fabric8; zamítl MiniMax strukturu modulů KMP a doporučení pro Native; zamítl Gemini hrubé rozvržení do 6 modulů.

- `hebe-features.md`[hebe-features.md](https://www.google.com/search?q=hebe-features.md) — úplný seznam funkcí v1/v2/L sladěný s architekturou.

- `hebe-brainstorming.md`[hebe-brainstorming.md](https://www.google.com/search?q=hebe-brainstorming.md) — pracovní dokument s názory, pushbacky, architektonickými sázkami a otevřenými otázkami, záměrně zaujímající vyhraněné pozice pro vyvolání diskuse s uživatelem.

- `agent-diff.md`[agent-diff.md](https://www.google.com/search?q=agent-diff.md) — přehledová tabulka srovnávající všechny čtyři agenty v ~25 dimenzích, detailní rozdíly a analýza shody/rozporů.


Syntéza označila následující body jako **shodné u tří ze čtyř agentů** (rozhodnutí s nejnižším rizikem):

- JVM, nikoliv Native (nesouhlasil pouze MiniMax).

- Zapouzdření koog místo stavby na zelené louce.

- SQLite jako výchozí DB.

- Markdown workspace (paměť jako FS).

- Hybridní vyhledávání (FTS + vektor + RRF).

- MCP jako prvotřídní transport pro nástroje.

- Abstrakce kanálů (Channel-trait).

- Webová konzole jako první milník (M1).

- Rámec pro schvalování a autonomii.

- Secrets at rest + injekce na hranici procesu.

- Skilly jako Markdown balíčky.

- Zaměření primárně na jednoho uživatele / malý tým.


A následující jako **rozporuplné** (kde bylo nutné rozhodnout):

- In-process WASM (Claude: Extism; GPT: sidecar; Gemini: Chicory; MiniMax: Wasmer).

- Rozsah kanálů pro v1.

- SOP v první verzi (Claude: odložit; GPT: implicitní úlohy; Gemini: uvedeno; MiniMax: rutiny povinné).

- Multi-tenancy od prvního dne.


## 3. První revize `req.md` uživatelem

Po přečtení syntézy uživatel revidoval zadání dvěma klíčovými způsoby:

> „Udělejme to na bázi JVM. Měním své původní požadavky takto:
>
> - Bude to Kotlin + JVM
>
> - Nepotřebuji WASM; místo toho vytvořme plugovatelnou architekturu pro JVM moduly (nástroje). Takže všechny nástroje budou buď JVM, nebo založené na MCP.“
>

To vyřešilo tři otevřené otázky najednou:

- **Native vs JVM** → JVM.

- **WASM in-process** → zcela zrušeno.

- **Plugin sandbox** → JVM plugin JARy (izolované přes classloader) + MCP servery jako hranice pro cizí kód. GPT intuice „sidecar“ byla přerámována: **MCP je sandbox**.


## 4. Realignace architektury a funkcí

Dokumentace architektury a funkcí byla aktualizována:

- Odstraněny všechny funkce související s WASM.

- Nahrazení WASM hostitele modelem pro JVM pluginy (původně navržen vlastní URLClassLoader, s PF4J jako zálohou).

- Přidána sekce o důvěryhodnosti: **JVM pluginy jsou pro důvěryhodná rozšíření. Cokoliv, čemu nedůvěřujete natolik, abyste to pustili v JVM, by mělo být MCP serverem**.

- MCP definováno jako **hlavní cesta pro vícejazyčná rozšíření**.


## 5. Odpovědi z brainstormingu

Uživatel odpověděl prostřednictvím souboru `hebe-brainstorming-responses.md`[hebe-brainstorming-responses.md](https://www.google.com/search?q=hebe-brainstorming-responses.md) a uzavřel otevřené otázky:

|Téma|Rozhodnutí|Odůvodnění|
|---|---|---|
|Kanály pro v1|**Web Console + CLI + Telegram**|Shoda na zúžení rozsahu; nejrychlejší ladění + jeden chat kanál.|
|Rutiny vs SOP|**Rutiny ve v1, SOP ve v2**|Odklad komplexnějších SOP.|
|Web framework|HTMX / Svelte (žádný React)|Shoda na lehkosti.|
|Rizika kubectl|High + vždy schvalovat mutace|Shoda na bezpečnosti.|
|Databáze|SQLite pro v1|Shoda.|
|**LLM provider**|**OpenAI API + BYOK**|Uživatel má interní LLM Gateway; hebe dodá **jeden** OpenAI-compat klient.|
|**Plugin loader**|**PF4J**|Přeskočen vývoj vlastního řešení; využití standardu.|
|**Distribuce**|**OCI / Azure Container Registry (ACR)**|Pluginy jsou interní/firemní artefakty.|

Klíčové zjednodušení: Díky internímu LLM Gateway odpadla nutnost řešit v rámci hebe nativní adaptéry pro Anthropic, Bedrock či Gemini, a také komplexní fallback řetězce.

## 6. Architektura a funkce (druhý průchod)

Oba hlavní dokumenty (`hebe-architecture.md` a `hebe-features.md`) byly aktualizovány:

- TL;DR reflektuje všech osm klíčových rozhodnutí.

- Modulární rozvržení: přidán `plugin-api`; `providers/openai-compat/` je jediným LLM providerem.

- §8 model pluginů přepsán pro PF4J a OCI/ACR flow.

- Odstraněno vše související s multi-tenancy a `TenantScope`.


## 7. Trio dokumentů v1

Po stabilizaci architektury vznikly tři realizační dokumenty:

- `v1-specs.md`[v1-specs.md](https://www.google.com/search?q=v1-specs.md)**v1-specs.md** — kontrakt rozsahu. Obsahuje téze v1, akceptační kritéria a milníky.

- `v1-architecture.md`[v1-architecture.md](https://www.google.com/search?q=v1-architecture.md)**v1-architecture.md** — schéma zapojení. Obsahuje Gradle moduly, Kotlin ABI, SQLite DDL, config schéma a boot sekvenci.

- `v1-tasks.md`[v1-tasks.md](https://www.google.com/search?q=v1-tasks.md)**v1-tasks.md** — seznam úkolů. ~95 úkolů (M0–M10) seřazených podle závislostí a náročnosti.


## 8. Kde jsme přistáli (souhrn rozhodnutí)

1. **Pouze Kotlin / JVM.**

2. **Žádné WASM.** Pluginy přes PF4J, cizí kód přes MCP.

3. **Distribuce pluginů přes OCI (Azure Container Registry).**

4. **Výchozí signature_mode = optional**`signature_mode = optional` pro pluginy ve v1.

5. **Pouze pro jednoho uživatele.** Žádný multi-tenant.

6. **Kanály v1: Web Console, CLI, Telegram.**

7. **LLM: Jeden OpenAI-kompatibilní klient (BYOK).**

8. **SOP až ve v2.** V1 má pouze rutiny.

9. **SQLite jako výchozí.**

10. **Potvrzení (receipts): Ed25519 podpis, NDJSON na disku.**

11. **Webová konzole je primárně ladicí nástroj.**


## 9. Co zůstává otevřené

Ačkoliv je plán dostatečně konkrétní pro začátek kódování, během implementace se pravděpodobně vynoří následující témata:

- **Co když koog v1.0 nevyjde včas?** Fasáda umožňuje výměnu, ale museli bychom se rozhodnout pro alternativu.

- **Obsah startovacích skillů.** Je třeba napsat reálný obsah pro `daily-briefing` atd.

- **První reálný plugin.** Volba prvního pluginu potvrdí, zda v1 sada oprávnění stačí.

- **Detekt pravidlo pro dispečink.** Pravděpodobně bude přesunuto z v2 do v1 pro zajištění čistoty kódu od začátku.


## Doporučené pořadí čtení pro nové členy týmu

1. `req.md` — co uživatel chce (v jedné straně).

2. `Hebe Brainstorming.md` (tento soubor) — jak jsme se k plánu dopracovali.

3. `v1-specs.md` — co je a co není ve v1.

4. `v1-architecture.md` — konkrétní kontrakty a schémata.

5. `v1-tasks.md` — vyberte úkol a začněte kódovat.


Původní návrhy jednotlivých agentů v `docs/{claude,gpt,gemini,minimax}/` jsou ponechány jako historický záznam pro případ, že by někoho zajímalo, proč jsme zvolili konkrétní řešení oproti alternativám.