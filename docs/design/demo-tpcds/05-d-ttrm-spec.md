# D — TTR-M model spec (golem-hartland)

Status: 🟢 **converged 2026-07-09** (D-1, D-4, D-6a, D-7 in the control-room log). This document is the **input spec for the TTR-M build** — the deliverable Bora named in the session brief. It derives entirely from the converged A/B/C: every entity earns its place from a beat or a hypothesis branch; every preferred query backs a scripted moment (P-2).

## The three layers

1. **`Collite/hartland` repo (NEW — D-7, Bora).** The ai-models-analog for the demo world, mirroring ai-models' layout: agent definition (`agents/hartland.yaml`-shaped), the TTR model packages, area defs, prompt seeds. Ariadne on the demo cluster consumes it as its model Git source. *This deliberately exercises the production onboarding path — model repo → Ariadne → assembled Shem — and is a demo talking point in itself.*
2. **kantheon Shem overlay bundle** — `agents/golem/shems/golem-hartland/` (shem.yaml overlay + mounted prompts), per the assembled-Shem canon (golem-ucetnictvi precedent, prompts-with-the-Shem).
3. **Dim display-value UPDATEs** — C-5α′, already specced in `surgery/` (warehouse/store names, placeholder reasons). The model's labels and the dim values must agree ("Memphis DC" in both).

The kantheon in-repo `model-ttr/tpcds/` seed (7 entities, 4 `q.tpcds.*` queries) **stays frozen as the integration-context fixture** — the hartland model is a separate, richer artefact; no dual-source drift because the seed never grows.

## Area (D-4)

`AreaDef hartland` → package(s) of the hartland model. Router description (English, Shem overlay carries the final wording): *"Sales, returns, inventory and customer analytics for Hartland Stores — store, web and marketplace channels."* Role: `kantheon-area-hartland` (PD-8 convention).

## Entity roster (D-5)

**In (19):**

| Entity | Label | Earns its place by |
|---|---|---|
| store_sales | Store Sales | Stores channel; beats 1–2 |
| web_sales | Web Orders | Web channel; cannibalization branch |
| catalog_sales | Marketplace Orders | **the** channel of the incident; beats 1–5 |
| store_returns / web_returns / catalog_returns | … Returns | returns-rate + reason evidence (S3) |
| inventory | Inventory Snapshots (weekly, DC-level) | the smoking gun (S1) |
| date_dim | Calendar | everything; holiday/seasonality |
| item | Products | category/brand drills |
| customer | Customers | overlap + demographics branches |
| customer_address | Customer Addresses | regional dying branch |
| customer_demographics / household_demographics / income_band | Demographics / Income Bands | demographic dying branch |
| store | Stores | Stores channel dim (6 TN stores) |
| warehouse | Distribution Centers | Memphis DC — beat 3 pivot |
| reason | Return Reasons | "Did not get it on time" |
| promotion | Promotions | promo dying branch + what-if lore |
| call_center | Seller Support Centers | Marketplace lore (light labeling only) |

**Out (recorded):** time_dim (no time-of-day beat), web_site/web_page/catalog_page (no beat), ship_mode (revisit only if the late-delivery story needs it), dbgen_version.

## Naming & synonym layer (D-6)

Channel vocabulary is **model-carried** (GI-6/C-5α′): catalog_* ⇒ *Marketplace* ("3P", "third-party", "marketplace sellers", "fulfilled by Hartland"), web_* ⇒ *Web* ("online", "hartland.com", "e-shop"), store_* ⇒ *Stores* ("brick and mortar", "retail stores", "TN stores"). Other synonym families: revenue ("sales", "turnover") → `ext_sales_price` sums; DC ("warehouse", "fulfillment center", "distribution center"); stockout ("out of stock", "zero on hand", "availability"); SKU/product/article → item; return rate ("refunds", "RMA"). Category display names are already clean (F-6).

**Measures policy (D-6a — Bora):** the model exposes **revenue (`ext_sales_price`), quantity, order/line counts, return amounts**. `net_profit`, wholesale/list-cost and discount-internals columns are **excluded from the model** — F-8 makes them nonsense-generators; margin questions gap gracefully ("profitability isn't modeled"). Physical columns untouched.

## D-2 — Preferred query set

Namespace: **`q.hartland.*`** (S-5 — mirrors `q.tpcds.*`; the table below uses bare names). Rule (P-2): **every scripted Golem answer hits a pattern plan**; free-SQL is reserved for its one SHOULD moment. Stacks (Filter/Project/Sort) cover chip amendments ("only Marketplace", "just 2025") without new queries. Constraint: all queries stay within **proven Proteus unparse shapes** (join+group / agg+ORDER/LIMIT / window / CTE+UNION) — conditional aggregation via CASE-sums, **no FILTER clause** until a golden spec covers it. Year params default into 2021–2026 (post-redate).

