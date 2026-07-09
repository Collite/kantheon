# Demo-TPCDS — Control Room

> Design effort: **Kantheon capabilities demo on TPC-DS data**.
> Lives at `kantheon/docs/design/demo-tpcds/` (repo moved to `/Users/bora/Dev/collite-gh/kantheon`).

## How we run this

Diverge-then-converge per the design skill. Workstreams below; options catalogued in `NN-<ws>-options.md`; decisions land only in the decision log. Chat is the scratchpad; these docs are the score.

## Workstream dashboard

| WS | Title | Status | Doc |
|---|---|---|---|
| A | Narrative & storylines | 🟢 converged 2026-07-09 (shape level; beat-level script = F) | `02-a-narrative-options.md` |
| B | Capability coverage map | 🟢 converged 2026-07-09 (Q-8 → F) | `04-b-coverage-map.md` |
| C | Data & grounding (SF, dates, anomaly seeding, retailer identity) | 🟢 converged 2026-07-09 (Q-4 magnitude tuning tracked in `surgery/`) | `03-c-data-options.md` |
| D | TTR-M model shape (areas/packages, # of Golems) | 🟢 converged 2026-07-09 — spec written (build-phase input) | `05-d-ttrm-spec.md` |
| E | Cluster / deployment shape | 🟢 converged 2026-07-09 — spec written (build-phase input; Q-12/Q-13 build-time) | `06-e-cluster-spec.md` |
| F | Script, rehearsal & fallbacks | 🟢 converged 2026-07-09 — presenter script written (R0 number-freeze = build-time gate) | `07-f-script.md` |

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
- 2026-07-09 · **C-1** · Date grounding = **β re-base +24 years (2002→2026), then back up the re-dated instance** so surgery never repeats (restore replaces reload). Implementation: facts re-point to date_dim's already-correct target rows (date_dim itself untouched); date-typed dim columns + c_birth_year shift too. Scripts: `surgery/run-redate.sh`. · Why: Bora — we touch the data anyway (C-3 seeds); a visibly-historical 1998–2002 screen is avoidable. · Rejected: α native dates (was the lean; overruled), γ DM drip-feed (stays parked as v1.1 Hebe upgrade), rewriting date_dim rows (breaks dim self-consistency). Open residue: Q-7 offset fine-point (+24 → present = Jan 2027; +23 if the present must sit inside the data). Ripple: curated-query year params + `tpcds-query` context asserts (see `surgery/README.md` checklist).
- 2026-07-09 · **C-2** · Scale factor = **SF1 as loaded**. · Why: latency on stage; $1B store channel reads real. · Rejected: SF10/100 (cost, no narrative gain).
- 2026-07-09 · **C-5** · Rename layer = **α′**: TTR-M labels/synonyms for structure + tiny dim-table UPDATEs for display values (warehouse/store names, placeholder reasons); fact tables touched only by the C-3 seed. · Rejected: β full physical rename (oracle/tooling drift), γ views (second naming surface).
- 2026-07-09 · **C-3** · Anomaly provenance = **β seeded root cause(s)**. We author the ground truth in the synthetic data; the load job is idempotent so surgery is cheap and reversible. · Why: a live Pythia RCA must be rehearsable (P-2), and honest — the data is synthetic either way. · Rejected: α natural-only (unrehearsable roulette); γ hybrid remains the *effective* outcome since natural seasonality stays in play — recon (Q-1) decides the exact seeding scope.
- 2026-07-09 · **C-1a** (amends C-1) · Offset = **+23** (2002→2025; last full year 2025, tail Jan 2026 — the present sits inside calendar 2026: "we just closed 2025"). · Why: Bora — a 2026 demo shouldn't narrate from Jan 2027. · Rejected: +24 (the original 2002→2026 phrasing; present lands ahead of the demo date).
- 2026-07-09 · **C-4** · Retailer identity = **Hartland Stores** — TN-headquartered; channels labeled **Stores / Web / Marketplace** (Marketplace = 3P sellers *fulfilled by Hartland* from 5 DCs, warehouse-pinned; Web auto-reroutes). Warehouses: Memphis/Columbus/Dallas/Reno/Allentown DC; stores = TN towns; placeholder return reasons relabeled. Labels live in the TTR-M model (C-5α′); display names via tiny dim UPDATEs. · Rejected: Cumberland & Co., Volunteer Trading Co., Smoky Ridge Supply; channel schemes Stores/Online/Catalog and Stores/Online/Direct.
- 2026-07-09 · **B-1/B-2/B-3** · Coverage map ratified as drafted (MUST/SHOULD/SAT/SUB/OUT per `04-b-coverage-map.md`); **routing visibility = α single Golem** + Pythia + one deliberate gap question; **free-SQL stays in the spine** (SHOULD, gate rehearsed). · Why: Bora — single Golem is enough for this demo. · Rejected: β second Golem for RoutingPickChip visibility. Opens **Q-8** (governance-cameo mechanics under single-Golem — F decides).
- 2026-07-09 · **D-1** (decided via B-2) · **One Golem — `golem-hartland` — over one area** (extends the `tpcds` seed model; area label per C-4 identity). Pythia is the second routable citizen. · Rejected: per-concern multi-Golem split (no routing-showcase need survives B-2α).
- 2026-07-09 · **E-1** · Demo host = **dedicated showcase cluster** (new olymp GitOps `clusters/<name>`, naming lean `showcase`); bring-up may start `:testing` but flips to **pinned MP-4 release tags** and moves pin-to-pin only; freeze window before demo day. · Why: Bora — full isolation from dev churn; D1/D2 chart estate makes bring-up mechanical. · Rejected: bp-dsk-pinned (my lean; `:testing` churn), olymp integration cluster (torn up by design). Opens **Q-12** (hardware/host).
- 2026-07-09 · **E-2** · Demo data = **separate `hartland` database** restored from a pristine `tpc-ds-1g` dump; ALL surgery (re-date, seeds, naming) applies to `hartland` only — `tpc-ds-1g` stays pristine, so the `tpcds-query` context + `q.tpcds.*` fixtures never move (**Q-11 void by construction**). New `pg-hartland` Arges connection + Kyklop mapping; provenance on stage reads `db=hartland`. Scripts re-defaulted (redate → `hartland`; recon takes a DB arg). · Rejected: surgery on `tpc-ds-1g` (fixture ripple, shared state). Opens **Q-13** (warehouse PG placement on showcase — lean: dedicated CNPG mirroring test-pg).
- 2026-07-09 · **D-4** · Area id = **`hartland`** (AreaDef → the hartland model packages; role `kantheon-area-hartland`); the kantheon `tpcds` seed area stays frozen for the integration context. · Rejected: extending `tpcds` in place (benchmark vocabulary leaks into router/roles).
- 2026-07-09 · **D-6a** · **`net_profit` + wholesale/list-cost columns excluded from the demo model** — F-8 makes them nonsense-generators; margin questions gap gracefully. Physical schema untouched. · Rejected: include-and-label-carefully.
- 2026-07-09 · **D-7** · Model + golem identity live in a **NEW repo `Collite/hartland`**, the ai-models-analog (Bora's own proposal — supersedes both offered options). Ariadne consumes it as the model Git source; Shem assembled via the production path (hartland def + Ariadne model + kantheon overlay + template constants). Demo talking point: this *is* the customer-onboarding flow. · Rejected: fully-in-kantheon seed (my lean; loses the production-path story), ai-models def (mixes demo assets into the production model repo).
- 2026-07-09 · **C-3a** (amends C-3 spec) · Seed spec v2 = **the Memphis DC Meltdown, H2 2025** — warehouse-wide (all categories), S1 inventory streak wk31–47 + S2 −60% Memphis Marketplace lines wk32–48 (+matching returns) + S3 "Did not get it on time" skew + S4 red-herrings-stay-natural; regional cut demoted to a dying branch. Resolves Q-5. · Why: per-category restriction was arithmetically inaudible (<1% channel impact); whole-DC failure is more real and needs fewer seeds. · Rejected: 2-category incident (v1 draft), S4-regional binding, S5 web-uptick nuance (default OFF, revisit at script build).
- 2026-07-09 · **F-1** (resolves Q-8) · Governance cameo = **α thin CFO-only Shem**: `golem-hartland-finance` over the *same* hartland model/area (2–3 preferred-query subset, no new data), `visibility_roles: [kantheon-role-finance]` — satellite scenery, structurally unroutable in Maya's spine (B-2α intact). On stage: CFO's Discover shows two cards vs Maya's one; one finance question; "invisible, not forbidden". · Why: the only option that *shows* visibility_roles; marginal cost = one overlay (GI-5) + one rehearsed Q&A; both dependencies (CFO persona, Discover) already in the estate for other reasons. · Rejected: β gap-only (contrast claimed, not shown; the gap moment already lives in the spine — β would mean cutting the cameo), γ RLS data contrast (unspecced Arges→PG identity work on a frozen showcase; demos the warehouse's mechanism, not Kantheon's; two-truths risk on stage).
- 2026-07-09 · **F-2** (resolves ε) · Audience coda = **γ conditional-go**: fully rehearsed (fresh throwaway session, gap machinery as the net, hard stop 30′), staged after the applause point; Bora decides live on room + clock (go-criteria in `07-f`). · Why: keeps P-2 determinism for the scripted arc and buys the credibility spike only when the room earns it; skipping is invisible — beat 6 is the true ending. · Rejected: α always-go (forces unscripted risk on a cold room), β no-go (leaves the strongest available trust beat unused).
- 2026-07-09 · **F-3** (closes C-3a's S5 residue) · **S5 web-uptick stays OFF** for v1. Cannibalization dies on genuinely flat Web; lore covers the demand loss (unfulfilled Marketplace buyers bought elsewhere, off-platform). · Rejected: ON (partial-truth nuance not worth a second Q-4 tuning knob + rehearsal surface). → parking lot with revisit condition.
- 2026-07-09 · **F-4** · Timing budget & default cut: base ≈27′ + 3′ slack = spine (B1 2′ · B2 6′ · B3 8–9′ · B4 1.5′ · B5 4.5′ · B6 1.5′) + Sat-G cameo 2′ + Sat-D Discover-closer 1.5′; SPLIT + feedback in the if-time pocket; cut ladder pocket→G→D; B3 checkpoint ≤17′; **the spine is never cut**. · Rejected: leaner spine-only base (drops the governance + self-serve messages), richer base incl. SPLIT (29–30′, no slack).
- 2026-07-09 · **SWEEP-1** (S9) · Consolidation sweep — S-1..S-14 batch-ratified (Bora): **S-1** beat numbering canon = 6-beat B-map/07-f scheme (A-3's "7 beats" historical); **S-2** persona card = Maya Chen, Senior Category Manager, category P&L across all channels, `maya@hartland.example`; **S-3** named fixtures = routine "Monday channel health brief", dashboard "Channel Health", fallback "Rehearsal" dashboard (standing fixture); **S-4** demo-reset preserve list = both dashboards + rehearsal investigation + routine + Keycloak users + `hartland` DB; **S-5** query namespace `q.hartland.*`; **S-6** DomainCards "Hartland Analytics" / "Hartland Finance"; **S-7** store naming keyed on `s_store_id` — 6 logical stores (12 rows = SCD versions): Nashville, Memphis, Knoxville, Chattanooga, Franklin, Murfreesboro; HQ Nashville; **S-8** Memphis DC = highest-Marketplace-share warehouse per r13 (NULL-named row gets another city); **S-9** return-reason relabels: Changed my mind · Found a better price · Wrong size · Wrong color · Ordered by mistake · No longer needed · Not as pictured · Unwanted gift · Incompatible with my device · Quality not as expected (kept clear of delivery-timing semantics — S3 signal undiluted); **S-10** demo dump in `tpcds-staging` under `hartland/` prefix (no new bucket); **S-11** ε coda = Maya's login, fresh throwaway session; **S-12** narrative present spoken-only ("Monday morning, mid-January"), briefing period-labeled H2-2025, no on-screen date pinning; **S-13** CFO persona = **Dan Whitaker, CFO** (`cfo@hartland.example`); **S-14 resolves Q-13** = dedicated CNPG for the `hartland` warehouse on showcase, mirroring test-pg. Defects fixed alongside: surgery/README stale "+24" prose → offset-parameterized (+23 default); 01-design-space-map stale E/F status lines.

## Parking lot

- **Degrade path** (pinned-tile cold-open instead of Hebe; pin instead of schedule) — demoted by SCOPE-1 to a *rehearsal fallback* only; revisit if wave-4/6 bring-up slips against a hard demo date.
- **E (cluster shape)** — revisit when B–C 🟢.
- Self-serve sandbox mode (audience login + Discover chips) — not v1 of the demo; revisit after live demo lands (FI-2 chose live).
- Recorded-video cut of the same script — natural byproduct of rehearsal R3 (it doubles as fallback L4 per `07-f`); a *published* video cut is still not designed for.
- **S5 web-uptick seed** (F-3: OFF) — revisit after the first full R3 rehearsal if the cannibalization kill feels thin on stage.

## Open questions

- ~~**Q-1**: what exists naturally in SF1?~~ — RESOLVED 2026-07-09 (recon run by Bora; results in `recon/results/`, analysis = `03-c-data-options.md` F-1..F-9). Headline: **flat canvas** — no natural trends at all; strong clean seasonality is the one real feature; stores are all in TN; stockouts naturally ~0.1%; `net_profit` structurally negative (metric trap); warehouse names are gibberish (naming curation needed).
- **Q-4**: seed magnitudes tuning (see `03-c-data-options.md`) — feeds `07-f` R0 (script number-freeze).
- ~~**Q-5**: incident categories + regions~~ — RESOLVED by C-3a (warehouse-wide, all categories; regional = dying branch).
- ~~**Q-8**: governance-cameo mechanics under single-Golem~~ — RESOLVED 2026-07-09 by **F-1** (thin CFO-only Shem `golem-hartland-finance`).
- ~~**ε coda**: go/no-go~~ — RESOLVED 2026-07-09 by **F-2** (conditional-go).
- **Q-6**: `cs_warehouse_sk` slice cleanliness — one follow-up recon query.
- ~~**Q-2**: device-bridge mount~~ — resolved 2026-07-09: repo moved to `/Users/bora/Dev/collite-gh/kantheon`; docs committed.
- **Q-3**: ~~deployed-estate state~~ resolved by GI-2; ~~demo date vs bring-up~~ resolved by SCOPE-1 (demo waits for Themis+Pythia+Hebe). Residue: which **Iris phases** are live/planned for the showcase (pins/inbox/discover are Iris P4 — beats 1/5/7 and the Discover satellite need them).

## Session index

| Date | Session | Gear | Outcome |
|---|---|---|---|
| 2026-07-09 | S1 | Framing → A divergence → A convergence | FI-1..5, GI-1..6, P-1..3; deploy-test grounding read; **SCOPE-1, A-1, A-2, A-3, C-3 decided**; recon pack authored (`recon/`) |
| 2026-07-09 | S2 | C divergence (recon analysis) | Recon run (Bora) + analyzed; F-1..F-9; `03-c-data-options.md` with C-1..C-5 options + the **2002 Fulfillment Incident** seed spec draft; C → 🟡 |
| 2026-07-09 | S3 | C convergence (partial) | **C-1 (re-base, amended), C-2, C-5 decided**; `surgery/run-redate.sh` + README authored; open: Q-4/Q-5 seed params, Q-6 warehouse slice, Q-7 offset fine-point, C-4 identity |
| 2026-07-09 | S4 | C close-out → B draft | **C-1a (+23), C-4 (Hartland Stores, Stores/Web/Marketplace), C-3a (Memphis DC Meltdown v2)**; C 🟢; r13 added to recon; `04-b-coverage-map.md` drafted — awaiting Bora's B-1 pass |
| 2026-07-09 | S5 | B convergence | **B-1/B-2/B-3 + D-1 decided** (classes ratified; single golem-hartland; free-SQL in spine); B 🟢; Q-8 opened (cameo mechanics → F); D 🔵 next |
| 2026-07-09 | S6 | D convergence | **D-4 (`hartland` area), D-6a (net_profit excluded), D-7 (NEW repo `Collite/hartland`, ai-models-analog)**; `05-d-ttrm-spec.md` written (19 entities, 15 preferred queries, Shem overlay, acceptance bar); D 🟢; Q-9..Q-11 opened (build-time) |
| 2026-07-09 | S7 | E convergence | **E-1 (dedicated showcase cluster), E-2 (separate `hartland` DB — Q-11 void)**; `06-e-cluster-spec.md` (estate roster, real-LLM note, demo-reset/pre-show ops, demo-ready bar); scripts re-parameterized; E 🟢. **Next session: F, fresh** |
| 2026-07-09 | S8 | F convergence + script authoring | **F-1 (Q-8 → thin CFO Shem), F-2 (ε → conditional-go), F-3 (S5 OFF), F-4 (timing/default cut)**; `07-f-script.md` written — beat-by-beat presenter script, satellites, ε protocol, 5-layer fallback architecture, rehearsal ladder R0–R5; F 🟢. **All workstreams 🟢 — next: consolidation sweep → design.md + detailed-design.md → /planning** |
| 2026-07-09 | S9 | Consolidation sweep | **SWEEP-1: S-1..S-14 batch-ratified** (names/fixtures/namespace/stores/DC rule/reasons/dump location/coda identity/narrative date/Dan Whitaker CFO/**Q-13 → dedicated CNPG**); README "+24" prose + 01-map staleness fixed; ripples applied to 05-d/06-e/07-f/surgery/01. **Next: design.md + detailed-design.md → /planning** |
