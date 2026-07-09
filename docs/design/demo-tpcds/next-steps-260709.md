# Demo-TPCDS — next steps (session handoff, updated 2026-07-09 post-F)

**Where the effort stands: ALL workstreams A–F 🟢.** The design phase's substance is done. Read
`00-control-room.md` first; build-phase inputs are `05-d` (TTR-M model + both Shems), `06-e`
(showcase cluster), and now `07-f-script.md` (presenter script, fallback architecture L0–L4,
rehearsal ladder R0–R5).

## Next design session: wrap-up

1. **Consolidation sweep** — batch-ratify the accumulated micro-decisions (S-n pass over all
   docs: naming, placeholder return-reason list, routine name "Monday channel health brief",
   dashboard name "Channel Health", persona details, Rehearsal-dashboard fixture, etc.) so no
   small fork is left decided-by-drift.
2. **`design.md`** — audience: the `/planning` session. Must carry: every decision (log),
   the seed spec v2, the three build specs (05-d incl. finance Shem, 06-e, 07-f), fallback/
   rehearsal requirements that create build artifacts (Rehearsal dashboard, L4 recording,
   R0 number-freeze gate), deferred items + parking lot.
3. **`detailed-design.md`** — the exhaustive human-readable write-up.
4. Then **/planning** → task lists: hartland repo bootstrap (both Shems), seed scripts,
   showcase bring-up, `hartland-query` run-set, rehearsal-fixture creation.

## Bora-side ops queue (unchanged, independent)

1. Pristine dump of `tpc-ds-1g` → `createdb hartland` → restore (surgery/README steps 1–2).
2. `bash surgery/run-redate.sh` (defaults dsk/23/hartland).
3. `bash recon/run-recon.sh dsk hartland` → commit results as the pre-seed baseline (+ r13).
4. `pg_dump` the hartland demo dump; stash in Seaweed.

## Open questions carried

Q-4 (seed magnitudes — gates `07-f` R0 number-freeze) · Q-6 (r13 warehouse slice — with
post-redate baseline) · Q-9 (Ariadne git-source config) · Q-10 (hartland repo tree) ·
Q-12 (showcase hardware) · Q-13 (warehouse PG placement).
Resolved this session: ~~Q-8~~ (F-1), ~~ε coda~~ (F-2), ~~S5~~ (F-3 — parked with revisit
condition).
