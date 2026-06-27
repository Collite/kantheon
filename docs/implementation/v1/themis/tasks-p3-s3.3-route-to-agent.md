# Stage 3.3 — `routeToAgent` four-layer cascade

> **Phase 3, Stage 3.3.** The biggest stage of Phase 3 — 7 tasks rather than 6.
>
> **Reads with.** [`tasks-p3-overview.md`](./tasks-p3-overview.md), [`plan.md`](./plan.md) §5.3, [`../../../architecture/themis/architecture.md`](../../../architecture/themis/architecture.md) §6.2, [`../../../architecture/themis/contracts.md`](../../../architecture/themis/contracts.md) §1.2 (RoutingDecision proto) + §4 (capabilities-client) + §8 (`route_to_agent_layer2.md` prompt).

## Goal

`routeToAgent` Koog node populated. Four-layer cascade exercised by tests. `CapabilitiesReadClient` integrated with fail-fast boot. `relevant_capabilities` computed correctly. Profile skip path verified (INVESTIGATION_DEEP skips routing entirely).

## Pre-flight

- [ ] **Stage 3.2 DONE** — intent classifier + multi-question detector merged.
- [ ] **capabilities-mcp running** in K3s with the seed `pythia` + `golem-erp` fixtures.
- [ ] **Branch**: `feat/p3-s3.3-route-to-agent` from `main`.

## Tasks

- [ ] **T1 — Wire `CapabilitiesReadClient` into Themis with fail-fast boot.**

  In `agents/themis/src/main/kotlin/.../App.kt`:

  ```kotlin
  fun Application.module(serverConfig: KtorServerConfig) {
      installKtorServerBase(serverConfig)

      val capabilitiesRead = CapabilitiesReadClient(
          endpoint = environment.config.property("themis.capabilities-mcp-url").getString(),
          cacheTtlMs = 30_000,
      )

      // Fail-fast at boot: if capabilities-mcp returns no agents, refuse to start.
      runBlocking {
          val agents = capabilitiesRead.listAgents().agentsList
          require(agents.isNotEmpty()) {
              "capabilities-mcp returned no agents at startup — refusing to start to avoid silent " +
              "routing-everything-to-Layer-3. Check the capabilities-mcp deployment and YAML fixtures."
          }
          logger.info { "loaded ${agents.size} agents from capabilities-mcp: ${agents.map { it.agentId }}" }
      }

      // ... rest of module ...
  }
  ```

  Update `application.conf`:

  ```hocon
  themis {
      capabilities-mcp-url = "http://capabilities-mcp.kantheon.svc.cluster.local:7501"
      capabilities-mcp-url = ${?CAPABILITIES_MCP_URL}
  }
  ```

  Tests:

  ```kotlin
  class FailFastBootSpec : StringSpec({
      "Themis fails to start when capabilities-mcp returns empty agents" {
          // Spin up a Wiremock returning empty agents list; expect Application.module to throw.
          val wm = WireMockServer(0).apply { start() }
          wm.stubFor(get("/v1/capabilities/agents").willReturn(okJson("""{"agents":[],"messages":[]}""")))
          shouldThrow<IllegalArgumentException> {
              testApplication { application { module(testConfig(wm.port())) } }
          }
          wm.stop()
      }

      "Themis starts when capabilities-mcp returns at least one agent" {
          val wm = WireMockServer(0).apply { start() }
          wm.stubFor(get("/v1/capabilities/agents").willReturn(okJson("""{"agents":[{"agentId":"pythia",...}],"messages":[]}""")))
          testApplication {
              application { module(testConfig(wm.port())) }
              client.get("/health").status shouldBe HttpStatusCode.OK
          }
          wm.stop()
      }
  })
  ```

  Acceptance: tests green; manual smoke: deploy themis with capabilities-mcp running → starts; deploy themis with `CAPABILITIES_MCP_URL` pointing at a non-existent service → pod crashloops with the require-message in the log.

