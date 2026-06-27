# Stage 3.6 — Iris stub + observability + cutover

> **Phase 3, Stage 3.6.** Final stage of the first kantheon arc.
>
> **Reads with.** [`tasks-p3-overview.md`](./tasks-p3-overview.md), [`plan.md`](./plan.md) §5.6, [`../../../architecture/themis/architecture.md`](../../../architecture/themis/architecture.md) §10 (observability), [`../../../architecture/themis/contracts.md`](../../../architecture/themis/contracts.md) §1.3 (RoutingPickChip).

> **Status (2026-06-21) — Themis-side observability + docs landed; Iris-arc tasks + tag deferred (Bora's call).**
> Scope split decided with Bora: do the cleanly-Themis-side work now, leave the
> cross-arc + tag pieces.
>
> - **Done:** **T4** — seven routing metrics in `ResolverOtel` (emitted from the
>   REST handler + the classify/multi-question nodes) + the Grafana dashboard
>   `agents/themis/observability/dashboards/themis-routing.json`. **T5** — routing-layer
>   section folded into `themis-design.md`. **T6 (partial)** — `README.md` routing +
>   observability sections; architecture §10.2 cache-metrics deferral note.
> - **Pre-existing:** **T1** — `RoutingPickChip` already lives in `envelope/v1`
>   (Iris arc); the broader `Chip` oneof is Iris-arc work.
> - **Superseded by the Iris arc:** **T2/T3** — the chip round-trip is built in the
>   *real* `agents/iris-bff` (Iris Phase 3 Stage 3.1), not a throwaway Themis stub.
> - **Deferred to Bora:** the `themis/v0.2.0` **tag** + the Phase-3-DONE flip; the
>   two `themis_capabilities_cache_*` metrics (need a `CapabilitiesReadClient`
>   shared-lib hook); the live nightly run on the populated corpus (Stage 3.5 T4).

## Goal

`RoutingPickChip` wired in `envelope/v1`. Iris BFF stub round-trips a chip pick end-to-end against a fixture LLM (chip click → reissue with `routing_hint` → Layer 0 short-circuit). Routing observability surfaces in Grafana (per-layer hit-rate, agent distribution, confidence histogram, intent-kind distribution, refusal breakdown). `themis-design.md` updated to fold in the routing layer. Tag `themis/v0.2.0`.

## Pre-flight

- [x] **Stage 3.5 DONE** — eval harness + CI gate landed (corpus fill Bora-owned).
- [x] **Iris BFF co-design owner** — the Iris arc owns the chip round-trip (T2/T3 superseded).
- [x] **Branch**: on `feat/p3-s3.5-eval-ci` (Themis-side 3.6 work stacked on 3.5 per Bora).

## Tasks

- [~] **T1 — `RoutingPickChip` in `envelope/v1`.** _(Pre-existing from the Iris arc; the broader `Chip` oneof is Iris-arc work.)_

  `RoutingPickChip` itself was added in Stage 3.1. For Iris's renderer to dispatch by chip kind, the broader `Chip` oneof needs to exist. **Add only what's necessary for the routing flow**:

  ```proto
  // Added to envelope/v1 in Stage 3.6 — minimal Chip union.
  // Full envelope/v1 (Block, Drilldown, TableDetails, ChartDetails, ChartIntent, other Chip variants) is the
  // Iris-extraction / Golem-rewrite arc's responsibility.

  message Chip {
    oneof kind {
      RoutingPickChip routing_pick = 1;
      // Other chip kinds (static, dynamic) deliberately deferred.
    }
  }

  message RoutingPickChip { /* defined in Stage 3.1 */ }
  ```

  Note: this is additive — by reserving `oneof.kind = 1` for `RoutingPickChip`, future chip kinds add at field numbers 2, 3, … without breaking compatibility.

  Run `just proto`; verify generated `Chip.kt` includes the `routing_pick` getter.

  Acceptance: proto rebuilds; Phase 2 tests still green.

- [~] **T2 — Iris BFF stub.** _(SUPERSEDED — built in the real `agents/iris-bff` by the Iris arc, not a throwaway stub.)_

  Create a minimal Iris BFF module — stub-grade, exercise-only:

  ```
  agents/iris-bff/
  ├── build.gradle.kts
  ├── src/main/kotlin/org/tatrman/kantheon/iris/bff/
  │   ├── App.kt
  │   ├── api/
  │   │   ├── ChatRoutes.kt          # POST /chat/turn — minimal; calls Themis; emits envelope events
  │   │   └── HealthRoutes.kt
  │   ├── routing/
  │   │   ├── ThemisClient.kt        # HTTP client for themis-mcp
  │   │   └── AlternatesTracker.kt   # tracks alternates_offered per in-flight resolution
  │   └── stream/
  │       └── ChipEmitter.kt         # builds an envelope with RoutingPickChips
  ├── src/main/resources/application.conf
  ├── src/test/kotlin/.../          # mocked component test against WireMock-stubbed Themis
  └── k8s/                           # placeholder; deploy in T4
  ```

  `build.gradle.kts`:

  ```kotlin
  plugins {
      alias(libs.plugins.kotlin.jvm)
      alias(libs.plugins.ktor)
      alias(libs.plugins.jib)
  }
  dependencies {
      implementation(project(":shared:proto"))
      implementation(libs.ai.platform.ktor.config)
      implementation(libs.ai.platform.otel.config)
      implementation(project(":shared:libs:kotlin:capabilities-client"))
      implementation(libs.ktor.client.cio)
      implementation(libs.ktor.server.cio)
      // ...
  }
  jib { to { image = "iris-bff:dev" }; container { mainClass = "org.tatrman.kantheon.iris.bff.AppKt" } }
  ```

  Add `include(":agents:iris-bff")` to `settings.gradle.kts`.

  **Mark explicitly as stub.** Top of `App.kt`:

  ```kotlin
  // STUB — Phase 3 Stage 3.6 chip-flow co-design only.
  // Full Iris BFF planning arc is separate; see docs/design/iris/ + (future) docs/implementation/v1/iris/plan.md.
  ```

  Acceptance: module compiles; deploys with a `/health` endpoint.

- [~] **T3 — Chip round-trip.** _(SUPERSEDED — Iris Phase 3 Stage 3.1.)_

  `routing/ThemisClient.kt`:

  ```kotlin
  class ThemisClient(private val endpoint: String, private val http: HttpClient) {
      suspend fun resolve(req: ResolveRequest): ResolveResponse =
          http.post("$endpoint/v1/resolve") { /* serialise + post */ }.body()
  }
  ```

  `routing/AlternatesTracker.kt` — in-memory per-session tracker (replace with Postgres in the full Iris arc):

  ```kotlin
  class AlternatesTracker {
      private val byConversation = ConcurrentHashMap<String, List<AgentId>>()

      fun record(conversationId: String, alternates: List<AgentId>) {
          byConversation[conversationId] = alternates
      }

      fun validate(conversationId: String, picked: AgentId): Boolean =
          byConversation[conversationId]?.contains(picked) ?: false
  }
  ```

  `api/ChatRoutes.kt`:

  ```kotlin
  fun Route.chatRoutes(themis: ThemisClient, tracker: AlternatesTracker) {
      post("/chat/turn") {
          val turn = call.receive<ChatTurnRequest>()

          // First call to Themis — CHAT_QUICK.
          val req = ResolveRequest.newBuilder()
              .setFresh(FreshQuestion.newBuilder().setText(turn.question))
              .setProfile(Profile.CHAT_QUICK)
              .setRegistry(turn.registry)
              .build()
          val resp = themis.resolve(req)

          when {
              resp.hasResolution() && resp.resolution.routing.needsUserPick -> {
                  // Emit chips for the user to pick from.
                  tracker.record(turn.conversationId, resp.resolution.routing.alternatesList.map { it.agentId })
                  val chips = resp.resolution.routing.alternatesList.map { alt ->
                      Chip.newBuilder().setRoutingPick(RoutingPickChip.newBuilder()
                          .setAgentId(alt.agentId)
                          .setLabel(alt.agentId.value)        // TODO: full Iris reads display_name from capabilities-mcp
                          .setWhy(alt.why)
                          .build()).build()
                  }
                  call.respond(buildJsonObject {
                      put("kind", JsonPrimitive("chips"))
                      put("chips", JsonArray(chips.map { it.toJsonPrimitive() }))
                  })
              }
              resp.hasResolution() -> {
                  // No chip pick needed; would dispatch to chosen_agent_id here in the full Iris.
                  call.respond(buildJsonObject {
                      put("kind", JsonPrimitive("resolution"))
                      put("chosenAgentId", JsonPrimitive(resp.resolution.routing.chosenAgentId.value))
                  })
              }
              // ... other outcomes ...
          }
      }

      // Chip-pick endpoint — reissue with routing_hint.
      post("/chat/pick") {
          val pick = call.receive<ChipPick>()
          require(tracker.validate(pick.conversationId, pick.pickedAgentId)) { "invalid pick" }

          // Reissue with routing_hint — Themis Layer 0 short-circuits.
          val req = ResolveRequest.newBuilder()
              .setResume(ResumeAnswer.newBuilder().setPickedAgent(pick.pickedAgentId))
              .setProfile(Profile.CHAT_QUICK)
              .setRoutingHint(pick.pickedAgentId)
              .build()
          val resp = themis.resolve(req)
          // Expect: resp.resolution.routing.chosenAgentId == pick.pickedAgentId, layerHit = 0
          call.respond(buildJsonObject {
              put("kind", JsonPrimitive("resolution"))
              put("chosenAgentId", JsonPrimitive(resp.resolution.routing.chosenAgentId.value))
              put("layerHit", JsonPrimitive(resp.resolution.routing.layerHit))
          })
      }
  }
  ```

  Mocked component test `agents/iris-bff/src/test/kotlin/.../IrisChipRoundTripSpec.kt` (WireMock-stubbed Themis — per the testing policy, planning-conventions.md §4: mocked unit tests only; integration suite is separate):

  ```kotlin
  class IrisChipRoundTripSpec : StringSpec({
      "chip round-trip end-to-end" {
          val themisStub = WireMockServer(0).apply { start() }
          // First resolve: Themis returns needs_user_pick = true with 2 alternates.
          themisStub.stubFor(post("/v1/resolve").inScenario("chip").whenScenarioStateIs(STARTED)
              .willReturn(okJson(needsUserPickResponseJson)).willSetStateTo("picked"))
          // Second resolve (after pick): Layer 0 short-circuit.
          themisStub.stubFor(post("/v1/resolve").inScenario("chip").whenScenarioStateIs("picked")
              .willReturn(okJson(layer0ResolutionJson)))

          testApplication {
              application { module(testConfig(themisStub.port())) }

              // Turn 1: user asks ambiguous question → chips.
              val turn1 = client.post("/chat/turn") { /* setBody */ }
              turn1.bodyAsText() shouldContain "\"kind\":\"chips\""

              // Turn 2: user picks one.
              val pick = client.post("/chat/pick") { /* setBody with pickedAgentId */ }
              val pickJson = Json.parseToJsonElement(pick.bodyAsText()).jsonObject
              pickJson["kind"]?.jsonPrimitive?.content shouldBe "resolution"
              pickJson["chosenAgentId"]?.jsonPrimitive?.content shouldBe "pythia"
              pickJson["layerHit"]?.jsonPrimitive?.int shouldBe 0
          }
          themisStub.stop()
      }
  })
  ```

  Acceptance: mocked component test green; chip round-trip works against fixture LLM / WireMock-stubbed Themis (real cross-service round-trip deferred to the separate integration-test suite).

- [x] **T4 — Routing observability metrics + Grafana dashboard.** _(7 metrics in `ResolverOtel`; dashboard `observability/dashboards/themis-routing.json`. Cache pair deferred — shared-lib hook.)_

  Add Phase 3 metrics in `agents/themis/src/main/kotlin/.../observability/RoutingMetrics.kt` per [`architecture.md`](../../../architecture/themis/architecture.md) §10.2:

  ```kotlin
  class RoutingMetrics(meter: Meter) {
      val routingLayerTotal = meter.counterBuilder("themis_routing_layer_total")
          .setDescription("Routing layer hit count by layer").build()
      val routingChosenTotal = meter.counterBuilder("themis_routing_chosen_total")
          .setDescription("Chosen agent distribution").build()
      val routingConfidence = meter.histogramBuilder("themis_routing_confidence")
          .setDescription("Routing decision confidence").build()
      val intentKindTotal = meter.counterBuilder("themis_intent_kind_total").build()
      val intentKindLlmFallbackTotal = meter.counterBuilder("themis_intent_kind_llm_fallback_total").build()
      val multiQuestionDetectedTotal = meter.counterBuilder("themis_multi_question_detected_total").build()
      val refusalTotal = meter.counterBuilder("themis_refusal_total").build()
      val capabilitiesCacheStaleTotal = meter.counterBuilder("themis_capabilities_cache_stale_total").build()
      val capabilitiesCacheAge = meter.gaugeBuilder("themis_capabilities_cache_age_seconds").build()
  }
  ```

  Increment counters in the relevant nodes:
  - `routeToAgent` → `routingLayerTotal{layer=...}` + `routingChosenTotal{agent_id=...}` + `routingConfidence.record(...)`.
  - `classifyIntentKind` → `intentKindTotal{kind=...}` + `intentKindLlmFallbackTotal` when LLM fallback fired.
  - `detectMultiQuestion` → `multiQuestionDetectedTotal` on MultiQuestion output.
  - `decideHitlOrEmit` → `refusalTotal{gap_kind=...}` on RefusalWithGaps emission.

  Create Grafana dashboard JSON at `agents/themis/observability/dashboards/themis-routing.json`. Panels:

  1. Per-layer hit-rate (stacked bar over time).
  2. Chosen-agent distribution (pie, last 1h).
  3. Confidence histogram (heatmap).
  4. Intent-kind distribution (counter).
  5. Refusal breakdown by GapKind (table).
  6. Capabilities cache age (gauge).

  Verify in Grafana (or capture screenshot for the PR if not deployed locally).

  Acceptance: metrics emit on every resolve; dashboard JSON committed.

- [x] **T5 — Fold the routing layer into `themis-design.md`.** _(New 'Routing layer (Phase 3 — kantheon arc)' section.)_

  Open `docs/design/themis/themis-design.md`. Add a new section "Routing layer (Phase 3)" between the existing "HITL contract" and "Caching" sections (or wherever the document's flow allows). Content (cite `architecture.md` §6.2 for the graph delta):

  ```markdown
  ## Routing layer (added Phase 3 — 2026-MM-DD)

  Themis's CHAT_QUICK profile runs an additional `routeToAgent` node after `jointInference`. This node populates `Resolution.routing` with a `RoutingDecision` naming the agent that should answer the turn (Pythia or one of N Golem instances).

  ### Four-layer cascade

  [Reference `architecture.md` §6.2 and `contracts.md` §1.2 — Layer 0/1/2/3 semantics + the +0.5/+0.4/+0.3/+0.2 default weights.]

  ### Profile semantics — what changes per profile

  [CHAT_QUICK vs INVESTIGATION_DEEP table from architecture.md §6.4.]

  ### Iris co-design — RoutingPickChip

  When `needs_user_pick: true`, Iris renders the alternates as `RoutingPickChip` chips. The user's click re-issues the resolve with `routing_hint = picked_agent_id`; Themis honours at Layer 0.

  ### RefusalWithGaps (STRICT mode)

  Third terminal outcome alongside `Resolution` and `AwaitingClarification`. Returns a structured `Gap[]` listing blockers (`ENTITY_UNMAPPED`, `CAPABILITY_UNAVAILABLE`, `OUT_OF_DATA_SCOPE`, `AMBIGUOUS_INTENT`).
  ```

  Also drop **all** lingering "Wrangler" → "Iris" references inside `themis-design.md` (e.g. the consumers list: "consumed by Pythia, Wrangler" → "consumed by Pythia, Iris BFF").

  And the proto-package note at the top of the doc — currently says `cz.dfpartner.resolver.v1`; update to `org.tatrman.kantheon.themis.v1`.

  Acceptance: design doc updated; lingering Wrangler refs purged; PR diff readable.

- [~] **T6 — README + (deferred) deploy/tag.** _(README routing+observability sections done; deploy/eval-on-populated-corpus + `themis/v0.2.0` tag left to Bora.)_

  ```
  just deploy-kt themis           # the Phase 3 themis-mcp version
  just deploy-kt iris-bff         # the new stub
  kubectl -n kantheon get pods    # both Ready

  just eval-themis-routing        # final run; assert thresholds green
  ```

  Update `agents/themis/README.md` — Phase 3 status; routing-cascade summary; chip-flow link; observability dashboard pointer. Cross-link `docs/design/themis/themis-design.md` (now updated), `docs/architecture/themis/architecture.md`, `docs/implementation/v1/themis/plan.md`.

  **Tag**: `git tag themis/v0.2.0` and push.

  Update kantheon memory file `kantheon_state_2026_05.md` (or create a new `kantheon_state_2026_<month>.md` if a new month has rolled over): "Phase 3 — closed at themis/v0.2.0 on <DATE>; eval-gate green; Iris chip round-trip verified; routing observability live in Grafana."

  Update `tasks-p3-overview.md` — check all six stage boxes. Update `kantheon/docs/implementation/v1/themis/plan.md` §9 Phase progression checklist — check Stage 3.6 + Phase 3 DONE.

  Acceptance: tag pushed; README + design doc + memory + plan checkbox all updated; PR merged. **The first Kantheon arc is shipped.**

## DONE — Stage 3.6 / Phase 3 / First Kantheon arc

- [~] Themis-side tasks done (T4/T5 + T6 docs); T1 pre-existing; T2/T3 Iris-arc; tag deferred.
- [~] Chip round-trip lives in the Iris arc (real iris-bff), not a Themis stub.
- [x] Seven Themis-observable metrics emit; dashboard JSON committed (cache pair deferred).
- [x] `themis-design.md` updated with the routing-layer section.
- [ ] Tag `themis/v0.2.0` pushed. **(Deferred to Bora.)**
- [ ] **Phase 3 DONE** — flip after corpus fill + nightly green + tag (Bora).
- [~] Themis routing complete; first-arc close pending the tag + corpus sign-off.

## Library / pattern references

- **ai-platform `EXAMPLES.md` §8 — OTel** — counter / histogram / gauge builder patterns.
- **Grafana dashboard JSON** — `https://grafana.com/docs/grafana/latest/dashboards/build-dashboards/import-dashboards/` for the model schema.
- **Iris design** at `docs/design/iris/iris-design.md` — the surface the stub *implements a slice of*. Cite when documenting the stub-vs-full delta in the BFF's README.
- **ai-platform `tools/query-mcp/`** Application — closest reference for a Kotlin Ktor service with full OTel + MCP integration.

## Out of scope for Stage 3.6

- Full Iris BFF — separate planning arc. The stub here is enough for the chip round-trip and nothing else.
- Vue 3 SPA — separate arc; the stub returns JSON, no front-end rendering.
- Persistence of `IrisSession` / `TurnPointer` / `EntityContext` / `Snapshot` — full Iris arc.
- Slash-command UX — full Iris arc.
- Edit-and-resend — full Iris arc.
- Retiring `ai-platform/agents/resolver/` — separate PR after a 1-week soak period of `themis/v0.2.0` in production-like usage.
