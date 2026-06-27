# agents/golem/shems/golem-ucetnictvi

The **first Kantheon Golem** — accounting ("Účetnictví"), `agent_id: golem-ucetnictvi`.
A per-Shem bundle mounted into the Golem template pod via ConfigMap. **Not a Gradle
module** — no `build.gradle.kts`, no `settings.gradle.kts` entry.

```
golem-ucetnictvi/
├── shem.yaml          # the thin overlay (visibility_roles + optional router seed/locales)
├── prompts/
│   ├── cs/            # intent.yaml · free-sql.yaml · chip-topup.yaml (seeded from ai-models)
│   └── en/            #   "
└── README.md
```

## How this Shem is assembled (converged design, 2026-06-25)

The Golem template builds its `capabilities/v1.AgentCapability` at boot from four sources;
this directory owns only two of them (the overlay + the prompts):

| Source | Supplies | Where |
|---|---|---|
| **ai-models agent def** | identity (`id`/`label`) + model slice (`areas` → packages) | `ai-models/agents/ucetnictvi.yaml` |
| **Ariadne model** | `area_entities`, `preferred_queries`, `area_terminology` (by package); area description/tags | `GetModel` / `ResolveArea` |
| **this overlay** (`shem.yaml`) | `visibility_roles` + optional `description_for_router` / examples / `locale_defaults` | here |
| **Golem-template constants** | `agent_kind=AREA_QA`, `intent_kinds=[PROCEDURAL]`, capability refs, `hitl_default`, endpoint | template |

The rich, hand-curated `domain_entities` / `preferred_queries` / `domain_terminology` of
the old Shem are **gone** — they are re-derived from the model per package, so they can
never drift from it.

## Prompts belong to the Shem

Prompts are part of the Shem (the inscription that animates the Golem), **not** the model.
The model is just the model (entities, packages, areas). The `prompts/` here are seeded
verbatim from `ai-models/prompts/golem/{cs,en}` and loaded by Golem's `PromptStore` from
the mounted Shem — Ariadne no longer serves prompts. The placeholders are `{{ name }}`
(substituted in-node); the model content (pattern catalog, bindings) is injected at runtime.

## Areas → packages

`source.areas: [accounting]` resolves (via Ariadne `ResolveArea`, from
`ai-models/model-ttr/areas/accounting.ttrm`) to packages **`obchodni_doklady`** + **`ucetnictvi`**.
`accounting.ttrm` carries `description: "Účetnictví a navazující obchodní doklady"` and
`tags: [finance]`, which seed `description_for_router` when the overlay omits it.

> Status: design artifact for Golem Phase 4. The Shem-assembly loader refactor and the
> Ariadne `ResolveArea` / prompt-removal land as the Phase 4 stages — see
> `docs/implementation/v1/golem/plan.md` §6 and `docs/architecture/golem/contracts.md` §6.
