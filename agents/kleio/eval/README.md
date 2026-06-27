# Kleio eval corpus (P5 Stage 5.3)

The eval gate for the grounded turn. Three properties, asserted by
`KleioEvalSpec` (the gate runs in `test-all`):

1. **Grounded-citation faithfulness.** Every citation in a `GroundedResponse`
   points at a node that was actually retrieved. A model that cites an id it was
   not given has that citation dropped at render — the response never claims a
   source it didn't read (contracts §5).
2. **NO_GROUNDING honesty.** A question with nothing retrievable above
   `min-score` ends `STATUS_NO_GROUNDING` with an honest CALLOUT and **zero**
   `sources_used` — Kleio refuses rather than inventing an answer.
3. **Mart-scope leakage (negatives).** Retrieval is RLS-scoped to the caller's
   visible mart (P4 S4.2); Kleio can only cite what `library.getContext` returned,
   so a turn can never surface another mart's content. The negative cases assert
   that out-of-mart ids never appear in `sources_used`.

The corpus is intentionally small + mock-driven (planning-conventions §4); the
live faithfulness eval against real Prometheus + the in-K3s e2e are the
integration suite. Faithfulness/NO_GROUNDING also covered structurally by
`KleioStrategySpec`.
