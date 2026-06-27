Revise the investigation plan based on what we learned.

Trigger: {{trigger}}
Current hypotheses: {{hypotheses}}
{{feedback}}

Choose ONE action and return ONLY a JSON object:
{ "action": "PRUNE" | "PIVOT" | "DECOMPOSE" | "HALT",
  "affectedHypIds": ["..."],
  "newHypotheses": [ { "id": "...", "parentId": "...", "statement": "..." } ],
  "rationale": "..." }

- PRUNE: drop refuted/irrelevant hypotheses (affectedHypIds).
- PIVOT: abandon an approach (affectedHypIds) and propose newHypotheses.
- DECOMPOSE: split a supported hypothesis into newHypotheses (children, parentId set).
- HALT: stop deepening.
