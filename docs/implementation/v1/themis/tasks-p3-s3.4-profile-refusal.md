# Stage 3.4 — Profile + `RefusalWithGaps` + corpus skeleton

> **Phase 3, Stage 3.4.**
>
> **Reads with.** [`tasks-p3-overview.md`](./tasks-p3-overview.md), [`plan.md`](./plan.md) §5.4, [`../../../architecture/themis/contracts.md`](../../../architecture/themis/contracts.md) §1.2 (RefusalWithGaps + Gap + GapKind) + §5 (resume token Phase 3 additions) + §7.2 (routing-corpus schema).

## Goal

`Profile` parameter changes graph traversal behaviour per the architecture's spec (fuzzy candidates 3 vs 10, alt-bindings, max HITL rounds 1 vs 3, routeToAgent skip). STRICT-mode `RefusalWithGaps` reachable with a populated Gap taxonomy. The routing eval corpus skeleton committed with 6 bucket comment-headers and **empty arrays ready for Bora's content fill** (which starts in parallel during this stage and Stage 3.5).

## Pre-flight

- [ ] **Stage 3.3 DONE** — `routeToAgent` four-layer cascade merged.
- [ ] **Branch**: `feat/p3-s3.4-profile-refusal` from `main`.

## Tasks

- [ ] **T1 — Tests-first for per-profile graph behaviour.**

  Create `agents/themis/src/test/kotlin/.../component/ProfileBehaviourSpec.kt`. Snapshot-style mocked component tests that capture which nodes executed and with what parameters for a given input (per the testing policy, planning-conventions.md §4: mocked unit tests only; integration suite is separate):

  ```kotlin
  class ProfileBehaviourSpec : StringSpec({

      "CHAT_QUICK uses 3 fuzzy candidates per span" {
          val req = chatQuickResolveRequest("Které faktury Shell neuhradil?")
          val telemetry = runWithTelemetry { themis.resolve(req) }
          telemetry.fuzzyCallsPerSpan.values.shouldHaveMaximum(3)
      }

      "INVESTIGATION_DEEP uses 10 fuzzy candidates per span" {
          val req = investigationDeepResolveRequest("Které faktury Shell neuhradil?")
          val telemetry = runWithTelemetry { themis.resolve(req) }
          telemetry.fuzzyCallsPerSpan.values.shouldHaveMaximum(10)
      }

      "CHAT_QUICK runs routeToAgent" {
          val req = chatQuickResolveRequest("...")
          val telemetry = runWithTelemetry { themis.resolve(req) }
          telemetry.routeToAgentInvoked shouldBe true
      }

      "INVESTIGATION_DEEP skips routeToAgent" {
          val req = investigationDeepResolveRequest("...")
          val telemetry = runWithTelemetry { themis.resolve(req) }
          telemetry.routeToAgentInvoked shouldBe false
      }

      "CHAT_QUICK caps HITL rounds at 1" {
          // Force low-confidence joint_inference; expect single AwaitingClarification, then on resume the second decision is best-effort (not another awaiting).
          val req = chatQuickResolveRequest("Ambiguous question producing low confidence")
          val resp1 = themis.resolve(req)
          resp1.outcomeCase shouldBe ResolveResponse.OutcomeCase.AWAITING
          val resp2 = themis.resolve(buildResume(resp1, selectedOptionId = "...", roundCounterStays = false))
          resp2.outcomeCase shouldBe ResolveResponse.OutcomeCase.RESOLUTION
      }

      "INVESTIGATION_DEEP allows up to 3 HITL rounds" {
          // Same low-confidence setup; first 2 responses are AwaitingClarification; the 3rd is Resolution or refusal.
          // ...
      }

      "INVESTIGATION_DEEP skips alt-bindings expansion (full instead)" {
          val req = investigationDeepResolveRequest("...")
          val telemetry = runWithTelemetry { themis.resolve(req) }
          telemetry.altBindingsExpansionDepth shouldBeGreaterThan 1     // expanded vs CHAT_QUICK's = 1
      }
  })
  ```

  Helper `runWithTelemetry { ... }` captures per-node telemetry from a test-only OTel exporter that records the fuzzy call counts, route invocation, and HITL round counter.

  Acceptance: tests written; they fail (current code doesn't differentiate by profile).

- [ ] **T2 — Plumb `Profile` through the Koog graph context.**

  Two places to wire:

  - **Resolve request adapter** at the HTTP / MCP boundary: extract `ResolveRequest.profile`; default to `CHAT_QUICK` if `PROFILE_UNSPECIFIED`. Put it in a context bag passed to the Koog `AIAgent.run(input)`.
  - **Per-node profile-aware behaviour**: each affected node reads the profile from its execution context and switches behaviour accordingly:
    - `fuzzyMatchSpans`: fuzzy candidate count `3` vs `10`.
    - `jointInference`: alt-bindings expansion skipped vs full.
    - `decideHitlOrEmit`: max rounds `1` vs `3`.
    - `routeToAgent`: skip entirely on INVESTIGATION_DEEP.

  Use Koog's `AIAgentContext` extension storage (or a plain `ThreadLocal` if Koog 0.8.x doesn't expose this cleanly — confirm against `~/Dev/view-only/koog/agents/agents-core/src/.../AIAgentContext.kt`).

  Re-run T1 tests; the four profile-snapshot tests pass.

  Acceptance: all T1 tests green; commit `[p3-s3.4] profile wiring`.

