# Stage 3.1 — Proto extensions

> **Phase 3, Stage 3.1.**
>
> **Reads with.** [`tasks-p3-overview.md`](./tasks-p3-overview.md), [`plan.md`](./plan.md) §5.1, [`../../../architecture/themis/contracts.md`](../../../architecture/themis/contracts.md) §1.2 (themis/v1 routing extensions) + §1.3 (envelope/v1 RoutingPickChip).

## Goal

All Phase 3 proto types (`RoutingDecision`, `AgentAlternate`, `IntentKind`, `Profile`, `AgentId`, `MultiQuestionDetected`, `RefusalWithGaps`, `Gap`, `GapKind`, extended `ResolveRequest` / `Resolution` / `ResolveResponse`) generate clean Kotlin bindings. The new `RoutingPickChip` type added to `envelope/v1`. Phase 2 tests still pass (additive change — old clients deserialise new wire shapes without error).

## Pre-flight

- [ ] **Phase 2 DONE** (`themis/v0.1.0` tagged).
- [ ] **Branch**: `feat/p3-s3.1-proto-extensions` from `main`.

## Tasks

- [ ] **T1 — Extend `themis.proto` with the routing types.**

  Open `shared/proto/src/main/proto/org/tatrman/kantheon/themis/v1/themis.proto`. Apply the additions from [`contracts.md`](../../../architecture/themis/contracts.md) §1.2 verbatim. Specifically add:

  - `IntentKind` enum (4 values + UNSPECIFIED).
  - `Profile` enum (2 values + UNSPECIFIED).
  - `AgentId` message wrapper.
  - `RoutingDecision` message with `chosen_agent_id` (1), `alternates` (2), `rationale` (3), `confidence` (4), `needs_user_pick` (5), `layer_hit` (6).
  - `AgentAlternate` message.
  - `MultiQuestionDetected` message.
  - `RefusalWithGaps` + `Gap` + `GapKind` enum.
  - `AwaitingClarification.kind` oneof gains `MultiQuestionDetected multi_question = 8;`.

  Extend existing messages additively (per contracts.md §1.2's field-number contract — DO NOT renumber):

  - `ResolveRequest`: `Profile profile = 6; optional AgentId routing_hint = 7;`
  - `ResolveRequest.ResumeAnswer.answer` oneof: `AgentId picked_agent = 4;`
  - `ResolveContext`: `optional ResolutionContinuation themis_prior_context = 3;`
  - `Resolution`: `IntentKind intent_kind = 10; optional RoutingDecision routing = 11;`
  - `ResolveResponse.outcome`: `RefusalWithGaps refusal = 4;`

  Verify by re-running `just proto`. Generated Kotlin should appear under `shared/proto/build/generated/source/proto/main/kotlin/org/tatrman/kantheon/themis/v1/`.

  Acceptance: `./gradlew :shared:proto:assemble` green; the generated `Resolution.kt` shows `intent_kind` and `routing` fields.

- [ ] **T2 — Add `RoutingPickChip` to `envelope/v1`.**

  Open (or create if not yet committed) `shared/proto/src/main/proto/org/tatrman/kantheon/envelope/v1/envelope.proto`. Add only the `RoutingPickChip` message per [`contracts.md`](../../../architecture/themis/contracts.md) §1.3. **Do NOT scaffold the rest of `envelope/v1` yet** — that's the Golem-rewrite / Iris-extraction arc's job. Stage 3.6 will use just this one type.

  If the file doesn't exist, create a minimal `envelope.proto`:

  ```proto
  syntax = "proto3";
  package org.tatrman.kantheon.envelope.v1;

  import "org/tatrman/kantheon/themis/v1/themis.proto";

  message RoutingPickChip {
    org.tatrman.kantheon.themis.v1.AgentId agent_id = 1;
    string label = 2;
    string why   = 3;
  }
  ```

  Acceptance: `./gradlew :shared:proto:assemble` green; `RoutingPickChip.kt` generated.

- [ ] **T3 — Tests-first for proto round-trip + backward-compat.**

  Add `shared/proto/src/test/kotlin/.../ThemisProtoExtensionsSpec.kt`:

  ```kotlin
  class ThemisProtoExtensionsSpec : StringSpec({

      "RoutingDecision round-trips through proto" {
          val rd = RoutingDecision.newBuilder()
              .setChosenAgentId(AgentId.newBuilder().setValue("pythia").build())
              .setConfidence(0.84)
              .setRationale("RCA → Pythia per Layer 1 rule")
              .setLayerHit(1)
              .build()
          val bytes = rd.toByteArray()
          val parsed = RoutingDecision.parseFrom(bytes)
          parsed.chosenAgentId.value shouldBe "pythia"
          parsed.layerHit shouldBe 1
      }

      "ResolveResponse refusal outcome populated" {
          val refusal = RefusalWithGaps.newBuilder()
              .setRationale("Entity 'Foo' could not be resolved")
              .addGaps(Gap.newBuilder()
                  .setKind(GapKind.ENTITY_UNMAPPED)
                  .setDescription("Foo: no match in customer namespace")
                  .build())
              .build()
          val resp = ResolveResponse.newBuilder().setRefusal(refusal).build()
          val bytes = resp.toByteArray()
          val parsed = ResolveResponse.parseFrom(bytes)
          parsed.outcomeCase shouldBe ResolveResponse.OutcomeCase.REFUSAL
          parsed.refusal.gapsList.first().kind shouldBe GapKind.ENTITY_UNMAPPED
      }

      "older client deserialises new IntentKind without crashing — unknown enum → UNRECOGNIZED" {
          // Simulate an older client receiving a Resolution with intent_kind set to an enum value the client doesn't know.
          // proto3 enum behaviour: unknown values deserialise to UNRECOGNIZED.
          val resolution = Resolution.newBuilder()
              .setFunctionId("foo")
              .setIntentKind(IntentKind.RCA)
              .build()
          val bytes = resolution.toByteArray()
          // This test is the same-version round-trip — for true cross-version testing,
          // use protoc to compile a "v0" version of themis.proto without IntentKind and parse bytes against it.
          // The intent here is to document the invariant; full cross-version is deferred.
          val parsed = Resolution.parseFrom(bytes)
          parsed.intentKind shouldBe IntentKind.RCA
      }

      "AwaitingClarification.kind = MultiQuestionDetected" {
          val mq = MultiQuestionDetected.newBuilder()
              .addSubQuestions("Co je objednávka 12345?")
              .addSubQuestions("A jaké je její celkové množství?")
              .build()
          val await = AwaitingClarification.newBuilder()
              .setMultiQuestion(mq)
              .setQuestion("Detected two independent clauses")
              .build()
          await.kindCase shouldBe AwaitingClarification.KindCase.MULTI_QUESTION
          await.multiQuestion.subQuestionsList shouldHaveSize 2
      }
  })
  ```

  Acceptance: all proto-extension specs green.

- [ ] **T4 — Update existing Resolver/Themis-side serialisation paths for the new outcomes.**

  In `agents/themis/src/main/kotlin/.../api/ResolveRoutes.kt` (or wherever `ResolveResponse` is built for the HTTP/MCP surface), add the `refusal` outcome to the sealed-class-like dispatch — most Kotlin code uses a `when` on `ResolveResponse.OutcomeCase`. Add the new case:

  ```kotlin
  fun ResolveResponse.toHttpResponse(): HttpResponseShape = when (this.outcomeCase) {
      OutcomeCase.RESOLUTION -> /* existing */
      OutcomeCase.AWAITING -> /* existing */
      OutcomeCase.REFUSAL -> /* new — STRICT mode */
      OutcomeCase.OUTCOME_NOT_SET -> error("ResolveResponse outcome missing")
  }
  ```

  Add `else -> error(...)` to existing when-clauses if your Kotlin version doesn't enforce exhaustiveness automatically for proto oneofs. **The new case must compile; the runtime path is wired in Stages 3.3 (routing) and 3.4 (refusal).**

  Run existing tests:

  ```
  just test-kt themis
  ```

  All green; no regressions.

- [ ] **T5 — Add proto-doc comments.**

  In `themis.proto`, prepend each new message with a one-paragraph doc comment matching [`contracts.md`](../../../architecture/themis/contracts.md) §1.2. Example:

  ```proto
  // RoutingDecision — produced by Themis's routeToAgent node (Phase 3 Stage 3.3).
  // Populated when ResolveRequest.profile == CHAT_QUICK; absent for INVESTIGATION_DEEP.
  // `layer_hit` records which of the four cascade layers produced this decision (0..3) — for observability.
  message RoutingDecision { ... }
  ```

  Same for `IntentKind`, `Profile`, `MultiQuestionDetected`, `RefusalWithGaps`, `Gap`, `GapKind`, `AgentId`, `AgentAlternate`. Brief but specific.

  Acceptance: proto comments saved; generated Kotlin has KDoc on the generated classes (the protobuf plugin propagates `//` proto comments into `/** ... */` Kotlin doc-comments).

- [ ] **T6 — PR; document additive contract.**

  PR title: `[p3-s3.1] themis/v1 + envelope/v1 routing proto extensions`.

  PR description must include:
  - Field-number table (which new fields go where) — copy from contracts.md §1.2 field-number contract.
  - Confirmation that all changes are additive (no field renumbers, no message renames, no removed fields).
  - Backward compat: any Phase 2 v0.1.0 client deserialising a Phase 3 response sees the new fields with default values (e.g. `intent_kind = INTENT_KIND_UNSPECIFIED`, `routing = unset`); does not crash.

  Acceptance: PR opened; CI green; merged.

## Status (2026-06-20) — reconciled to the shipped proto

The task list above assumed contracts.md §1.2 could be applied **verbatim**. It can't: §1.2 described a richer base proto that never shipped, and its field numbers collide with `themis/v0.1.0` (e.g. `profile=6` vs shipped `mode=6`; `refusal=4` vs shipped `trace_id=4`). Per Bora's decision (*additive-to-shipped + fix the doc*, 2026-06-20), the routing types landed **additively onto the shipped base** with corrected, non-colliding numbers, and contracts.md §1.2 was reconciled (see its ⚠ reconciliation note + corrected field-number contract).

- [x] **T1 — themis.proto routing types.** Added additively: `profile=7`, `routing_hint=8`, `prior_context=9` (HandoffContext) on `ResolveRequest`; `refusal=6` in `ResolveResponse.outcome`; `picked_agent=4` on `ResumeAnswer`; `intent_kind=10`/`routing=11` on `Resolution`; `multi_question=8` in a new `AwaitingClarification.kind` oneof; new `Profile`/`IntentKind`/`RoutingDecision`/`AgentAlternate`/`MultiQuestionDetected`/`Decomposition`/`RefusalWithGaps`/`Gap`/`GapKind`. `AgentId` imported from `common/v1` (not redeclared). `:shared:proto:assemble` green.
- [x] **T2 — RoutingPickChip in envelope/v1.** Already present (Iris arc Stage 1.1): `RoutingPickChip` + the `Chip.kind` oneof slot (`routing = 2`) referencing `common/v1.AgentId`. No-op; verified.
- [x] **T3 — proto round-trip + backward-compat spec.** `ThemisProtoExtensionsSpec.kt` (7 specs incl. a v0.1.0-shaped Resolution deserialising with defaults). Green. Uses the nested `Themis.*` names (themis.proto has no `java_multiple_files`).
- [x] **T4 — refusal outcome dispatch.** No-op for Stage 3.1: the response is built from a sealed `NodeResult` (`Main.kt`), not a `when` on `OutcomeCase`; no exhaustive `OutcomeCase` switch exists anywhere in the repo. Adding `refusal` to the oneof compiles with no source change. Runtime path is Stage 3.4 (a new `NodeResult.EmitRefusal`).
- [x] **T5 — proto-doc comments.** Each new message/enum carries a one-paragraph doc comment (folded into T1).
- [ ] **T6 — PR.** Branch `feat/p3-s3.1-proto-extensions`; PR + merge pending.

## DONE — Stage 3.1

- [x] T1–T5 done; T6 (PR/merge) pending.
- [x] `./gradlew :shared:proto:assemble` green.
- [x] All Phase 2 Themis tests still green (`:agents:themis:test`) — additive change.
- [x] New proto specs (T3) green.
- [ ] PR merged.

## Library / pattern references

- **ai-platform `EXAMPLES.md` §2b** — sealed-interface dispatch when handling proto oneof outcomes (avoids non-exhaustive-`when` surprises).
- **protobuf-kotlin** — generated Kotlin DSL: `Resolution.newBuilder().setIntentKind(IntentKind.RCA).build()` vs the Kotlin DSL helper `resolution { intentKind = IntentKind.RCA }`. Either is fine; pick the style already used in the carried-over codebase.
- **proto3 enum compatibility** — https://protobuf.dev/programming-guides/proto3/#enum — unknown enum values surface as `UNRECOGNIZED` and round-trip safely.

## Out of scope for Stage 3.1

- Implementing the routing nodes — Stages 3.2 + 3.3.
- Wiring `RoutingDecision` into `Resolution.routing` at runtime — Stage 3.3.
- `RoutingPickChip` rendering or BFF round-trip — Stage 3.6.
- Eval-corpus extension — Stage 3.4 + 3.5.
- Other envelope/v1 types (Block, Chip variants, Drilldown, etc.) — Iris / Golem arcs.
