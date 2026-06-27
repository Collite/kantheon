# Stage 2.4 — Themis deploy + eval gate

> **Phase 2, Stage 2.4.** Final stage of Phase 2.
>
> **Reads with.** [`tasks-p2-overview.md`](./tasks-p2-overview.md), [`plan.md`](./plan.md) §4.4, [`../../../architecture/themis/contracts.md`](../../../architecture/themis/contracts.md) §7.1 (eval-corpus schema).

> **Status (2026-06-20): CLOSED by relocation — Phase 2 done at `themis/v0.1.0`.** This task list was written (2026-05-30) as a parity check against ai-platform's Resolver and then deferred because that stack wasn't deployable locally. The **fork overtook the premise**: fork Stage 2.6 (2026-06-14) switched Themis onto the in-repo forked stack (Kadmos/Echo/Prometheus), cut the last `cz.dfpartner` Maven dep, and **relocated the eval no-regression gate into the integration track** (repo-wide unit-tests-only policy). Tasks T2 (Resolver baseline) and T5 (parity diff) are **superseded** — the comparand is being retired and Themis no longer calls ai-platform. The 50-question corpus eval now lives in the `themis-routing` nightly integration context (testing Stage 3.1, lands when Themis routing reaches the cluster). Close = themis-mcp builds + overlay wired to the forked stack + deploys via `just deploy-kt themis` (now in `deploy-fork`) + `themis/v0.1.0` tagged. See the superseded deferral note at [`../../../../agents/themis/eval/STAGE-2.4-DEFERRAL.md`](../../../../agents/themis/eval/STAGE-2.4-DEFERRAL.md).

## Goal

`themis-mcp` pod deployed to local K3s. The 50-question Czech eval corpus from ai-platform's Stage 03 runs against it. Quality is equal-or-better than the ai-platform plain-coroutines Resolver baseline on the same corpus. Tag `themis/v0.1.0`.

## Pre-flight

- [ ] **Stage 2.3 DONE** — Koog cutover merged.
- [ ] **ai-platform `agents/resolver/` still deployed** in its own namespace — needed for the baseline run.
- [ ] **`infra/llm-gateway`, `tools/nlp-mcp`, `tools/fuzzy-mcp`** all healthy in ai-platform namespace.
- [ ] **Branch**: `feat/p2-s2.4-deploy-eval` from `main`.

## Tasks

- [x] **T1 — Carry over the eval corpus from ai-platform.**

  Copy `/Users/bora/Dev/ai-platform/infra/nlp/eval/corpus/seed.jsonl` to `kantheon/agents/themis/eval/corpus/seed.jsonl`. Schema per [`contracts.md`](../../../architecture/themis/contracts.md) §7.1.

  Verify integrity:

  ```bash
  wc -l agents/themis/eval/corpus/seed.jsonl    # expect 50
  jq -c . agents/themis/eval/corpus/seed.jsonl | head -3   # JSONL well-formed
  ```

  Also bring over the eval harness from `ai-platform/agents/resolver/eval/` (per the extraction in Stage 2.2 — this should already be present at `agents/themis/eval/`; verify, and if missing, copy + Kotlinify against the kantheon module paths).

  Acceptance: corpus file present with 50 entries; harness `EvalRunner` class exists in `agents/themis/eval/src/...` (or wherever it landed post-extraction).

