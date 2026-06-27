**Pythia Brainstorming — záznam o procesu**  
**Účel.** Tento dokument zaznamenává, _jak_ bylo dosaženo návrhu Pythia v1, nikoliv _co_ bylo výsledkem. Závěry jsou uvedeny v dokumentech [`Pythia-v1-Design.md`](https://www.google.com/search?q=./Pythia-v1-Design.md), [`v1.5-backlog.md`](https://www.google.com/search?q=./v1.5-backlog.md) a [`open-questions.md`](https://www.google.com/search?q=./open-questions.md). Tento dokument zachycuje diskusi: otázky, které byly na stole, zvažované alternativy, poznatky, které rozhodly, a kdo čím přispěl.  
Dokument je užitečný při zpětném přezkoumávání rozhodnutí a otázce „proč jsme to vybrali?“ — designový dokument obsahuje _co_, tento dokument obsahuje _proč_.  
**Formát.** Každá sekce představuje jednu epizodu rozhodování v pořadí, v jakém jsme na nich pracovali.  
**Datum.** 4. 5. 2026. Bora a Claude.

**Poznámka k pojmenování (doplněno 2026-05-04, post-brainstorm).** Několik person agentů bylo přejmenováno po skončení tohoto brainstormingu. Tento dokument zachovává původní terminologii brainstormingu pro historickou přesnost. Mapa přejmenování: **Wrangler → Iris**, **Mover → Charon**, **DataScientist → Metis**, **Resolver → Themis** (služba `resolver-mcp` → `themis-mcp`), **Secretary → Hebe** (Hebe žije v projektu Kyklop, ne v Pythii — nahrazuje Talose v této roli). Také reframing "Iris je FE, Golem je parametrizovaný šablonový backendový agent" se odehrál po tomto brainstormingu — viz konverzaci, která následovala.  
**1. Jak tento brainstorming vznikl**  
Bora zahájil diskusi zadáním v `pythia-brief.md`: autonomní analytický agent, který provádí (polo)autonomní analýzu dat, analýzu kořenových příčin (RCA), prognózování a simulace. Zadání nastínilo čtyři případy užití (komplexní Q&A, RCA, prognózy, simulace), čtyři kandidáty na sub-agenty (Mover, DataScientist, Wrangler, Secretary) a preferenci technologického stacku (JVM/Kotlin pro hlavního agenta, Python pro workery).  
První čtení zadání ze strany Claude (předtím, než byly k dispozici dokumenty k platformě) vyústilo v pracovní rámec s pěti napětími: topologie agentů, reprezentace plánu, ukotvení LLM v modelu, výstup jako artefakt a propojení s člověkem (HITL). Bora následně doplnil dokumentaci k platformě — architekturu platformy V1, specifikaci Analytického agenta na V1 a dokumentaci k frontendovému Golemovi.  
Prostudování těchto dokumentů výrazně korigovalo rámec diskuse:

- Pythia **není** konverzační front-end. Analytický agent V1 (nástupce ER-AS / AI-AG) již vlastní chat, pojmenované dotazy, vrstvitelnou kompozici (variations), EntityContext, klikání pro výběr a úpravu dotazů. Práce Pythie _začíná tam, kde Analytický agent končí_ — u vícekrokových plánů, RCA, prognózování a simulací.

- Platforma již poskytuje Pythii mimořádně čisté prováděcí rozhraní: `query-mcp.compile`, `query-mcp.query` (s kompozicí TransDSL), `metadata-mcp`, `fuzzy-mcp`, `data-formatter`. Pythia nikdy nepíše čisté SQL; komponuje dotazy vrstvením na pojmenované dotazy.

- Platforma se již _vyvíjí směrem k potřebám Pythie_ — Polars Worker se session DataFrame, sticky routing, schéma `cnc.role`, zobrazování varování v pipeline.


Bora potvrdil toto přerámování a brainstorming pokračoval proti tomuto korigovanému obrazu. Pět napětí se změnilo na šest (kontrakt Pythia/Wrangler, reprezentace plánu, vyšetřovací artefakt, multi-agent topologie, technologický stack, HITL šev) a jako první jsme vybrali napětí (1).  
**2. Napětí 1 — Kontrakt vyšetřování (Investigation contract)**  
Jednalo se o nejdelší samostatnou epizodu, která přinesla nejvíce rozhodnutí.  
**Pracovní návrh (Strawman)**  
Claude navrhl postupovat pozpátku od tří případů užití (komplexní Q&A, RCA, prognóza/simulace) — jak vypadá požadavek pro každý z nich, jak vypadá streamovaný artefakt a jak vypadá stav „hotovo“? To vyústilo v pracovní definici požadavku `Investigation` a odpovědi `InvestigationArtifact`, spolu se streamovacím protokolem zrcadlícím stávající slovník Analytického agenta (`step` / `tool_call` / `envelope`).  
**Šest designových otázek k pracovnímu návrhu**  
Claude identifikoval šest voleb, které pracovní návrh odhalil:

1. Styl jako vstup nebo výstup? (caller hints vs. Pythia odvozuje)

2. Schvalování plánu jako HITL brána (výchozí hodnoty, kdy je VYŽADOVÁNO)

3. Klasifikátor podezření (zachycení chybového stavu typu „LLM vidělo prázdnou tabulku, halucinovalo a prohlásilo úkol za splněný“)

4. Vyšetřování jako citovatelné / přehratelné / pokračovatelné objekty

5. Co Pythia explicitně NEDĚLÁ

6. Pracovní paměť vs. session DataFrames


**Borovy postoje a klíčový poznatek**  
Bora přímo potvrdil body (1), (2) a (4). Hlavní přínos však spočíval v bodech (3) a (5):  
K bodu (3) — od podezření k hypotézám. Bora rozšířil myšlenku klasifikátoru podezření: _„podle mého názoru by plán měl definovat hypotézy; a pokud se prokáže, že hypotéza není pravdivá, měli bychom plán aktualizovat — přidat další kroky, některé odebrat atd.“_  
Jednalo se o nejvýznamnější přerámování celého brainstormingu. Posunulo to Pythii od „provádění plánu“ k „vědecké metodě“ — formulování hypotéz, navrhování experimentů k jejich testování, vyhodnocování výsledků a revize. Procedurální Q&A se stává triviálním případem (jedna banální hypotéza); RCA se nativně řídí hypotézami; prognózování a simulace jsou o hypotézách shody modelu, respektive citlivosti scénářů.  
Důsledky pro návrh: struktura plánu nyní nese vrstvu hypotéz s vlastním stavovým automatem (NAVRŽENO → PODLOŽENO / VYVRÁCENO / NEPRŮKAZNÉ / OPUŠTĚNO); klasifikátor podezření získává druhou roli (vyhodnocování hypotéz) a strukturovanou odpověď pro revizi plánu (PROŘEZAT / PIVOTOVAT / DEKOMPONOVAT / ZASTAVIT); revize plánu se stává prvořadou operací, nikoliv dodatečným nápadem.  
K bodu (5) — Pythia je z hlediska dat bezstavová. Bora odmítl rámec pracovní paměti navržený Claudem: _„nelíbí se mi myšlenka, že by Pythia byla úložištěm; mezivýsledky jsou buď ve stávajícím Workeru (Polars nebo DuckDB), nebo materializované a uložené v Seaweed nebo Redis nebo jinde, v nativním formátu Arrow.“_  
Tím se vykrystalizoval model handle tabulek: Pythia drží typované ukazatele (`Handle`); Mover materializuje bajty mezi vrstvami úložiště; Arrow IPC je univerzální formát pro výměnu. Pythia se nikdy nerozroste v datový sklad. Mover se stává explicitním nástrojem datové vrstvy, nikoliv abstrakcí úložiště uvnitř Pythie.  
Tyto dva Borovy příspěvky spolu s potvrzením bodů (1), (2) a (4) definovaly páteř kontraktu: strukturovaný požadavek, strukturovaný streamovatelný artefakt, plán řízený hypotézami, datový model založený na handlech, citovatelná identita a princip „Pythia nevykresluje (render)“.  
**Streamovací protokol**  
Streamovací protokol vyplynul z kontraktu. Wrangler se přihlásí k odběru událostí pro konkrétní vyšetřování (`plan_drafted`, `step_started`, `step_completed`, `hypothesis_supported` atd.) a smysluplně vykresluje postup. Protokol zrcadlí stávající slovník událostí Analytického agenta, takže Wrangler nepotřebuje druhý handler.  
**3. Zpracované příklady — Nescafe-Maggi a RCA soukromého kanálu**  
Claude navrhl dva kompletní příklady pro otestování kontraktu před úpravou schématu. Bora souhlasil.  
**Nescafe-Maggi (procedurální)**  
Komplexní Q&A: _„Zákazníci, kteří v posledním roce vrátili zásoby Nescafe a jejichž tržby z Maggi za poslední 2 kvartály klesly.“_ Plánem byl DAG se 4 uzly (dotaz na vratky → dotaz na tržby Maggi → pivot/filtr DataFrame → vykreslení tabulky).  
Vynořily se následující problematické body (1–6):

1. **Discovery jako úvod, nikoliv krok plánu.** Vyhledávání metadat a fuzzy lookupy probíhají _před_ sestavením plánu, v rámci přípravy draftu. Jsou to skutečná volání nástrojů LLM, ale nejsou to uzly plánu — měly by žít v bloku `discovery` v artefaktu, viditelném pro audit.

2. **Vázání parametrů napříč kroky.** `customer_ids = $H1.customer_id` je základní prvek vazby; vyžaduje varianty pro seznam/skalár; velké seznamy (IN-lists) vyžadují rozhodnutí o materializaci vs. ponechání v paměti.

3. **Kroky DataFrame vyžadují Polars Worker.** Fáze 2.2 platformy. Pythia v0 by měla komponovat pouze SQL a nechat uzel DataFrameNode aktivovat až v momentě, kdy se zaregistruje příslušná schopnost.

4. **Přehrání (Replay) vs. reprodukce.** Relativní časové odkazy („posledních 12 měsíců“) se chovají odlišně pro opětovné spuštění nad čerstvými daty vs. auditní reprodukci.

5. **Triviální hypotézy jsou jednotné, ale neviditelné pro uživatele.** Je zapotřebí pole priority zobrazení.

6. **Důvěra (Confidence) u procedurálních plánů nemá smysl.** Pole by mělo být volitelné (nullable).


**RCA soukromého kanálu**  
Složitější příklad: _„Proč jsou naše tržby meziročně nižší pro kanál Private?“_ Plánem byl strom hypotéz — sedm sourozeneckých hypotéz první vlny (méně zákazníků / nižší hodnota objednávky / pokles cen / změna mixu / sezónnost / odchod velkých zákazníků / kvalita dat) plus deklarované nedořešené body z doby plánování (počet zaměstnanců Salesforce a výdaje na marketing, obojí mimo datový rozsah).  
Vynořily se další problematické body (7–14):

1. **Paralelní provádění je pro RCA povinné** — to hovoří pro napětí (5).

2. **Vyhodnocování hypotéz je náročné** — argument pro dvouúrovňovou strategii LLM (silný plánovač + levný vyhodnocovatel).

3. **Vynucování rozpočtu nákladů je nezbytné** — vyžaduje tracker rozpočtu, který zasáhne před paralelními dávkami.

4. **Prioritizace hypotéz** pro prohloubení — heuristika `confidence × explanatory_power × cost_remaining` s LLM jako rozhodčím při rovnosti.

5. **Atribuce rozptylu vyžaduje DataScientista** — nutnost registru schopností.

6. **Nedořešené body deklarované už při plánování, nejen v závěru** — upřímnost ohledně rozsahu hned na začátku.

7. **Formalizace podmínek zastavení** — kdy Pythia rozhodne, že „máme dost“?

8. **Renderer narativu je samostatná disciplína** — artefakt s několika odstavci a grafy není chatovací bublina; argument pro Report Renderer jako samostatnou součást.


Příklad RCA také odhalil tři architektonické závazky, které dosud nebyly explicitní: scheduler pro paralelní provádění, registr schopností a tracker rozpočtu.  
**4. Discovery je proces, nikoliv krok**  
První procedurální bod (Discovery) vyvolal podstatnou změnu v chápání. Borova přesná formulace: _„Není to 'krok', je to ve skutečnosti 'proces' — kandidátů může být více, stejný termín může odkazovat na různé koncepty, překlepy v entitách atd. Celé 'vyjasnění záměru' s 'vyjasněním entit' je proces. Agent? Sada nástrojů? Rozhodně to není jednorázová záležitost.“_  
**Co se skutečně skrývá uvnitř Discovery**  
Sedm dílčích úkolů ve smyčce: extrakce kandidátů, rozlišení entit, disambiguace významu termínů, klasifikace záměru, párování schopností, detekce mezer, rozhodnutí (vyjasnit / spekulovat / odmítnout). Každý průchod může změnit vstupy pro průchod příští — rozlišení entit napájí klasifikaci záměru, klasifikace záměru může změnit priority rozlišení entit atd. Jde o konvergenci, nikoliv o jeden pokus.  
**Pozice: vyčlenit jako sdíleného sub-agenta**  
Tři důvody, proč jej vyčlenit jako vlastní službu MCP, místo vnoření do Pythie:

- Analytický agent již většinu této práce dělá v Pythonu. Buď to implementovat znovu pro Pythii v Kotlinu (duplicita, dvojí údržba), nebo to jednou vyčlenit.

- Secretary to potřebuje také — i bez chatovací session Wrangleru musí Secretary rozlišit „tržby Maggi za poslední kvartál“, než Pythia začne plánovat.

- Vzorem platformy je „vše přes MCP“; `resolver-mcp` zapadá vedle `query-mcp` a `metadata-mcp`.


Pojmenováno jako Resolver. Bora potvrdil.  
**Zvrat s multi-turn HITL**  
Bora dodal: _„rozlišení je proces s člověkem v cyklu (volitelně), takže bude docházet k interakcím tam a zpět.“_  
To byl druhý největší architektonický poznatek. Znamenalo to, že Resolver vrací nejen `ResolvedIntent`, ale také `ClarificationRequest` (pokud existují blokátory a disambiguace je INTERAKTIVNÍ), a volající (Pythia nebo Wrangler) drží pokračování rozlišení napříč několika koly. Resolver zůstává bezstavový; Pythia předává `prior_context` mezi voláními.  
Větší důsledek: toto se zobecnilo. Pythia je vyšetřovatel s možností pozastavení — do architektury byly zabudovány čtyři stavy `AWAITING_*` (INPUT_PRO_ROZLIŠENÍ, SCHVÁLENÍ_PLÁNU, VSTUP_UŽIVATELE, SCHVÁLENÍ_REVIZE_PLÁNU). Stav žije v Postgresu; běžící kroky se při zaparkování dokončí; obnovení je prvořadou funkcí.  
**Zafixováno**

- Resolver jako špičková služba MCP platformy

- Pro v1 navázáno na E-R model; ve v1.5 s podporou cnc

- Bezstavová služba; Pythia drží pokračování rozlišení

- Režimy INTERAKTIVNÍ / SPEKULATIVNÍ / STRIKTNÍ (řízené volajícím)

- Multi-turn HITL přes dávkový `ClarificationRequest`

- Smyčka konvergence, max 3 průchody

- Dva profily: CHAT_QUICK (dnešní Analytický agent), INVESTIGATION_DEEP (Pythia)


**5. Registr schopností —** `capabilities-mcp`  
Zjednodušující zjištění: schopnost (capability) je nástroj MCP plus metadata pro plánovač. Vzorem platformy je „vše přes MCP“; registr schopností = agregovaný katalog nástrojů MCP s informacemi o nákladech, prerekvizitách, vyhledávacími tagy a příklady. Vyvolání probíhá prostým voláním nástroje MCP — není potřeba nová dispečerská vrstva.  
**Rozdělení s metadata-mcp**  
Dva odlišné registry, dvě odlišné otázky:

- `metadata-mcp` odpovídá na „co je ve světě?“ — entity, atributy, vztahy, **pojmenované dotazy** (vázané na model) a časem role `cnc`. Již existuje.

- `capabilities-mcp` (nový) odpovídá na „co lze udělat?“ — operační schopnosti nevázané na model: operace s DataFrame, ML/statistické operace, přesun dat, vykreslování.


Pojmenovaný dotaz je vázán na model (definován nad entitami `customer` a `invoice`) → metadata-mcp. Schopnost jako `model.forecast.arima` funguje nad libovolnou časovou řadou → capabilities-mcp. Resolver se dotazuje obou během párování schopností a v `ResolvedIntent` vrací sjednocenou relevantní sadu.  
**Rozlišení mezi schopností a politikou (policy)**  
Claude položil otázku: kam patří pravidlo „vždy materializovat výstup DataScientista do Seaweed“? Do schopností nebo do politiky?  
Borovo vymezení to vyjasnilo: _„přesunout něco je schopnost; nikdo však není povinen schopnost použít; skutečnost, že by se něco mělo přesunout, je politika.“_  
Tedy: schopnosti jsou primitiva (možnost existuje); politiky jsou výchozí nastavení (kdy možnost použít). Mover vystavuje `move.materialize.seaweed`; executor Pythie (nebo prompt LLM plánovače) nese politiku „vždy perzistovat výstupy prognóz po jejich vygenerování“. Účinky na schopnosti jsou popisné (co _se stane_); politiky jsou předpisové (co _by se mělo stát_).  
**Zafixováno**

- Nový `capabilities-mcp` jako špičková služba platformy

- Schopnost = MCP nástroj + metadata pro plánovač; vyvolání přes MCP

- Resolver se dotazuje jak metadata-mcp, tak capabilities-mcp; vrací sjednocenou sadu

- Deklarováno ve verzovaných manifestech + registrace za běhu; podpora více verzí

- Primitiva jsou schopnosti, výchozí nastavení jsou politiky (Borův test)


**6. Scheduler pro paralelní provádění — a jak rozdělil napětí 5**  
Poznatek, který z této diskuse vyplynul, byl tím větším přínosem: napětí 5 se dělí do dvou vrstev.  
**Rozdělení**  
To, co Pythia potřebuje, je typovaný DAG executor s podporou souběžnosti, opakování (retries), pozastavení/obnovení a povědomím o rozpočtu. To _není_ primárně to, co poskytují LangChain4j / Koog / Embabel / Spring AI — to jsou nástroje pro orchestraci LLM. Jsou užitečné pro části ve tvaru LLM (prompt plánovače, vyhodnocovatel, syntetizér), ale nejsou správným primitivem pro provádění 12uzlového DAGu hypotéz se 7 paralelními sourozenci.  
Tedy:

- **Vrstva DAG executoru** — vlastní, postavená na Kotlin coroutines + strukturované souběžnosti. Malá, dobře přizpůsobená JVM. (Rozhodnuto v tomto kole.)

- **Vrstva orchestrace LLM** — Koog / Embabel / LangChain4j / Spring AI si konkurují. (Odloženo na cílené vyhodnocení.)


Bora to potvrdil a přidal užitečné zasazení do budoucna: _„Temporal poběží později. Pokud budeme chtít tuto úroveň posunout výše (rozsáhlé simulace místo jedné), bude to nová infraktruktura.“_ Tím byl Temporal definován jako spouštěč pro upgrade na v2, nikoliv pro v1.  
**Borou potvrzené vlastnosti scheduleru**

- Vlastní Kotlin coroutines + checkpointer v Postgresu pro v1

- Limit paralelismu na vyšetřování (výchozí 5), globální limit Pythie, limit **na providera** (Bora explicitně souhlasil — DataScientist může říct „max 3 souběžné ARIMA fity na pod“; deklarováno v manifestu schopnosti)

- Sticky-affinity: zděděná od rodičů `WorkerSessionDF` (lokalita dat), zrušená pro rodiče `SeaweedArrowBlob` (volnost v balancování)

- Odstupňované zpracování chyb: přechodné opakování / trvalé NEPRŮKAZNÉ / systémové ZASTAVENÍ

- Sémantika vyprázdnění (drain) pro stavy pozastavení (běžící se dokončí; žádné nové se nespouštějí)

- Projekce a rezervace rozpočtu před paralelními dávkami

- Spouštění s ohledem na prioritu s povýšením, jakmile se uvolní sloty


**Pozice NATS**  
Otázka Clauda: NATS jako event-sourced autorita pro stav, nebo jen transport?  
Bora: _„NATS NENÍ zdrojem pravdy pro stav.“_  
Tedy: Postgres je autoritativní pro stav; NATS je transport pro streamování událostí (odběry událostí pro klienty podle vyšetřování) a oznamovací sběrnice mezi službami. Log událostí v Postgresu uchovává trvalý záznam po vypršení retence v JetStreamu.  
**Borou přidaní kandidáti na frameworky**  
Během této epizody (konkrétně když byla otázka frameworku pro orchestraci LLM odložena) Bora dodal: _„Koog a Embabel jako dva další frameworky, na které je třeba se podívat.“_  
Ty byly uloženy do paměti projektu a přidány na seznam pro vyhodnocování frameworků v otevřených otázkách.  
**7. Dvouúrovňová strategie LLM — upřesněno na modalita × úroveň**  
Původní rámec Clauda: SILNÝ (plánovač, syntetizér) + LEVNÝ (vyhodnocovatel) + EMBEDDING. Borova připomínka:  
_„embedding je jiná modalita, nikoliv úroveň, a můžeme mít SILNÉ embeddingy a LEVNÉ embeddingy. Ale obecně souhlasím, jde jen o slovíčka.“_  
Čistší rozdělení (Borova korekce): modalita × úroveň, dvě kolmé osy.

- **Modalita**: CHAT, EMBEDDING (rozšiřitelné)

- **Úroveň**: SILNÝ, LEVNÝ


Volání je tedy `(modalita, úroveň)`. CHAT/SILNÝ = Sonnet/Opus pro plánovač. CHAT/LEVNÝ = Haiku pro vyhodnocovatel. EMBEDDING/SILNÝ = dnešní Azure OpenAI. EMBEDDING/LEVNÝ = self-hosted (BGE-M3 nebo podobný), až bude k dispozici.  
Navíc 0. úroveň založená na pravidlech (predikáty, kontroly prahových hodnot, kontroly tvaru schématu) — běží jako první, žádné volání LLM, pokud může rozhodnout pravidlo.  
**Borova preference „ze staré školy“**  
K 0. úrovni založené na pravidlech:  
_„Ano, prosazuj pravidla agresivně; preferuji pravidla (stará škola).“_  
Uloženo do paměti jako Borova preference. Důsledek pro návrh: pravidla jsou výchozí disciplínou pro klasifikátor podezření, vyhodnocovatel hypotéz, klasifikátor záměru i detekci mezer. LLM je záložní variantou, když pravidlo nemůže rozhodnout.  
**Další zafixovaná rozhodnutí v tomto kole**

- Úroveň jako záměr (intent): Pythia deklaruje (modalita, úroveň, task_kind); LLM Gateway vybere model

- Výchozí úrovně pro jednotlivá volání v konfiguraci Pythie (YAML); možnost přebití v rámci vyšetřování

- Eskalace úrovně uprostřed běhu odložena na v1.5+

- Strukturovaný výstup / tool-use pro všechna volání parsovaná Pythií

- Caching na straně LLM Gateway (přes Redis); nikoliv v Pythii

- Streaming pouze pro syntetizér + plánovač (levné klasifikátory jej nepotřebují)

- Embeddingy již v produkci (Azure OpenAI); self-hosted LEVNÁ varianta je ve v1.5


**8. Tracker rozpočtu**  
Většina trackeru rozpočtu byla specifikována již v době, kdy jsme se k němu explicitně dostali. Toto byl rychlý průchod.  
**Vícerozměrný rozpočet**  
Volající může nastavit libovolnou kombinaci `max_llm_cost_usd`, `max_llm_tokens`, `latency_budget_ms`, `max_step_count` plus kategorický `depth_budget` (PLYTKÝ / NORMÁLNÍ / HLUBOKÝ), který se mapuje na výchozí hodnoty. Nejpřísnější omezení vyhrává.  
**Prahový žebříček**

- 75 % → vygenerovat událost `budget_threshold` (varování; žádná akce)

- 90 % → pokud je `on_budget_threshold: ASK`, pozastavit; jinak silnější varování

- 100 % → HALT_GRACEFULLY: přeskočit zbývající dávky, přejít k syntetizéru s aktuálními důkazy, závěr označen jako `budget_truncated`

- 110 % → nouzové tvrdé zastavení; jedno volání syntetizéru je stále povoleno pro korektní ukončení


**Borovy postoje**

- Výchozí politika je HALT_GRACEFULLY při 100 %, nikoliv ASK (vyhnutí se modálním přerušením uprostřed vyšetřování). ASK je volitelné.

- Odhady tokenů na typ úkolu jako konfigurované konstanty pro v1; naučené klouzavé průměry odloženy na v1.5.


Tracker se připojuje přímo k scheduleru (projekce a rezervace před paralelními dávkami) a k LLM Gateway (API pro ceny úrovní pro projekci nákladů; příznak `cached: bool` v odpovědích pro přesnou atribuci).  
**9. Podmínky zastavení**  
Klíčový poznatek: podmínky zastavení jsou specifické pro typ vyšetřování se společným základem, nikoliv jedno univerzální kritérium.  
**Společný základ (všechny typy vyšetřování)**

- STOP_USER (explicitní zastavení)

- STOP_BUDGET (delegováno na tracker rozpočtu)

- STOP_HARD_CAP (max_step_count, max_revisions, max_depth)

- STOP_PLAN_EXHAUSTED (žádné proveditelné kroky, žádné čekající revize)

- STOP_GOAL_REACHED (splněno kritérium dokončení specifické pro daný typ)


**Kritéria dokončení podle typu**

- PROCEDURÁLNÍ: dokončen krok finální odpovědi

- RCA: vysvětlený rozptyl ≥ 0,75 A ZÁROVEŇ ≥ 1 PODLOŽENÁ hypotéza s důvěrou ≥ 0,6 A ZÁROVEŇ vyhodnoceny všechny hypotézy nejvyšší úrovně

- PROGNÓZA: shoda modelu A ZÁROVEŇ diagnostika v pořádku A ZÁROVEŇ interval spolehlivosti v rámci cíle

- SIMULACE: vypočteny všechny scénáře (nebo stabilní konvergence)


Jedná se o politiky, nikoliv kód — deklarativní výrazy, které lze operativně ladit.  
**Čtyři brzdy dynamického růstu plánu RCA**  
Vrstvy:

- Hloubka dekompozice ≤ 3 úrovně (tvrdý limit)

- Počet testů na hypotézu ≤ 3 (tvrdý limit)

- Počet revizí plánu ≤ 2 pro NORMÁLNÍ, ≤ 5 pro HLUBOKÝ (tvrdý limit)

- Heuristika mezní hodnoty: každá nová hypotéza nese vlastní hodnocení LLM ohledně očekávané vysvětlovací síly; pod 5 % → nepokračovat (měkká brzda)


**Borův krok k vymezení rozsahu**  
Když Claude poznamenal, že heuristika mezní hodnoty nese riziko kalibrace (LLM neumí dobře předvídat vlastní užitečnost), Borova reakce byla jasným rozhodnutím o rozsahu:  
_„Prozatím pro v1 souhlasím. Dynamické plánování a jeho části budou samostatným projektem; nyní pro to pouze nastavujeme rámec (envelope).“_  
Toto se v brainstormingu opakovalo: Bora důsledně stavěl Pythii v1 jako rámec (envelope) — strukturovanou podobu, kontrakty, životní cyklus, komponenty, integrace — přičemž ty skutečně AI-formované části (sofistikované dynamické plánování, kalibrované vyhodnocovatele, naučené odhady tokenů) odložil jako projekty pro v1.5+. Rámec je to, co se těžko refaktoruje; AI sofistikovanost se může vyvíjet iterativně.  
Poctivá dekompozice rozptylu (`model.decompose.variance` u DataScientista) byla také explicitně označena jako práce pro v1.5; Pythia v1 používá heuristiku s limitem.  
**Podmínění syntetizéru důvodem zastavení**  
Prompt syntetizéru je podmíněn hodnotou `stop_reason`. STOP_GOAL_REACHED → sebevědomé rámování. STOP_BUDGET / STOP_HARD_CAP → označeno příznakem `budget_truncated` a nižší důvěrou. Sekce `confidence.caveats` v artefaktu nese důvod zastavení explicitně.  
**10. Nedořešené body (Loose ends)**  
Zjednodušující poznatek: nedořešené body nejsou samostatným konceptem — jsou odvozeným pohledem na stav hypotéz.  
Do enumu stavu hypotéz se přidá `OUT_OF_SCOPE`: `PROPOSED | UNDER_TEST | SUPPORTED | REFUTED | INCONCLUSIVE | ABANDONED | OUT_OF_SCOPE`.  
Poté: každý nedořešený bod je hypotéza, která nedosáhla definitivního verdiktu, plus důvod. Artefakt v době zastavení nese pohled `loose_ends` na stav hypotéz, čerpaný z DOBY_PLÁNOVÁNÍ (deklarováno plánovačem předem jako mimo rozsah) nebo DOBY_PROVÁDĚNÍ (osiřelé v důsledku zastavení, opuštěné po NEPRŮKAZNÉM výsledku, opuštěné po pivotu).  
**Co to umožňuje**

- **Upřímnost předem.** Když je `plan_approval: VYŽADOVÁNO`, uživatel vidí hypotézy v rozsahu i mimo rozsah. Může reagovat: „Počet zaměstnanců Salesforce JE k dispozici — viz /sharepoint/hr_data“ → plánovač znovu vytvoří draft.

- **Syntetizér odkazuje na nedořešené body přímo v narativu**, nikoliv v patičce. „Nevyšetřovali jsme počet zaměstnanců Salesforce, protože tato data jsou mimo rozsah. V rámci dat, která máme k dispozici, se zdá být hlavním hybatelem…“

- **suggested_followup činí nedořešené body akčními.** Každý nedořešený bod nese volitelný řetězec, který se stane základem pro následné vyšetřování, pokud jej uživatel bude chtít sledovat.


**Borovo rozhodnutí: REJECT_WITH_COMMENT ve v1, plné úpravy ve v1.5**  
Pro schvalování plánu:  
_„Ano, pro v1 to dává smysl (ODMÍTNOUT S KOMENTÁŘEM).“_  
V1 tedy podporuje `APPROVE | REJECT_WITH_COMMENT` (nové naplánování s komentářem jako dodatečným kontextem). Skutečná interaktivní úprava plánu — povyšování/snižování/přidávání/přeřazování hypotéz — je prací pro v1.5. Datový model to podporuje; odloženo je pouze UX.  
**Navrhované sledování (Suggested-followup) ve v1**  
_„Ano, ve v1.“_  
Nenáročné (jedno volitelné textové pole); v artefaktu hodnotné, i když jej Wrangler zpočátku vykreslí jen jako prostý text. Skutečné UX pro následné vyšetřování jedním kliknutím je ve v1.5.  
**11. Prioritizace hypotéz**  
Tři momenty, kdy dochází k prioritizaci:

- **Počáteční plánování** — co se testuje jako první? Určuje, které hypotézy naplní první paralelní dávky.

- **Prohlubování po první vlně** — když se vrátí několik hypotéz jako PODLOŽENÉ, která se dekomponuje jako první?

- **Revize plánu** — kam se zařadí nové hypotézy ve frontě čekajících?


**Jeden bodovací rámec, pět dimenzí**

```
priority_score(hyp) = confidence                    × estimated_explanatory_power                    × (1 / cost_estimate_for_next_step)                    × diagnostic_power                    × novelty_bonus
```


Každá hodnota v rozsahu [0, 1]. Vzorec je pevně daný; váhy dimenzí jsou vystaveny v YAML pro ladění.  
**Rozhodčí LLM, úzce vymezený**  
Pokud heuristika vygeneruje top-2 s rozdílem do ~10 %, spustí se volání úrovně LEVNÝ, které rozhodne. Většinou rozhoduje heuristika; LLM se vyvolává pouze při skutečné rovnosti. Úspora energie záměrem.  
**Borova jasná potvrzení**  
K (i) diagnostické síle jako samostatné dimenzi: _„Souhlas.“_  
K (ii) původu `diagnostic_power` (dodáno plánovačem při návrhu vs. strukturálně odvozeno): _„Souhlas.“_  
Tempo brainstormingu se v tomto bodě zrychlilo — Bora dával rychlé, rozhodné odpovědi ano/ne s občasným upřesněním.  
**Vrstvení s souvisejícími zájmy**  
Tři oblasti se doplňují bez překryvu:

- **Brzda mezní hodnoty** (#13) rozhoduje, _zda vůbec spustit_ — brána

- **Prioritizace** (#10) rozhoduje o _pořadí_ — fronta

- **Scheduler** (#7) rozhoduje o _alokaci souběžných slotů_ — pool


Každá žije na svém místě.  
**12. Rozdělení RenderNode + Report Renderer**  
Klíčový poznatek: vykreslování (rendering) je _vždy_ až po artefaktu a rendererů je _mnoho_, nikoliv jeden. Wrangler vykresluje chatovací bublinu; Report Renderer produkuje DOCX/PDF/HTML; Secretary produkuje souhrny zpráv; spotřebitelé API dostávají JSON; budoucí BI nástroje vkládají artefakty.  
**Tři vrstvy, čistě oddělené**

```
1. Syntetizér (v Pythii, CHAT/SILNÝ)   produkuje strukturovaný RenderableArtifact — sekvenci typovaných Bloků2. Formátovací knihovny (platforma)   - data-formatter: Handle → markdown/csv/tsv/json tabulka   - chart-formatter (NOVÝ): Handle → Vega-Lite specifikace3. Renderery (následní spotřebitelé)   - Wrangler: chatovací bublina (streaming v reálném čase)   - Report Renderer: DOCX/PDF/HTML (na vyžádání)   - E-mail/souhrn, API, budoucí BI
```


Artefakt je švem. Každý renderer konzumuje stejný strukturovaný artefakt; rozdíly jsou v cílovém médiu, nikoliv v obsahu.  
**Vega-Lite jako kanonická specifikace grafu**  
Claude navrhl Vega-Lite namísto ECharts. Důvody: deklarativní JSON, podpora více cílů, vyspělost; adaptér Vega-Lite → ECharts na straně front-endu je malý. Stávající FE používá ECharts; kompromisem je práce na adaptéru na straně FE vs. konzistence napříč renderery.  
Bora: _„Vega Lite je ok.“_  
Toto zůstává jako Q8 v `open-questions.md` k potvrzení proti skutečným preferencím FE týmu, až Wrangler vykreslí svůj první graf z Pythie.  
**NARRATIVE_FRAGMENT ponechán**  
Pro lokální generování textu ke konkrétní hypotéze („Počet zákazníků meziročně vzrostl o +2 %, čímž vyvrátil hypotézu A“), odděleně od plné kompozice artefaktu syntetizérem. Volání LLM úrovně LEVNÝ pro každý fragment.  
Bora: _„ano, ponechat NARRATIVE FRAGMENT.“_  
**Streamování blok po bloku**  
Události `synthesizer_block_started` / `synthesizer_block_streaming` (na úrovni tokenů uvnitř bloku) / `synthesizer_block_completed` s explicitním `block_index`. Umožňuje doručení mimo pořadí (ChartBlock může trvat 100–500 ms; textové bloky se mohou streamovat, zatímco se graf renderuje asynchronně).  
**13. Procedurální dočištění (vyřízeny body č. 2-6)**  
Na závěr byly zbývající problematické body (č. 2–6 z procedurálního příkladu, ponechané na konec) rychle hromadně potvrzeny:

- **(2) Prahová hodnota seznamu IN-list** — výchozí 500; upřesnění podle engine ve v1.5

- **(3) Polars Worker jako umožňující prvek, nikoliv prerekvizita** — uzel DataFrameNode bude skryt za příznak liveness přes capabilities-mcp

- **(4) Přehrání vs. reprodukce** — oba režimy ve v1; věrnost reprodukce omezena retencí v Seaweed

- **(5) Priorita zobrazení hypotéz** — enum SKRYTÁ / SEKUNDÁRNÍ / PRIMÁRNÍ, počáteční nastavení plánovačem, syntetizér jej může upravit v závěru

- **(6) Důvěra jako volitelná pro procedurální dotazy** — `Conclusion.confidence` je nullable; pro procedurální dotazy null, u hypotéz vyplněno


Bora: _„To vše dává smysl.“_  
**14. Syntéza — vytvoření dokumentace návrhu**  
Poté, co bylo všech 14 problematických bodů zafixováno, návrh se přesunul z roviny brainstormingu do artefaktu. Claude navrhl tři výstupní soubory:

- `Pythia-v1-Design.md` — komplexní dokument návrhu, modelovaný podle struktury `Analytický agent na V1.md` (vize, místo v platformě, kontrakt, zpracované příklady, komponenty, závislosti, roadmapa, rozhodnutí, glosář)

- `v1.5-backlog.md` — explicitně odložené položky s vysvětlením co / proč / kdy se k nim vrátit

- `open-questions.md` — věci vyžadující rozhodnutí před nebo krátce po implementaci v0 (Q1 = vyhodnocení frameworku; plus sedm menších, ale strukturálních otázek)


Bora potvrdil: _„Potvrzeno, prosím o kompletní draft, který následně zrecenzuji.“_  
Návrhový dokument byl sestaven ve čtyřech krocích (vize/kontrakt; zpracované příklady; komponenty/závislosti; roadmapa/rozhodnutí/glosář). Následně byly vytvořeny dokumenty s backlogem a otevřenými otázkami. Každý zachycuje to, o čem se diskutovalo, ale v jiném rámci — designový dokument je _co_; backlog je _záměrně odložené_; otevřené otázky jsou _dosud nerozhodnuté_; tento dokument z brainstormingu je _jak jsme se k tomu dopracovali_.  
**15. Reflexe procesu**  
Tři věci, které stojí za zmínku o tom, jak tento brainstorming probíhal, užitečné pro budoucí diskuse o designu Pythie.  
**Dokumentace k platformě byla klíčová**  
První rámec Clauda (před dokumentací k platformě) byl v důležitých ohledech chybný — Pythia byla naddimenzovaná (zasahovala do kompetencí Analytického agenta) a chápání instalatérských záležitostí opomíjelo, jak čisté prováděcí rozhraní platforma již má. Brainstorming se po doplnění dokumentů Borou výrazně vyostřil. Ponaučení: diskuse o designu nad nespecifikovanou platformou produkují pracovní verze (strawmen); diskuse o designu proti skutečné platformě produkují architekturu.  
**Borovo přerámování „pouze nastavujeme rámec“**  
Tento moment se opakoval. Když se objevily sofistikované záležitosti formované AI (kalibrované brzdy mezních hodnot, naučené odhady tokenů, eskalace úrovní uprostřed běhu), Borovým instinktem bylo důsledně je odložit a nejprve uzamknout rámec (envelope). Výsledek: Pythia v1 je strukturálně kompletní — kontrakty, životní cyklus, komponenty, integrace jsou specifikovány — zatímco skutečně experimentální práce s AI je explicitně odložena do dalších fází. To je v dobrém slova smyslu konzervativní. Kontrakty se nezmění (doufejme), až se sofistikovanost AI vyvine.  
**Přerámování na proces řízený hypotézami bylo nejúčinnějším momentem**  
Borův příspěvek k napětí 1, podotázka 3 — „plán by měl definovat hypotézy; pokud se hypotéza neprokáže, aktualizujte plán“ — přerámoval Pythii z vykonavatele plánu na vyšetřovatele využívajícího vědeckou metodu. Téměř každé pozdější rozhodnutí z toho vycházelo: dvojí role klasifikátoru podezření, FSM revize plánu (PROŘEZAT / PIVOTOVAT / DEKOMPONOVAT / ZASTAVIT), nedořešené body jako odvozený pohled, podmínky zastavení podle typu, čtyři brzdy růstu plánu, podmínění syntetizéru důvodem zastavení. Bez tohoto přerámování by byla Pythia konvenčnějším agentem typu „proveď plán, vrať výsledek“, méně vhodným pro skutečné případy užití (RCA, výběr prognostického modelu, citlivost simulace).  
**Pozorování tempa**  
Rané sekce (napětí 1, zpracované příklady) byly hutné a pomalé — mnoho designových otázek, pečlivé zvažování, několikanásobné interakce. Pozdější sekce (capabilities-mcp, scheduler, LLM úrovně, rozpočet, podmínky zastavení, prioritizace, renderery) se pohybovaly mnohem rychleji — kontrakt byl dohodnut, následná rozhodnutí do sebe čistě zapadala. Dočištění procedurálních bodů (#2-6) proběhlo jako jedno hromadné potvrzení. Tak vypadají kvalitní brainstormingy: hloubka na začátku, rychlost v závěru.  
_Vlastník dokumentu: Bora. Vytvořeno během brainstormingu o návrhu autorem Claude (Sonnet 4.7), 4. 5. 2026._