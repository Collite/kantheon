# Demo-TPCDS data surgery

Ordered, idempotent-guarded scripts that turn the canonical SF1 load into the demo instance.
Decision C-1 (amended 2026-07-09, offset settled same day): re-base all dates **+23 years**
(2002 → 2025; last full year 2025, tail week Jan 2026 — the narrative present sits inside
calendar 2026: *"we just closed 2025"*); then back up so the surgery never has to be repeated.

## Design: why date_dim is not rewritten

`date_dim` spans 1900–2199 and already carries **correct** weekday/holiday/week-seq values for
the target years. Rewriting its rows would break its internal consistency (d_date vs d_dow vs
d_week_seq). Instead `run-redate.sh` **re-points the facts**: every `*_date_sk` column in every
table except `date_dim` jumps by the offset (default **+23** years, per C-1a) via an
old_sk→new_sk map (calendar-exact, `d_date + interval '<offset> years'` — month/day preserved,
so the F-2 seasonality shape is intact). Plain `date`-typed columns (item/store/call_center/
web_site validity windows) shift by the same interval, and `c_birth_year` bumps by the offset
so customer ages stay ~45 relative to the new present. *(SWEEP-1 doc fix: this paragraph
previously said "+24" from the pre-C-1a draft.)*

## Order of operations (E-2 revision, 2026-07-09: surgery targets a separate `hartland` DB — `tpc-ds-1g` stays pristine, integration fixtures never notice)

1. **Pristine dump** of the untouched benchmark DB:
   ```sh
   kubectl --context dsk -n data exec test-pg-1 -c postgres -- \
     pg_dump -Fc -Z6 -d tpc-ds-1g > tpc-ds-1g-pristine-$(date +%Y%m%d).dump
   ```
2. **Create + restore the demo DB:**
   ```sh
   kubectl --context dsk -n data exec test-pg-1 -c postgres -- createdb hartland
   kubectl --context dsk -n data exec -i test-pg-1 -c postgres -- \
     pg_restore -d hartland --no-owner < tpc-ds-1g-pristine-*.dump
   ```
3. **`run-redate.sh [ctx] [offset] [db]`** — defaults `dsk`, `23`, **`hartland`**. Single
   transaction, guarded against double-runs; verification expects sales
   2021-01-02 → 2026-01-08, inventory → 2025-12-25.
4. **Seed scripts** — the Memphis DC Meltdown (`02-seed-*`, built next phase per
   `03-c-data-options.md` spec v2) + dim display-name UPDATEs (Hartland naming), all against
   `hartland`. Baseline refresh: `../recon/run-recon.sh dsk hartland`.
   Naming-UPDATE canon (SWEEP-1): **stores** keyed on `s_store_id` — 6 logical stores, the 12
   rows are SCD versions sharing a name (S-7: Nashville, Memphis, Knoxville, Chattanooga,
   Franklin, Murfreesboro); **warehouses** — Memphis DC = the highest-Marketplace-share
   warehouse per r13, the NULL-named row gets one of the remaining cities (S-8: Columbus,
   Dallas, Reno, Allentown); **placeholder reasons** (S-9, in order, as many as needed):
   Changed my mind · Found a better price · Wrong size · Wrong color · Ordered by mistake ·
   No longer needed · Not as pictured · Unwanted gift · Incompatible with my device ·
   Quality not as expected.
5. **Demo dump** — the versioned artifact the showcase cluster restores from:
   ```sh
   kubectl --context dsk -n data exec test-pg-1 -c postgres -- \
     pg_dump -Fc -Z6 -d hartland > hartland-demo-$(date +%Y%m%d).dump
   ```
   Store in the `tpcds-staging` Seaweed bucket under the `hartland/` prefix (S-10; no new bucket).

## Ripple checklist — mostly VOID by E-2 (tpc-ds-1g pristine ⇒ fixtures untouched)

- ~~Ariadne `q.tpcds.*` year params~~ — void: the seed queries keep targeting pristine
  `tpc-ds-1g` via `pg-tpcds`; nothing moves.
- ~~`tpcds-query` integration context~~ — void: its oracle asserts run against the pristine DB.
- [ ] **recon baselines** — post-surgery reference: `run-recon.sh dsk hartland` (r01/r02/r08
      become the pre-seed baselines for Q-4 magnitude tuning; after seeding, re-run again to
      verify the Meltdown reads as spec'd).
- [ ] **TTR-M hartland model** (05-d) — all example questions/params authored against
      2021–2026 from the start; queries target the **`pg-hartland`** connection (E-2).

## Open

- ~~Q-7 (offset fine-point)~~ — **decided 2026-07-09: +23** (Bora). Script default updated.
- **Q-4 magnitudes** — tune the seed percentages after r13 (warehouse share) confirms;
  see `03-c-data-options.md` seed spec v2.
