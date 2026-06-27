# TransDSL codec

Two-way mapping between **TransDSL** (the protobuf-shaped transformation DSL) and the platform's
canonical `org.tatrman.plan.v1.PlanNode` proto. Both directions are pure functions over proto
messages — no Calcite, no I/O.

```
TransDSL JSON ─┐                         ┌─► TransDSL Query proto
               ├─►  TransDslCodec  ◄────►│
TransDSL Query ┘                         └─► PlanNode (canonical)
```

## Public API

`object TransDslCodec` in `TransDslCodec.kt`:

| Method | Direction | Returns |
|---|---|---|
| `parse(query, queryRefs?)` | `Query` → `PlanNode` | canonical plan |
| `parseJson(json, queryRefs?)` | TransDSL JSON → `PlanNode` | canonical plan |
| `unparse(plan)` | `PlanNode` → `Query` | TransDSL `Query` |
| `unparseJson(plan)` | `PlanNode` → TransDSL JSON | string |
| `queryRefsIn(query)` | — | `Set<String>` of `query_ref` source qnames |
| `queryRefsInJson(json)` | — | same, from JSON form |

### `query_ref` resolution (DF-T03 / Phase 03 B1)

`Source.QUERY_REF` lets a TransDSL query embed a reference to another stored query by qname.
The codec defers resolution to the caller: pass a `queryRefs: Map<String, PlanNode>` containing
the *canonical-form bytes* parsed into `PlanNode`s, and the codec inlines them as a `SubqueryNode`
at the reference site. With `queryRefs = null` (default) the codec emits a placeholder
`SubqueryNode(alias=ref)` — useful for parsers that don't need the resolved form. With `queryRefs`
supplied but missing a reference, the codec throws `TransDslParseException("query_ref_unresolved", ...)`.

The `services/translator` gRPC service handles the resolution loop: `queryRefsInJson(source)` →
fetch each ref via `MetadataService.GetQuery(include_canonical_form = true)` → assemble the map →
pass it to `parse`. See `services/translator/.../grpc/TranslatorServiceImpl.kt`.

## Supported / unsupported

Supported node shapes (see `TransDslCodec.kt` for the full set):

- Sources: `TABLE`, `VIEW`, `QUERY_REF` (via the mechanism above), nested `QUERY`.
- Joins: `INNER`, `LEFT`, `RIGHT`, `FULL`; conditions via expression encoding.
- Projection, filter, aggregate, sort, limit/offset.
- Subqueries (correlated and uncorrelated).

Currently rejected / unsupported (Phase 08 follow-ups):

- Multi-core TransDSL Cartesian joins without conditions — DF-DSL03 / B3.
- The full expression operator catalog — DF-DSL04 + `IN` operator DF-S05 / B4.

## Extending the codec

1. Add the new shape to the TransDSL proto (`shared/proto/.../transdsl/v1`).
2. Add a `case` in `parseInto` / `unparseInto` (in `TransDslCodec.kt`).
3. Add a round-trip case to `RoundTripSpec` (`shared/libs/kotlin/query-translator/.../roundtrip/`).
4. If the new shape requires resolution against the model (column qualification etc.),
   coordinate with the RESOLVE stage (DF-T01, Phase 08 A1).
