# A — Narrative & storylines: options catalogue

Status: 🟢 **converged 2026-07-09** — A-1 = γ (+δ-lite), A-2 = category manager (actor: Bora), A-3 = composite Catalog-Slump arc, alongside SCOPE-1 (demo waits for Themis+Pythia+Hebe) and C-3 = seeded. See the control-room decision log; this catalogue is retained as the option record. ε (audience coda) remains open → workstream F.
Grounding: **GI-1** — Bora's brief names *Golem and Pythia (et al)* as the stars; both must be central, the rest of the constellation appears in supporting roles.

## A-1 — Narrative shape

| Opt | Shape | Buys | Costs | Notes |
|---|---|---|---|---|
| α | **Single continuous arc** — one persona, one escalating problem | Narrative momentum; mirrors real usage; every capability *motivated* (P-1); emotional payoff at the conclusion | Fragile live (one dead beat stalls the story); hard to cut for time | The classic "day in the life" |
| β | **Capability tour** — 5–6 standalone vignettes | Robust; modular timing; each vignette rehearsable in isolation | Feels like a feature list; weak story for FI-1 business audience | |
| γ | **Spine + satellites** — ~20 min core arc + 2–3 detachable vignettes | α's story with β's cut-to-time flexibility; satellites double as fallbacks | Needs clean seams; satellites risk feeling bolted on | Satellites: governance moment, discover page, compound-question SPLIT |
| δ | **Two-screen theater** — business narrative + live machinery (hypothesis tree, routing decisions, provenance) side by side | Serves the *mixed* audience in one pass: business sees the story, everyone sees it's real (internal-validation goal too); the Pythia tree pane was built for exactly this | AV complexity; presenter attention split; risks distraction | Composable with α/γ rather than exclusive |
| ε | **Audience-driven coda** — live questions from the room after the scripted arc | Credibility spike ("not canned"); Themis clarification + gap handling *is* the safety net — a graceful "I don't have supplier data" is itself a demo of gap detection | Direct P-2 tension; needs a throwaway session + presenter judgment | Box it: optional last 3 min, never mid-arc |