- [ ] **T3 — Tests-first for `RefusalWithGaps`.**

  Create `RefusalWithGapsSpec.kt`:

  ```kotlin
  class RefusalWithGapsSpec : StringSpec({

      "STRICT + entity unmapped → RefusalWithGaps with ENTITY_UNMAPPED gap" {
          val req = strictResolveRequest("Show me Foo's invoices", entityFooNotInRegistry = true)
          val resp = themis.resolve(req)
          resp.outcomeCase shouldBe ResolveResponse.OutcomeCase.REFUSAL
          resp.refusal.gapsList.first().kind shouldBe GapKind.ENTITY_UNMAPPED
          resp.refusal.gapsList.first().description shouldContain "Foo"
      }

      "STRICT + no agent matches → RefusalWithGaps with CAPABILITY_UNAVAILABLE" {
          // capabilities-mcp returns an empty agent set under filter; routing Layer-1/2/3 all fail to score above threshold.
          val req = strictResolveRequest("...", agentsReturnedByCapsMcp = emptyList())
          val resp = themis.resolve(req)
          resp.refusal.gapsList.first().kind shouldBe GapKind.CAPABILITY_UNAVAILABLE
      }

      "STRICT + out-of-data-scope → RefusalWithGaps with OUT_OF_DATA_SCOPE" {
          // The caller's Registry includes an entity_type with `fuzzy_matcher_namespace` that has no data in the current tenant.
          // ...
      }

      "STRICT + irreducibly ambiguous → RefusalWithGaps with AMBIGUOUS_INTENT" { /* ... */ }

      "INTERACTIVE + entity unmapped → AwaitingClarification (NOT refusal)" {
          // Same scenario but with INTERACTIVE: the original HITL flow remains.
          val req = interactiveResolveRequest("Show me Foo's invoices", entityFooNotInRegistry = true)
          val resp = themis.resolve(req)
          resp.outcomeCase shouldBe ResolveResponse.OutcomeCase.AWAITING
      }

      "Gap.suggested_action populated where reasonable" {
          val resp = themis.resolve(strictResolveRequest("..."))
          resp.refusal.gapsList.forEach { gap ->
              // Each gap may optionally have a suggested action; assert when expected.
              if (gap.kind == GapKind.OUT_OF_DATA_SCOPE) {
                  gap.hasSuggestedAction() shouldBe true
                  gap.suggestedAction shouldContain "Charon"  // e.g. "Stage HR data via Charon..."
              }
          }
      }
  })
  ```

  Acceptance: tests written; failing.

- [ ] **T4 — Implement `RefusalWithGaps` emission path.**

  In `decideHitlOrEmit` (Koog node):

  ```kotlin
  private fun emitOutcome(state: ResolveState): ResolveResponse {
      val isStrict = state.context.hitl == HitlProfile.STRICT
      val blockers = state.collectBlockers()      // list of Gap shapes from prior nodes

      if (isStrict && blockers.isNotEmpty()) {
          val gaps = blockers.map { it.toProto() }   // GapKind + description + optional suggested_action
          return ResolveResponse.newBuilder()
              .setRefusal(RefusalWithGaps.newBuilder()
                  .addAllGaps(gaps)
                  .setRationale("STRICT mode: ${gaps.size} blocker(s)")
                  .build())
              .build()
      }

      // existing logic for AwaitingClarification / Resolution
  }
  ```

  Define `Blocker` sealed type that accumulates during graph execution:

  ```kotlin
  sealed interface Blocker { fun toGap(): Gap }
  data class EntityUnmapped(val span: String, val entityType: String) : Blocker
  data class CapabilityUnavailable(val intentKind: IntentKind) : Blocker
  data class OutOfDataScope(val entityType: String, val tenantHint: String?) : Blocker
  data class AmbiguousIntent(val description: String) : Blocker
  ```

  Each upstream node that detects a blocker registers it via `state.recordBlocker(...)`. The `decideHitlOrEmit` node then aggregates.

  Re-run T3 tests; all 6 pass.

  Acceptance: tests green; commit `[p3-s3.4] RefusalWithGaps`.

