# Demo-TPCDS — next steps (session handoff, updated 2026-07-09 post-SWEEP)

**Where the effort stands: ALL workstreams A–F 🟢 and the consolidation sweep is DONE**
(SWEEP-1: S-1..S-14 in the control-room log — names, fixtures, query namespace, store/DC
naming rules, return-reason list, dump location, coda identity, narrative-date handling,
Dan Whitaker CFO persona, Q-13 → dedicated CNPG). Read `00-control-room.md` first; build-phase
inputs are `05-d` (model + both Shems), `06-e` (showcase cluster), `07-f-script.md` (presenter
script, fallbacks L0–L4, rehearsal ladder R0–R5), `surgery/README.md` (data pipeline + naming
canon).

## Next design session: final deliverables

1. **`design.md`** — audience: the `/planning` session. Must carry: the full decision log
   (A/B/C/D/E/F + SWEEP-1), seed spec v2, the three build specs, rehearsal-created artifacts
   (Rehearsal dashboard, L4 recording, R0 number-freeze gate), parking lot + deferred items.
2. **`detailed-design.md`** — the exhaustive human-readable write-up.
3. Then **/planning** → task lists: hartland repo bootstrap (both Shems), seed + naming
   scripts, showcase bring-up (incl. dedicated warehouse CNPG per S-14), `hartland-query`
   run-set, rehearsal fixtures.

## Bora-side ops queue (unchanged, independent)

1. Pristine dump of `tpc-ds-1g` → `createdb hartland` → restore (surgery/README steps 1–2).
2. `bash surgery/run-redate.sh` (defaults dsk/23/hartland).
3. `bash recon/run-recon.sh dsk hartland` → commit results as the pre-seed baseline (+ r13).
4. `pg_dump` the hartland demo dump → `tpcds-staging/hartland/` (S-10).

## Open questions carried

Q-4 (seed magnitudes — gates `07-f` R0 number-freeze) · Q-6 (r13 warehouse slice — with
post-redate baseline) · Q-9 (Ariadne git-source config) · Q-10 (hartland repo tree) ·
Q-12 (showcase hardware — Bora/ops).
Resolved 2026-07-09: ~~Q-8~~ (F-1) · ~~ε coda~~ (F-2) · ~~S5~~ (F-3, parked) · ~~Q-13~~ (S-14).
