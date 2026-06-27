Plan an analytical investigation as a single PlanDag JSON object.

Question / resolved params: {{question}}
Intent kind: {{intent}}
Available capabilities (use only these for query nodes): {{capabilities}}
Anchor (what the user was looking at): {{anchor}}
Relevant domain context (terminology + preferred queries from related Golems): {{shems}}
{{feedback}}

Return ONLY a JSON object of this shape (no prose, no code fences):

{
  "rationale": "one sentence on the approach",
  "hypotheses": [
    { "id": "H0", "statement": "the data exists for this question", "displayPriority": "HIDDEN" }
  ],
  "nodes": [
    { "nodeId": "N1", "testsHypIds": ["H0"], "kind": "query", "queryRef": "<capability id>", "paramsJson": "{}" },
    { "nodeId": "N2", "kind": "render", "renderKind": "TABLE", "blockRole": "PRIMARY", "caption": "..." }
  ],
  "edges": [
    { "fromNodeId": "N1", "toNodeId": "N2", "binding": "N2.input = N1.output" }
  ]
}

Rules:
- Node kinds: "query", "reasoning", "render"; with the data plane also "dataframe" (a Polars op over a source handle: dfdsl + sourceHandleId) and "model" (Metis: model.fit/project/simulate/diagnose with capabilityId + inputHandleIds).
- Every queryRef MUST be one of the available capabilities.
- Every edge endpoint MUST be a declared nodeId and MUST carry a non-empty binding.
- testsHypIds MUST reference declared hypothesis ids.
- Keep the plan within the depth budget for the intent.
