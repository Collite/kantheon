# Demo-TPCDS — Control Room

> Design effort: **Kantheon capabilities demo on TPC-DS data**.
> Lives at `kantheon/docs/design/demo-tpcds/` (repo moved to `/Users/bora/Dev/collite-gh/kantheon`).

## How we run this

Diverge-then-converge per the design skill. Workstreams below; options catalogued in `NN-<ws>-options.md`; decisions land only in the decision log. Chat is the scratchpad; these docs are the score.

## Workstream dashboard

| WS | Title | Status | Doc |
|---|---|---|---|
| A | Narrative & storylines | 🟢 converged 2026-07-09 (shape level; beat-level script = F) | `02-a-narrative-options.md` |
| B | Capability coverage map | ⚪ next — derive checklist from the converged arc | — |
| C | Data & grounding (SF, dates, anomaly seeding, retailer identity) | 🔵 diverging — recon pack prepared (`recon/`), awaiting run + results | — |
| D | TTR-M model shape (areas/packages, # of Golems) | ⚪ not started (constraints-only in this effort) | — |
| E | Cluster / deployment shape | ⏸ parked until B–C converge | — |
| F | Script, rehearsal & fallbacks | ⚪ not started | — |

## Framing inputs

- **FI-1** (Bora, 2026-07-09): Audience = prospective customers/business analysts + management/investors + internal self-validation. NOT primarily technical evaluators — internals appear only where they build trust.
- **FI-2** (Bora, 2026-07-09): Delivery = **live, presenter-driven**. Implies rehearsability + fallbacks.
- **FI-3** (Bora, 2026-07-09): Length = **~25–30 min, one full story** with room for the Pythia tree to breathe.
- **FI-4** (Bora, session brief): English demo, US grounding, TPC-DS data; TPC-DS queries are *inspiration only* — we design our own problems.
- **FI-5** (Bora, session brief): Downstream deliverables after this design: TTR-M model for TPC-DS + showcase cluster (test deployment; Kantheon and Olymp already equipped with TPC-DS data).

## Grounding inputs

- **GI-1** (Bora, session brief): *Golem and Pythia (et al.)* are the stars — both must be central.
- **GI-2** (deploy-test status 2026-07-07): **bp-dsk estate live** — 23 pods: full query path (theseus→proteus→argos→kyklop→**arges**), registry/core (capabilities-mcp, ariadne, prometheus, echo, kadmos + mcps), charon, metis (+mcps), iris, iris-bff, golem. **NOT deployed:** themis + pythia (D3 wave 4), hebe (wave 6), midas et al. (wave 5), whois/health/landing (wave 7). golem→Prometheus LLM roundtrip proven (C2 golem-erp green 2026-07-08); themis/pythia LLM tiers unblocked.
- **GI-3** (WS-T1, 2026-07-06): **TPC-DS SF1** (~1.2 GB, 25 tables) loaded in `tpc-ds-1g` on `test-pg` CNPG; `tpcds_readonly` role; deterministic oracle row counts (store_sales 2,880,404 …). Reload is idempotent (drop+recreate Job) — **data surgery for anomaly seeding is operationally cheap**.
- **GI-4** (WS-T2, 2026-07-07): embryonic **Ariadne `tpcds` model already exists** — self-contained `model-ttr/tpcds/` seed package: 7 entities (store_sales, catalog_sales, web_sales, date_dim, item, customer, store), `tpcds` area, 4 curated queries (`store_sales_by_month`, `top_items_by_revenue`, `customer_running_total` [window], `channel_revenue_cte` [CTE]), connection `pg-tpcds`, Kyklop routing. All four proven live end-to-end (MP-2). The demo TTR-M model **extends this seed**, not greenfield.
- **GI-5** (C2 session): **per-Shem Golem deployment needs no image rebuild** — Shem delivered via ConfigMap; dynamic `bp-dsk-golems` ApplicationSet. A `golem-tpcds` is cheap to stand up.
- **GI-6** (Bora, 2026-07-09): **we own the data instance completely** — up to and including renaming channels/tables ("catalog sales" → "online sales" or whatever the retailer identity needs). Opens fork C-5 (rename layer: TTR-M labels vs physical DDL).

## Asset inventory

- TPC-DS v4.0.0 spec (uploaded PDF): retail supplier, 3 sales channels (store/catalog/web) + returns + inventory + promotions; 7 fact tables, 17 dimensions; snowflake; query classes = reporting / ad-hoc / iterative OLAP / data mining; **data-maintenance (refresh) functions** — usable to simulate a *living* warehouse.
- Kantheon constellation (from project memory): Themis (intent PROCEDURAL/RCA/FORECAST/SIMULATION, routing, SPLIT decomposition, visibility_roles), Golem (area Q&A: pattern/free_sql/amend/drill/clarification plan sources, chips, envelope blocks, assembled Shem), Pythia (investigations, hypothesis tree pane, budget states, inbox), Charon (data movement), Metis (SARIMAX/Prophet fit/diagnose/project/simulate), Iris (chat, pins/dashboards with replay/reproduce refresh, discover DomainCards, reask, feedback, audit), Hebe (scheduled turns → inbox), BlockProvenance (ⓘ + SQL expander, visible to all in v1).
- TPC-DS date range 1998–2003 (d_date spans 1900–2199 in date_dim).

## Design principles

- **P-1 — The story is the demo.** Every capability appears because the story needs it; no feature detours. (FI-1: business audience.)
- **P-2 — Live means deterministic.** Every beat must be rehearsable and replayable; each beat has a fallback. We control the data, so we may author its ground truth.
- **P-3 — Trust is the differentiator.** No number on screen the presenter can't click to justify (provenance ⓘ everywhere). This separates Kantheon from generic chat-over-data.

## Load-bearing forks

- **A-1** Narrative shape (arc / vignettes / hybrid / two-screen)
- **A-2** Protagonist persona(s)
- **A-3** The central business problem (the anomaly the story pivots on)
- **B-1** Must-show vs nice-to-show vs out capabilities
- **C-1** Date grounding (historical-as-is / re-based to present / DM-driven living warehouse)
- **C-2** Scale factor
- **C-3** Anomaly provenance (natural / seeded / hybrid)
- **D-1** Areas & number of Golems

## Decision log (append-only)

- 2026-07-09 · **SCOPE-1** · The demo is given **only when Themis, Pythia and Hebe are deployed**; it is tested extensively end-to-end first. The full arc — including the Hebe cold-open/close and the Pythia escalation — is in scope, un-degraded. · Why: Bora — the demo waits for the constellation, not vice versa. · Rejected: designing the degraded arc as primary (the pinned-tile degrade path survives only as a rehearsal fallback, see parking lot).
- 2026-07-09 · **A-1** · Narrative shape = **γ spine + satellites**: ~20 min composite arc + detachable satellites (governance cameo, SPLIT compound question, Discover page). The "machinery screen" is δ-lite: Pythia's in-product hypothesis-tree pane, no extra AV. · Why: story momentum + cut-to-time robustness; Bora confirmed. · Rejected: β vignette tour (feature-list feel, violates P-1), α pure arc (live fragility), δ full two-screen (AV complexity for marginal gain). ε audience coda: **undecided** — tracked as a satellite candidate for F.
- 2026-07-09 · **A-2** · Protagonist = **Category Manager**; the actor is **Bora**. Second-persona governance cameo stays a satellite. · Rejected: CFO-voice (too far from drill mechanics), new-hire framing (gimmicky).
- 2026-07-09 · **A-3** · Central problem = the **composite Catalog-Slump arc** (7 beats: Hebe briefing → Golem orient/drill → InvestigateChip → Pythia RCA → conclusion+pin → Metis forecast/what-if → schedule the opening briefing). Hero scenario for all remaining workstreams. · Rejected as *frames* (they survive as acts/hypotheses inside the arc): returns-margin, stockout-only, promo-only, forecast-first.
- 2026-07-09 · **C-3** · Anomaly provenance = **β seeded root cause(s)**. We author the ground truth in the synthetic data; the load job is idempotent so surgery is cheap and reversible. · Why: a live Pythia RCA must be rehearsable (P-2), and honest — the data is synthetic either way. · Rejected: α natural-only (unrehearsable roulette); γ hybrid remains the *effective* outcome since natural seasonality stays in play — recon (Q-1) decides the exact seeding scope.

## Parking lot

- **Degrade path** (pinned-tile cold-open instead of Hebe; pin instead of schedule) — demoted by SCOPE-1 to a *rehearsal fallback* only; revisit if wave-4/6 bring-up slips against a hard demo date.
- **E (cluster shape)** — revisit when B–C 🟢.
- Self-serve sandbox mode (audience login + Discover chips) — not v1 of the demo; revisit after live demo lands (FI-2 chose live).
- Recorded-video cut of the same script — natural byproduct later; not designed for.

## Open questions

- **Q-1**: Which anomalies/trends *actually exist* in generated TPC-DS SF1 data? (Web-channel growth? holiday seasonality amplitude? returns skew?) Needs a data-recon pass against `tpc-ds-1g` before A-3/C-3 converge. Recon can run today — the query path is live (MP-2).
- ~~**Q-2**: device-bridge mount~~ — resolved 2026-07-09: repo moved to `/Users/bora/Dev/collite-gh/kantheon`; docs committed.
- **Q-3**: ~~deployed-estate state~~ resolved by GI-2; ~~demo date vs bring-up~~ resolved by SCOPE-1 (demo waits for Themis+Pythia+Hebe). Residue: which **Iris phases** are live/planned for the showcase (pins/inbox/discover are Iris P4 — beats 1/5/7 and the Discover satellite need them).

## Session index

| Date | Session | Gear | Outcome |
|---|---|---|---|
| 2026-07-09 | S1 | Framing → A divergence → A convergence | FI-1..5, GI-1..6, P-1..3; deploy-test grounding read; **SCOPE-1, A-1, A-2, A-3, C-3 decided**; recon pack authored (`recon/`) |