- [ ] **T2 — Tests-first for the four routing layers.**

  Create `agents/themis/src/test/kotlin/.../koog/nodes/RouteToAgentSpec.kt`. Each layer gets fixtures:

  ```kotlin
  class RouteToAgentSpec : StringSpec({

      // Fixtures: registry preloaded with 'pythia' (INVESTIGATOR) + 'golem-erp' (DOMAIN_QA, ERP entities).

      "Layer 0 — routing_hint short-circuits" {
          val input = routeInput(
              hint = AgentId.newBuilder().setValue("pythia").build(),
              intentKind = IntentKind.RCA,
          )
          val out = runBlocking { RouteToAgentNode(stubs).run(input) }
          out.decision.chosenAgentId.value shouldBe "pythia"
          out.decision.layerHit shouldBe 0
          out.decision.confidence shouldBe 1.0
          stubs.llmExecutor.callCount shouldBe 0    // no LLM call
      }

      "Layer 1 — RCA + single-Pythia-match returns high-confidence" {
          val input = routeInput(intentKind = IntentKind.RCA, entities = listOf("customer:Shell"))
          val out = runBlocking { RouteToAgentNode(stubs).run(input) }
          out.decision.chosenAgentId.value shouldBe "pythia"
          out.decision.layerHit shouldBe 1
          out.decision.confidence shouldBeGreaterThan 0.5
          stubs.llmExecutor.callCount shouldBe 0
      }

      "Layer 1 — PROCEDURAL on single Golem-ERP domain entity goes to golem-erp" {
          val input = routeInput(intentKind = IntentKind.PROCEDURAL, entities = listOf("invoice:Shell.unpaid"))
          val out = runBlocking { RouteToAgentNode(stubs).run(input) }
          out.decision.chosenAgentId.value shouldBe "golem-erp"
          out.decision.layerHit shouldBe 1
      }

      "Layer 2 — tied Layer-1 scores trigger LLM call returning structured decision" {
          // Set up: register two agents with overlapping intent_kinds; Layer-1 scores within ε.
          // Stub LLM to return RoutingDecision JSON.
          val input = routeInput(intentKind = IntentKind.PROCEDURAL, entities = listOf("ambiguous:Foo"))
          val llmStub = stubLlmReturningRouting("pythia", confidence = 0.82, rationale = "cross-domain")
          val out = runBlocking { RouteToAgentNode(stubs.copy(llmExecutor = llmStub)).run(input) }
          out.decision.chosenAgentId.value shouldBe "pythia"
          out.decision.layerHit shouldBe 2
          out.decision.confidence shouldBe 0.82
          llmStub.callCount shouldBe 1
      }

      "Layer 3 — low Layer-2 confidence triggers needs_user_pick with top-3 alternates" {
          val input = routeInput(intentKind = IntentKind.PROCEDURAL, entities = listOf("ambiguous"))
          val llmStub = stubLlmReturningRouting("pythia", confidence = 0.35, alternates = listOf("golem-erp", "golem-hr"))
          val out = runBlocking { RouteToAgentNode(stubs.copy(llmExecutor = llmStub)).run(input) }
          out.decision.needsUserPick shouldBe true
          out.decision.layerHit shouldBe 3
          out.decision.alternatesList shouldHaveSize 3       // top-3 (chosen + alternates merged)
      }

      "INVESTIGATION_DEEP profile skips routing entirely" {
          val input = routeInput(profile = Profile.INVESTIGATION_DEEP)
          val out = runBlocking { RouteToAgentNode(stubs).run(input) }
          out.skip shouldBe true                          // Resolution.routing remains unset
      }

      "relevant_capabilities computed from entity types + intent_kind against search_tags" {
          val input = routeInput(intentKind = IntentKind.FORECAST, entities = listOf("invoice:..."))
          val out = runBlocking { RouteToAgentNode(stubs).run(input) }
          out.relevantCapabilities shouldContain "model.fit.arima:v1"
      }
  })
  ```

  Helpers: `routeInput(...)`, `stubLlmReturningRouting(...)` in `Fixtures.kt`.

  Acceptance: tests written; they fail.

