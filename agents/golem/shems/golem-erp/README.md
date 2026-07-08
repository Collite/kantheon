# agents/golem/shems/golem-erp

The **deploy-test agent-showcase Golem** — ERP Q&A, `agent_id: golem-erp`. A per-Shem
bundle mounted into the Golem template pod via ConfigMap. **Not a Gradle module** — no
`build.gradle.kts`, no `settings.gradle.kts` entry.

```
golem-erp/
├── shem.yaml          # the thin overlay (visibility_roles + router seed/locales)
├── prompts/
│   ├── cs/            # intent.yaml · free-sql.yaml · chip-topup.yaml (classpath-fallback locale)
│   └── en/            #   "
└── README.md
```

## Deploy-test role (WS-C2 T4)

This Shem drives the kantheon `GolemErpIntegrationSpec` in the olymp `golem-erp` integration
context. It points at the **existing bundled Ariadne `accounting` area** (`areas/accounting.ttrm`
→ `obchodni_doklady` + `ucetnictvi`), so the context needs **no new Ariadne model and no image
rebuild** — the standing `golem:testing` + `ariadne:testing` images serve it as-is.

The context proves two things fixture-only (no real query data — that is the `tpcds-query`
showcase's job):

1. **PD-8 Shem admission** end-to-end on-cluster — a missing bearer fails closed (401), an
   outsider role is forbidden (403). Both run at Golem's `/v1/answer/sync` edge, before any
   model/LLM/query work.
2. The **LLM-planned agent turn** (gated) — admission → `PlanComposer` (LLM, WireMock-stubbed
   via Prometheus) → `MiniPlanExecutor` render → a `STATUS_DONE` `ConversationalResponse`. The
   rendered table is data-less: the MiniPlan carries a render node only, so the turn completes
   without the query chain. Real query rows are the `tpcds-query` / real-data path.

## How this Shem is assembled (converged design, 2026-06-25)

The Golem template builds its `capabilities/v1.AgentCapability` at boot from four sources; this
directory owns two of them (the overlay + the prompts):

| Source | Supplies | Where |
|---|---|---|
| **ai-models agent def** | identity (`id`/`label`) + model slice (`areas` → packages) | `ai-models/agents/erp.yaml` |
| **Ariadne model** | `area_entities`, `preferred_queries`, `area_terminology` (by package) | `GetModel` / `ResolveArea` |
| **this overlay** (`shem.yaml`) | `visibility_roles` + optional `description_for_router` / examples / `locale_defaults` | here |
| **Golem-template constants** | `agent_kind=AREA_QA`, `intent_kinds=[PROCEDURAL]`, capability refs, `hitl_default`, endpoint | template |

## Prompts

Prompts are part of the Shem, **not** the model. The harness mounts only `shem.yaml` (a flat
ConfigMap from `kubectl create configmap --from-file`), so at runtime the prompts resolve from
the pod image's **classpath fallback** (`prompts/cs/*`, the Golem locale default `cs`). The
`prompts/{cs,en}/` here keep the bundle self-contained + canonical (seeded verbatim from the
Golem prompt set); a future harness change can mount them directly.

Drift guard: `GolemErpBundleSpec` (unit) parses this bundle against the live `ShemOverlayParser`
+ `ShemAssembler`.
