# v1 implementation — archive

Historical cross-cutting docs, superseded by [`../master-plan.md`](../master-plan.md) (the two-stream orchestration plan, 2026-06-14). Kept for provenance; **not** authoritative. Do not link to these from live docs.

| File | What it was | Superseded by |
|---|---|---|
| `next-steps.md` | 2026-05-12 resumption pointer; cross-arc "what's done / prepared / remaining". | `master-plan.md` — §"Status board" + §"Resumption pointer". |
| `handover-2026-06-12-architecture-review.md` | End-of-day state handover: Hebe arrival, 15 PD resolutions, contract-delta table, 10-point review checklist. | Folded into the arc plans + `master-plan.md`. |
| `handover-2026-06-12-aip-migration.md` | Handover note for the ai-platform → kantheon migration (pre-"fork" reframe). | The fork arc (`../fork/plan.md`) + `master-plan.md`. |
| `review-2026-06-12-v1-cohesion-findings.md` | Cohesion review findings D1–D8 (now locked in `CLAUDE.md` / `kantheon-architecture.md`). | Decisions live in the architecture docs; memory `kantheon_cohesion_review_2026_06_12`. |
| `aip-v1-status-audit.md` | Claude Code audit of ai-platform v1 state (2026-05-12, rev 2). | ai-platform is maintenance-only since the fork; audit no longer drives kantheon work. |
| `aip-v1-gap-closure-plan.md` | Plan to close ai-platform gaps blocking kantheon bootstrap. CRITICAL items closed 2026-05-15. | The fork made the gap-closure track moot (services copied in, not consumed). |
| `aip-v1-pg-worker-plan.md` | Cross-repo plan for ai-platform `workers/postgres` (Midas P3.2 dependency). | Open item — re-homed as the kantheon **Arges** postgres worker. See `master-plan.md` §"Open items". |
| `aip-v1-status-report-2026-05-12.md` | Earlier (already-superseded) status report. | History only. |
| `aip-v1-status-review-brief.md` | Brief used to produce the status audit. | History only. |

*Archived 2026-06-14 when the master plan replaced the cross-cutting `next-steps`/`aip-*` layer.*
