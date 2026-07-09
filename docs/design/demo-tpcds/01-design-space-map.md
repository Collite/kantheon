# Demo-TPCDS — Design-Space Map

One section per workstream: Question → Branches → Cross-links → Open.

## A — Narrative & storylines

**Question:** What story does the 25–30 min live demo tell, and how is it shaped?

- **A-1 Narrative shape**
  - α **Single continuous arc** — one persona, one escalating problem, constellation revealed as the story needs it.
  - β **Capability tour** — 5–6 independent vignettes.
  - γ **Spine + satellites** — ~20 min core arc + detachable vignettes to include/cut live.
  - δ **Two-screen theater** — business narrative on screen 1, live machinery (hypothesis tree, routing, provenance) on screen 2, simultaneously.
  - ε **Audience-driven coda** — take live questions from the room at the end (risk-boxed).
- **A-2 Protagonist** — category manager / CFO-facing analyst / merchandising VP / new-hire analyst ("first day, no SQL").
- **A-3 Central problem** — candidates: catalog slump (channel cannibalization), returns eating margin, stockout detective, promo lift-or-shift. Full catalogue in `02-a-narrative-options.md`.

Cross-links: A-3 ↔ C-3 (the chosen anomaly may need seeding); A-1δ ↔ B (machinery screen = capability showcase); ε constrained by P-2.

Open: Q-1 (data recon), Q-3 (which agents live at demo time).

## B — Capability coverage map

**Question:** Which Kantheon capabilities must the story surface, which are optional, which are explicitly out?

Branches (to enumerate): Themis routing + SPLIT + RoutingPickChip + gap detection; Golem pattern/free-SQL/amend/drill/clarification + chips + charts; Golem→Pythia escalation (InvestigateChip); Pythia hypothesis tree + budget + inbox + halt/partials; Metis forecast + scenario; Charon (visible or invisible plumbing?); pins/dashboards + refresh semantics (replay vs reproduce); Hebe scheduled briefing; provenance ⓘ; visibility_roles two-persona moment; feedback 👍/👎; discover page.

Cross-links: B is the checklist A must satisfy; B-1 ↔ Q-3 (deployed reality).

## C — Data & grounding

**Question:** How is TPC-DS grounded so the story feels real and every beat is deterministic?

- **C-1 Date grounding** — α keep 1998–2003 ("historical dataset" framing) / β re-base dates to 2024–2026 at load / γ living warehouse: DM refresh sets drip-feed "this week's" data.
- **C-2 Scale factor** — SF1 / SF10 / SF100 (feel-of-scale vs cluster cost vs query latency on stage). *GI-3: SF1 already loaded and proven on bp-dsk; departing from SF1 needs a reason.*
- **C-3 Anomaly provenance** — α natural (recon finds real skews) / β seeded (inject a controlled root cause; honest — data is synthetic anyway; guarantees the RCA lands) / γ hybrid (natural seasonality + one seeded incident).
- **C-4 Retailer identity** — fictional US brand, store geography, category names; presenter-facing lore sheet. Includes **channel naming**: GI-6 allows renaming "catalog", but "online" collides with the web channel — the three channels need a deliberate identity (e.g. Stores / Online / Direct · Marketplace · Call-center — to diverge).
- **C-5 Rename layer** — α **TTR-M model labels/synonyms** (physical schema stays canonical TPC-DS; load-job oracle, curated queries, and TPC-DS tooling stay valid; business naming lives in the model — the Kantheon discipline) / β physical DDL renames (WYSIWYG in psql too; costs: load job, DDL, oracle drift, curated-query rewrite) / γ views layer (rename via views; costs a second naming surface). *Lean: α.*

Cross-links: C-3 ↔ A-3 (converged: seeded); C-1γ ↔ Hebe weekly-briefing beat (needs data that changes); C-2 ↔ E; C-4/C-5 ↔ D (labels land in the model).

## D — TTR-M model shape (constraints-only in this effort)

**Question:** What must the demo's TTR-M model provide — areas, packages, entities, preferred queries — and how many Golems exist?

- **D-1 Areas/Golems** — α one Golem, one `retail` area / β per-concern areas (sales, customers, inventory, promotions) with 2–4 Golems / γ one Golem + Pythia only (minimal).
- **D-2 Preferred-query set** — authored from the chosen storyline beats (each scripted Golem answer should hit a pattern plan, not free-SQL roulette — P-2).
- **D-3 Model source** — GI-4: the `model-ttr/tpcds/` seed (7 entities, 4 queries, `tpcds` area) exists and is live; the demo model extends it (returns/inventory/promotion/warehouse/demographics entities, richer labels/synonyms, storyline-driven queries). Fork: extend in-repo seed vs author in `ai-models` (the WS-T2 model-source note leans in-repo seed for test warehouses).

Cross-links: D-1 ↔ B (routing showcase needs ≥2 routable targets to be visible); D-2 ↔ A beats; D-3 ↔ GI-5 (golem-tpcds Shem via ConfigMap, no image rebuild).

## E — Cluster shape (⏸ parked)

**Question:** What runs where for the showcase (olymp test deployment), and what does "demo-ready" mean operationally?

## F — Script, rehearsal & fallbacks

**Question:** What does the presenter actually say/click, and what happens when a beat fails live?

Branches (later): beat-by-beat script; pre-warmed sessions; canned-replay fallback per beat (pins replay = natural fallback mechanism); reset procedure between runs.
