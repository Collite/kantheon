# Golem Phase 4 · Stage 4.2 — Ariadne `ResolveArea` + drop prompt-serving

> **Arc.** Golem Phase 4 (golem-ucetnictvi assembled Shem + cutover). **Branch.** `feat/p4-s4.2-ariadne-resolvearea` *(see the landing-coupling note — may share a branch with 4.3)*.
> **Companions.** [`plan.md`](./plan.md) §6 Stage 4.2, [`../../../architecture/golem/contracts.md`](../../../architecture/golem/contracts.md) §6.3 (area resolution — the Ariadne work item), [`../../../architecture/golem/architecture.md`](../../../architecture/golem/architecture.md) §4.1, [`../../../architecture/fork/contracts.md`](../../../architecture/fork/contracts.md) §1.1 (`GetPrompts` REMOVED 2026-06-25; `ResolveArea` added), [`../fork/plan.md`](../fork/plan.md) (Ariadne fork Stage 2.1 — the prompt-serving wiring this stage reverses).
> **Goal.** The model becomes *just* the model: areas are resolvable (`ResolveArea(area) → packages` + description/tags), and **prompts leave Ariadne entirely** (they now live in the Shem — Stage 4.3). After this stage Ariadne serves model + `ResolveArea` only.

## Pre-flight

- Stage 4.1 closed (`area_*`/`AREA_QA` landed in capabilities/v1).
- Ariadne is the forked model edge (`ariadne/v0.1.0`); prompt-serving exists from fork Stage 2.1 (`AriadneService.GetPrompts` + `tools/ariadne-mcp` `get_prompts` + the `prompts/` Git subtree). This stage **reverses** that prompt-serving.
- `ai-models/model-ttr/areas/*.ttrm` exists upstream (`accounting.ttrm` → packages `obchodni_doklady` + `ucetnictvi`, `description: "Účetnictví a navazující obchodní doklady"`, `tags: [finance]`). Areas are an editor/registry concept ai-platform does **not** load into the metadata graph today — so Ariadne must newly parse them.

## ⚠ Landing coupling with Stage 4.3 (monorepo compile)

Removing the `GetPrompts` proto (T3) breaks compilation of `agents/golem/.../prompts/PromptStore.kt`, which imports `org.tatrman.ariadne.v1.GetPromptsResponse` (via `PromptSnapshot.fromAriadne`). The Golem-side swap to the **mounted Shem** is Stage 4.3 T4. **Keep `main` green:** land T3 together with 4.3 T4 — develop 4.2+4.3 on one branch (`feat/p4-s4.2-4.3-shem-assembly`), or sequence so the proto removal and the `PromptStore` swap co-commit. T1/T2 (`ResolveArea`) have **no** such coupling and can land first.

## Tasks

- [x] **T1 — tests first: `ResolveAreaSpec` (areas → packages).** Spec the area registry against fixture `model-ttr/areas/*.ttrm`: `ResolveArea("accounting") → packages [obchodni_doklady, ucetnictvi]` + `description`/`tags`; unknown area → empty + Rule-6 message; area with no members → packages empty, not error. Fixtures under `services/ariadne/src/test/resources/`. *(TTR parse via the `org.tatrman:ttr-parser` toolchain — the standing Collite/modeler dep, CLAUDE.md §7.3.)*
- [x] **T2 — `ResolveArea` RPC + area source.** Add `ResolveArea(ResolveAreaRequest) returns (ResolveAreaResponse)` to `org.tatrman.ariadne.v1` (`ariadne.proto`, beside the forked RPCs); `area`, returns `repeated string packages` + `description` + `repeated string tags` + Rule-6 `messages=99`. Implement an `AreaRegistry`/`AreaSource` that reads `model-ttr/areas/*.ttrm` from the existing Git source (no second poller — same `/refresh`/poll); wire into `MetadataServiceImpl`. `tools/ariadne-mcp` gains `resolve_area` (zero-logic wrapper, `Tools.kt` + schema, register in capabilities). `just proto`; bindings compile.
- [x] **T3 — remove `GetPrompts` (RPC + tool + Git subtree) — *co-lands with 4.3 T4*.** Delete `GetPrompts` RPC + `GetPromptsRequest`/`GetPromptsResponse`/`PromptDef` from `ariadne.proto`; remove `buildPromptRegistry` + the `prompts/` package (`PromptRegistry`, `*PromptSource`, `Sha256`) + the `MetadataServiceImpl.getPrompts` impl + `Application.kt` wiring; narrow the Git source back to `model-ttr/` only (drop `prompts/`). Remove `tools/ariadne-mcp` `get_prompts` (`Tools.kt`, schema, `ManifestLoader`). Delete the prompt specs (`GetPromptsSpec`, `GetPromptsLiveSpec`, `prompts/PromptSourceSpec`, ariadne-mcp `GetPromptsToolSpec`/`GetPromptsMcpLiveSpec`); fix `ModuleBootSpec` refs.
- [x] **T4 — docs.** `fork/contracts.md` §1.1 already records the reversal (verify the proto block + the §2 MCP-surface table show `get_prompts` removed / `resolve_area` added); update `services/ariadne` README + `tools/ariadne-mcp` README (prompt-serving gone; `ResolveArea` added); note the `application.conf` `metadata.prompts.sources` block is removed.
- [x] **T5 — component: area resolution.** Component-tier spec (Testcontainers/real Git source per testing §2) resolving `accounting` against the in-repo `model-ttr/areas` fixture → the two packages + description/tags; assert no prompt RPC/tool remains on either surface.
- [ ] **T6 — tag.** *(deferred to Bora — release action)* ** Tag `ariadne/v0.2.0` (model + `ResolveArea`, no prompt surface); bump `gradle/libs.versions.toml` if Ariadne is a published consumer; CI green (lint + mocked suite).

> Order note: T1→T2 (`ResolveArea`) land first and independently. T3 (prompt removal) co-lands with 4.3 T4 to keep the monorepo compiling. T4/T5/T6 close. Stage runs to 6 tasks.

## DONE

Ariadne serves the model + `ResolveArea` only — no `GetPrompts` RPC, no `get_prompts` tool, Git source narrowed to `model-ttr/`. `ResolveArea("accounting")` returns `[obchodni_doklady, ucetnictvi]` + description/tags. Tag `ariadne/v0.2.0`. Unblocks the Stage 4.3 assembly (areas→packages) and prompts-into-Shem.
