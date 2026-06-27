# Golem Phase 4 · Stage 4.3 — Shem-assembly refactor + prompts-into-Shem

> **Arc.** Golem Phase 4 (golem-ucetnictvi assembled Shem + cutover). **Branch.** `feat/p4-s4.3-shem-assembly` *(may share `feat/p4-s4.2-4.3-shem-assembly` with 4.2 — see the coupling note)*.
> **Companions.** [`plan.md`](./plan.md) §6 Stage 4.3, [`../../../architecture/golem/contracts.md`](../../../architecture/golem/contracts.md) §6 (the four assembly sources + §6.2 prompts-into-Shem), [`../../../architecture/golem/architecture.md`](../../../architecture/golem/architecture.md) §4.1, [`agents/golem/shems/golem-ucetnictvi/`](../../../../agents/golem/shems/golem-ucetnictvi/) (the landed overlay + prompt bundle — the parser target).
> **Goal.** Golem stops parsing a rich hand-curated Shem and instead **assembles** its `capabilities/v1.AgentCapability` from four sources at boot (ai-models agent def + Ariadne `ResolveArea`/`GetModel` + thin overlay + template constants); `PromptStore` reads the **mounted Shem**, not Ariadne.

## Pre-flight

- Stage 4.2 closed — Ariadne `ResolveArea(area) → packages` reachable; prompt-serving removed.
- The overlay + prompt bundle landed at `agents/golem/shems/golem-ucetnictvi/` (`shem.yaml` `apiVersion: kantheon.shem/v1` + `prompts/{cs,en}/{intent,free-sql,chip-topup}.yaml`) — the **design artifact** this stage's parser/loader is built against.
- The current rich path to replace: `ShemLoader.parse` (rich `ShemYaml`), `GolemModelSubsystem.fromConfig` (`golem.shem.resource` classpath load; `PackageContext(packages…)`; `PromptStore(client, agentId…)` off Ariadne).

## ⚠ Landing coupling with Stage 4.2 (monorepo compile)

T4 (PromptStore → mounted Shem) is what lets 4.2 T3 (delete the `GetPrompts` proto) compile — `PromptStore.kt` currently imports `org.tatrman.ariadne.v1.GetPromptsResponse`. **Co-land T4 with 4.2 T3.** The rest of this stage (T1–T3, T5–T7) depends only on 4.2's `ResolveArea`.

## The four sources (contracts §6)

| Source | Supplies | Authored where |
|---|---|---|
| ai-models agent def | `agent_id=golem-<id>`, `display_name`, model slice (`areas`/`packages`/`entities`) | `ai-models/agents/<id>.yaml` |
| Ariadne model | `area_entities`, `preferred_queries`, `area_terminology`; area `description`/`tags` | `GetModel`/`ResolveArea` |
| kantheon overlay | `visibility_roles` + OPTIONAL `description_for_router`/`example_questions`/`counter_examples`/`locale_defaults` | `shems/<id>/shem.yaml` |
| template constants | `AREA_QA`, `[PROCEDURAL]`, capability refs, `hitl_default`, `service_endpoint`, `health_check_path` | the template |

## Tasks

- [x] **T1 — tests first: `ShemAssemblySpec`.** Assemble from a fixture ai-models agent def + a fixture `ResolveArea` result + a recorded `GetModel` `ModelBundle` + the overlay + template constants → expected `AgentCapability`: `agent_id=golem-ucetnictvi`, `area_name`/`area_entities`/`preferred_queries`/`area_terminology` model-derived, `visibility_roles` from overlay, `AREA_QA`/`[PROCEDURAL]`/endpoint constant. Cover overlay-omits-router → `description_for_router` seeded from area description/tags.
- [x] **T2 — overlay parser for `kantheon.shem/v1`.** New `ShemOverlay` parser (`source{repo,agentDef,id,areas}` + `overlay{visibility_roles, optional router/examples/locales}`) — **replaces** the rich `ShemYaml` parse. Keep `ShemLoader`'s SNAKE_CASE/strict-fail discipline; the only required lint now is `source.id`/`source.areas` non-empty + valid `apiVersion`/`kind`. Retire the rich-field lints (area_* now model-derived).
- [x] **T3 — boot assembly in `GolemModelSubsystem`.** Read the agent def (`source.agentDef`) → `id`/`label`; `ResolveArea(source.areas)` → packages → `PackageContext` (`GetModel`); derive `area_entities`/`preferred_queries`/`area_terminology` from the model snapshot; merge overlay + template constants → `AgentCapability` → `ShemContext`. `golem.shem.resource` (single classpath YAML) gives way to a mounted-Shem dir (`golem.shem.dir`, default `/etc/golem/shem`).
- [x] **T4 — `PromptStore` from the mounted Shem (*co-lands with 4.2 T3*).** Load `prompts/{locale}/{intent,free-sql,chip-topup}.yaml` from the mounted Shem dir; drop the Ariadne prompt client (`PromptSnapshot.fromAriadne`, the `MetadataGrpcClient` prompt path). Keep the `{{ name }}` contract, in-node substitution, and atomic reload unchanged; `/v1/refresh` model-refresh stays, prompt reload becomes a ConfigMap remount. Update `PromptStore`/`PromptSnapshot`/`ClasspathPromptFallback` accordingly.
- [x] **T5 — `description_for_router` precedence + `ShemContext` getters.** Precedence: overlay override → area `description`/`tags`. `ShemContext` getters map to the assembled capability (`areaName`/`areaEntities`/`terminology`/`preferredQueries` now model-derived); `ShemRegistration` registers the assembled `AgentCapability` unchanged.
- [x] **T6 — `/ready` + `/v1/refresh` reconcile.** `/ready` gates on Shem-assembled + PackageContext (model) loaded + PromptStore (mounted) loaded; `GolemReadiness`/`GolemModelSubsystem.isReady` updated. `/v1/refresh` re-pulls model + `ResolveArea`; prompt set is remount-driven (document).
- [x] **T7 — component: full boot against fixtures.** Component spec boots the assembled `golem-ucetnictvi` Shem against Wiremock/gRPC `ResolveArea`+`GetModel` fixtures + the mounted prompt bundle; asserts the registered `AgentCapability` shape + a sync turn renders. Drop/replace `ShemLoaderSpec` rich-parse cases superseded by `ShemAssemblySpec`.

> Order note: T2 (parser) + T3 (assembly) are the core; T4 co-lands with 4.2 T3; T1 leads (tests first); T5–T7 close. Stage runs to 7 tasks.

## DONE

Golem boots an **assembled** Shem (four sources) with no rich hand-curated manifest; `area_*`/`preferred_queries` are model-derived (cannot drift from the model); prompts load from the mounted Shem; no Ariadne prompt dependency remains. Full boot green against fixtures. Unblocks Stage 4.4 (deploy the real bundle).
