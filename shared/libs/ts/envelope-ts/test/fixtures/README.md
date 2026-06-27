# Golden-sample fixtures — FormatEnvelope v2

These 12 JSON files are **FormatEnvelope v2** envelopes (the agents-fe Stage 07-B
wire), the compatibility corpus for the envelope/v1 golden-sample gate
(`test/golden.spec.ts` and the Kotlin `EnvelopeGoldenSpec`). Each carries all
eight required v2 fields and exercises a distinct slice of the contract:

| File | Exercises |
|---|---|
| 01-table-basic | table: headers, columns (printf `format`), paging, `content` rows |
| 02-table-sorted-filtered | table: `alternateColors`, `sort`, `filters` (native `value`) |
| 03-chart-intent | chart: `intent` + nested `vega_lite_spec` object |
| 04-chart-loose | chart: Stage-06 loose shape (`series_field`/`series`/`rows`), `content: null` |
| 05-markdown | markdown: `allow_mermaid`, fenced mermaid in `text` |
| 06-plaintext | plaintext |
| 07-chips | all four chip sources + `prefilled_args` |
| 08-clarification-entity | `pending_clarification` kind=`entity_choice` |
| 09-clarification-missing-arg | `pending_clarification` kind=`missing_arg` |
| 10-drilldowns | `drilldowns` row + point |
| 11-entity-context-view | `entity_context` + `current_view` (nested `args` object) |
| 12-error-tail | `error_code` + `warnings` |

## Provenance & refresh discipline

**Source.** Synthesised from the v2 source-of-truth shapes —
`ai-platform/frontends/agents-fe/src/types/envelope.ts` and
`ai-platform/agents/golem/src/api/v2/models.py` — at Iris Stage 1.1. No live
new-golem session was reachable from the dev box at authoring time.

**Refresh.** When a live new-golem `/v2` session is available, **re-record** real
envelopes (the plan pre-flight task) and replace/augment these. The gate is only
as trustworthy as the corpus: synthesised fixtures and the proto both descend
from the same contract doc, so they test *internal* consistency until real
captures land. Re-record on any new-golem envelope change (envelope-ts README owns
this discipline; CI golden job is the tripwire).

`provenance` (PD-9) and `InvestigateChip` (PD-1) are **additive** envelope/v1
fields with no v2 source — deliberately absent here; the gate asserts their
absence is tolerated.