| # | Query | Shape | Params | Serves |
|---|---|---|---|---|
| 1 | `channel_revenue_monthly` | join+group | channel?, year_from, year_to | beats 1–2 charts; Metis input series |
| 2 | `channel_revenue_yoy` | CTE compare | year, prior_year | the briefing KPI (−11% tile) |
| 3 | `category_revenue` | join+group | channel, year | category drill (drop spans categories) |
| 4 | `marketplace_revenue_by_warehouse` | join+group | year_from, year_to | **beat-3 pivot** — Memphis stands out |
| 5 | `top_items_by_revenue` | agg+ORDER/LIMIT | channel?, year, limit | drill (seed heritage) |
| 6 | `returns_by_reason` | join+group | channel, year, quarter? | S3 evidence ("Did not get it on time") |
| 7 | `returns_rate_by_channel` | CTE (sales⋈returns) | year | returns branch |
| 8 | `warehouse_stockout_weeks` | join+group (CASE-sum) | warehouse?, year | **the smoking gun** (17-week streak) |
| 9 | `inventory_on_hand_series` | join+group | warehouse, year, category? | evidence chart behind #8 |
| 10 | `customer_channel_overlap` | CTE + bool_or | year | cannibalization branch (dies: flat) |
| 11 | `revenue_by_customer_state` | join+group | channel, year | regional branch (dies: even) |
| 12 | `buyer_age_profile` | join+group | channel, year | demographic branch (dies: 45.0 flat) |
| 13 | `promo_share` | CASE-sum | channel, year | promo branch (dies: dense as always) |
| 14 | `store_sales_by_month` | join+group | — (seed heritage) | Stores-channel texture |
| 15 | `customer_running_total` | **window** | — (seed heritage) | top-customer drill; window coverage |

(15 = 13 new + 2 carried; the kantheon `q.tpcds.*` four remain frozen fixtures.)

## Shem overlay (golem-hartland)

`agents/golem/shems/golem-hartland/shem.yaml`: `agent_id: golem-hartland` · display name / DomainCard **"Hartland Analytics"** (S-6) · area `hartland` · `visibility_roles: [kantheon-area-hartland]` · `description_for_router` (per Area section) · `example_questions` (the Discover chips, all rehearsed beats): "How did Marketplace revenue develop in 2025?" / "Which categories drove the H2 2025 drop?" / "What is our return rate by channel?" / "Which items were out of stock at Memphis DC in October 2025?" / "Compare Web and Marketplace revenue, 2024 vs 2025" / "Top products by revenue last year" · `counter_examples` (the B-2 gap ammunition): supplier contract terms, employee payroll, competitor pricing · `locale_defaults`: en-US, USD. Prompts: `prompts/en/` mounted with the bundle (cs optional, unused per FI-4). Template constants per canon (AREA_QA, theseus.query/compile, render.table/chart, INTERACTIVE).

## Shem overlay #2 — golem-hartland-finance (F-1/Q-8 addendum, 2026-07-09)

The governance-cameo satellite Shem (control-room **F-1**): `agents/golem/shems/golem-hartland-finance/shem.yaml` · `agent_id: golem-hartland-finance` · display name / DomainCard **"Hartland Finance"** (S-6) · same area `hartland`, same model, **no new data** · `visibility_roles: [kantheon-role-finance]` (the CFO persona only — structurally unroutable for Maya, so B-2α holds) · `description_for_router`: *"Financial analytics for Hartland Stores — returns exposure, revenue rollups."* · preferred-query subset: `channel_revenue_yoy`, `returns_rate_by_channel` (+ optionally `returns_by_reason`) · `example_questions`: "What is our total returns exposure in dollars for 2025?" / "Revenue by channel, 2025 vs 2024" · `counter_examples` shared with the main Shem · D-6a stands: no profitability anywhere. Lives in the `Collite/hartland` repo beside the main agent def (a second pass through the D-7 onboarding path — itself a talking point).

Acceptance additions: second Shem assembles + registers; visible to CFO / absent for Maya in Discover **and** in Themis routing; its example_questions hit pattern plans.

## Acceptance criteria (build-phase DONE bar)

Model loads clean (ModelTtrLoadSpec-style completeness against the hartland Git source) · `ResolveArea("hartland")` green · `ListQueries` returns the 15 with declared params on `pg-tpcds` · Proteus golden PG unparse per query (new goldens for CASE-sum shapes) · every example_question resolves to a pattern plan in rehearsal · labels verified against the post-surgery dim values (Memphis DC etc.) · no `net_profit`/cost column reachable through the model.

## Open (build-time, not design)

- **Q-9**: Ariadne source config on the demo cluster — hartland repo as *the* Git source vs alongside ai-models (single- vs multi-source support to verify).
- **Q-10**: exact hartland-repo tree — mirror ai-models at build time (agents/, model dirs, prompts/golem seed location).
- **Q-11**: `q.tpcds.*` year-literal fixups post-redate (surgery ripple checklist) — owned by the deploy-test arc, cross-referenced here.