- [ ] **T3 — Implement Layer 0 — `routing_hint` short-circuit.**

  In `koog/nodes/RouteToAgentNode.kt`:

  ```kotlin
  class RouteToAgentNode(
      private val capabilities: CapabilitiesReadClient,
      private val llmExecutor: PromptExecutor,
      private val cheapModel: LLModel,
      private val layerWeights: LayerWeights = LayerWeights.default(),
  ) {
      suspend fun run(input: RouteInput): RouteOutput {
          if (input.profile == Profile.INVESTIGATION_DEEP) return RouteOutput.skip()

          // Layer 0: explicit override.
          input.routingHint?.let { hint ->
              return RouteOutput.decision(RoutingDecision.newBuilder()
                  .setChosenAgentId(hint)
                  .setConfidence(1.0)
                  .setRationale("hint honoured")
                  .setLayerHit(0)
                  .build())
          }

          // Layers 1-3 — implemented in T4-T6.
          TODO("layers 1-3")
      }
  }
  ```

  Run T2 tests; "Layer 0" case passes; others still fail. Commit.

- [ ] **T4 — Implement Layer 1 — rule-based scoring (no LLM).**

  ```kotlin
  data class LayerWeights(
      val intentKindMatch: Double = 0.5,
      val entitiesInDomain: Double = 0.4,
      val rcaForecastSimulationToPythia: Double = 0.4,
      val crossDomainToPythia: Double = 0.3,
      val capabilityMatch: Double = 0.2,
      val epsilon: Double = 0.2,
      val minScore: Double = 0.5,
  ) {
      companion object { fun default() = LayerWeights() }
  }

  private suspend fun layer1(input: RouteInput): RoutingDecision? {
      val agents = capabilities.listAgents().agentsList
      val scores = agents.map { agent ->
          var score = 0.0
          if (input.intentKind in agent.intentKindsSupportedList)                        score += layerWeights.intentKindMatch
          if (input.intentKind == IntentKind.PROCEDURAL &&
              input.entities.allFitIn(agent.domainEntitiesList))                         score += layerWeights.entitiesInDomain
          if (input.intentKind in setOf(IntentKind.RCA, IntentKind.FORECAST, IntentKind.SIMULATION)
              && agent.agentId == "pythia")                                              score += layerWeights.rcaForecastSimulationToPythia
          if (input.entities.spanMultipleAgents(agents) && agent.agentId == "pythia")    score += layerWeights.crossDomainToPythia
          if (input.relevantCapabilities.containsAll(agent.capabilityRefsList))          score += layerWeights.capabilityMatch
          ScoredAgent(agent.agentId, score)
      }.sortedByDescending { it.score }

      val top = scores.first()
      val second = scores.getOrNull(1)
      val gap = top.score - (second?.score ?: 0.0)
      if (gap > layerWeights.epsilon && top.score >= layerWeights.minScore) {
          return RoutingDecision.newBuilder()
              .setChosenAgentId(AgentId.newBuilder().setValue(top.agentId))
              .setConfidence(top.score)
              .setRationale("rule-based: top=${top.score}, second=${second?.score ?: 0.0}, gap=$gap")
              .setLayerHit(1)
              .build()
      }
      return null
  }
  ```

  Note: the weights (`+0.5 / +0.4 / +0.3 / +0.2`) are **hand-tuned guesses**. Stage 3.5 re-tunes them against the populated eval corpus.

  Run T2 tests; Layer-1 cases pass.

  Acceptance: Layer 1 implemented; commit.

