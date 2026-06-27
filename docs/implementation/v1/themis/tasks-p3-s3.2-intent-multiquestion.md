# Stage 3.2 — `classifyIntentKind` + `detectMultiQuestion`

> **Phase 3, Stage 3.2.**
>
> **Reads with.** [`tasks-p3-overview.md`](./tasks-p3-overview.md), [`plan.md`](./plan.md) §5.2, [`../../../architecture/themis/architecture.md`](../../../architecture/themis/architecture.md) §6.2 (Phase 3 graph delta), [`../../../architecture/themis/contracts.md`](../../../architecture/themis/contracts.md) §8 (prompts — `intent_kind_rules.yaml`).

## Goal

Two new Koog nodes wired into the Themis graph: `detectMultiQuestion` (before `extractUniversal`) and `classifyIntentKind` (after `extractUniversal`). `Resolution.intent_kind` populated for every CHAT_QUICK resolve. `MultiQuestionDetected` fires correctly on compound questions; conservative bias toward single-question.

## Pre-flight

- [ ] **Stage 3.1 DONE** — proto extensions merged.
- [ ] **Branch**: `feat/p3-s3.2-intent-multiquestion` from `main`.
- [ ] **NLP service availability** — `infra/nlp` (ai-platform) needs `parse.tokens.dep` populated so the dependency-graph rule in `detectMultiQuestion` can fire. Confirm with a Czech test call against `/v1/resolve` returning non-empty `parse.tokens.dep`.

## Tasks

- [ ] **T1 — Tests-first for `ClassifyIntentKindNode`.**

  Create `agents/themis/src/test/kotlin/org/tatrman/kantheon/themis/koog/nodes/ClassifyIntentKindSpec.kt`:

  ```kotlin
  class ClassifyIntentKindSpec : StringSpec({

      "Czech 'proč' triggers RCA via rules layer" {
          val parse = parseFixture("Proč klesly tržby Castrolu v soukromých garážích?", lang = "cs")
          val node = ClassifyIntentKindNode(rules = loadRules(), llmExecutor = stubNoCall)
          val out = runBlocking { node.run(parse) }
          out.intentKind shouldBe IntentKind.RCA
          out.source shouldBe IntentSource.RULE
          stubNoCall.callCount shouldBe 0       // LLM not invoked
      }

      "English 'why' triggers RCA via rules layer" {
          val parse = parseFixture("Why did Q3 revenue drop?", lang = "en")
          val out = runBlocking { ClassifyIntentKindNode(loadRules(), stubNoCall).run(parse) }
          out.intentKind shouldBe IntentKind.RCA
      }

      "Czech 'predikce' triggers FORECAST" { /* same shape */ }
      "English 'forecast' triggers FORECAST" { /* same shape */ }
      "Czech 'co kdyby' triggers SIMULATION" { /* same shape */ }
      "English 'what if' triggers SIMULATION" { /* same shape */ }
      "no trigger words → PROCEDURAL default" {
          val parse = parseFixture("Které faktury Shell ještě neuhradil?", lang = "cs")
          val out = runBlocking { ClassifyIntentKindNode(loadRules(), stubNoCall).run(parse) }
          out.intentKind shouldBe IntentKind.PROCEDURAL
          out.source shouldBe IntentSource.RULE_DEFAULT
      }

      "ambiguous (two triggers tied) → LLM fallback" {
          // Construct a parse where both RCA and FORECAST triggers fire.
          val parse = parseFixture("Proč klesly tržby a jaká je předpověď?", lang = "cs")
          val llmStub = stubLlmReturning(IntentKind.RCA, confidence = 0.82)
          val out = runBlocking { ClassifyIntentKindNode(loadRules(), llmStub).run(parse) }
          out.intentKind shouldBe IntentKind.RCA
          out.source shouldBe IntentSource.LLM_FALLBACK
          out.confidence shouldBe 0.82
      }

      "triggers operate on lemmas, not raw text" {
          // Czech 'predikuji' (1st person sing, present) → lemma 'predikovat'.
          // If rules YAML has 'predikce' (noun) AND lemmatisation aligns these, FORECAST should fire.
          // Test: a Czech sentence using a different inflected form should still match.
          val parse = parseFixtureWithLemmas("Predikuji další pokles.", lemmas = listOf("predikovat", "další", "pokles", "."))
          // Assuming rules has 'predikovat' (lemma) — expect FORECAST.
          val out = runBlocking { ClassifyIntentKindNode(loadRules(), stubNoCall).run(parse) }
          out.intentKind shouldBe IntentKind.FORECAST
      }
  })
  ```

  Helpers (`parseFixture`, `loadRules`, `stubNoCall`, `stubLlmReturning`) in `Fixtures.kt`.

  Acceptance: tests written; they fail.

