# Kantheon — Documentation

This is the canonical place for all documentation about the Kantheon constellation.

The documentation is organised into three top-level areas. Each area has a README index and, where applicable, per-agent subfolders (`iris/`, `themis/`, `pythia/`, `golem/`).

## Layout

```
docs/
├── design/                     # what we decided to build, and why
│   ├── README.md
│   ├── iris/                   # FE + BFF design
│   ├── themis/                 # routing + question-understanding design
│   ├── pythia/                 # analytical investigator design
│   ├── golem/                  # per-domain Q&A template design
│   ├── hebe/                   # personal-agent design records (standalone era, migrated 2026-06-12)
│   └── product-design-issues.md  # living working list — Kantheon v1 product design convergence
│
├── architecture/               # how it is built — technical architecture, contracts, deployment, security
│   ├── README.md
│   ├── kantheon-architecture.md  # the overall constellation architecture (cross-cutting)
│   ├── kantheon-security.md      # authorization + audit (PD-8 resolution, 2026-06-12; cross-cutting)
│   ├── iris/                     # Iris architecture + contracts (arc planned 2026-06-12)
│   ├── themis/                   # Themis architecture + contracts
│   ├── pythia/                   # Pythia architecture + contracts (arc planned 2026-06-12)
│   ├── golem/                    # Golem architecture + contracts (arc planned 2026-06-12)
│   ├── charon/                   # Charon architecture + contracts (arc planned 2026-06-12)
│   ├── metis/                    # Metis architecture + contracts (arc planned 2026-06-12; Python)
│   ├── hebe/                     # Hebe integration architecture + contracts + standalone v1 architecture (arc planned 2026-06-12)
│   ├── capabilities-mcp/         # design companion
│   ├── fork/                     # the platform fork — architecture + contracts (decided 2026-06-12)
│   └── midas/                    # Midas arc architecture + contracts
│
└── implementation/             # what we're building right now, planned phases, task lists, status
    ├── README.md
    ├── planning-conventions.md   # task → stage → phase hierarchy (cross-cutting)
    ├── kantheon-v1.1.md          # deferred-items ledger (cross-cutting; opened 2026-06-12)
    └── v1/                       # the v1 implementation arc
        ├── README.md
        ├── master-plan.md        # cross-arc orchestration: two streams + mergepoints + status board
        ├── _archive/             # superseded cross-cutting docs (next-steps, aip-v1-*, handovers)
        ├── fork/                 # the platform fork — plan.md (runs before Iris execution)
        ├── iris/                 # plan.md (arc planned 2026-06-12)
        ├── themis/               # phased plan + per-stage task lists + carry-over from ai-platform
        ├── pythia/               # plan.md (arc planned 2026-06-12) + v1.5 backlog
        ├── golem/                # plan.md (arc planned 2026-06-12)
        ├── charon/               # plan.md (arc planned 2026-06-12)
        ├── metis/                # plan.md (arc planned 2026-06-12)
        ├── hebe/                 # plan.md (arc planned 2026-06-12) + standalone/ M0–M10 history
        ├── midas/                # Midas arc plan
        └── sysifos/              # Sysifos data-entry workbench arc (split from Midas 2026-06-13)
```

## Where to start

1. **[`design/README.md`](./design/README.md)** — what each agent *is*. Read first if you're new to Kantheon. Contains brainstorming records, briefs, and design conclusions.
2. **[`architecture/kantheon-architecture.md`](./architecture/kantheon-architecture.md)** — the overall constellation. Cross-cutting.
3. **[`implementation/planning-conventions.md`](./implementation/planning-conventions.md)** — task / stage / phase hierarchy that applies to all planning in this repo.
4. **[`implementation/v1/master-plan.md`](./implementation/v1/master-plan.md)** — the cross-arc orchestration plan (two streams, mergepoints, live status board). The resumption pointer for v1.

## Conventions

- Per-agent files live under each area's per-agent subfolder (e.g. `design/themis/themis-design.md`).
- Cross-cutting files live at the top of each area (e.g. `architecture/kantheon-architecture.md`, `implementation/planning-conventions.md`).
- Filenames use lowercase-hyphenated form (`themis-design.md`, not `Themis-Design.md`) — except where pre-existing capitalisation is preserved for legacy reasons (`Pythia-Brainstorming.md`, `Pythia-v1-Design.md`).
- READMEs at every folder level — they exist to orient a reader who clicks into the folder cold.
- Markdown only. Diagrams as Mermaid inside the markdown.

## Cross-repo orientation