- [ ] **T5 — Implement Layer 2 — CHEAP-tier LLM fallback.**

  Create `prompts/route_to_agent_layer2.md`:

  ```markdown
  You are routing a question to the most appropriate agent in a constellation.

  Question (verbatim): {{question}}
  Intent kind classified upstream: {{intentKind}}
  Resolved entities: {{entitiesYaml}}

  Top-5 candidate agents:
  {{#each candidates}}
  - **{{agentId}}** ({{agentKind}}): {{descriptionForRouter}}
    Example questions:
    {{#each exampleQuestions}}- {{this}}
    {{/each}}
    Counter-examples (do NOT route to this agent for these):
    {{#each counterExamples}}- {{this}}
    {{/each}}
  {{/each}}

  Conversation history excerpt: {{historyExcerpt}}

  Return a JSON object matching this schema:

  ```json
  {
    "chosen_agent_id": "<agent_id>",
    "confidence": 0.0,
    "rationale": "<one sentence>",
    "alternates": [
      { "agent_id": "<agent_id>", "score": 0.0, "why": "<one short reason>" }
    ]
  }
  ```

  Choose exactly one `chosen_agent_id` from the candidates. List up to 3 alternates ranked by score.
  Be conservative on confidence: if multiple agents could plausibly answer, set confidence ≤ 0.7.
  ```

  Implement Layer 2:

  ```kotlin
  @Serializable
  data class RoutingLlmResult(
      @SerialName("chosen_agent_id") val chosenAgentId: String,
      val confidence: Double,
      val rationale: String,
      val alternates: List<RoutingLlmAlternate> = emptyList(),
  )

  @Serializable
  data class RoutingLlmAlternate(
      @SerialName("agent_id") val agentId: String,
      val score: Double,
      val why: String,
  )

  private suspend fun layer2(input: RouteInput, scored: List<ScoredAgent>): RoutingLlmResult {
      val top5 = scored.take(5).map { capabilities.get(it.agentId) }   // fetches full manifest
      val prompt = renderPrompt("route_to_agent_layer2.md", mapOf(
          "question" to input.question,
          "intentKind" to input.intentKind.name,
          "entitiesYaml" to input.entities.toYaml(),
          "candidates" to top5,
          "historyExcerpt" to input.conversationExcerpt,
      ))
      val parser = StructureFixingParser(model = cheapModel, retries = 2)
      val executor = llmExecutor
      val raw = executor.execute(prompt, LLMParams(temperature = 0.0))
      return parser.parse(executor, Structure.from(RoutingLlmResult.serializer()), raw)
  }
  ```

  In `run()`: if Layer 1 returns null, call Layer 2. If `result.confidence >= 0.7`, return as `RoutingDecision(layerHit = 2)`. Otherwise fall through to Layer 3.

  Run T2 tests; Layer-2 case passes.

  Acceptance: Layer 2 implemented; commit.

- [ ] **T6 — Implement Layer 3 — `needs_user_pick` + alternates.**

  ```kotlin
  private suspend fun layer3(input: RouteInput, llmResult: RoutingLlmResult): RoutingDecision {
      val top3 = (listOf(RoutingLlmAlternate(llmResult.chosenAgentId, llmResult.confidence, llmResult.rationale))
                  + llmResult.alternates).take(3)
      return RoutingDecision.newBuilder()
          .setNeedsUserPick(true)
          .addAllAlternates(top3.map { alt ->
              AgentAlternate.newBuilder()
                  .setAgentId(AgentId.newBuilder().setValue(alt.agentId))
                  .setScore(alt.score)
                  .setWhy(alt.why)
                  .build()
          })
          .setRationale("Layer-2 confidence ${llmResult.confidence} below 0.7 threshold — user pick required")
          .setLayerHit(3)
          .build()
  }
  ```

  Run T2 tests; Layer-3 case passes (all 7 tests now green).

  Acceptance: Layer 3 implemented; commit; full RouteToAgent spec green.

