# Q-1 data recon — what does SF1 `tpc-ds-1g` actually contain?

Purpose: establish which trends/skews exist **naturally** in the generated data, so C-3 seeding
(decision: seeded root causes, 2026-07-09) injects only what the story needs and rides whatever
dsdgen already provides. Every arc beat and every TTR-M label we author downstream should be
grounded in these numbers, not in TPC-DS folklore.

## Run

```sh
./run-recon.sh            # kube-context defaults to dsk
git add results/ && git commit -m "demo-tpcds: Q-1 recon results"
```

Read-only (`SET ROLE tpcds_readonly`), aggregates only, small CSVs; a few minutes at SF1.
Same access path as the WS-T1 runbook (kubectl exec → `test-pg-1` local socket).

## What each query informs

| CSV | Question it answers | Feeds |
|---|---|---|
| r00_rowcounts | sanity vs the T1 oracle | trust in everything below |
| r01_channel_year | do channels trend? is there a *natural* catalog slump / web growth? | A-3 beat 2, C-3 seeding scope |
| r02_channel_month | seasonality shape + amplitude per channel | Metis forecast beat (SARIMAX seasonality), beat 6 |
| r03_returns_year | returns volume per channel/year | "returns spike" hypothesis branch |
| r04_reason_mix | return-reason distribution (35 reasons) | hypothesis-tree evidence texture; governance of the reason dim in TTR-M |
| r05_category_channel_year | which categories move where; YoY decliners | protagonist's category choice; drill beats |
| r06a/r06b | geography per channel (store-state vs customer-state lens) | "the Midwest slump" regional framing |
| r07_channel_overlap | per-year customer channel-mix (8 combos) | cannibalization hypothesis substrate (the crown-jewel joins) |
| r08a/r08b | natural zero-inventory incidence, per warehouse | **the seeded stockout design** — how loud must our seed be to stand out? |
| r09_promo_share | promo participation per channel/year | promo-starvation hypothesis + what-if beat |
| r10a/r10b | buyer age trend + income mix, catalog vs web | demographic-shift hypothesis |
| r11_hygiene | fact date spans, null rates, category/brand cardinalities | TTR-M model authoring (D), C-1 date grounding |
| r12_holiday_share | d_holiday revenue share | seasonality narrative; holiday-forecast beat |

## After the run

The design session analyzes `results/` and converges: C-3 seeding spec (what to inject, where,
how loud), A-3 concrete parameters (which category, which region, which years), C-1 date
grounding, and the D-2 preferred-query list. Analysis lands as `03-c-data-options.md` +
decisions in the control-room log.
