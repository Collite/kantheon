# Stage 2.1 — Koog spike (go / no-go)

> **Phase 2, Stage 2.1.** Gates Stage 2.3.
>
> **Reads with.** [`tasks-p2-overview.md`](./tasks-p2-overview.md), [`plan.md`](./plan.md) §4.1, [`../../../architecture/themis/architecture.md`](../../../architecture/themis/architecture.md) §6.3 (how Koog fits) + §12 (risks).

## Goal

Validate Koog 0.8.x (or current at run time) compiles and runs against kantheon's current Ktor dependency graph. Produce a spike report with Bora's explicit go / no-go decision on adopting Koog for the Themis migration in Stage 2.3.

**Spike box:** 1–2 days. Not a polished implementation. Goal is to surface API mismatches, dependency conflicts, and the shape-cost of porting nodes — not to deliver code that ships.

## Pre-flight

- [ ] **Phase 1 DONE.**
- [ ] **`agents/_koog-spike` will be a temporary module** — deleted at the end of Stage 2.3 once migration completes. Mark with TODO.
- [ ] **Branch**: `feat/p2-s2.1-koog-spike` from `main`.
- [ ] Read Koog 0.8.0 docs once before starting: `https://docs.koog.ai/` plus local clone at `/Users/bora/Dev/view-only/koog/`. Especially `README.md`, `examples/simple-examples/`, and `agents/agents-core/.../entity/AIAgentGraphStrategy.kt` (the entrypoint).

## Tasks

