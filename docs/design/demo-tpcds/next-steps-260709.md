# Demo-TPCDS — next steps (session handoff, updated 2026-07-09 post-wrap-up)

**The design effort is COMPLETE.** A–F 🟢, SWEEP-1 done, surgery landed (redate + hartland
recon baseline committed, pre-seed dump stashed), and the wrap-up deliverables are written:

- **`demo-transcript.md`** — **the design artifact** (W-1: the transcript IS the design).
  Real hartland baseline numbers; ⟨R0⟩ marks the seed-dependent values; Appendix A =
  requirements rollup for /planning; Appendix B = baselines + Q-4 arithmetic guidance.
- **`olymp/clusters/hartland/plan-cluster.md`** — the demo-cluster plan (E-1a: overlay
  `hartland`): phases H1 fork/foundation → H2 warehouse CNPG+restore → H3 estate/personas →
  H4 `hartland-query` run-set → H5 demo ops + E-5 bar; gates G1–G5.
- `07-f-script.md` stays the presenter-operations companion (timing/fallbacks/rehearsal);
  dialogue canonical in the transcript, synced at R0.

## Next: /planning task lists

Kantheon-side (from transcript Appendix A): **`Collite/hartland` repo bootstrap** (model,
15 queries + Proteus CASE-sum goldens, both Shems, prompts — Q-9/Q-10 resolve here),
**seed scripts** (S1–S4 + naming UPDATEs; magnitude per Q-4 guidance; → final demo dump →
`tpcds-staging/hartland/`), **`hartland-query` fixtures/oracle rows**, rehearsal fixtures.
Olymp-side: execute `plan-cluster.md` (H1 can start as soon as Q-12/G5 hardware is picked;
H2 restores the pre-seed dump without waiting).

## Open questions carried

Q-4 (magnitudes — guidance locked in S-8a/App. B; decide at seed build, freeze at R0) ·
Q-9 (Ariadne git-source config — resolves at plan-cluster H3.1) · Q-10 (hartland repo tree —
resolves at repo bootstrap) · Q-12 (showcase hardware — gates olymp H1).
Resolved this session: ~~Q-6~~ (r13 clean; Memphis DC = the ex-NULL warehouse, S-8a).
