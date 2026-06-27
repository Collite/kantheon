# Golem Phase 3 · Stage 3.3 — diff harness + corpus

> **Arc.** Golem Phase 3 (conversational surface, **closes Phase 3**). **Branch.** `feat/golem-p3-s3.3-diff-harness`.
> **Companions.** [`plan.md`](./plan.md) §5 Stage 3.3, [`../../../architecture/golem/contracts.md`](../../../architecture/golem/contracts.md) §8 (diff-harness), [`../../../architecture/golem/architecture.md`](../../../architecture/golem/architecture.md) §4. envelope-render + `envelope-ts` for envelope parsing; the legacy new-golem `/v2` as the reference implementation.
> **Goal.** A replay diff-harness compares Kotlin-Golem envelopes against recorded new-golem v2 envelopes across ≥30 conversations; divergences classified and bugs fixed; **tag `golem/v0.2.0`**.
>
> **Note.** This is the parity-confidence gate before the Phase-4 cutover. It does **not** require a live Iris — it replays recorded conversations against the Golem `/v1/answer` surface and the v2 reference envelopes.

## Pre-flight

- Stages 3.1 + 3.2 closed — the full conversational surface (format + chips + drilldowns + clarification/resume + selection) is in place.
- **Bora-owned content:** ≥30 representative recorded v2 conversations picked (the §8 corpus; spans plan sources × format kinds × clarification/selection). Claude tools the capture; Bora picks the sessions.
- The deleted-upstream features are correctly **absent** (live-log stream, typed sort/filter/paginate) so the harness does not flag their absence as a divergence.

## Tasks

- [ ] **T1 — capture corpus (Bora picks; Claude tools).** Capture ≥30 recorded v2 conversations into `agents/golem/eval/corpus/` — request + recorded v2 SSE/envelope per turn, spanning the five plan sources, all format kinds (table/chart/markdown/plaintext), chips, drilldowns, clarification (all 4 kinds), and row-detail selection. Normalise to the §8 fixture shape.
- [ ] **T2 — `eval/diff-harness` replay CLI (`just eval-golem`).** A CLI that replays each corpus turn through Golem `/v1/answer` (mocked platform / recorded upstream responses), parses both envelopes via `envelope-ts`/the KT bindings, and emits a structured diff (kind, blocks, chips, drilldowns, current_view, provenance-absence-tolerant). *(Reference: contracts §8; EXAMPLES.md CLI/test harness patterns.)*
- [ ] **T3 — first parity run + classify divergences.** Run the harness; classify each divergence as **bug** (Golem wrong), **acceptable** (intended kantheon difference — e.g. typed `TableDetails`, provenance addition, fixed event set), or **v2-bug** (the reference was wrong). Produce a parity report.
- [ ] **T4 — fix bugs + document acceptable divergences.** Fix the `bug`-class divergences; record the `acceptable`/`v2-bug` set in the parity report with rationale (so Phase-4 soak does not re-litigate them).
- [ ] **T5 — tag.** Re-run to green-or-acceptable across the corpus; commit the parity report; **tag `golem/v0.2.0`**.

> Order note: T1 (corpus) is Bora-gated content and front-loads; T2 (harness) can be built in parallel against a handful of seed turns; T3/T4 (run + fix) iterate; T5 tags. Stage runs to 5 tasks.

## DONE

## Status (2026-06-24) — harness code-complete

- [~] **T1 — corpus.** Seed turns (`eval/corpus/conversations/seed.jsonl`: table / typed-numeric / chart-on-hint) committed so the harness has coverage. **The ≥30 curated real-v2 sessions are Bora-owned** (§8) — drop more JSONL into the dir; the harness globs it. Shape + capture/normalisation notes in `eval/corpus/README.md`.
- [x] **T2 — `eval/diff-harness` replay CLI (`just eval-golem`).** `CorpusReplay` drives each corpus turn through the Kotlin executor + format pipeline (in-process, recorded upstream rows, no live services); `EnvelopeDiff` diffs field-wise (kind / content / chip+drilldown sources / current_view.total_rows / plan_source / typed-columns), writes `eval/diff-harness/report.md`. `just eval-golem` runs `DiffHarnessSpec`.
- [x] **T3 — first parity run + classify.** Divergences classified `BUG | ACCEPTABLE | V2_BUG`; kantheon's intended additions (Δ5 typed `TableDetails`, the `investigate` chip source, provenance) are `ACCEPTABLE`. The seed run is **0 bug-class** (one ACCEPTABLE typed-columns delta on the numeric turn).
- [x] **T4 — fix bugs + document acceptable.** No bug-class divergences on the seeds; the acceptable set is encoded in `EnvelopeDiff`'s classification + surfaced in the report.
- [ ] **T5 — tag `golem/v0.2.0`** (Bora-authorised). Re-run `just eval-golem` after the real corpus lands; tag once it's 0-bug-class.

## DONE

Diff-harness code-complete: `CorpusReplay` + `EnvelopeDiff` + `just eval-golem` + committed seed corpus + generated parity report (0 bug-class). **`golem/v0.2.0` tag + the ≥30-session curated corpus deferred to Bora** (release action + Bora-owned content). Phase 3 conversational surface is code-complete.
