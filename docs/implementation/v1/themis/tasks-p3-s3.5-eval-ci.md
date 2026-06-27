# Stage 3.5 — Eval harness + CI gates

> **Phase 3, Stage 3.5.**
>
> **Reads with.** [`tasks-p3-overview.md`](./tasks-p3-overview.md), [`plan.md`](./plan.md) §5.5, [`../../../architecture/themis/contracts.md`](../../../architecture/themis/contracts.md) §7.2 (routing-corpus schema).

> **Status (2026-06-21) — harness + gate landed; corpus fill soft-blocks the ≥60 % sign-off.**
> Two fork-era reconciliations vs. the text below (which predates the fork):
>
> 1. **The harness is Python, not Kotlin.** Themis's eval harness landed as
>    `agents/themis/eval/run_eval.py` (Stage 2.2/2.4 carry-over), not a Kotlin
>    `EvalRunner.kt`. The routing checks (T1/T2/T3) are therefore a sibling Python
>    harness — `agents/themis/eval/run_routing_eval.py` (+ `selftest.py`) — with the
>    same intent_kind / chosen_agent / layer_hit checks, `LayerHitStats`, JSONL + MD
>    reports, and a `thresholds.yaml` gate. The Kotlin data-class snippets below are
>    the *shape* spec, realised in Python.
> 2. **The live gate relocates to the nightly `themis-routing` context (T6), not a
>    `ci.yml` Wiremock replay.** The ratified fork-era policy is unit/component-only
>    in CI; the full-constellation integration tier runs nightly (testing arch §2;
>    contracts §5.2). The routing eval needs the forked NLP stack + a real LLM
>    (Layer 2) + capabilities-mcp — integration-tier. So, exactly as the Stage 2.4
>    corpus gate was relocated: **PR CI** runs the deterministic `eval-themis-routing-selftest`;
>    the **live corpus run** is the nightly `themis-routing` context (olymp
>    `context.yaml` + a kantheon `integrationTest` spec land with the testing arc
>    "as arcs reach the cluster"). The recipe `just eval-themis-routing` runs ad-hoc
>    against any deployed Themis until then.
>
> **Landed in this stage:** `run_routing_eval.py`, `selftest.py`, `thresholds.yaml`,
> `tuning-2026-06.md`, `just eval-themis-routing[-selftest]`, the `ci.yml` self-test
> step, and `eval/README.md`. **Open (Bora-owned):** corpus content fill (~180 q) and
> the consequent ≥60 % Layer-1 sign-off + threshold retune; the olymp `themis-routing`
> context (testing arc).

## Goal

Eval harness extended to check `expected.intent_kind` and `expected.chosen_agent_id` and the routing-layer-hit. CI threshold gates enforced. Layer-1 hit-rate ≥ 60% on the populated routing corpus (Bora's content fill is the soft-blocker; this stage closes once that's met).

## Pre-flight

- [x] **Stage 3.4 DONE** — routing eval corpus skeleton committed.
- [ ] **Bora's corpus content fill in flight** — ~30 questions per bucket × 6 buckets. Tracked separately; this stage's tasks proceed independently and meet at task T5. **(Open — soft-blocks the ≥60 % sign-off.)**
- [x] **Branch**: `feat/p3-s3.5-eval-ci` from `main`.

## Tasks

- [x] **T1 — Extend the eval harness for routing checks.** _(Python `run_routing_eval.py`; fake-Themis HTTP self-test green — `selftest.py`.)_

  Open `agents/themis/eval/src/main/kotlin/.../EvalRunner.kt` (carried over from Resolver in Stage 2.2 and modified in Stage 2.4).

  Add per-question routing checks:

  ```kotlin
  data class EvalQuestion(
      val question: String,
      val lang: String,
      val expected: EvalExpected,
  )

  data class EvalExpected(
      // Existing resolution checks (from Stage 2.4):
      val tokens: List<TokenExpectation>?,
      val entities: List<EntityExpectation>?,
      val functionId: String?,
      val args: JsonObject?,

      // New routing checks (Stage 3.5):
      val intentKind: IntentKind?,                            // PROCEDURAL / RCA / FORECAST / SIMULATION
      val chosenAgentId: String?,                              // "pythia" / "golem-erp" / null (means Layer 3 needs_user_pick)
      val alternatesPresent: List<String> = emptyList(),       // for Layer 3 cases
      val routingLayerExpected: Int? = null,                   // 0/1/2/3
  )

  // Eval-runner: per question, capture actual:
  data class EvalResult(
      val question: String,
      val expected: EvalExpected,
      val actual: ActualResolution,
      val routingMatch: RoutingMatch,
  )

  data class RoutingMatch(
      val intentKindMatch: Boolean?,
      val chosenAgentMatch: Boolean?,
      val layerHitMatch: Boolean?,
      val alternatesMatch: Boolean?,
  )
  ```

  The runner POSTs each question to `themis-mcp/v1/resolve` with `profile = CHAT_QUICK` and asserts.

  Tests: parse a small fixture corpus (5 questions covering all 6 buckets), run against a fake Themis (Wiremock returning structured `RoutingDecision` JSON), assert RoutingMatch fields.

  Acceptance: harness compiles; fake-Themis test green.

