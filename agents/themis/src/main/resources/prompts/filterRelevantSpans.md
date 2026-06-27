# Prompt: filterRelevantSpans

**Node**: `filterRelevantSpans`  
**Model**: `haiku` (cheap — structured output, no reasoning required)  
**Temperature**: 0.0

## Template

```
You are a domain relevance filter for a Czech/English ERP question resolver.
Given these candidate text spans:
  [0] 'zákazníka' (pos=NOUN, dep=nmod)
  [1] 'Novák' (pos=PROPN, dep=flat)
And these domain entity types:
  - customerId: Customer identifier (fuzzyMatcher=customers)
  - invoiceId: Invoice number (fuzzyMatcher=invoices)
Return ONLY a JSON array where each element is an object with:
  - "index": the span index (integer)
  - "entityTypes": array of likely entityTypeRef values for that span
Example: [{"index":0,"entityTypes":["customerId"]}, {"index":2,"entityTypes":["invoiceId","orderId"]}]
Only include spans that are likely domain entities.
```

## Design rationale

**Why haiku instead of sonnet?**  
The task is classification: match spans against a known closed set of entity type refs. It does not require reasoning or generation; a smaller, faster model is accurate enough and keeps per-request cost low.

**Why a JSON array of index+entityTypes objects?**  
Returning indices rather than the span text decouples the output from language/encoding issues (Czech special characters, word boundaries). The caller re-hydrates the full span via `spans.getOrNull(entry.index)`, so the LLM only needs to output stable integer keys.

The `entityTypes` array allows one span to match multiple candidate types (e.g. `["invoiceId","orderId"]`), which gives the fuzzy-matcher and joint-inference nodes the full candidate set to work with rather than forcing premature disambiguation.

**Why `Only include spans that are likely domain entities`?**  
This phrase prunes prepositions, auxiliaries, and determiners that survive the NOUN/PROPN POS filter. Haiku understands this instruction reliably and returns an empty array for unambiguous non-entity questions, which avoids wasted fuzzy-matcher calls.

**Fallback behaviour**  
`parseFilterResponse` catches any JSON parse error and returns all input spans unchanged (`return spans`). This means a malformed LLM response degrades to "pass everything through" rather than "drop everything" — a conservative choice for recall over precision.

## Known limitations

- The prompt does not pass the actual question text, only the extracted spans. This means context words that would help disambiguation (e.g. "zákazník" vs "dodavatel" in the same question) are not visible to the model. Passing the full sentence as additional context is a potential improvement.
- Multi-word spans (e.g. "Novák s.r.o.") are not yet proposed by `proposeDomainSpans` — each head noun is a separate span. The filter will correctly classify them individually, but the fuzzy matcher may produce lower scores on partial strings.
