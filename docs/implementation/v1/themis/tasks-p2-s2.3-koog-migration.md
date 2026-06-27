# Stage 2.3 — Koog graph migration

> **Phase 2, Stage 2.3.** Blocked by Stage 2.1's go-decision and Stage 2.2's extraction.
>
> **Reads with.** [`tasks-p2-overview.md`](./tasks-p2-overview.md), [`plan.md`](./plan.md) §4.3, [`../../../architecture/themis/architecture.md`](../../../architecture/themis/architecture.md) §6 (Koog graph at v1 + Phase 3).

> **Scope split (2026-05-30, Bora).** Stage 2.3 ships across multiple PRs rather than a single one. Refined after PR #5's scoping (smaller-than-planned per-PR surface):
> - **PR #3 — T1 only** (Koog deps in `agents/themis/build.gradle.kts`; existing 35 tests still green). **Merged.**
> - **PR #4 — T2** (ThemisGraph skeleton + `LlmGatewayClient → PromptExecutor` wrapper + `AIAgentConfig` plumbing; no node bodies ported yet; existing graph untouched). **Merged.**
> - **PR #5 — T3 only** (port 2 deterministic node bodies — `extractUniversal`, `proposeDomainSpans` — as pure functions; ResolverGraph delegates to them; ThemisGraph wires them as real Koog nodes; strategy I/O pivoted to `ResolverContext → ResolverContext` for the migration's duration). `branchOnInput` falls away naturally — it's the dispatch loop the Koog graph replaces.
> - **PR #6 — T4** (port MCP/LLM nodes: `detectLangAndParse`, `filterRelevantSpans`, `fuzzyMatchSpans`; introduce `JsonStructure` for the LLM filter; `tests` rewrite to drive the Koog graph via `setExecutionPoint` instead of `stateAt(lastNode, …)`).
> - **PR #7 — T5 only** (port the 4 LLM/HITL nodes: `jointInference`, `decideHitlOrEmit`, `decodeTokenAndApplyChoice`, `entitiesOnlyAssemble`; lift `NodeResult` to top-level; `ResolverGraph` collapses to a 188-LOC dispatch loop).
> - **PR #8 — T6** (wire branching edges into `buildThemisGraph`; cutover `Main.kt` to `AIAgent.run`; delete `ResolverGraph` + `_koog-spike/`).
>
> **Why the split:** in-flight discovery — `ResolverGraphNodeTest` constructs `ResolverContext` with `lastNode = "detectLangAndParse"` etc. to enter the graph mid-execution, then asserts on `result.state.parseState.universalEntities`. That entry pattern leans on the dispatch loop the migration removes; tests need a parallel rewrite to drive the Koog graph via Koog's own `AIAgentGraphStrategy.setExecutionPoint` API. Combined with the `PromptExecutor` wrapper around `LlmGatewayClient` (Koog expects `Message`/`Prompt` shapes, not raw strings) and `JsonStructure.create<T>()` schemas for the two LLM-using nodes, the realistic surface is ~800–1200 LOC of new Kotlin — too large for one safe PR.

## Goal

Replace the plain-coroutines `ResolverGraph` class with a Koog `AIAgentGraphStrategy`. Identifier renames from `ResolverGraph` → `ThemisGraph`. All existing unit + component tests still pass (real-dependency integration verification deferred to the separate integration-test suite).

**If Stage 2.1 closed NO-GO:** this stage shrinks to a rename-only stage. Re-read Stage 2.1's spike report and update this task list accordingly before starting. The body below assumes GO.

## Pre-flight

- [ ] **Stage 2.1 GO** (recorded decision in the spike report).
- [ ] **Stage 2.2 DONE** — `agents/themis/` lives in kantheon, all tests green, deploys to K3s.
- [ ] **Branch**: `feat/p2-s2.3-koog-migration` from `main`.
- [ ] **Tag the pre-migration HEAD** for easy rollback: `git tag pre-koog-migration` and push.

## Tasks

- [x] **T1 — Add Koog dependencies to `agents/themis/build.gradle.kts`.**

  Add the libraries the spike used in Stage 2.1:

  ```kotlin
  dependencies {
      // ... existing ...
      implementation("ai.koog:koog-agents:${libs.versions.koog.get()}")
      // If the spike found additional Koog modules necessary (e.g. ai.koog:koog-ktor or ai.koog:prompt-executor-anthropic-client), add them here.
      // Reference: ~/Dev/view-only/koog/ for the published module catalogue.
  }
  ```

  If the spike applied any dependency overrides or exclusions to resolve Ktor 2.x/3.x conflicts, replicate them here.

  Smoke check:

  ```
  ./gradlew :agents:themis:build --no-daemon
  ```

  Existing tests should still pass (Koog is added but not yet used).

  Acceptance: build green; commit `[p2-s2.3] add Koog dependencies`.

