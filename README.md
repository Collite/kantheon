# Kantheon

**Kantheon** ("Kotlin pantheon") is a self-contained agent constellation **and** the platform
beneath it — the successor to `ai-platform` (forked 2026-06-12, now maintenance-only). It owns
the user-facing surface, the question-understanding + routing layer, the autonomous
investigator, the per-domain Q&A agents, and the whole query/knowledge/estimation platform they
run on.

- Project guide: [`CLAUDE.md`](./CLAUDE.md)
- Developer guide: [`AGENTS.md`](./AGENTS.md)
- Canonical patterns: [`EXAMPLES.md`](./EXAMPLES.md)
- Documentation index: [`docs/README.md`](./docs/README.md)

---

## The constellation — personas

Naming follows a **two-tier mythology rule** (Greek transliterations, not Latin): the
**agents** are the speaking / user-facing gods; the **platform services and workers** are the
older, chthonic, or heroic figures who serve them. Off-constellation infrastructure
deliberately keeps a plain functional name — no forced persona.

### Agents — the speaking gods

| Persona | Myth | Role |
|---|---|---|
| **Iris** | messenger goddess; the rainbow between gods and mortals | The user-facing chat surface — Vue SPA (`frontends/iris`) + the dispatch BFF (`agents/iris-bff`) that holds conversation state and multiplexes agent streams back to the UI. |
| **Themis** | goddess of divine order and law | Question understanding + agent routing — classifies intent and picks the agent (the constellation's traffic cop). |
| **Pythia** | the Oracle of Delphi | The autonomous analytical investigator — root-cause, forecast, simulation, cross-domain, over a custom DAG executor. |
| **Golem** | the clay figure brought to life by a *Shem* (inscription) | The per-domain Q&A template — one instance per subject **area**, each animated by an area manifest (its Shem). The one non-Greek persona, kept for the Shem metaphor. |
| **Hebe** | cup-bearer to the gods | The personal autonomous agent — per-user instances, cron routines + channels (CLI/web/Telegram), receipts, plugins; four deployment profiles. |
| **Kleio** | Muse of history | The knowledge / Document-Warehouse agent (routable, `KNOWLEDGE` intent) — graph-primary retrieval over the compiled wiki (relational · full-text · vector · graph, one Postgres). |
| **Midas** | the king whose touch turned all to gold | The brokerage-domain agent (`agents/midas/core`) — the append-only operational core of the investment product + its loaders. |
| **Sysifos** | Sisyphus, condemned to roll his boulder forever | The data-entry & data-management workbench for Midas — the forms-shaped sibling to Iris (BFF `agents/sysifos-bff` + FE `frontends/sysifos`). |

### Platform services — the older, chthonic & heroic figures who serve

| Persona | Myth | Role |
|---|---|---|
| **Charon** | ferryman of the dead across the Styx | The Arrow data mover — Seaweed / Redis / worker sessions / named DB connections. |
| **Metis** | Titaness of wisdom and counsel | Model estimation — SARIMAX (auto-order) / Prophet / linear; diagnose / project / simulate (Python). |
| **Ariadne** | gave Theseus the thread through the labyrinth | The model / metadata graph — the thread through the schema that Proteus and Theseus resolve entities against. |
| **Theseus** | the hero who navigated the labyrinth | The query orchestrator + plan cache — drives the resolve → translate → validate → dispatch path. |
| **Echo** | the nymph who could only repeat | The Czech-aware fuzzy matcher. |
| **Kadmos** | founder of Thebes who brought the alphabet to Greece | The NLP foundation (Python). |
| **Proteus** | the shape-shifting sea god | The translator — language ↔ RelNode ↔ SQL. |
| **Kyklop** | the Cyclops (the genus) | The worker dispatcher — routes a plan to the right engine worker (the dispatcher carries the genus name; the workers are the individual Kyklopes). |
| **Argos** | Argus Panoptes, the hundred-eyed watchman | The validator + row-level-security policy — the all-seeing guard on every query. |
| **Prometheus** | the Titan who brought fire to mortals | The LLM gateway (+ `EmbedText`) — brings the model's fire to the constellation (Spring Boot). |
| **Kallimachos** | Callimachus, chief librarian of Alexandria | The DocWH corpus warehouse (read path) — compiled wiki (sources · parts · pages) + retrieval. |
| **Pinakes** | Callimachus's *Pínakes*, the catalog of the Library | The DocWH write path — pipeline manager + asset catalog + lineage + the LLM compile that turns sources into a wiki. |

### Workers — the Kyklopes (Cyclops smiths of the forge)

Each engine worker is one of the three Hesiodic Cyclopes; the dispatcher (**Kyklop**, above)
carries the genus.

| Persona | Myth | Role |
|---|---|---|
| **Brontes** | "thunder" | The MSSQL worker. |
| **Steropes** | "lightning" | The Polars worker (Python). |
| **Arges** | "bright" | The Postgres worker (+ `SET LOCAL app.tenant_id` RLS). |

*Bench (reserved for future workers): Pyrakmon, Halimedes, Euryalos, Elatreus, Trachios.*

### Off-constellation infrastructure — no persona

Infrastructure keeps its functional name (constellation citizens get personas; infrastructure
does not).

| Module | Role |
|---|---|
| `whois` | User / role directory + OPA bundle server. |
| `health` | Cluster health aggregator. |
| `landing` | Multilingual landing page / service dispatcher. |
| `backstage` | Developer portal. |
| `report-renderer` | Report rendering (XLSX / PPTX / PDF / HTML) for the Midas domain — a functional service, kept functional. |

### The registry that ties them together

| Module | Role |
|---|---|
| `tools/capabilities-mcp` | The single registry for both **tools** and **agents** (`kind: TOOL \| AGENT`) — Themis reads agent manifests to route; Pythia reads agent + tool manifests to plan. Thin `tools/<persona>-mcp` wrappers register each service's capabilities. |

---

See [`CLAUDE.md` §2](./CLAUDE.md) for the full module/stack table and the load-bearing
invariants (`envelope/v1` rendering contract, `capabilities/v1` registry).