- [ ] **T7 — `relevant_capabilities` + wire into `ThemisGraph`.**

  Implement `relevant_capabilities` matching inside the node (computed once per call, used by Layer 1's `capabilityMatch` weight):

  ```kotlin
  private suspend fun computeRelevantCapabilities(input: RouteInput): List<String> {
      // Match entity types + intent_kind against capabilities-mcp tools' search_tags.
      val tools = capabilities.search(SearchRequest.newBuilder()
          .addAllIntentKinds(listOf(input.intentKind))
          .addAllEntityTypes(input.entities.map { it.type })
          .build()).entriesList
          .filter { it.hasTool() }
          .map { it.tool.capabilityId }
      return tools
  }
  ```

  Wire into `ThemisGraph` per [`architecture.md`](../../../architecture/themis/architecture.md) §6.2:

  ```
  ... → jointInference → routeToAgent → decideHitlOrEmit → ...
  ```

  When `Profile == INVESTIGATION_DEEP`: edge skips `routeToAgent` and goes directly from `jointInference` to `decideHitlOrEmit` with `Resolution.routing` unset.

  When `routeToAgent` returns `needs_user_pick = true`: the result still flows through `decideHitlOrEmit` → `assembleResp`; the `Resolution.routing.needs_user_pick = true` field tells Iris to render chips (Stage 3.6 wires the FE).

  Add a mocked component test (full in-process graph, capabilities client mocked — per the testing policy, planning-conventions.md §4: mocked unit tests only; integration suite is separate):

  ```kotlin
  "CHAT_QUICK full-graph: a Czech ERP question routes to golem-erp via Layer 1" {
      val req = ResolveRequest.newBuilder()
          .setFresh(FreshQuestion.newBuilder().setText("Které faktury Shell ještě neuhradil?"))
          .setProfile(Profile.CHAT_QUICK)
          .setRegistry(testRegistry())
          .build()
      val resp = themis.resolve(req)
      resp.resolution.routing.chosenAgentId.value shouldBe "golem-erp"
      resp.resolution.routing.layerHit shouldBe 1
  }
  ```

  Acceptance: component test green; PR opened.

## Status (2026-06-21) — landed; deviations from the pre-fork task list

The illustrative code assumed a typed-proto capabilities client, a `RouteToAgentNode` class with `PromptExecutor`/`StructureFixingParser`, and an `Application.module`. Adapted to the real code:

- **`CapabilitiesReadClient` returns raw `JsonObject`** (camelCase REST mirror) and has **no `search`** method — agents/tools are parsed by hand; `relevant_capabilities` is computed from `list()` tools whose `searchTags` intersect the intent/entity tags.
- **`routeToAgent` is a free function + `routeToAgentStep`** (codebase idiom), not a class. Layer 2 uses `LlmGatewayClient.complete(model="haiku")` + kotlinx `Json` (the established pattern), **not** `StructureFixingParser`. The Layer-2 prompt is built inline (no `route_to_agent_layer2.md` file — the codebase loads no prompt files).
- **`profile` + `routingHint` added to `ResolverContext`** (threaded from `ResolveRequest` in `handleRestResolve`); **`routingDecision` added to `ParseState`**; `decideHitlOrEmit` attaches it to `Resolution.routing`; projected over REST + MCP.
- **Layer-1 scoring generalised to `agentKind == INVESTIGATOR`** (not hardcoded `"pythia"`); `non_routable` agents filtered out. Weights are hand-tuned (Stage 3.5 re-tunes). visibility-roles filtering is deferred to Stage 3.4 (PD-8).
- **`routeToAgentStep` degrades gracefully**: a registry hiccup at request time logs + leaves `routing` unset rather than failing the resolve (routing is advisory).
- **Boot:** no `Application.module` — fail-fast is a standalone `assertRoutableAgentsAvailable(client)` called in `main()` (unreachable → `IllegalStateException`; empty → `IllegalArgumentException`), unit-tested against WireMock.
- Graph wiring: `jointInference → routeToAgent → decideHitlOrEmit` (NORMAL mode); `routeToAgent` no-ops internally for INVESTIGATION_DEEP. `ThemisGraphDispatch` (legacy) still not updated — see the Stage 3.2 recommendation to delete it.

- [x] **T1 — fail-fast boot** (`assertRoutableAgentsAvailable` + `CapabilitiesReadClient` wired in `main`; `themis.capabilities` HOCON + k8s `CAPABILITIES_MCP_HOST/PORT`). `FailFastBootSpec` (3) green.
- [x] **T2/T3–T6 — the four layers.** `RouteToAgentSpec` (7) green: Layer 0 hint, Layer 1 RCA→investigator + PROCEDURAL→golem-erp, Layer 2 LLM tie-break, Layer 3 user-pick top-3, INVESTIGATION_DEEP skip, relevant_capabilities.
- [x] **T7 — `relevant_capabilities` + graph wiring + component test.** `Phase3RoutingComponentSpec`: a CHAT_QUICK Czech ERP question routes to `golem-erp` via Layer 1 through `runThemisGraph`, with `Resolution.routing` populated.
- [x] Full `:agents:themis:test` green (102 tests, 0 failures); ktlint clean; kustomize valid.
- [ ] PR merged.

## DONE — Stage 3.3

- [x] T1–T7 done; PR/merge pending.
- [x] `RouteToAgentSpec` green.
- [x] Fail-fast boot verified (unit test; live crashloop smoke deferred to deploy).
- [x] Component test routing a Czech ERP question to `golem-erp` via Layer 1 passes.
- [ ] PR merged.

## Post-review hardening (2026-06-21)

Code-review pass on Stages 3.1–3.4 surfaced a few refinements; applied in the same branch:

- **MCP `resolve` is now an explicit reduced surface.** The MCP tool schema exposes no registry/profile/hitl, so a full cascade was firing a Layer-2 LLM call per request with no usable input. Added `ResolverContext.routingEnabled` (default `true`); `handleResolveTool` sets it `false`; `routeToAgentStep` leaves `Resolution.routing` unset when disabled. Rich routing + STRICT refusal remain on REST `/v1/resolve`. (Decision: option (a) — reduced surface — over (b) schema expansion, which belongs to the 3.6 Iris co-design.)
- **Layer 0 is genuinely zero-cost** — `computeRelevantCapabilities` moved below the `routing_hint` short-circuit, so a pinned agent no longer triggers a capabilities-mcp round-trip.
- **`elapsed_ms`** now measures wall-clock from handler entry (REST + MCP) instead of `now − nlp.elapsedMs` (a duration subtracted from an epoch timestamp).
- **Robustness**: Layer-2 alternate `score` parses via `doubleOrNull` (a malformed score no longer drops the whole verdict to fallback); the Layer-2 prompt uses the verbatim turn text rather than a space-joined token reconstruction.
- **New regression tests**: Layer-0-skips-capabilities, no-routable-agents-at-request-time → `needs_user_pick`, and `routingEnabled=false` leaves routing unset. Suite: 116 green (was 113).

Not changed (needs a contract decision, deferred to Stage 3.6): the shipped `AwaitingClarification` proto carries no wire `resume_token`, so the REST awaiting path can't surface the token the way the MCP JSON does. Flagged for the Iris BFF co-design.

## Library / pattern references

- **Koog `StructureFixingParser`** — pattern from Stage 2.1 spike + Stage 2.3 migration.
- **kantheon `shared/libs/kotlin/capabilities-client`** — used here; Stage 1.3 deliverable. API at [`contracts.md`](../../../architecture/themis/contracts.md) §4.
- **MCP search input** — `SearchRequest` shape at [`contracts.md`](../../../architecture/themis/contracts.md) §1.1.
- **Capabilities-mcp YAML fixtures** — `agents/pythia.yaml`, `agents/golem-erp.yaml`. Bora's content fill informs Layer-2 prompt quality.

## Out of scope for Stage 3.3

- Profile semantics deep wiring (fuzzy candidate count, alt-bindings, HITL max-rounds) — Stage 3.4.
- `RefusalWithGaps` emission — Stage 3.4.
- Routing eval corpus + CI gates — Stage 3.5.
- Iris chip rendering — Stage 3.6.
- Re-tuning Layer-1 weights — Stage 3.5 after eval-corpus baseline.