- [x] **T2 — Layer-hit instrumentation.** _(`LayerHitStats` + per-layer counters in the report; Layer-1 denominator excludes ambiguous/L3.)_

  Verify `RoutingDecision.layer_hit` is populated correctly by the routing node (it should be from Stage 3.3 — T2 of that stage). Add an assertion in the eval harness: for each `expected.routing_layer_expected`, compare against `actual.routing.layer_hit`.

  Per-layer counters in the harness output:

  ```kotlin
  data class LayerHitStats(
      val total: Int,
      val layer0: Int,
      val layer1: Int,
      val layer2: Int,
      val layer3: Int,
  ) {
      fun layer1HitRate(): Double = layer1.toDouble() / total
  }
  ```

  Acceptance: harness emits `LayerHitStats` in its report.

- [x] **T3 — `just eval-themis-routing` recipe + Markdown report writer.** _(JSONL + MD: aggregate, per-bucket, failed-question table.)_

  Recipe (extends the `eval-themis` recipe from Stage 2.4):

  ```just
  eval-themis *args:
      ./gradlew :agents:themis:eval:run --args="{{args}}"

  eval-themis-routing:
      just eval-themis -- run \
          --corpus agents/themis/eval/corpus/routing-seed.jsonl \
          --target http://themis-mcp.kantheon.svc.cluster.local:7401 \
          --output agents/themis/eval/results/routing-$(date +%Y%m%d).jsonl \
          --report agents/themis/eval/results/routing-$(date +%Y%m%d).md
  ```

  Markdown report shape:

  ```markdown
  # Themis Routing Eval — <date>

  ## Aggregate

  - Questions: NNN
  - Routing accuracy: M / NNN (XX.X%)
  - Intent-kind accuracy: P / NNN (YY.Y%)
  - Layer-1 hit-rate (non-ambiguous): A / B (Z%)
  - Layer-hit distribution: L0=...  L1=...  L2=...  L3=...

  ## Per bucket

  | Bucket | Questions | Routing accuracy | Notes |
  |---|---|---|---|
  | PROCEDURAL single-Golem-ERP | ... | ... | ... |
  | PROCEDURAL cross-domain | ... | ... | ... |
  | RCA | ... | ... | ... |
  | FORECAST | ... | ... | ... |
  | SIMULATION | ... | ... | ... |
  | Ambiguous | ... | ... | ... |

  ## Per-question (failed only)

  | # | Question | Expected | Actual | Diagnosis |
  ```

  Acceptance: `just eval-themis-routing` produces both JSONL and MD outputs against a deployed Themis.

