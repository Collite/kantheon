# Prompt: jointInference

**Node**: `jointInference`  
**Model**: `sonnet` (fast — requires reasoning over function specs and entity bindings)  
**Temperature**: 0.0

## Template

```
Question: zákazníka Novák
Universal entities: Novák(PERSON)
Available functions:
listInvoices: List invoices for a customer
    params: customerId: string
findOrders: Find open orders for a customer
    params: customerId: string, status: string
Return a JSON object with: functionId, argsJson (camelCase keys), confidence (0-1), rationale.
Example: {"functionId":"listInvoices","argsJson":{"customerId":"CUST-001"},"confidence":0.95,"rationale":"..."}
```

## Design rationale

**Why sonnet instead of haiku?**  
Joint inference requires the model to reason over multiple function specs simultaneously, disambiguate entity bindings from fuzzy candidates, and produce a calibrated confidence score. Haiku underperforms on confidence calibration and multi-function disambiguation in early experiments. Sonnet is the minimum capability tier for this task.

**Why pass reconstructed token text (`tokens.joinToString(" ")`) instead of the raw question?**  
The tokenized text is already normalized (whitespace-clean, one token per word) and matches the char offsets used throughout the pipeline. Passing raw question text would create a mismatch with `charStart`/`charEnd` values if the input had unusual spacing or encoding.

**Why `argsJson` as a JSON-serialized string rather than a nested object?**  
`parseJointInferenceResponse` reads `argsJson` via `jsonPrimitive.content`, which requires the field to be a JSON string primitive (`"argsJson": "{...}"`), not a JSON object (`"argsJson": {...}`). This avoids a nested-deserialization step and keeps the outer parse simple. The trade-off is that the LLM must emit a JSON-within-JSON string, which sonnet handles correctly at temperature 0.

**Why include `rationale`?**  
The rationale is surfaced in the HITL clarification options (`ClarificationOption.description`) so the user can understand why the model made a particular binding. It also appears in `Resolution.rationale` for audit logging. An empty rationale degrades the user experience of the HITL flow.

**Confidence calibration**  
The model is instructed to return confidence in [0, 1]. Values above `hitl.confidenceThreshold` (default 0.75) bypass HITL; values below trigger a clarification round. The model is NOT told the threshold, which prevents gaming (always emitting 0.76 to avoid HITL).

**Code-fence stripping**  
`parseJointInferenceResponse` strips ` ```json ` and ` ``` ` fences before parsing. Sonnet sometimes wraps JSON in code fences even at temperature 0 when given a structured output instruction. Stripping fences is necessary for robust parsing.

**Known limitations**

- The prompt does not include fuzzy-match scores or candidate IDs. The model receives only the raw entity text and must infer the binding from context. A future improvement would pass top-N fuzzy candidates per span so the model can select among them rather than hallucinating entity IDs.
- There is no few-shot example for the Czech language. Adding 2-3 Czech-language examples in the system prompt would improve Czech entity binding accuracy.
- The `argsJson` round-trip (string → parse → object → string) introduces a risk of double-escaping if the LLM includes backslash sequences inside argsJson. This is tested in the unit test suite (`jointInference — partial JSON falls back gracefully`).
