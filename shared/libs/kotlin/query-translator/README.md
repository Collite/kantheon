# query-translator

> **forked-from:** `ai-platform@2575b923dca521fea0e3156257e4b779f02a6ed4` (`shared/libs/kotlin/query-translator/`), tag `kantheon-fork-point`, forked 2026-06-13.
> Maintained independently since the fork; do not assume parity with the ai-platform original.

Calcite-grounded heart of the v1 query pipeline. Converts SQL / TransDSL / DataFrame DSL ↔ canonical RelNode form, encodes/decodes the proto `PlanNode` wire format, and unparses RelNode → dialect-specific SQL.

Embedded directly by JVM consumers (`services/proteus`, `services/argos`, `workers/brontes`, `services/theseus`); the `services/proteus` gRPC service is a thin wrapper around this library.

## Calcite pin

Calcite is pinned at `1.41.0` in `gradle/libs.versions.toml` (per the ai-platform fork-point README). Do not bump in this phase without consulting `~/Dev/view-only/calcite` for breakage (the q8 nulls-direction CASE expansion rule depends on Calcite's behaviour at this exact version).

## Proto import rewrites (fork-time, 2026-06-13)

All `cz.dfpartner.*` proto imports in the source were rewritten to the in-repo Stage 1.2 packages: `plan.v1` / `dfdsl.v1` / `transdsl.v1`. The two `cz.dfpartner.translator.v1.{Language,SqlDialect}` enum imports were retargeted to a new library-only proto at `shared/proto/src/main/proto/org/tatrman/proteus/v1/translator.proto` (the full Proteus service proto lands in fork Phase 2 Stage 2.4; this stage creates the library enums in advance so the library can be forked in isolation).

## Package root

`shared.translator` (preserved from ai-platform per the fork convention — see [`tasks-p1-s1.3-shared-libs.md`](../../../docs/implementation/v1/fork/tasks-p1-s1.3-shared-libs.md) pre-flight note).

## Sub-package layout

```
shared/translator/
├── codec/
│   ├── sql/        SQL ↔ RelNode (Calcite SqlParser + RelToSqlConverter)
│   ├── transdsl/   TransDSL ↔ RelNode
│   ├── dfdsl/      DataFrame DSL ↔ RelNode
│   └── relnodewire/ proto PlanNode ↔ RelNode
├── schema/         SchemaPlus adapter wrapping ModelHandle
├── joiner/         logical + physical Join expansion
├── orchestrator/   Translator entry point (PARSE → TO_REL → … → UNPARSE)
├── dialects/       SqlDialect registry (rule: never use MssqlSqlDialect.DEFAULT)
├── framework/      Calcite framework lifecycle (rule: single-use Planner per query)
├── params/         ParametrizedSql ↔ RexDynamicParam bridge
└── wire/           proto PlanNode ↔ RelNode encoder / decoder
```

See the [Calcite engagement rules](#calcite-engagement-rules-where-they-re-enforced) in the ai-platform README (`/tmp/aip-fork/shared/libs/kotlin/query-translator/README.md` or upstream) — they apply unchanged.