- [ ] **T4 — Wait-point for Bora's corpus content (parallel task).** _(OPEN — Bora-owned ~180-question fill.)_

  This stage's DONE criterion is "Layer 1 hit-rate ≥ 60% on the populated corpus." That requires Bora's content fill. The fill happens in parallel:

  - **What Bora produces**: ~30 questions per bucket, total ~180.
  - **Where**: directly committed to `agents/themis/eval/corpus/routing-seed.jsonl` (the skeleton's seed lines stay; Bora appends).
  - **Quality bar**: each question is realistic Czech or English ERP/analytical user input; each `expected.chosen_agent_id` is what the routing should produce; ambiguous-bucket questions should genuinely have low confidence under the prompts.

  No code task here — sync check-in with Bora before T5. If content fill is partial: T5 still proceeds against whatever corpus is committed; the threshold gate is set conservatively in T5 to accommodate.

  Acceptance: Bora confirms the corpus is ready (or that "current partial content is what we baseline against") and tags the corpus state at that point.

- [x] **T5 — `thresholds.yaml` + gate + tuning log.** _(Floors 70/60/85 held until the populated-corpus run; `tuning-2026-06.md` seeded; retune to current-minus-5pp after fill.)_

  Run `just eval-themis-routing` against the populated corpus. From the resulting report:

  - Record the aggregate routing accuracy (call it `A`).
  - Record the Layer-1 hit-rate (`L`).
  - Record the intent-kind accuracy (`I`).

  Add CI threshold config at `agents/themis/eval/thresholds.yaml`:

  ```yaml
  thresholds:
    routing_accuracy: <max(0.7, A - 0.05)>            # current minus 5pp, but at least 70%
    layer1_hit_rate: <max(0.6, L - 0.05)>             # plan §3.5 specifies ≥ 60%
    intent_kind_accuracy: <max(0.85, I - 0.05)>
  ```

  The harness reads this file at `eval-themis-routing` time and exits non-zero if any threshold is breached.

  **Layer-1 weights tuning pass:** if `L < 0.60`, adjust the `+0.5 / +0.4 / +0.3 / +0.2` weights in `RouteToAgentNode.LayerWeights` (Stage 3.3 task T4) and re-run. Goal: get `L` above 0.60 without sacrificing accuracy. Track the tuning attempts in a short `agents/themis/eval/tuning-2026-05.md` log.

  Acceptance: thresholds set; current run green; per-attempt tuning log committed.

- [x] **T6 — Wire eval gate into CI (relocated).** _(PR CI runs `eval-themis-routing-selftest`; live corpus gate is the nightly `themis-routing` context — supersedes the `ci.yml` replay-mode proposal, per fork policy.)_

  Extend `.github/workflows/ci.yml`:

  ```yaml
  jobs:
    ci:
      # ... existing steps ...
      - name: routing eval gate (requires deployed Themis or replay-mode)
        if: github.event_name == 'pull_request'
        run: just eval-themis-routing-replay  # see below
  ```

  Since CI doesn't have a live K3s cluster: introduce a **replay mode** in the harness that runs against a recorded fixture of capabilities-mcp + LLM Gateway responses captured from a recent successful run. Cache the fixtures at `agents/themis/eval/fixtures/replay-2026-05.tar.gz`. Run against a local-Wiremock-driven mock instead of live services.

  ```just
  eval-themis-routing-replay:
      ./gradlew :agents:themis:eval:run --args="run --corpus ... --replay agents/themis/eval/fixtures/replay-2026-05.tar.gz --thresholds agents/themis/eval/thresholds.yaml"
  ```

  Acceptance: CI gate green; PR opened titled `[p3-s3.5] routing eval harness + CI gate`.

## DONE — Stage 3.5

- [x] All six harness/gate tasks landed (T4 corpus fill remains Bora-owned).
- [ ] Layer-1 hit-rate ≥ 60% on the populated corpus. **(Pending Bora's corpus fill.)**
- [ ] Routing accuracy + intent-kind accuracy baselines locked; CI gate at "current minus 5pp". **(Pending first live run on populated corpus.)**
- [ ] Bora's corpus content fill recognised as a partial / complete state at this stage close.
- [ ] PR merged.

## Library / pattern references

- **ai-platform `agents/resolver/eval/`** (carried over to `agents/themis/eval/` in Stage 2.2) — base harness.
- **Wiremock replay fixtures** — `https://wiremock.org/docs/record-playback/`. Use Wiremock's recording mode to capture a live successful run, then replay in CI.
- **JSONL streaming** with kotlinx-serialization: `.jsonl` files are line-delimited JSON; one `Json.encodeToString(...)` per line.

## Out of scope for Stage 3.5

- Iris BFF chip rendering — Stage 3.6.
- Multi-language eval (Slovak, Hungarian, German) — v1.5+.
- Embedding-based agent pre-selection for large registries — v1.5+ (current Layer-2 lists top-5).
- Auto-tuning of layer weights — manual tuning in T5; learned tuning is v1.5+.