- [x] **T2 — Create the Koog graph skeleton alongside the existing `ResolverGraph`.**

  The migration is parallel-track: keep `ResolverGraph` working while building `ThemisGraph`. Cut over in T6.

  Create `agents/themis/src/main/kotlin/org/tatrman/kantheon/themis/koog/ThemisGraph.kt`:

  ```kotlin
  package org.tatrman.kantheon.themis.koog

  import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
  import ai.koog.agents.core.dsl.builder.strategy
  // ... other imports from Koog 0.8.x as confirmed by the Stage 2.1 spike ...

  /**
   * Themis = Resolver post-extraction.
   *
   * Input: `ResolveRequest` (themis/v1).
   * Output: `ResolveResponse` (themis/v1) with `Resolution` / `AwaitingClarification` outcome.
   *
   * Graph shape — see docs/architecture/themis/architecture.md §6.1.
   */
  val themisGraph: AIAgentGraphStrategy<ResolveRequest, ResolveResponse> = strategy("themis") {
      // Nodes added in T3 — T5.
      // For now: a placeholder edge so build is green.
      edge(nodeStart forwardTo nodeFinish)
  }
  ```

  Create `koog/nodes/` directory — one file per node per [`architecture.md`](../../../architecture/themis/architecture.md) §3.1.

  Wire **Koog tools** (the wrappers around `nlp-mcp`, `fuzzy-mcp`, `llm-gateway`) in `koog/tools/`. Spike at Stage 2.1 T3 showed the pattern.

  Acceptance: empty graph compiles; commit `[p2-s2.3] Koog graph skeleton`.

- [x] **T3 — Port deterministic nodes: `branchOnInput`, `extractUniversal`, `proposeDomainSpans`.**

  These three nodes have no LLM call. Easiest to port first.

  For each:
  1. Open the existing implementation in `ResolverGraph.kt`.
  2. Extract the logic into a function with explicit input + output types.
  3. Wrap that function in a Koog node:

     ```kotlin
     val extractUniversal by node<ParseResult, UniversalEntities>("extractUniversal") { input ->
         // ... the exact logic from ResolverGraph's extractUniversal ...
     }
     ```

  4. Add it to the `themisGraph` strategy.

  5. Write a parallel test class `ExtractUniversalKoogSpec` that exercises the new node directly with the same fixtures the existing `ExtractUniversalSpec` uses. Both specs green.

  After all three: edges `nodeStart → branchOnInput → detectLangAndParse(...future) → extractUniversal → proposeDomainSpans → ...`. Use placeholders for nodes not yet ported.

  Acceptance: 3 nodes ported with parallel tests green; `ResolverGraph` still working as before; commit `[p2-s2.3] port deterministic nodes`.

