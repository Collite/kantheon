# C — Data & grounding: recon findings + options

Status: 🟢 **converged 2026-07-09** — C-1 re-base **+23y** (2002→2025), C-2 SF1, C-3 seed spec v2 below, C-4 **Hartland Stores** identity, C-5 α′. Residue: Q-4 magnitude tuning after r13. Findings F-1..F-9 retained below as the evidence record.

## Recon findings (F-1..F-9)

- **F-1 — The data is a flat canvas.** Channel revenue is constant 1998–2002 (catalog ~$727M ±0.3%/yr, store ~$1.02B ±1%, web ~$366M ±1.5%). Category×channel flat; buyer age pinned at 45.0 every channel every year; income mix uniform; per-year channel overlap constant (r01/r05/r07/r10). **There is no natural slump, no web growth, no migration — every narrative signal must be seeded (confirms C-3β).** The upside: any seed we inject is unmistakable, and every red-herring hypothesis branch dies on *genuinely flat* evidence.
- **F-2 — One real gift: strong, clean seasonality.** All channels, every year: Jan–Jul base, Aug–Oct ≈ 2.2× step, Nov–Dec ≈ 3.4× peak (r02). Perfect substrate for the Metis beats (seasonal fit + holiday forecast will look credible) and it gives the arc a natural clock — *the story happens heading into / just after the holiday season*.
- **F-3 — Returns are steady and usable.** ~5% of revenue in every channel (1998 is a half-volume ramp-in artifact — returns lag sales; 2003 is a tail). Reason mix is near-uniform across 35 reasons (r03/r04), which means a seeded skew on one reason will pop. The reason labels include demo-ready strings — “Did not get it on time”, “Package was damaged”, “Parts missing”, “Stopped working” — *“Did not get it on time” pairs exactly with a fulfillment/stockout root cause.* ~10 of 35 are “reason NN” placeholders (see F-6).
- **F-4 — Stockouts are naturally near-zero.** 0.09–0.10% of weekly item×warehouse snapshots are zero, isolated single weeks, no streaks; 5 warehouses, each ~440 zero-rows/yr spread over ~430 items (r08a/b). **A seeded multi-week zero streak will be unambiguous**; r08 is the calibration baseline for “how loud”.
- **F-5 — Geography quirk: all 12 stores are in Tennessee** (SF1; r06a). Store-side regional stories are impossible — but customer geography for catalog/web spans all states with stable per-state revenue (r06b). Either run regional cuts on *customer* states, or embrace it as lore: **a Tennessee-based retailer with nationwide catalog + online reach** (mail-order heritage → e-commerce; very US-authentic).
- **F-6 — Naming needs curation.** Warehouse names are dsdgen gibberish (“Bad cards must make.”, “Doors canno”, one NULL); ~10/35 reasons are placeholders. Categories are clean (Books, Children, Electronics, Home, Jewelry, Men, Music, Shoes, Sports, Women). C-4 must cover: brand, warehouse names, store names, placeholder reasons. Concrete scope for C-5.
- **F-7 — Promo linkage is dense (97.7–100% of lines carry a promo_sk; r09).** “Promo participation” is not an axis. Promo narratives must use *specific* promotions (date windows, `p_discount_active`, channel flags) — or the win-back promotion is simply a *scenario input* to Metis, no data change needed.
- **F-8 — Metric trap: `net_profit` is structurally negative** at line level (store −44% of revenue — dsdgen arithmetic, not a story). The demo speaks **revenue** (`ext_sales_price`) and quantities; margin language only if we deliberately define it. (P-3: no number we can't defend.)
- **F-9 — Date span**: facts 1998-01-01 → 2003-01-08 (a one-week 2003 tail; inventory ends 2002-12-26); ~4.5% of store_sales rows have NULL sold-date (loader/generator artifact — model + queries must tolerate it).

## C-1 — Date grounding

| Opt | Approach | Buys | Costs |
|---|---|---|---|
| α | **Native dates; demo-present = early Jan 2003** — "we just closed the holiday season" | Zero data surgery; the 2003-01 tail *is* "this week"; F-2 seasonality fresh in frame; honest | Dates on screen read 1998–2002 (visibly historical) |
| β | Re-base +24y at load (2002→2026) | Screens read "now" | Weekday/holiday drift vs date_dim flags; every doc/oracle offset; re-load churn |
| γ | DM refresh drip-feed ("living warehouse") | Hebe weekly briefings see *new* data; strongest illusion | Operationally heaviest; refresh sets must be curated to preserve seeds |

*Lean: α for v1 of the demo — "our dataset is the retailer's 1998–2002 history, we stand at Jan 2003" is one presenter sentence, and it makes the holiday-forecast close natural. γ parked as a v1.1 upgrade for the Hebe beat (control-room parking lot).*

## C-2 — Scale factor

*Lean: **SF1 as loaded** (GI-3). Latency on stage beats bulk; SF1 numbers ($1B store channel) are plenty "real". Revisit only if query latency is so fast it looks canned.*

## C-3 — Seeding spec **v2** (supersedes the v1 draft; incorporates Q-5 resolution + C-4 identity)

**The Memphis DC Meltdown (H2 2025).** One authored ground truth; every other hypothesis dies on genuinely flat evidence. Key change vs v1: the incident is **warehouse-wide, all categories** — a per-category restriction made the effect arithmetically too quiet (2 categories ≈ 20% of one warehouse's ≈20% share ⇒ <1% channel impact), and a whole-DC failure is more real anyway (fire / botched WMS migration). Q-5 thereby resolved; the regional cut becomes a *dying* branch (drop spread evenly — "not regional"), which drops seed complexity.

Story: Hartland's **Marketplace** channel (3P sellers, fulfilled-by-Hartland) ships from 5 DCs; the Marketplace fulfillment pipeline is warehouse-pinned, while **Web** auto-reroutes across DCs. Memphis DC melts down late July 2025 → Marketplace order fulfillment collapses through the autumn ramp into the holiday peak; Web is unaffected (rerouting — the lore covers why only one channel suffers).

Seeds (all on the re-dated instance; idempotent, deterministic — selections keyed on `mod(hash)` of stable ids, not random()):

1. **S1 — inventory root cause:** `inv_quantity_on_hand → 0` at Memphis DC for ~70% of items, **weeks 31–47 of 2025** (late Jul → mid-Nov). Baseline noise is 0.1% isolated zeros (r08) — a 17-week streak is unambiguous.
2. **S2 — the slump:** delete ~60% of `catalog_sales` lines with `cs_warehouse_sk = Memphis` sold in **weeks 32–48 of 2025**, plus their matching `catalog_returns` rows (no orphan returns). Expected (Memphis ≈ 1/5 of lines, r13 to confirm): **Marketplace H2-2025 ≈ −10..12% YoY**, Nov ≈ −12% against four flat prior years — a screaming KPI tile on an otherwise flat canvas.
3. **S3 — corroboration:** for *surviving* Memphis-shipped `catalog_returns` in weeks 33–49/2025, reassign ~40% of reasons to **“Did not get it on time”** (r_reason lookup). Near-uniform baseline (r04) makes the skew pop.
4. **S4 — red herrings stay natural (no action):** Web flat (kills cannibalization), demographics flat (kills segment shift), promo dense as always (kills promo starvation), Stores untouched (isolates the channel), geography even (kills "regional problem").

Optional S5 (nuance, default OFF): +small Web uptick in Nov–Dec 2025 ("customers switched to Web when Marketplace orders slipped") — gives the cannibalization branch a *partial* truth to quantify. Decide during script build; adds rehearsal surface.

Execution: `surgery/02-seed-incident` scripts (built in the TTR-M/cluster phase), run **after** re-date, **before** the final demo dump. Calibration baselines: post-redate re-run of r01/r02/r03/r04/r08 + **r13** (warehouse share, added to the battery — Q-6).

## C-4 — Retailer identity — **DECIDED 2026-07-09 (Bora)**

**Hartland Stores.** Tennessee-headquartered retailer; ~6 stores (all TN — embraces F-5); nationwide reach through two more channels. Channel labels (TTR-M layer; physical tables unchanged):

| Physical | Label | Lore |
|---|---|---|
| `store_*` | **Stores** | the 6 Tennessee stores |
| `web_*` | **Web** | hartland.com — Hartland's own e-commerce; fulfills from all 5 DCs with auto-rerouting |
| `catalog_*` | **Marketplace** | third-party sellers on Hartland's platform, **fulfilled by Hartland** from its DCs; fulfillment pipeline is warehouse-pinned (call centers = seller/order support) |

The fulfilled-by-Hartland framing is what makes the Meltdown coherent: a DC failure hits exactly Marketplace while Web reroutes around it.

**Warehouses (5, display-name UPDATEs per C-5α′):** *Memphis DC* (the incident), *Columbus DC* (OH), *Dallas DC* (TX), *Reno DC* (NV), *Allentown DC* (PA) — classic US distribution geography, one per region. The NULL-named warehouse gets one of these too. **Stores:** TN towns (Nashville, Memphis, Knoxville, Chattanooga, Franklin, Murfreesboro). **Placeholder return reasons** ("reason NN", ~10 of 35): relabel with plausible retail reasons (e.g. "Changed my mind", "Better price found online", "Arrived too late for the occasion", …) — final list at script build.

## C-5 — Rename layer

*Lean (unchanged): α — TTR-M labels/synonyms; physical schema stays canonical TPC-DS. F-6's gibberish warehouse names are the test case: `w_warehouse_name` values can also be UPDATEd physically (5 rows, zero blast radius, survives in psql screenshots) — a pragmatic **α′: model labels for structure, tiny dim-table UPDATEs for display values** (warehouse/store names, placeholder reasons). Fact tables are never touched except by the C-3 seed.*

## Open

- **Q-4**: exact seed magnitudes — tune against "screaming KPI tile, not a catastrophe" once r13 + post-redate baselines are in.
- ~~**Q-5**: which categories/regions~~ — RESOLVED in seed spec v2: warehouse-wide, all categories; regional cut is a dying branch.
- **Q-6**: `cs_warehouse_sk` slice cleanliness — **r13 added to the recon battery**; run with the post-redate baseline refresh.
- ~~**Q-7**: offset~~ — RESOLVED: **+23** (2002→2025; present inside 2026).
