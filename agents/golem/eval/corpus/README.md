# Golem parity corpus (diff-harness, S3.3)

Recorded new-golem **v2** conversations replayed against the Kotlin Golem to prove
envelope parity before the Phase-4 cutover (contracts §8). `just eval-golem` replays
every turn and writes the report to `../../build/diff-harness/report.md` (untracked); the
gate is **zero `BUG`-class divergences** — kantheon's intended additions (typed
`TableDetails`, `InvestigateChip`, provenance, the fixed SSE set) are tolerated as
`ACCEPTABLE`. The gate runs as an ordinary unit spec (`DiffHarnessSpec`), so it is **already
enforced by `just test-all` / CI**, not deferred to Phase 4.

The diff is **value-level** on `content` (per-row, per-cell; real JSON numbers compared with a
float tolerance, strings exactly) plus render kind, `plan_source`, `current_view.total_rows`,
chip/drilldown sources, and column-directive count (fewer than v2 = a lost-directive BUG).

## Status

- **Seed turns (`conversations/seed.jsonl`)** — committed; exercise table / typed-numeric /
  chart-on-hint so the harness has coverage out of the box. This is a **seed smoke gate** until
  the curated corpus below lands — green here is not yet full parity.
- **The ≥30-conversation curated corpus is Bora-owned** (§8 open items): pick representative
  real v2 sessions spanning the five plan sources, all format kinds (table/chart/markdown/
  plaintext), chips, drilldowns, all four clarification kinds, and row-detail selection. Drop
  them in `conversations/` as JSONL — the harness globs the directory.

## Line shape (one JSON object per line)

```
{ "name": "...",
  "request": { "question": "...", "golemId": "golem-erp" },
  "model":   { "patterns": [ { "id": "...", "sourceText": "...", "resultKindHint": "",
                               "params": [ { "name": "...", "type": "varchar", "optional": false } ] } ] },
  "plan":    <MiniPlan proto-JSON>,          // the composed plan to execute (deterministic; no LLM)
  "rows":    [ { ... } ], "rowCount": N,     // the recorded upstream result
  "expectedEnvelope": <FormatEnvelope proto-JSON, kantheon-normalised> }
```

> Capture note: record the **normalised** (envelope/v1) expected envelope — `content_json` as a
> JSON-array string, camelCase enums. The v2→v1 normalisation (the `content` array → `content_json`
> string lift, `total_rows` placement) mirrors `envelope-ts`'s `normalizeEnvelopeJson`.