- [ ] **T2 — Create `prompts/intent_kind_rules.yaml`.**

  Use the schema from [`contracts.md`](../../../architecture/themis/contracts.md) §8.2. Start with the first-pass seed:

  ```yaml
  rules:
    - intent: RCA
      triggers:
        cs: ["proč", "co způsobilo"]
        en: ["why", "what caused"]
      operates_on: lemmas
    - intent: FORECAST
      triggers:
        cs: ["predikce", "prognóza", "očekávat", "predikovat"]
        en: ["forecast", "predict", "expect"]
      operates_on: lemmas
      extra_signals:
        - future_tense_temporal_reference
        - explicit_future_date
    - intent: SIMULATION
      triggers:
        cs: ["co kdyby"]
        en: ["what if"]
      operates_on: lemmas
      extra_signals:
        - hypothetical_conditional
    - intent: PROCEDURAL
      is_default: true
  ```

  Place at `agents/themis/src/main/resources/prompts/intent_kind_rules.yaml`. Load via existing Jackson YAML reader (same pattern as Phase 1 capabilities-mcp's `ManifestYamlLoader`).

  Note: Czech/English coverage will grow iteratively from eval-corpus disagreements. Treat the YAML as living config.

  Acceptance: YAML loads without error; `loadRules()` test helper returns the parsed structure.

- [ ] **T3 — Implement `ClassifyIntentKindNode` (rules first, LLM fallback).**

  ```kotlin
  package org.tatrman.kantheon.themis.koog.nodes

  data class IntentClassifyInput(val parse: AnalyzeResponse, val locale: String)
  data class IntentClassifyOutput(
      val intentKind: IntentKind,
      val confidence: Double,
      val source: IntentSource,
      val rationale: String,
  )
  enum class IntentSource { RULE, RULE_DEFAULT, LLM_FALLBACK }

  class ClassifyIntentKindNode(
      private val rules: IntentKindRules,
      private val llmExecutor: PromptExecutor,
      private val cheapModel: LLModel,
  ) {
      suspend fun run(input: IntentClassifyInput): IntentClassifyOutput {
          val lemmas = input.parse.tokensList.map { it.lemma }
          val matches = rules.matchAllLemmas(lemmas, lang = input.locale)
              .plus(rules.matchExtraSignals(input.parse))
              .distinct()

          return when {
              matches.isEmpty() -> {
                  // Default to PROCEDURAL per rule chain.
                  IntentClassifyOutput(IntentKind.PROCEDURAL, 1.0, IntentSource.RULE_DEFAULT, "no trigger; default")
              }
              matches.size == 1 -> {
                  IntentClassifyOutput(matches.first(), 1.0, IntentSource.RULE, "rule match: ${matches.first()}")
              }
              else -> {
                  // LLM fallback for tie.
                  val llmResult = invokeCheapLlm(input, llmExecutor, cheapModel)
                  IntentClassifyOutput(llmResult.intentKind, llmResult.confidence, IntentSource.LLM_FALLBACK, llmResult.rationale)
              }
          }
      }

      private suspend fun invokeCheapLlm(...): LlmIntentResult { /* uses StructureFixingParser with prompts/intent_kind_llm.md */ }
  }

  @Serializable
  data class LlmIntentResult(val intentKind: IntentKind, val confidence: Double, val rationale: String)
  ```

  Implement `IntentKindRules.matchAllLemmas` and `.matchExtraSignals` per the YAML schema. `extra_signals` like `future_tense_temporal_reference` are computed from `parse.tokens` (POS + dep-graph features — there's already a `TemporalSignals` helper carried over from Resolver; reuse).

  Also create `prompts/intent_kind_llm.md` — short prompt instructing the LLM to choose one of the four IntentKind values for the given Czech/English question. Output: `LlmIntentResult` JSON (used by `StructureFixingParser`).

  Wire `ClassifyIntentKindNode` as a Koog node in `koog/ThemisGraph.kt` after `extractUniversal`.

  Re-run tests from T1. All green.

  Acceptance: T1 tests green; `Resolution.intent_kind` populated across the existing mocked component tests.

- [ ] **T4 — Tests-first for `DetectMultiQuestionNode`.**

  Create `DetectMultiQuestionSpec.kt`:

  ```kotlin
  class DetectMultiQuestionSpec : StringSpec({

      "single question — does not fire" {
          val parse = parseFixture("Které faktury Shell ještě neuhradil?", lang = "cs")
          val node = DetectMultiQuestionNode()
          val out = runBlocking { node.run(parse) }
          out shouldBe DetectMultiQuestionOutput.SingleQuestion
      }

      "two disjoint clauses fires" {
          val parse = parseFixture("Které faktury Shell neuhradil a jaká byla Q3 marže?", lang = "cs")
          val out = runBlocking { DetectMultiQuestionNode().run(parse) }
          out shouldBe DetectMultiQuestionOutput.MultiQuestion(
              subQuestions = listOf("Které faktury Shell neuhradil?", "Jaká byla Q3 marže?")
          )
      }

      "two clauses same entity — does NOT fire (conservative bias)" {
          val parse = parseFixture("Které faktury Shell neuhradil a jaká je jejich celková částka?", lang = "cs")
          val out = runBlocking { DetectMultiQuestionNode().run(parse) }
          out shouldBe DetectMultiQuestionOutput.SingleQuestion
      }

      "single clause with multiple verbs — does NOT fire" {
          val parse = parseFixture("Které faktury Shell neuhradil a má zaplatit?", lang = "cs")
          val out = runBlocking { DetectMultiQuestionNode().run(parse) }
          out shouldBe DetectMultiQuestionOutput.SingleQuestion
      }

      "English two-clauses with 'and' — fires" {
          val parse = parseFixture("What are unpaid invoices and what is Q3 margin?", lang = "en")
          val out = runBlocking { DetectMultiQuestionNode().run(parse) }
          out::class shouldBe DetectMultiQuestionOutput.MultiQuestion::class
      }
  })
  ```

  Acceptance: failing tests written.

- [ ] **T5 — Implement `DetectMultiQuestionNode`.**

  ```kotlin
  sealed interface DetectMultiQuestionOutput {
      data object SingleQuestion : DetectMultiQuestionOutput
      data class MultiQuestion(val subQuestions: List<String>) : DetectMultiQuestionOutput
  }

  class DetectMultiQuestionNode {
      suspend fun run(parse: AnalyzeResponse): DetectMultiQuestionOutput {
          // 1. Find independent clause roots (tokens whose POS is VERB and dep_rel ∈ {ROOT, conj_root}).
          val clauseRoots = findClauseRoots(parse)
          if (clauseRoots.size <= 1) return DetectMultiQuestionOutput.SingleQuestion

          // 2. For each clause, extract referenced entities (NER spans + nominal NPs).
          val entitiesPerClause = clauseRoots.map { entitiesIn(it.clauseSpan) }

          // 3. Conservative-bias check: clauses must have DISJOINT entity references AND DIFFERENT verbs.
          val disjoint = entitiesPerClause.allPairsDisjoint()
          val differentVerbs = clauseRoots.map { it.lemma }.toSet().size == clauseRoots.size
          if (!disjoint || !differentVerbs) return DetectMultiQuestionOutput.SingleQuestion

          // 4. Reconstruct sub-question text from token spans.
          val subQuestions = clauseRoots.map { reconstructSentence(parse, it.clauseSpan) }
          return DetectMultiQuestionOutput.MultiQuestion(subQuestions)
      }
  }
  ```

  Wire into `ThemisGraph` between `detectLangAndParse` and `extractUniversal`. If output is `MultiQuestion`, emit `AwaitingClarification` with `kind: MultiQuestionDetected` and short-circuit (no further nodes run). If `SingleQuestion`, edge continues to `extractUniversal`.

  Re-run T4 tests. All green.

  Acceptance: T4 tests green; graph component test verifies the short-circuit path emits `AwaitingClarification.MULTI_QUESTION` outcome.

- [ ] **T6 — Component test against the full graph (mocked).**

  Per the testing policy (planning-conventions.md §4): mocked unit tests only; integration suite is separate. This exercises the full in-process Koog graph against fixtures and mocked tool clients (no real external dependencies).

  Add `agents/themis/src/test/kotlin/.../component/Phase3IntentMultiQuestionComponentSpec.kt`:

  ```kotlin
  class Phase3IntentMultiQuestionComponentSpec : StringSpec({
      "CHAT_QUICK resolve populates Resolution.intent_kind for a Czech RCA question" {
          val req = ResolveRequest.newBuilder()
              .setFresh(FreshQuestion.newBuilder().setText("Proč klesly tržby Castrolu?"))
              .setProfile(Profile.CHAT_QUICK)
              .setRegistry(testRegistry())
              .setContext(ResolveContext.newBuilder().setLocale("cs"))
              .build()
          val resp = themis.resolve(req)
          resp.outcomeCase shouldBe ResolveResponse.OutcomeCase.RESOLUTION
          resp.resolution.intentKind shouldBe IntentKind.RCA
      }

      "compound Czech question produces AwaitingClarification.MultiQuestion" {
          val req = ResolveRequest.newBuilder()
              .setFresh(FreshQuestion.newBuilder().setText("Které faktury Shell neuhradil a jaká byla Q3 marže?"))
              .setProfile(Profile.CHAT_QUICK)
              .setRegistry(testRegistry())
              .setContext(ResolveContext.newBuilder().setLocale("cs"))
              .build()
          val resp = themis.resolve(req)
          resp.outcomeCase shouldBe ResolveResponse.OutcomeCase.AWAITING
          resp.awaiting.kindCase shouldBe AwaitingClarification.KindCase.MULTI_QUESTION
          resp.awaiting.multiQuestion.subQuestionsList shouldHaveSize 2
      }
  })
  ```

  Acceptance: component test green; existing Phase 2 eval gate (50-question Czech corpus from Stage 2.4) **still passes** (with one expected difference: `intent_kind` field is now populated; the eval harness needs to tolerate this — either ignore the field or assert against newly-added expectations).

  Update `agents/themis/eval/corpus/seed.jsonl` entries to add `"intent_kind": "PROCEDURAL"` to each (since the 50-question corpus is single-domain ERP — all should be PROCEDURAL). This is a one-off update to keep the eval gate green during Phase 3.

  PR opened.

## Status (2026-06-20) — landed; deviations from the pre-fork task list

The illustrative code in T1/T3/T5 assumed shapes that don't exist post-fork/post-Koog-migration. Adapted to the real code (verified by Explore map), keeping the stage's intent:

- **Nodes operate on the in-graph `NlpAnalyzeResult`/`NlpToken`** (kotlinx-serializable, with `lemma`/`upos`/`depHead`/`depRelation`/`feats`), **not** the proto `AnalyzeResponse`. Node bodies are free `*Step(state, …)` functions (codebase idiom), not classes.
- **Rules externalised as YAML** (`prompts/intent_kind_rules.yaml`) loaded by a Jackson-YAML `IntentKindRules` (same stack as the `*-mcp` manifest loaders; added jackson deps to themis). Matching is contiguous-word-sequence over lemmas **and** raw text (catches inflected/base forms); `extra_signals` computed from UD `feats` (`Tense=Fut`, `Mood=Cnd`).
- **No `intent_kind_llm.md` file** — the codebase loads no prompt files; the tie-break prompt is built inline and calls `LlmGatewayClient.complete(model="haiku")`, matching the other LLM nodes' stub-on-failure pattern.
- **No `TemporalSignals` helper existed** — `extra_signals` are computed inline.
- **`detectMultiQuestion` discriminator:** ≥2 clause heads (`dep_relation ∈ {root, conj}`, handles copulas), each clause owns a **distinct content noun**, and no clause contains an **anaphoric pronoun**. Strong single-question bias. Fires → `AwaitingClarification.kind=MultiQuestionDetected` (decomposition `SPLIT`) and short-circuits to `nodeFinish`.
- **Wired into the production Koog graph** (`buildThemisGraph`): `detect → detectMulti →[multi] nodeFinish /[single] extract`, and `extract → classify → propose`. `intent_kind` carried on `ParseState`, set on `Resolution` in `decideHitlOrEmit`, projected over REST + MCP.
- **`ThemisGraphDispatch` left untouched** (the legacy `lastNode` loop used by 3 pre-Koog tests; it doesn't classify/detect, but those tests don't assert the new fields → green). It was slated for deletion in Stage 2.3 T6 — **recommend deleting it + migrating its 3 tests to `runThemisGraph`** as a small follow-up to remove the dual wiring.

- [x] **T1/T3 — `ClassifyIntentKind`** (rules-first + cheap-LLM tie-break). `ClassifyIntentKindSpec` (10 specs) green.
- [x] **T2 — `prompts/intent_kind_rules.yaml`** seed triggers + `IntentKindRules` loader.
- [x] **T4/T5 — `DetectMultiQuestion`** deterministic UD detector. `DetectMultiQuestionSpec` (5 specs) green.
- [x] **T6 — component test** (`Phase3IntentMultiQuestionComponentSpec`) drives the real `runThemisGraph`: intent_kind populated; compound question short-circuits to `MULTI_QUESTION`. Eval corpus `seed.jsonl` annotated `intent_kind: PROCEDURAL` (×50).
- [x] Both new nodes wired into the Koog graph; full `:agents:themis:test` green (91 tests, 0 failures).
- [ ] PR merged.

## DONE — Stage 3.2

- [x] T1–T6 done; PR/merge pending.
- [x] `prompts/intent_kind_rules.yaml` committed with first-pass seed triggers.
- [x] Both new nodes wired into the Koog graph; existing tests still green.
- [x] Component test green; eval corpus annotated (the corpus eval itself runs in the integration track per the Stage 2.4 relocation).
- [ ] PR merged.

## Library / pattern references

- **ai-platform `agents/resolver/src/main/kotlin/.../prompts/`** — for prompt-file loading conventions if not already established.
- **Koog `StructureFixingParser`** for the LLM-fallback structured-output call — pattern from Stage 2.1 spike and Stage 2.3 migration.
- **kotlinx-serialization `@Serializable`** for `LlmIntentResult`.
- **Universal Dependencies (UD)** docs — https://universaldependencies.org — for understanding `parse.tokens.dep` features in `detectMultiQuestion`.

## Out of scope for Stage 3.2

- `routeToAgent` (Stage 3.3).
- Routing eval corpus (Stage 3.4 + 3.5).
- LLM model selection / cost tracking — uses existing CHEAP-tier from llm-gateway.
- Czech/English trigger-set expansion beyond first-pass — iterative from eval-corpus disagreements.
- Languages beyond Czech and English (Slovak, Hungarian, German planned for later versions).
