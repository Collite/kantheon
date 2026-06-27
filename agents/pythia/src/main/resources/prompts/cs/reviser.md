Uprav plán šetření na základě toho, co jsme zjistili.

Spouštěč: {{trigger}}
Aktuální hypotézy: {{hypotheses}}
{{feedback}}

Zvol JEDNU akci a vrať POUZE JSON objekt:
{ "action": "PRUNE" | "PIVOT" | "DECOMPOSE" | "HALT",
  "affectedHypIds": ["..."],
  "newHypotheses": [ { "id": "...", "parentId": "...", "statement": "..." } ],
  "rationale": "..." }

- PRUNE: zahoď vyvrácené/irelevantní hypotézy (affectedHypIds).
- PIVOT: opusť přístup (affectedHypIds) a navrhni newHypotheses.
- DECOMPOSE: rozděl podpořenou hypotézu na newHypotheses (potomci, parentId nastaveno).
- HALT: zastav prohlubování.
