# Format-catalog gotcha fixtures (Stage 1.1)

The plan (`docs/implementation/v1/golem/plan.md` Stage 1.1 T2) names five gotcha
**classes** to institutionalise as fixtures, referenced as "G-21…G-25". A repo +
git-history sweep of `ai-platform/agents/golem` found **no `G-21…G-25` catalog**
anywhere (the cited `golem/docs/v2/v2-overview.md` does not exist). The five
classes are therefore distilled from the plan's own naming + the recovered Python
semantics (`format_catalog.py` + `nodes.py` @ git `5281954d^`, and the live
`nodes_v2/format.py`). Each maps to one or more `FormatCatalogSpec` cases.

| Class | What it guards | Spec case |
|---|---|---|
| **chart-on-text-heavy-data** | a `/format chart` on id/code/name rows with no numeric measure must degrade to a readable table, never a `str(dict)` dump | "a chart request on text-heavy data falls back to a table" |
| **tool_choice not honoured** | the model returns no tool call (or an unknown tool); the catalog retries with the error fed back, and succeeds on a later attempt | "a no-tool-call attempt is retried with the prior error" |
| **markdown re-parse trap** | markdown source (mermaid fences, table pipes, backslashes) is carried VERBATIM — never re-parsed or round-tripped through a JSON content payload | "markdown source is carried verbatim" |
| **missing headers** | when the table tool omits `headers`, they are inferred from row keys (union, first-appearance order, `title == name`) | "missing headers are inferred from row keys" |
| **retry exhaustion** | after `maxRetries + 1` failed attempts, the deterministic fallback fires — table when rows are structured, plaintext only when there is no structure | "retry exhaustion falls back to a table / to plaintext" |

Fixtures are expressed inline in `FormatCatalogSpec` (Czech-domain rows) rather
than as standalone JSON — the gotchas are about catalog *control flow*, not wire
shapes, so a scripted `StructuredFormatter` is the natural fixture vehicle.
