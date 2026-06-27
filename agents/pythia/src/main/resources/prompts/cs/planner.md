Naplánuj analytické šetření jako jeden JSON objekt PlanDag.

Otázka / vyřešené parametry: {{question}}
Druh záměru: {{intent}}
Dostupné schopnosti (pro query uzly použij pouze tyto): {{capabilities}}
Kotva (na co se uživatel díval): {{anchor}}
Relevantní doménový kontext (terminologie + preferované dotazy souvisejících Golemů): {{shems}}
{{feedback}}

Vrať POUZE JSON objekt tohoto tvaru (žádný text, žádné code fences):

{
  "rationale": "jedna věta o postupu",
  "hypotheses": [
    { "id": "H0", "statement": "data pro tuto otázku existují", "displayPriority": "HIDDEN" }
  ],
  "nodes": [
    { "nodeId": "N1", "testsHypIds": ["H0"], "kind": "query", "queryRef": "<id schopnosti>", "paramsJson": "{}" },
    { "nodeId": "N2", "kind": "render", "renderKind": "TABLE", "blockRole": "PRIMARY", "caption": "..." }
  ],
  "edges": [
    { "fromNodeId": "N1", "toNodeId": "N2", "binding": "N2.input = N1.output" }
  ]
}

Pravidla:
- Druhy uzlů jsou omezeny na "query", "reasoning" a "render" (pouze SQL plány).
- Každý queryRef MUSÍ být jedna z dostupných schopností.
- Každý konec hrany MUSÍ být deklarované nodeId a MUSÍ nést neprázdnou vazbu (binding).
- testsHypIds MUSÍ odkazovat na deklarovaná id hypotéz.
- Drž plán v rámci hloubkového rozpočtu pro daný záměr.