- [ ] **T2 — Baseline capture: run corpus against ai-platform Resolver.**

  Hit the **deployed** ai-platform `agents/resolver` (NOT the kantheon `agents/themis` — that's the comparand). Use the eval harness from `ai-platform/agents/resolver/eval/`:

  ```bash
  cd /Users/bora/Dev/ai-platform
  just eval-resolver --corpus infra/nlp/eval/corpus/seed.jsonl \
                      --target http://resolver.ai-platform.svc.cluster.local:NNNN \
                      --output /tmp/baseline-resolver.jsonl
  ```

  If `just eval-resolver` recipe doesn't exist, run the eval main class directly:

  ```bash
  ./gradlew :agents:resolver:eval:run \
      --args="--corpus infra/nlp/eval/corpus/seed.jsonl --target http://localhost:NNNN --output /tmp/baseline-resolver.jsonl"
  ```

  Each output line = `{ question, lang, expected, actual, match: bool, diagnostics: {...} }`.

  Compute baseline metrics: parse-accuracy, entity-binding accuracy, function-id accuracy, args-json accuracy, overall green-rate.

  Capture the result as `agents/themis/eval/baselines/resolver-<DATE>.jsonl` in kantheon. Commit it.

  Acceptance: baseline file committed; baseline metrics summarised in a short `eval/baselines/SUMMARY.md` (per-question pass/fail breakdown + aggregate %).

- [ ] **T3 — Deploy `themis-mcp` to local K3s.**

  ```
  just deploy-kt themis
  kubectl -n kantheon wait deployment/themis-mcp --for=condition=Available --timeout=120s
  kubectl -n kantheon port-forward svc/themis-mcp 7401:7401 &
  curl -sf http://localhost:7401/health
  ```

  Post-deploy smoke (a single manual resolve call — sanity check, not a gating test; full real-services round-trip verification belongs to the separate integration-test suite per planning-conventions.md §4):

  ```bash
  curl -sfX POST http://localhost:7401/v1/resolve \
      -H 'Content-Type: application/json' \
      -d '{
        "conversationId": "smoke-1",
        "fresh": { "text": "Které faktury Shell ještě neuhradil?" },
        "registry": { ... minimal registry ... },
        "context": { "locale": "cs" }
      }' | jq .
  ```

  Should return a `Resolution` with `function_id: listUnpaidInvoices` and `bindings` containing a `Shell` customer entity. (Exact result varies; smoke goal is "non-error response with structured payload".)

  Acceptance: pod Ready; `/health` 200; smoke POST returns structured response (no 5xx).

- [ ] **T4 — Run corpus against kantheon `themis-mcp`.**

  Use kantheon's now-extracted eval harness:

  ```
  just eval-themis --corpus agents/themis/eval/corpus/seed.jsonl \
                    --target http://localhost:7401 \
                    --output agents/themis/eval/results/themis-v0.1.0.jsonl
  ```

  Update `justfile` with the recipe if missing:

  ```just
  eval-themis *args:
      ./gradlew :agents:themis:eval:run --args="{{args}}"
  ```

  Acceptance: all 50 questions return a result (none time out / crash); results JSONL committed under `agents/themis/eval/results/`.

- [ ] **T5 — Diff against baseline; remediate regressions.**

  Write a diff tool (or extend the eval harness):

  ```bash
  ./gradlew :agents:themis:eval:run --args="diff \
      --baseline agents/themis/eval/baselines/resolver-<DATE>.jsonl \
      --candidate agents/themis/eval/results/themis-v0.1.0.jsonl \
      --report agents/themis/eval/results/diff-<DATE>.md"
  ```

  Output structure (in `diff-<DATE>.md`):

  ```markdown
  # Eval Diff — themis-v0.1.0 vs resolver baseline

  ## Aggregate
  - baseline green-rate: NN/50
  - candidate green-rate: MM/50
  - Δ: ±X

  ## Per-question
  | # | Question | baseline | candidate | Notes |
  |---|---|---|---|---|
  | 1 | "Které faktury Shell ještě neuhradil?" | PASS | PASS | — |
  | 17 | "..." | PASS | FAIL | wrong functionId — diagnose |
  ...
  ```

  **Acceptance criterion for Phase 2 close**: `MM ≥ NN` (kantheon Themis equal-or-better than ai-platform Resolver baseline).

  If `MM < NN`:
  - Identify per-question regressions. Are they:
    - Prompt drift during Koog migration? → revert to verbatim prompts.
    - Koog `StructureFixingParser` parsing differently than the previous hand-rolled JSON path? → adjust schema or retries.
    - HTTP-client-config drift (timeouts, headers) between extraction-era and now? → align.
  - Fix and re-run until `MM ≥ NN`. Document each fix as a commit.

  Acceptance: diff report committed; aggregate criterion met.

- [ ] **T6 — Tag, README, hand-off.**

  - Update `agents/themis/README.md` — what the service is, how to deploy, how to run the eval, env-var contract, status. Cross-link `docs/architecture/themis/architecture.md`, `docs/architecture/themis/contracts.md`, `docs/design/themis/themis-design.md`. Note that the routing layer ships in Phase 3.

  - Update `gradle/libs.versions.toml` if you want to bump `themis-version` (not strictly needed — module-level versioning via Jib is fine).

  - **Tag**: `git tag themis/v0.1.0` and push.

  - Update kantheon memory file `kantheon_state_2026_05.md` — replace "Phase 2 — in progress" with "Phase 2 — closed at themis/v0.1.0 on <DATE>; eval-gate green; Phase 3 (routing layer) unblocked."

  - Update `docs/implementation/v1/themis/tasks-p2-overview.md` — check all four stage boxes.

  Acceptance: tag pushed; README written; PR merged; Phase 2 closed.

## DONE — Stage 2.4 / Phase 2 (closed 2026-06-20 by relocation)

- [x] **T1 (corpus + harness)** done. `run_eval.py` retargeted to Themis; corpus + ENTITIES_ONLY corpus in tree from Stage 2.2.
- [x] **T2 (baseline) superseded** — the ai-platform Resolver comparand is being retired; post-fork Themis no longer calls any ai-platform service, so a Resolver-parity baseline is no longer the gate.
- [x] **T3 (deploy) done** — themis-mcp builds (`themis-mcp:dev` via Jib), its `overlays/local` is wired to the forked stack (Kadmos 7270 / Echo 7265 / Prometheus 7280), kustomize validates, and it deploys via `just deploy-kt themis` (added to `deploy-fork`). Live runtime round-trip is the integration track's job.
- [x] **T4/T5 (candidate eval + diff) relocated** — the 50-question corpus eval runs in the `themis-routing` nightly integration context (testing Stage 3.1), not here. Per the repo-wide unit-tests-only policy (fork Stage 2.6).
- [x] **T6 (README + tag)** — README current; `themis/v0.1.0` tagged; memory updated.
- [x] **Phase 2 DONE — `themis/v0.1.0`.** Routing layer is Phase 3.

## Library / pattern references

- **ai-platform `infra/nlp/eval/`** — eval corpus location + format.
- **ai-platform `agents/resolver/eval/`** (now `agents/themis/eval/` post-extraction) — the harness implementation.
- **JSONL** — one record per line; `jq -c .` produces it from JSON.
- **kantheon `tools/capabilities-mcp/`** Phase 1 — local-K3s deployment reference.

## Out of scope for Stage 2.4

- Routing layer or any Phase 3 capability — kept strictly off the table to keep eval-gate parity clean.
- Retiring ai-platform `agents/resolver/` — separate PR after a soak period (suggested: 1 week running themis-v0.1.0 in production-like usage).
- Multi-instance / HA deployment of themis — single-replica in v1.
- Postgres-backed conversation persistence — Themis is stateless via HMAC resume tokens (v1).