- [x] **T4 — Port `detectLangAndParse`, `filterRelevantSpans`, `fuzzyMatchSpans`.**

  These call MCP tools (`nlp-mcp`, `fuzzy-mcp`) and one of them (`filterRelevantSpans`) calls LLM Gateway.

  **MCP tool wrappers** as Koog `ToolDescriptor` instances. Reference: Koog example at `~/Dev/view-only/koog/agents/agents-mcp/` for the canonical MCP-tool-from-Kotlin pattern.

  Each node:

  1. Define a `@Serializable` input + output schema (Koog's `Structure` requires kotlinx-serialization).
  2. Inside the node: call the appropriate MCP client (`NlpClient`, `FuzzyClient` — both exist from the carried-over Resolver code). Use the existing HTTP clients; do NOT switch to Koog's prompt-executor for these (they're not LLM calls — they're plain MCP HTTP).
  3. For `filterRelevantSpans` (LLM-using): use Koog's `StructureFixingParser` from the Stage 2.1 spike pattern. Wrap the CHEAP-tier call with retries=2.
  4. Write parallel tests; assert outputs match the existing `*Spec` fixtures.

  Edges updated. Smoke run via testApplication.

  Acceptance: 6 nodes ported total; commit `[p2-s2.3] port MCP-tool nodes`.

- [x] **T5 — Port `jointInference`, `decideHitlOrEmit`, `decodeTokenAndApplyChoice`, `assembleResp`.**

  The hardest nodes — `jointInference` is the FAST-tier LLM call (Sonnet-class), structured-output, sensitive to prompt drift.

  - **`jointInference`**: use `StructureFixingParser` with retries=3 (more than CHEAP since the FAST call is more expensive to repair). Structure schema = `Resolution`-shaped data class. Carry over the existing prompt from `agents/themis/prompts/joint_inference.md` (preserve byte-for-byte; the eval gate in Stage 2.4 demands prompt parity).
  - **`decideHitlOrEmit`**: branches on `confidence` and `roundIndex`. Pure Kotlin; no LLM. Koog `node<...>` returns one of `RoutingDecision` (placeholder) or `AwaitingClarification`. The actual `RoutingDecision` is unused at this stage — Phase 3 wires that.
  - **`decodeTokenAndApplyChoice`**: resume-token codec; the HMAC + JSON parse is plain Kotlin. The existing `ResumeTokenCodec` class carries over from ai-platform unchanged.
  - **`assembleResp`**: packages the outcome with parse + trace metadata. In the original `ResolverGraph` this is inline in `decideHitlOrEmit`'s call site — extract it as a named node per the design.

  All four nodes have parallel test specs. Run the existing ResolverGraph component tests (in-process graph specs with mocked tool clients) against the Koog ThemisGraph by adding a test-only `RoutingGraphAdapter` that lets test specs target either graph implementation.

  Acceptance: all 9 nodes ported (`branchOnInput`, `detectLangAndParse`, `extractUniversal`, `proposeDomainSpans`, `filterRelevantSpans`, `fuzzyMatchSpans`, `jointInference`, `decideHitlOrEmit`, `decodeTokenAndApplyChoice`, `assembleResp`). All parallel tests green; commit `[p2-s2.3] port LLM + HITL nodes`.

- [x] **T6 — Cutover: switch `App.kt` to use `ThemisGraph`; delete `ResolverGraph`.**

  In `App.kt`:

  ```kotlin
  fun Application.module(serverConfig: KtorServerConfig) {
      // ... existing OTel + Ktor base ...

      // Was: val graph = ResolverGraph(deps)
      // Now:
      val graph = themisGraph
      val agentExecutor = AIAgent(
          strategy = graph,
          model = cheapModel /* injected from config */,
          executor = llmExecutor,
          // ... per Koog 0.8.x AIAgent constructor — confirm against ~/Dev/view-only/koog/koog-agents/ ... ../AIAgent.kt ...
      )

      routing {
          resolveRoutes(agentExecutor)
      }
  }
  ```

  Update `routes/ResolveRoutes.kt` to invoke `agentExecutor.run(input)` instead of `resolverGraph.run(input)`. The output `ResolveResponse` shape is identical.

  Delete `ResolverGraph.kt` and the parallel `Spec` classes (the original ones, not the Koog ones).

  Run the **full** test suite (unit + mocked component tests) — green count must equal the pre-migration baseline. (Real-dependency integration verification deferred to the separate integration-test suite per planning-conventions.md §4.)

  Acceptance: cutover commit clean; `git log --diff-filter=D --name-only -- 'ResolverGraph*'` shows the deletion; all tests green; commit `[p2-s2.3] Koog cutover; remove ResolverGraph`.

## DONE — Stage 2.3

- [x] All six tasks above checked.
- [x] `ResolverGraph` class renamed to `ThemisGraphDispatch`; `buildThemisGraph` is the live Koog `AIAgentGraphStrategy` with all 9 nodes + conditional edges; `runThemisGraph` wraps `AIAgent.run` + maps `state.terminal*` → `NodeResult` and is the production runtime path in `Main.kt`. **`ThemisGraphDispatch` kept as a transitional test-only fixture** — the 35 carry-over Resolver specs construct it and use mid-graph entry via `stateAt(...)`, which only the dispatch loop supports. Cleanup deferred until Stage 2.4 eval-gate confirms Koog-runtime parity end-to-end.
- [x] Full test suite green at 74 tests across 8 specs (pre-migration baseline + 39 new focused unit tests from T3/T4/T5).
- [x] `agents/_koog-spike/` deleted; `settings.gradle.kts` no longer registers it.
- [x] `pythia_framework_choice` memory updated; records Koog is the live runtime and lists the patterns Pythia / Golem should inherit.
- [ ] PR merged. _(pending Bora.)_

## Library / pattern references

- **Koog 0.8.0** at `~/Dev/view-only/koog/`. Key sub-modules:
  - `agents/agents-core/` — `AIAgent`, `AIAgentGraphStrategy`, `node`, `edge`, `strategy` DSL.
  - `agents/agents-mcp/` — MCP-tool integration for connecting MCP servers as Koog tools.
  - `prompt/prompt-executor/` — `PromptExecutor` and `StructureFixingParser`.
  - `examples/simple-examples/src/main/kotlin/ai/koog/agents/example/tone/ToneStrategy.kt` — graph-strategy reference.
- **Stage 2.1 spike report** at `agents/_koog-spike/docs/koog-spike-report.md` — any dependency overrides or API mismatches noted there apply here too.
- **ai-platform `agents/resolver/prompts/`** (now `agents/themis/prompts/` post-extraction) — DO NOT EDIT prompt text during migration. Eval gate in Stage 2.4 requires prompt parity.
- **kotlinx-serialization** for `@Serializable` data classes used as `Structure` schemas.

## Out of scope for Stage 2.3

- Adding routing nodes (`classifyIntentKind`, `detectMultiQuestion`, `routeToAgent`) — Phase 3.
- Changing prompt text or LLM model selection — preserve for eval-gate parity. Stage 2.4 surfaces any drift.
- Performance optimisation — only "feels comparable" check. Performance regressions caught by eval gate.
- Deleting `agents/resolver/` from ai-platform — separate PR after Phase 2 + soak period.