- [ ] **T5 — Routing eval corpus skeleton.**

  Create `agents/themis/eval/corpus/routing-seed.jsonl` with **only the bucket headers** per [`contracts.md`](../../../architecture/themis/contracts.md) §7.2. The file structure (JSONL with `# bucket` comment lines — JSONL technically doesn't support comments, but the harness skips lines starting with `#`):

  ```
  # PROCEDURAL — single Golem-ERP domain
  # PROCEDURAL — cross-domain (should route to Pythia)
  # RCA (should route to Pythia)
  # FORECAST (should route to Pythia)
  # SIMULATION (should route to Pythia)
  # Ambiguous (should fire Layer 3 needs_user_pick)
  ```

  Add ONE seed example per bucket to anchor the schema for Bora's content fill — these are placeholders that Bora can keep or replace:

  ```jsonl
  # PROCEDURAL — single Golem-ERP domain
  {"question":"Které faktury Shell ještě neuhradil?","lang":"cs","expected":{"intent_kind":"PROCEDURAL","chosen_agent_id":"golem-erp","alternates_present":[],"routing_layer_expected":1}}
  # PROCEDURAL — cross-domain (should route to Pythia)
  {"question":"How does headcount growth correlate with sales growth across regions?","lang":"en","expected":{"intent_kind":"PROCEDURAL","chosen_agent_id":"pythia","alternates_present":[],"routing_layer_expected":1}}
  # RCA (should route to Pythia)
  {"question":"Proč klesly tržby Castrolu v soukromých garážích?","lang":"cs","expected":{"intent_kind":"RCA","chosen_agent_id":"pythia","alternates_present":[],"routing_layer_expected":1}}
  # FORECAST (should route to Pythia)
  {"question":"Jaká bude předpověď tržeb Q3?","lang":"cs","expected":{"intent_kind":"FORECAST","chosen_agent_id":"pythia","alternates_present":[],"routing_layer_expected":1}}
  # SIMULATION (should route to Pythia)
  {"question":"Co kdybychom zrušili řadu Sheron na Slovensku?","lang":"cs","expected":{"intent_kind":"SIMULATION","chosen_agent_id":"pythia","alternates_present":[],"routing_layer_expected":1}}
  # Ambiguous (should fire Layer 3 needs_user_pick)
  {"question":"Jak si vede prodej?","lang":"cs","expected":{"intent_kind":"PROCEDURAL","chosen_agent_id":null,"alternates_present":["pythia","golem-erp"],"routing_layer_expected":3}}
  ```

  **The "Bora's content fill" task starts here in parallel** — open a tracker issue / TODO for Bora to populate ~30 questions per bucket (total ~180 questions). Stage 3.5 has Layer 1 hit-rate ≥ 60% as DONE criterion, which depends on corpus content.

  Commit the skeleton; flag Bora's content task in the PR description.

  Acceptance: file committed; harness from Stage 2.4 can parse the JSONL without error (skip the `#` lines).

- [ ] **T6 — Extend resume token shape for Phase 3 fields.**

  In `koog/resume/ResumeTokenCodec.kt` (carried over from Resolver), extend the JSON payload per [`contracts.md`](../../../architecture/themis/contracts.md) §5:

  ```kotlin
  @Serializable
  data class ResumeTokenPayload(
      val version: Int = 1,
      val issuedAt: String,
      val expiresAt: String,
      val conversationId: String,
      val questionHash: String,
      val parseHash: String,
      val spanCandidates: List<SpanCandidate>,
      val universalEntities: List<UniversalEntityRecord>,
      val askedAbout: AskedAbout? = null,
      val roundIndex: Int,
      val maxRounds: Int,

      // Phase 3 additions:
      val profileAtIssue: String,                          // "CHAT_QUICK" or "INVESTIGATION_DEEP"
      val themisPriorContext: String? = null,             // opaque continuation
      val alternatesOffered: List<String> = emptyList(),  // for Layer-3 chip-pick validation in Stage 3.6
  )
  ```

  HMAC-signing semantics unchanged; only the JSON payload grows. Old tokens (from pre-Phase-3 Themis instances) deserialise with default values for the new fields — confirm via:

  ```kotlin
  "old token without Phase 3 fields deserialises cleanly" {
      val oldJson = """{"version":1,"issuedAt":"...","expiresAt":"...","conversationId":"...","questionHash":"sha256:abc","parseHash":"sha256:def","spanCandidates":[],"universalEntities":[],"roundIndex":1,"maxRounds":1}"""
      val payload = ResumeTokenCodec.decode(hmacSign(oldJson))
      payload.profileAtIssue shouldBe "CHAT_QUICK"     // default fallback for missing field
      payload.alternatesOffered shouldBe emptyList()
  }
  ```

  Note: `kotlinx.serialization` requires explicit defaults on data class fields to allow missing fields on decode. The defaults shown above satisfy this.

  Acceptance: codec spec green; existing HITL-resume component tests still green.

## Status (2026-06-21) — landed; deviations + the two agreed deferrals

Decisions (Bora, 2026-06-21): **add `hitl` to ResolveContext** (additive proto) for the STRICT signal; **defer** the two behaviours with no shipped mechanism.

- **STRICT signal:** the shipped proto had no `hitl` (the richer ResolveContext never shipped). Added a themis-local `HitlProfile` enum + `ResolveContext.hitl = 4` (additive); threaded to `ResolverContext.hitl` (UNSPECIFIED → INTERACTIVE). contracts.md §1.2 field-number contract updated.
- **No Koog `AIAgentContext` plumbing** — the nodes already read `state.profile`/`state.hitl` off `ResolverContext` (added in 3.3/3.4); no extension-storage needed.
- **Resume token is `HmacTokenManager.TokenPayload`** (not a `ResumeTokenCodec`) — added `profileAtIssue` + `alternatesOffered` with kotlinx defaults (pre-Phase-3 tokens decode cleanly).
- **`RefusalWithGaps` plumbed as a terminal** — added `ParseState.terminalRefusal` + `NodeResult.EmitRefusal` (checked first in `toNodeResult`) + REST/MCP projection. Blockers are computed in `collectBlockers(state, threshold)` (pure): ENTITY_UNMAPPED (unresolved domain span), CAPABILITY_UNAVAILABLE (routeToAgent → no usable agent), AMBIGUOUS_INTENT (low confidence, no structural blocker), structural-first precedence.
- **Deferred (agreed):** (a) `jointInference` alt-bindings expansion depth — no expansion mechanism exists (it's a single structured call); (b) `OUT_OF_DATA_SCOPE` gap — undetectable without a data layer. Both GapKind/Profile values remain for later.

- [x] **T1/T2 — per-profile behaviour.** fuzzy `3` (CHAT_QUICK) vs `10` (INVESTIGATION_DEEP) via `fuzzyLimitFor`; HITL rounds `1` vs config (`maxHitlRoundsFor`); routeToAgent skip done in 3.3. `ProfileBehaviourSpec` (2) green.
- [x] **T3/T4 — RefusalWithGaps.** `RefusalWithGapsSpec` (5, unit `collectBlockers`) + `Phase3ProfileRefusalComponentSpec` (2, STRICT→refusal / INTERACTIVE→awaiting through `runThemisGraph`).
- [x] **T5 — routing corpus skeleton.** `eval/corpus/routing-seed.jsonl` with 6 bucket headers + one seed each; `#`-comment-skipping format.
- [x] **T6 — resume-token Phase 3 fields.** `ResumeTokenPhase3Spec` (2: round-trip + default-on-missing).
- [x] Full `:agents:themis:test` green (113, 0 failures); `:shared:proto:test` green; ktlint clean; kustomize valid.
- [ ] PR merged.

## DONE — Stage 3.4

- [x] T1–T6 done; PR/merge pending.
- [x] `ProfileBehaviourSpec` + `RefusalWithGapsSpec` both green.
- [x] Routing corpus skeleton + 6 seed examples committed.
- [x] Bora-owned content-fill task tracked — documented in the `routing-seed.jsonl` header and as Stage 3.5 pre-flight + T4 (the Layer-1 ≥60% gate lives there).
- [x] Resume-token Phase 3 fields wired.
- [x] All Phase 2 + Phase 3.1–3.3 tests still green.
- [ ] PR merged.

## Library / pattern references

- **Koog `AIAgentContext`** — for per-graph-execution context state. Check `~/Dev/view-only/koog/agents/agents-core/src/.../AIAgentContext.kt`.
- **kotlinx-serialization defaults** — https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/serialization-guide.md#default-values for missing-field handling.
- **ai-platform `agents/resolver/src/main/kotlin/.../ResumeTokenCodec.kt`** — the original codec (now in `agents/themis/` post-extraction). Extend, don't rewrite.

## Out of scope for Stage 3.4

- Populating routing corpus content — Bora's parallel task; the harness gate enforces the Layer-1 hit-rate criterion in Stage 3.5.
- Routing eval harness (`expected.intent_kind` / `expected.chosen_agent_id` checks) — Stage 3.5.
- CI gates — Stage 3.5.
- Iris chip rendering — Stage 3.6.