- **ai-platform** (`/Users/bora/Dev/ai-platform`) — the predecessor platform, **maintenance-only since the 2026-06-12 fork decision**. Keeps running untouched (`whois`, `health`, `landing`, `backstage`, the legacy ERP-SQL line, and the originals of everything forked). The Maven-publishing coupling was **removed in fork Phase 1 (done)**; the pipeline runtime coupling is gone too (Phases 1–4 complete 2026-06-17). The technical services above are still ai-platform-served until **Fork Phase 5** forks them in.
- **kantheon** (`/Users/bora/Dev/kantheon`) — this repo: the agent constellation (Iris, Themis, Pythia, Golem instances, Hebe, `capabilities-mcp`) **and the surviving platform services** (Charon, Metis, Kallimachos, Pinakes, report-renderer, landing). **The forked "read spine" — Ariadne, Theseus, Echo, Kadmos, Proteus, Kyklop, Argos, Prometheus + the Brontes/Steropes/Arges workers, plus the off-constellation `whois`/`health`/`backstage` — was extracted to the open-source [`tatrman-server`](https://github.com/Collite/tatrman-server) repo (2026-07, SV-P0/P1) and renamed to functional names (Ariadne→Veles, Theseus→ttr-query, Echo→ttr-fuzzy, Kadmos→ttr-nlp, Proteus→ttr-translate, Kyklop→ttr-dispatch, Argos→ttr-validate, Prometheus→ttr-llm-gateway, workers→ttr-worker-{mssql,polars,postgres}, whois→ttr-identity). These services no longer live in kantheon.** See [`architecture/fork/architecture.md`](./architecture/fork/architecture.md) for the fork build history.
- **golem** (`/Users/bora/Dev/golem`) — legacy single-agent (Vue + Python). Retires after Kantheon cutover.
- **pythia** (`/Users/bora/Dev/pythia`) — legacy design folder; all content mirrored under `docs/design/pythia/`.
- **hebe** (`/Users/bora/Dev/hebe`) — original standalone repo of Hebe (personal autonomous agent with messaging channels + scheduler). Source copied into `kantheon/agents/hebe` on 2026-06-12; integration arc pending; original repo retains the full git history. (Supersedes the earlier note that Hebe lived in the separate `koklyp` project.)

## What changed 2026-06-17

- **The platform fork is complete (Phases 1–4).** The forked platform pipeline now stands on its own with **zero ai-platform coupling** — no Maven consumption, no runtime calls either way; the full mocked suite passes with no ai-platform client wired in. Independence asserted in [`architecture/fork/architecture.md`](./architecture/fork/architecture.md) §9; the cross-repo-coupling sections (CLAUDE.md §7, `kantheon-architecture.md` §10) are flipped to **dissolved/ACHIEVED**. The one remaining tie is **Fork Phase 5** (the technical wave — whois/health/landing/backstage), off the critical path, after which ai-platform can be switched off entirely. The `fork first, then develop` gate (mergepoint **M1**) is satisfied — **Iris Stage 1.1 execution is unblocked** ([`implementation/v1/master-plan.md`](./implementation/v1/master-plan.md) §6).

## What changed 2026-06-12

- **The platform fork decided (end of day).** ai-platform's intelligent services fork into kantheon — copy-paste, not migration; ai-platform stays untouched and maintenance-only; kantheon becomes a self-contained platform with zero cross-repo coupling at end state. Ten arrivals renamed into the pantheon (Ariadne, Theseus, Echo, Kadmos, Proteus, Kyklop, Argos, Prometheus, Brontes, Steropes); two-tier naming rule locked (agents = gods, platform services = the figures who serve them). Artefacts: [`architecture/fork/`](./architecture/fork/) + [`implementation/v1/fork/plan.md`](./implementation/v1/fork/plan.md). Sequencing: fork first, then the constellation arcs.
- **Hebe joined the constellation.** Source moved from `~/Dev/hebe` into `agents/hebe`; integration arc planned (`architecture/hebe/` + `implementation/v1/hebe/plan.md`): four profiles as presets over orthogonal axes (`local`/`personal`/`server`/`k8s`; 2026-06-13), PG schema-per-instance, Keycloak OBO for any platform-reaching profile, llm-gateway, `non_routable` capabilities registration, scheduled turns via iris-bff (resolves PD-10 layer 3). Standalone design records migrated to `design/hebe/`; M0–M10 history to `implementation/v1/hebe/standalone/`.
- **Product design review opened.** [`design/product-design-issues.md`](./design/product-design-issues.md) — 15 issues (PD-1…PD-15) to converge on the final Kantheon v1 design.
- **Iris, Golem, and Pythia arcs planned.** Each now has the full three-artefact set (`architecture.md` + `contracts.md` under `architecture/<agent>/`, `plan.md` under `implementation/v1/<agent>/`). Arc execution order locked: **Iris → Golem → Pythia**.
- Reality corrections folded into planning: Iris FE builds on `ai-platform/frontends/agents-fe` (not legacy `golem/frontend/`); the Golem rewrite ports `ai-platform/agents/golem` v2 semantics; `mcp-server-base` does not exist as a published lib (the MCP/Ktor base is `ktor-configurator`).
- `envelope/v1` full definition is owned by the Iris arc ([`architecture/iris/contracts.md`](./architecture/iris/contracts.md) §1.1), derived field-for-field from the proven FormatEnvelope v2 contract.

## What changed 2026-05-15

- Documentation restructured from a flat `docs/v1/` layout to the three-area structure above.
- `docs/v1/` and `docs/_orphans/` physically removed 2026-06-12 (cohesion review, D8); git history retains them.

---

*Top-level docs README. Update when the layout changes.*