- [x] **T1 — Sandbox `agents/_koog-spike/` module.**

  Create module under `agents/_koog-spike/`. `build.gradle.kts`:

  ```kotlin
  plugins {
      alias(libs.plugins.kotlin.jvm)
      alias(libs.plugins.ktor)
  }

  dependencies {
      implementation("ai.koog:koog-agents:${libs.versions.koog.get()}")
      implementation(libs.ai.platform.ktor.config)       // pulls Ktor 3.x current
      implementation(libs.ai.platform.otel.config)
      implementation(libs.ktor.server.cio)               // CIO engine — explicit to test conflict
      implementation(libs.kotlinx.coroutines.core)
      implementation(libs.kotlin.logging)

      testImplementation(libs.kotest.runner.junit5)
      testImplementation(libs.kotest.assertions.core)
      testImplementation(libs.wiremock)                  // to fake the LLM gateway in T3
  }
  ```

  Add to `settings.gradle.kts`: `include(":agents:_koog-spike")`.

  **Smoke build:**
  ```
  ./gradlew :agents:_koog-spike:build --no-daemon
  ```

  If the build fails with a Ktor 2.x/3.x transitive conflict (the original blocker per `pythia_framework_choice` memory):
  - Run `./gradlew :agents:_koog-spike:dependencies --configuration runtimeClasspath > deps.txt`
  - Grep for `io.ktor:ktor-*` entries; identify the transitive that pulls Ktor 2.x.
  - Document the conflict in the spike report (T4).

  Acceptance: build either succeeds (proceed to T2) or fails with a documented conflict (skip to T4 and write the report — Bora's decision is informed).

- [x] **T2 — Port `extractUniversal` (deterministic, no LLM call).**

  Reference: ai-platform `agents/resolver/src/main/kotlin/.../koog/ResolverGraph.kt` line ~95 (the existing `extractUniversal` node). It's the simplest node — reads `parse.entities`, normalises universal types into typed values (ISO dates, money), no LLM call. Around 30 lines of logic.

  Port to a Koog node. Reference shape — adapt to current Koog 0.8.x API by consulting `~/Dev/view-only/koog/agents/agents-core/src/commonMain/kotlin/ai/koog/agents/core/agent/entity/AIAgentGraphStrategy.kt`:

  ```kotlin
  import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
  import ai.koog.agents.core.dsl.builder.strategy

  data class ExtractInput(val parse: AnalyzeResponse, val locale: String)
  data class UniversalEntities(val entities: List<UniversalEntity>, val parse: AnalyzeResponse)

  val extractUniversalSpike: AIAgentGraphStrategy<ExtractInput, UniversalEntities> = strategy("extract-universal") {
      val node by nodeDoExtractUniversal()
      edge(nodeStart forwardTo node)
      edge(node forwardTo nodeFinish)
  }

  fun StrategyDsl<*>.nodeDoExtractUniversal() = node<ExtractInput, UniversalEntities>("extract-universal") { input ->
      val entities = input.parse.entitiesList.mapNotNull { entity ->
          when (entity.label) {
              "DATE" -> normaliseDate(entity, input.locale)?.let { UniversalEntity.date(entity, it) }
              "MONEY" -> normaliseMoney(entity)?.let { UniversalEntity.money(entity, it) }
              // ... per existing Resolver impl ...
              else -> null
          }
      }
      UniversalEntities(entities, input.parse)
  }
  ```

  Add a Kotest spec exercising the node with 3 input fixtures (one date, one money, one no-match). Assert outputs match the reference behaviour from ai-platform's existing `ExtractUniversalSpec`.

  Acceptance: `just test-kt _koog-spike` green for the deterministic node.

- [x] **T3 — Port `filterRelevantSpans` (CHEAP LLM call with `StructureFixingParser`).**

  Reference: ai-platform `agents/resolver/.../koog/ResolverGraph.kt` line ~162. Cross-references candidate spans against the caller's `Registry.entityTypes` and emits only spans likely to be domain entities. CHEAP-tier LLM call.

  Use Koog's `StructureFixingParser` at `~/Dev/view-only/koog/prompt/prompt-executor/prompt-executor-model/src/commonMain/kotlin/ai/koog/prompt/executor/model/StructureFixingParser.kt`. Public API:

  ```kotlin
  public class StructureFixingParser(
      public val model: LLModel,
      public val retries: Int,
      private val prompt: (PromptBuilder, String, Structure<*, *>, SerializationException) -> PromptBuilder
          = ::defaultFixingPrompt,
  ) {
      public suspend fun <T> parse(executor: PromptExecutor, structure: Structure<T, *>, content: String): T
  }
  ```

  Define a `Structure` for the expected output (list of relevant spans). Wrap the LLM call:

  ```kotlin
  @Serializable
  data class FilteredSpans(val spans: List<FilteredSpan>)
  @Serializable
  data class FilteredSpan(val begin: Int, val end: Int, val entityTypeCandidates: List<String>)

  val filterRelevantSpike = strategy("filter-relevant-spans") {
      val filter by node<ProposeOutput, FilteredSpans>("filter") { input ->
          val parser = StructureFixingParser(model = cheapModel, retries = 2)
          val raw = llmExecutor.execute(
              prompt = filterPrompt(input.parse, input.spans, input.registry),
              params = LLMParams(temperature = 0.0),
          )
          parser.parse(llmExecutor, Structure.from(FilteredSpans.serializer()), raw)
      }
      // ...
  }
  ```

  **LLM fake for testing**: Wiremock stub returning a deterministic JSON. Acceptance:
  - Happy path: stub returns valid JSON → parser succeeds.
  - Repair path: stub returns malformed JSON on first call, valid on second → parser repairs and succeeds with retries=2.
  - Hard fail: stub returns garbage three times → parser raises `LLMStructuredParsingError` after retries exhausted.

  Acceptance: 3 Kotest cases green; observed retry behaviour matches Koog's `StructureFixingParser` docs.

- [x] **T4 — Write spike report.**

  Create `agents/_koog-spike/docs/koog-spike-report.md` covering:

  ```markdown
  # Koog Spike Report — <DATE>

  ## Versions

  - Koog: <version actually built against>
  - Ktor: <version pulled in by ai-platform shared libs>
  - Kotlin: <toolchain version>

  ## Result

  ☐ GO — proceed to Stage 2.3 migration as planned
  ☐ NO-GO with fallback — Themis ships in plain Kotlin coroutines per v1 drift; Koog migration deferred to v1.1
  ☐ DEFER — return to Stage 2 planning; needs further analysis

  ## Build outcome

  - Initial `./gradlew :agents:_koog-spike:build` result: [pass / fail-with-trace]
  - Dependency conflicts surfaced: [none / list]
  - Resolution applied (if any): [overrides in libs.versions.toml / module exclusions / forced versions]

  ## Node port — deterministic (T2)

  - Lines of Kotlin in original ResolverGraph: [N]
  - Lines of Kotlin in Koog-ported version: [M]
  - Ratio M/N: [...]
  - Surprising patterns or API mismatches: [...]

  ## Node port — LLM-using with StructureFixingParser (T3)

  - Retry behaviour observed: [as documented / different]
  - Performance overhead vs raw LLM call: [...]
  - Test outcomes: [3/3 green]
  - Notes on graph composition with the prompt-executor abstraction: [...]

  ## Recommended decision

  [Claude's read; Bora makes the final call.]

  ## If GO — what changes vs the original plan

  - [None — proceed as plan §4.2 + §4.3 describe]
  - [Adjustments: ...]

  ## If NO-GO — fallback plan

  - Stage 2.3 becomes "preserve plain-coroutines ResolverGraph; rename `Resolver` → `Themis` in identifiers; document deviation."
  - v1.1 Koog migration ticket: [...].

  ## Decision log

  - Date:
  - Decision (Bora):
  - Rationale:
  ```

  Acceptance: report committed; Bora reviews and records the decision in the "Decision log" section.

- [x] **T5 — Update memory + Themis docs based on the decision.**

  After Bora records the decision in T4's "Decision log":

  - Update the kantheon memory file `pythia_framework_choice.md`:
    - If GO: "Resolver-vs-Koog v1 drift resolved as part of Phase 2 Stage 2.3 — Themis runs on Koog." Remove "Themis ships in plain coroutines initially" line.
    - If NO-GO: "Koog v1 drift maintained as v1; v1.1 migration ticket filed. Themis runs on plain Kotlin coroutines."

  - Update `docs/architecture/themis/architecture.md` §6 (Themis internal structure) — annotate Phase 2 Stage 2.3 description with the decision.

  - If NO-GO: update `docs/implementation/v1/themis/plan.md` Stage 2.3 to be "Preserve plain-coroutines graph; rename identifiers; document deviation" — and shrink the task count accordingly. Also update Stage 2.3 task list in advance of starting that stage.

  Acceptance: memory + architecture.md + (if applicable) plan.md updated; PR opened titled `[p2-s2.1] Koog spike — <GO/NOGO>` with the spike report as the primary deliverable.

## DONE — Stage 2.1

- [x] All five tasks above checked.
- [x] Spike report committed at `agents/_koog-spike/docs/koog-spike-report.md` with a recorded decision. _(written; commit + PR pending Bora.)_
- [x] `pythia_framework_choice` memory updated.
- [x] **GO** — Stage 2.3 proceeds as planned. (Decision 2026-05-29.)
- [ ] PR merged. _(pending Bora.)_

## Library / pattern references

- **Koog 0.8.0** at `~/Dev/view-only/koog/`. Especially:
  - `README.md` — feature overview.
  - `agents/agents-core/src/commonMain/kotlin/ai/koog/agents/core/agent/entity/AIAgentGraphStrategy.kt` — public API.
  - `prompt/prompt-executor/prompt-executor-model/src/commonMain/kotlin/ai/koog/prompt/executor/model/StructureFixingParser.kt` — retry+repair shape.
  - `examples/simple-examples/src/main/kotlin/ai/koog/agents/example/tone/ToneStrategy.kt` — graph-strategy example.
  - `koog-ktor/` — Ktor integration module (may be useful if Ktor compat needs adapter).
- **ai-platform `agents/resolver/src/main/kotlin/.../koog/ResolverGraph.kt`** — the reference being ported. Nine nodes as plain coroutines today.
- **kotlinx-serialization** for `@Serializable` data classes used as Koog `Structure` schemas — `https://kotlinlang.org/docs/serialization.html`.

## Out of scope for Stage 2.1

- Porting all nine Resolver nodes — only the two specified (T2 + T3).
- Production-quality prompt engineering — use the existing Resolver prompts as-is for the spike.
- Performance benchmarking — only a coarse "feels OK / slow" note in the report.
- K8s deployment — sandbox module isn't deployed.
- Eval-gate runs — those happen in Stage 2.4 against the full migrated graph.