*Lean: γ + δ-lite (the Pythia inbox/tree pane is already in-product — "second screen" can just be the product's own debug-grade pane, no real AV needed) and ε as an explicitly optional coda. Not a decision.*

## A-2 — Protagonist

| Opt | Persona | Buys | Costs |
|---|---|---|---|
| α | **Category manager** (e.g. "Maya, Home Entertainment category at the retailer") | Owns a P&L slice → naturally asks reporting, RCA, forecast, and promo questions; single coherent voice | One voice = no governance contrast |
| β | **Two personas: analyst + CFO/VP** | Enables visibility_roles beat (same question, different agents visible) + reask/feedback; shows Kantheon is *per-user* (OBO, RLS) | Stage time; context-switch cost; two prepared logins |
| γ | **New-hire, no SQL, day one** | Dramatizes Discover page + example chips + clarification flow; "anyone can do this" message | Slightly gimmicky; underplays power-user depth |

*Lean: α as the spine voice, with a 60-second β cameo for the governance beat (login switch, one question, one contrast). Not a decision.*

## A-3 — The central business problem

The pivot of the arc. Candidates, each with the TPC-DS substrate that makes it work:

| Opt | Problem | Substrate (inspiration) | Buys | Costs |
|---|---|---|---|---|
| α | **The Catalog Slump** — catalog revenue sliding YoY in specific regions | Q75 (YoY decline), Q78 (channel ratios per customer), Q54/Q64 (cross-channel identity), Q72 (stockouts suppress catalog orders) | Uses TPC-DS's crown jewel — same customer across 3 channels; rich, branchy hypothesis tree (cannibalization / demographics / promo starvation / stockouts / returns); period-authentic *and* timeless "old channel vs new channel" story | "Catalog" reads dated to a 2026 audience (mitigable by framing) |
| β | **Returns Are Eating Margin** — margin dip traced to a returns spike | Q49 (worst return ratios), Q93 (return reasons), `reason` dim | Margin story lands with management; `reason` codes give a *concrete* conclusion ("quality issue on supplier X's line") | Returns facts are thin; cross-channel identity underused |
| γ | **Stockout Detective** — lost sales from inventory gaps | `inventory` fact (catalog+web only), Q72, Q39 (inventory variance) | Operational, actionable; flows naturally into Metis reorder forecast | Stockout inference is subtle to *show*; store channel has no inventory |
| δ | **Promo Lift-or-Shift** — did the flagship promotion create demand or move it? | `promotion` dim, Q61 (promo revenue share), Q7/Q26 | Sophisticated incrementality story; analytics credibility | Causality claims on synthetic data; visually non-obvious |
| ε | **Holiday Readiness** (forward-looking) — plan Q4 by category/state | d_holiday seasonality, sales facts | Metis-centric; management loves planning; upbeat ending | Weak RCA moment — Pythia's tree (its best pane) underused |

### The composite arc (hero-scenario sketch)

The candidates are not mutually exclusive — α can *contain* γ, δ, ε as acts:

1. **Cold open** — Monday briefing in the inbox (Hebe scheduled turn, arrived overnight): "Catalog revenue down 9% YoY in the Midwest." *(Hebe, inbox, envelope blocks)*
2. **Orient** — persona asks Golem plain questions: trend by channel, by region, by category; drills and amends via chips; every number has ⓘ→SQL. *(Themis routing, Golem pattern plans, drill/amend, provenance)*
3. **Escalate** — "this isn't a mix effect — *why* is it dropping?" → **InvestigateChip** → Pythia. Hypothesis tree unfolds live: web cannibalization? demographic shift? promo starvation? stockouts? returns? Branches die on evidence; one survives. *(Golem→Pythia handoff, hypothesis tree, budget visible, cross-fact joins)*
4. **Conclusion** — seeded ground truth found (e.g. a warehouse's stockout streak starved catalog fulfillment for two categories — C-3β decides this); conclusion block with full provenance + loose ends. *(Pythia conclusion, BlockProvenance, LooseEnds)*
5. **Act** — pin the finding + the weekly channel view to a dashboard; refresh semantics stated in one sentence (replay = moving, reproduce = frozen evidence). *(Iris pins/artifacts)*
6. **Look forward** — "if we fix the stockout and run a win-back promo, what does Q4 look like?" → Metis forecast + scenario side-by-side. *(Metis FORECAST + SIMULATION via Pythia)*
7. **Close the loop** — schedule the Monday briefing to keep watching catalog vs web (the same Hebe routine from the cold open — the story eats its own tail). Optional ε coda: audience question.

Satellites (γ shape): the β-persona governance cameo; a compound question showing Themis SPLIT; the Discover page as the "what else can I ask" closer.

## Deployment-reality note (GI-2, 2026-07-09)

Against the composite arc's seven beats: beats 2 (Golem orient, query path) and the ⓘ provenance substrate are **live today** (MP-2 + golem-erp green); beat 3 (Themis routing, Pythia tree) needs **D3 wave 4** + the themis-routing/pythia-rca contexts; beat 6 (Metis) has the service deployed but needs Pythia P4 integration; beats 1/7 (Hebe) need **wave 6** — the *most* deferred dependency. Degrade path if Hebe misses the date: cold-open from a **pinned dashboard tile gone red** (Iris artifacts) instead of an inbox briefing, and close by pinning rather than scheduling. Beat 5 (pins) itself needs Iris P4 state confirmed (Q-3 residue).

## What convergence on A needs

1. **Q-1 data recon** — which trends/skews exist naturally in SF1 `tpc-ds-1g` (web growth? seasonality amplitude? returns distribution?) → determines how much of A-3 must be seeded (C-3). Runnable today over the live query path or directly via `tpcds_readonly`.
2. **Q-3 residue** — target demo date vs D3 wave-4/6 bring-up + Iris-phase state; pick the arc's degrade path accordingly.
3. Bora's read on the leans above, esp. A-1 shape and whether the composite arc is the right ambition level for 25–30 min.
