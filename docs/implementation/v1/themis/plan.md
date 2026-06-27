# Themis — Phased Implementation Plan (kantheon arc)

> **Scope.** The phased plan that takes us from "today's kantheon repo is empty" to "Themis is live in kantheon with the routing layer." Three phases, fourteen stages, roughly 80 tasks.
>
> **Companions.** [`architecture.md`](../../../architecture/themis/architecture.md), [`contracts.md`](../../../architecture/themis/contracts.md), [`../../planning-conventions.md`](../../planning-conventions.md), [`themis-design.md`](../../../design/themis/themis-design.md).
>
> **Hierarchy** (per `planning-conventions.md`): task → stage (~6 tasks, testable) → phase (set of stages, deployable). All planning in this repo follows this convention.

## 1. Phase summary

| Phase | Goal — what deploys at the end | Stages | Estimated effort |
|---|---|---|---|
| **Phase 1 — Kantheon foundation + `capabilities-mcp`** | `tools/capabilities-mcp` running in local K3s; seed `AgentManifest`/`ShemManifest` fixtures loaded; ai-platform `query-mcp` registers via heartbeat as PoC; OTel traces span the registration end-to-end. | 1.1 / 1.2 / 1.3 / 1.4 | ~2–3 weeks |
| **Phase 2 — Resolver → Themis in kantheon, Koog-based** | `agents/themis` running in local K3s; Koog graph; eval gate green against Stage 03 Czech corpus; equal-or-better quality than the plain-coroutines Resolver baseline. | 2.1 / 2.2 / 2.3 / 2.4 | ~2–3 weeks |
| **Phase 3 — Themis routing layer + Iris co-design** | Themis with `RoutingDecision` / `IntentKind` / `Profile` / `RefusalWithGaps` / `MultiQuestionDetected`; Iris BFF chip round-trip green against fixture LLM; routing eval-gate enforced in CI. | 3.1 / 3.2 / 3.3 / 3.4 / 3.5 / 3.6 | ~3–4 weeks |

**Total:** ~7–10 weeks of focused work. Critical path runs strictly Phase 1 → Phase 2 → Phase 3 (no parallelisation of phases; Phase 2 hard-depends on Phase 1, Phase 3 hard-depends on Phase 2).

Within a phase, some stage parallelisation is possible (see "Dependencies" per stage below) but the conservative default is sequential.

## 2. Pre-flight — what must be true before Phase 1 starts

These are the things outside the kantheon repo that must be settled before kantheon-side work begins. Each links back to `aip-v1-gap-closure-plan.md` where the work is tracked.

| Pre-flight item | Source | Status (2026-05-15) |
|---|---|---|
| ai-platform Maven publishing live (GitHub Packages) for `shared/proto` + `otel-config` + `fuzzy-common` + `ktor-configurator` + `logging-config` (no `mcp-server-base` — corrected 2026-06-12) | Gap 1 | **closed** — `maven-publish` plugin in shared/proto + 4 shared libs |
| ai-platform G1 — Czech-aware fuzzy (NFD + inflection trim) | Gap 2 | **closed** — `TextNormalizer.fold` + lemma map |
| ai-platform Resolver builds at HEAD | Gap 3 | implicit closed — later commits touch resolver and build |
| ai-platform `nlp-mcp.analyze` ops-array bug | Gap 4 | **closed** — `args?.get("ops")?.jsonArray` |
| Bora confirms GitHub org name for Maven `<org>/ai-platform` URL | Gap 1 follow-up | — confirm before Phase 1 Stage 1.1 |
| Bora has a `read:packages` PAT in `~/.gradle/gradle.properties` | Gap 1 dev-onboarding | — first-time setup; documented in Stage 1.1 README |

All structural CRITICAL gaps are closed. Phase 1 is unblocked.

## 3. Phase 1 — Kantheon foundation + `capabilities-mcp`

**Goal.** Bring the kantheon repo to life with a deployable `tools/capabilities-mcp` service that other services (kantheon-side and ai-platform-side) can register with.

**Deployable at phase close.** `capabilities-mcp` running in local K3s, manifests loaded, ai-platform `query-mcp` heartbeating; observability surfaces in Grafana.

### Stage 1.1 — Repo bootstrap

**Goal.** Kantheon repo skeleton compiles green: `just init / build-kt / test-all / lint-all` succeed against an empty service stub.

**Pre-flight.**
- ai-platform Maven publishing closed (confirmed above).
- Bora's GitHub PAT with `read:packages` set up in `~/.gradle/gradle.properties`.
- Bora confirms the GitHub org name to embed in `settings.gradle.kts`'s GitHub Packages URL.

**Tasks (6).**
1. **Top-level skeleton** — Create `kantheon/` dirs: `agents/`, `tools/`, `frontends/`, `shared/proto/`, `shared/libs/kotlin/`, `gradle/`, `.github/workflows/`. Add `.gitignore`, `.editorconfig`, top-level `README.md`.
2. **Gradle wiring** — `settings.gradle.kts` declaring GitHub Packages repo (with `cz.dfpartner` group filter); top-level `build.gradle.kts` (Kotlin DSL); `gradle/libs.versions.toml` with `ai-platform-*` version refs per `contracts.md` §9.2; gradle wrapper.
3. **Justfile** — Recipes: `init`, `proto`, `build-kt`, `deploy-kt`, `test-kt`, `test-all`, `lint-all`. Mirrors ai-platform layout. `init` includes the PAT-onboarding doc prompt.
4. **CI workflow** — `.github/workflows/ci.yml` running `init → lint-check → test-all`; auto-detect Jib vs Docker via Gradle plugins. First green run on an empty stub.
5. **Proto codegen** — `shared/proto/build.gradle.kts` with Kotlin + Python + TypeScript outputs; `just proto` regenerates all three.
6. **Empty service stub to exercise the build** — `tools/_smoke-test/` Ktor "hello world" service; built green via `just build-kt _smoke-test`; deleted at end of Phase 1.

**Stage 1.1 DONE.** `just init && just build-kt _smoke-test && just test-all && just lint-all` all green on a clean checkout. CI passes on PR merge.

**Deliverable.** kantheon repo at `git tag bootstrap/v0.1.0`.

**Dependencies.** None. Starts immediately.

### Stage 1.2 — Capabilities proto + service skeleton

**Goal.** `tools/capabilities-mcp` module exists, compiles, exposes a Ktor server bound to `/health` and `/ready`. Capabilities proto generated; in-memory registry skeleton compiles with empty implementations.

**Pre-flight.** Stage 1.1 DONE.

**Tasks (6).**
1. **Write `capabilities.proto`** — per `contracts.md` §1.1; generate Kotlin/Python/TS bindings via `just proto`.
2. **Module skeleton** — `tools/capabilities-mcp/build.gradle.kts` declares deps on `cz.dfpartner:ktor-configurator` (incl. the MCP/Ktor base), `cz.dfpartner:otel-config`, `cz.dfpartner:logging-config`, generated kantheon-proto. `App.kt` mirrors ai-platform EXAMPLES.md §1b (≤45 lines).
3. **Tests first** — Write `InMemoryRegistrySpec` (Kotest StringSpec) covering: register tool, register agent, idempotent re-register, get-by-id, list, listAgents, capacity sanity. Tests fail (no implementation yet).
4. **In-memory registry** — Make tests pass. `ConcurrentHashMap<String, RegistryEntry>` keyed by `capability_id` or `agent_id`; `RegistryEntry` carries proto-shape + assigned `registration_id` + `last_heartbeat_at`.
5. **OTel + Ktor base** — Wire `createOpenTelemetrySdk()` per EXAMPLES.md §8 and `installKtorServerBase()` per §1a. `/health` and `/ready` routes (per `contracts.md` §2.2).
6. **Lint + format** — Resolve all ktlint warnings; commit clean.

**Stage 1.2 DONE.** `just test-kt capabilities-mcp` green (registry unit tests). `just build-kt capabilities-mcp` produces a runnable JAR. `/health` returns 200; `/ready` returns 503 (no loader yet).

**Deliverable.** capabilities-mcp module compiles + unit tests pass.

**Dependencies.** Stage 1.1.

### Stage 1.3 — MCP + REST surface + heartbeat client

**Goal.** `capabilities-mcp` answers all six MCP tools and their REST mirrors against the in-memory registry. Heartbeat client library exists and is testable.

**Pre-flight.** Stage 1.2 DONE.

**Tasks (6).**
1. **MCP tool surface tests first** — Wiremock-driven `CapabilitiesMcpSpec` testing each of `search / list / list_agents / get / register / heartbeat` against fixture-seeded registry; tests fail.
2. **MCP tools** — Implement the six tools per `contracts.md` §2.1. Validate input JSON against proto shapes; emit `structuredContent` with `messages: []`.
3. **REST mirror** — Mirror endpoints per `contracts.md` §2.2; same Kotlin handler delegates from both surfaces.
4. **TTL pruning** — `TtlPruner` background coroutine (every 30s); filters pruned entries from `list*()` but keeps them in `get()`; source-controlled fixtures (no `last_heartbeat_at`) exempt. Tests added (`TtlPrunerSpec`).
5. **Version handling** — `VersionResolver.resolve(id)` parses `capability_id` for `:vN` suffix; unsuffixed returns latest. Tests (`VersionResolverSpec`).
6. **Heartbeat client library** — Create `shared/libs/kotlin/capabilities-client` module per `contracts.md` §4. `CapabilitiesClient.startupRegister(...)` + `CapabilitiesReadClient`. Wiremock-driven tests (`CapabilitiesClientSpec`) covering: idempotent register, exponential backoff, 30s-TTL cache, warn-and-continue on unreachable.

**Stage 1.3 DONE.** Full MCP + REST surface answered; pruning + versioning + client library all green in unit + Wiremock-component tests.

**Deliverable.** capabilities-mcp + capabilities-client at component-test green.

**Dependencies.** Stage 1.2.

### Stage 1.4 — Deployment + ai-platform PoC

**Goal.** Deploy `capabilities-mcp` to local K3s; seed fixtures load; `query-mcp` (ai-platform) registers via heartbeat as PoC; OTel trace propagation verified at the component level (mocked) — real all-hops trace confirmation deferred to the separate integration-test suite.

**Pre-flight.** Stage 1.3 DONE. ai-platform `query-mcp` deployable to local K3s (already true).

**Tasks (6).**
1. **YAML loader tests + impl** — `ManifestYamlLoaderSpec` covering: valid YAML loads, invalid YAML logs + skips, fixture + runtime collision lets runtime win. Implement loader per `contracts.md` §3.4.
2. **Seed fixtures** — Create `tools/capabilities-mcp/src/main/resources/manifests/agents/pythia.yaml` and `golem-erp.yaml` per `contracts.md` §3.1 + §3.2 with **structural placeholders + comments where Bora fills content**. Phase 1 does NOT block on Bora filling the placeholders — the loader works against the structural fixtures; the content can land iteratively before Phase 3.
3. **K8s manifests** — `tools/capabilities-mcp/k8s/{base,overlays/local}/`. `imagePullPolicy: Never` in local. `readinessProbe` blocks on `/ready` (fixtures loaded).
4. **Deploy + readiness validation** — `just deploy-kt capabilities-mcp`; verify pod is Ready only after fixtures load. Verify `kubectl exec` test on `/v1/capabilities/agents` returns the two fixture agents.
5. **PoC: query-mcp heartbeat wiring (ai-platform side)** — In `ai-platform/tools/query-mcp/`: add `cz.tatrman:capabilities-client` dep (resolved from kantheon Maven if/when published, or via local Maven cache for now); wire `CapabilitiesClient.startupRegister(...)` in `App.kt`. Generate `query.named.yaml` style ToolCapability for query-mcp. Deploy.
6. **OTel propagation verification (mocked)** — Component test: drive a `register` call into capabilities-mcp with an incoming trace context and assert the span is created and the context is propagated/recorded. (Real query-mcp → capabilities-mcp end-to-end trace confirmation in Tempo is deferred to the separate integration-test suite.) Per the testing policy (planning-conventions.md §4): mocked unit tests only; integration suite is separate.

**Stage 1.4 DONE.** capabilities-mcp pod Ready in local K3s; `list_agents()` returns two fixture entries; `list()` includes one runtime-registered tool capability (query-mcp); Grafana shows traces spanning ai-platform → kantheon.

**Deliverable.** `git tag capabilities-mcp/v0.1.0`.

**Phase 1 DONE.** All four stages above closed. The constellation's registry exists and is wired.

**Dependencies.** Stage 1.3. Stage 1.4's PoC task (#5) requires a small PR in ai-platform — confirm sequencing with whoever owns query-mcp.

## 4. Phase 2 — Resolver → Themis in kantheon, Koog-based

**Goal.** `agents/themis` running in kantheon, Koog-backed, eval gate green against the existing 50-question Stage 03 corpus.

**Deployable at phase close.** `themis-mcp` in local K3s; Resolver semantics preserved; Koog migration complete.

### Stage 2.1 — Koog spike (go/no-go gate)

**Goal.** Validate Koog 0.8.x (or current) compiles and runs against kantheon's current Ktor dependency graph. Produce a go/no-go report.

**Pre-flight.** Phase 1 DONE. Bora unblocks the spike.

**Tasks (5 — smaller stage by design).**
1. **Sandbox module** — `agents/_koog-spike/` with current Ktor (from `libs.versions.toml`) and Koog deps. Builds green.
2. **Port one node** — Implement a Koog `AIAgentStrategy` containing the `extractUniversal` node (deterministic, no LLM call). Local-cloned Koog reference: `~/Dev/view-only/koog`.
3. **Port one LLM-using node** — Implement `filterRelevantSpans` (CHEAP-tier LLM) using Koog `StructureFixingParser` against a Wiremock-driven fake LLM gateway. Verify retry + structured-output flow.
4. **Compatibility report** — Document: Koog version that worked, any Ktor conflict surfaced, any dependency overrides required, performance overhead measurement (vs plain coroutines).
5. **Go/no-go decision** — Bora reviews the spike report; either: (a) proceed to Stage 2.2 + 2.3 as planned; (b) keep plain-coroutines fallback per acknowledged v1 drift; or (c) defer further (return Stage 2 to plan).

**Stage 2.1 DONE.** Spike report committed; Bora's decision noted; `agents/_koog-spike/` retained for reference until Stage 2.3 closes.

**Deliverable.** Spike report at `agents/themis/docs/koog-spike-report.md`.

**Dependencies.** Phase 1 DONE. Independent of other Phase 2 stages — gates them all.

### Stage 2.2 — Resolver extraction (`git filter-repo`)

**Goal.** `agents/resolver/` from ai-platform appears at `kantheon/agents/themis/` with proto package renamed; existing tests still pass.

**Pre-flight.** Stage 2.1 closed with go-decision (or Bora's go-with-fallback decision).

**Tasks (6).**
1. **filter-repo dry-run** — On a scratch checkout of ai-platform, run `git filter-repo --path agents/resolver/ --to-subdirectory-filter agents/themis/`. Inspect the output. Verify history preserved.
2. **Apply filter-repo + import** — Apply against a fresh kantheon clone branch. Commit. (Do NOT cross-merge with ai-platform's history; this is a one-way move.)
3. **Proto package rename** — Use `git ls-files | xargs sed -i` (or `replace_all` edits) to rename `cz.dfpartner.resolver.v1` → `org.tatrman.kantheon.themis.v1` in all proto + Kotlin sources + tests. Move the proto file from `cz/dfpartner/resolver/v1/` to `org/tatrman/kantheon/themis/v1/`.
4. **Module rename and Gradle wiring** — Rename module to `agents/themis`; update `settings.gradle.kts`; declare ai-platform Maven deps for `cz.dfpartner.nlp.v1` etc. per `contracts.md` §9.2.
5. **K8s manifests refresh** — `agents/themis/k8s/{base,overlays/local}/`; image name `themis-mcp`; service-name `themis-mcp.kantheon.svc.cluster.local`.
6. **First green build + test** — `just build-kt themis && just test-kt themis` both green. All existing unit + component tests still pass against the moved code (any real-dependency integration tests move to the separate integration-test suite).

**Stage 2.2 DONE.** `agents/themis` compiles, all carried-over tests pass, image builds via Jib.

**Deliverable.** `agents/themis` in kantheon at parity with ai-platform Resolver at HEAD.

**Dependencies.** Stage 2.1.

### Stage 2.3 — Koog graph migration

**Goal.** Replace the plain-coroutines `ResolverGraph` with a Koog `AIAgentStrategy`. All existing tests still pass.

**Pre-flight.** Stage 2.2 DONE. Spike report's go-decision in hand.

**Tasks (6).**
1. **Koog deps + graph skeleton** — Add Koog libraries from `libs.versions.toml`; create `agents/themis/src/main/kotlin/.../koog/ThemisGraph.kt` with empty `AIAgentStrategy`; existing tests continue to pass against the old `ResolverGraph` (kept in place during migration).
2. **Port deterministic nodes first** — `branchOnInput`, `extractUniversal`, `proposeDomainSpans` ported to Koog node API. Existing unit tests rerun against Koog versions (parallel test classes for confidence).
3. **Port LLM-using nodes** — `filterRelevantSpans`, `jointInference` use Koog `StructureFixingParser` with retry + structured-output. Pattern verified in Stage 2.1.
4. **Port HITL nodes** — `decideHitlOrEmit`, `decodeTokenAndApplyChoice`, `assembleResp`. HMAC resume token codec unchanged.
5. **Wire Koog tools** — Wrap MCP/HTTP calls to `nlp-mcp`, `fuzzy-mcp`, `llm-gateway` as Koog `ToolDescriptor` instances. Pattern reference: `~/Dev/view-only/koog/`.
6. **Cutover** — Switch `App.kt` to use the new Koog graph; remove `ResolverGraph` class; all existing unit + component tests pass (real-dependency integration verification deferred to the separate integration-test suite).

**Stage 2.3 DONE.** Koog-based Themis graph operational; `ResolverGraph` class deleted; full test suite green.

**Deliverable.** Koog-based `agents/themis`.

**Dependencies.** Stage 2.2.

### Stage 2.4 — Themis deploy + eval gate

**Goal.** Themis deployed to local K3s; the Stage 03 50-question Czech eval corpus runs green; quality is equal-or-better than ai-platform Resolver baseline.

**Pre-flight.** Stage 2.3 DONE.

**Tasks (5).**
1. **Carry-over eval corpus** — Copy `ai-platform/infra/nlp/eval/corpus/seed.jsonl` to `agents/themis/eval/corpus/seed.jsonl`. Verify schema per `contracts.md` §7.1.
2. **Baseline capture** — Run the corpus against ai-platform's still-deployed Resolver; record per-question outcomes as `eval/baseline-resolver.jsonl`.
3. **Run on kantheon Themis** — Deploy `themis-mcp` to local K3s; run the same corpus; capture `eval/results-themis-v0.1.0.jsonl`.
4. **Compare + remediate** — Diff against baseline. Any regressions → diagnose (Koog adapter? prompt drift from migration?) and fix. Document any deliberate divergences.
5. **Tag + retire** — Tag `themis/v0.1.0`. Mark `ai-platform/agents/resolver/` as ready-to-retire (separate PR in ai-platform; not part of this stage).

> **Closed by relocation (2026-06-20).** Tasks 2–5 above were written as a parity check against ai-platform's Resolver. The fork overtook that premise: fork Stage 2.6 (2026-06-14) switched Themis's runtime onto the in-repo forked stack (Kadmos/Echo/Prometheus), cut the last `cz.dfpartner` Maven dep, and **relocated the eval no-regression gate into the integration track** per the repo-wide unit-tests-only policy. The Resolver baseline (T2) and parity diff (T5) are **superseded** — the comparand is being retired and Themis no longer calls ai-platform. The 50-question corpus eval is now the `themis-routing` nightly integration context (testing Stage 3.1, lands when Themis routing reaches the cluster). Stage 2.4 close = themis-mcp builds + manifests wired to the forked stack + deploys via `just deploy-kt themis` + tag.

**Stage 2.4 DONE.** `themis-mcp` builds and deploys to local K3s against the forked stack (`just deploy-kt themis`, in `deploy-fork`); corpus eval relocated to the integration track; tag pushed.

**Phase 2 DONE.** Themis lives in kantheon with Koog, running on the forked stack; no routing yet, but resolution semantics preserved.

**Deliverable.** `git tag themis/v0.1.0`.

**Dependencies.** Stage 2.3.

## 5. Phase 3 — Themis routing layer + Iris co-design

**Goal.** Themis answers `routeToAgent`; Iris BFF stub round-trips chip picks; routing eval gate enforced in CI.

**Deployable at phase close.** Themis with the four-layer routing cascade live, Iris stub validates chip flow.

### Stage 3.1 — Proto extensions

**Goal.** All proto types for routing (`RoutingDecision`, `IntentKind`, `Profile`, `RefusalWithGaps`, `MultiQuestionDetected`, extended `ResolveRequest`/`Resolution`/`ResolveResponse`) generate clean Kotlin bindings.

**Pre-flight.** Phase 2 DONE.

**Tasks (6).**
1. **Extend `themis.proto`** — Add types per `contracts.md` §1.2 (RoutingDecision, AgentAlternate, IntentKind, Profile, MultiQuestionDetected **with `decomposition`/`decomposition_rationale` (PD-13)**, RefusalWithGaps, Gap, GapKind **incl. `NO_ENTITLED_AGENT = 5` (PD-8)**). Extend ResolveRequest (**incl. `prior_context` HandoffContext, PD-1**) /Resolution/ResolveResponse. **Cohesion-review renames execute here (2026-06-12):** `AgentId` lives in `common/v1` (D2 — referenced, not defined here); Rule-6 messages swap to `org.tatrman.kantheon.common.v1.ResponseMessage` (D1); `ResolveContext.themis_prior_context` → `continuation` (finding 2.3).
2. **Extend `envelope.proto`** — Add `RoutingPickChip` per `contracts.md` §1.3 (single proto type only — full envelope is Iris-arc work; `agent_id` typed `common.v1.AgentId`).
3. **Regenerate bindings** — `just proto`; verify Kotlin + Python + TS bindings compile.
4. **Update existing serialisation paths** — Any Kotlin sealed-class adapters for `ResolveResponse.outcome` extended to cover `refusal`; existing tests should still pass (additive proto change).
5. **Resolution.intent_kind backward-compat test** — Unit test demonstrating an `unknown intent_kind` from an older client deserialises to `INTENT_KIND_UNSPECIFIED` without crashing.
6. **Proto-doc** — Add per-message comments to `themis.proto` matching the prose in `contracts.md` §1.2.

**Stage 3.1 DONE.** Proto compiles green; bindings regenerate; existing tests unchanged.

**Deliverable.** Extended kantheon proto bindings.

**Dependencies.** Phase 2 DONE.

### Stage 3.2 — `classifyIntentKind` + `detectMultiQuestion`

**Goal.** Two new Koog nodes; `Resolution.intent_kind` populated; `MultiQuestionDetected` fires correctly on fixtures.

**Pre-flight.** Stage 3.1 DONE.

**Tasks (6).**
1. **Tests first — `ClassifyIntentKindSpec`** — Fixtures covering each IntentKind (Czech + English); rules-first hit; LLM-fallback path; tie handling.
2. **Rules YAML + parser** — Create `prompts/intent_kind_rules.yaml` per `contracts.md` §8.2; parser loads at boot, validates schema, hot-reload in dev.
3. **`classifyIntentKind` node** — Implement node: rules first against lemmas, LLM fallback (CHEAP-tier, structured-output via Koog StructureFixingParser) when rules tie/none-fire. Writes `Resolution.intent_kind`.
4. **Tests first — `DetectMultiQuestionSpec`** — Fixtures: single-clause OK, two disjoint clauses fires with `SPLIT`, two-clauses-same-entity stays single, **relating-intent clauses (compare / correlate / explain-by / rank-across) → `KEEP_TOGETHER` (PD-13)**, ambiguous → joint-inference verdict, conservative-bias tests.
5. **`detectMultiQuestion` node** — Cheap dependency-parse rule before `extractUniversal`; relation-cue patterns set the `decomposition` verdict (ambiguous cases get it from the existing joint-inference LLM call — one more output field, no new call); emits `AwaitingClarification.MultiQuestionDetected` and terminates only on `SPLIT`.
6. **Wire into Koog graph** — `detectMultiQuestion` between `detectLangAndParse` and `extractUniversal`; `classifyIntentKind` after `extractUniversal`. **Coreference resolves against `prior_context.entities` where present (PD-1).** Existing unit + component tests still pass (additive nodes).

**Stage 3.2 DONE.** Both nodes green in unit + component tests; full graph still passes the existing mocked component suite (real-dependency integration verification deferred to the separate integration-test suite).

**Deliverable.** Themis with intent-kind classification + multi-question detection.

**Dependencies.** Stage 3.1.

### Stage 3.3 — `routeToAgent` + four-layer cascade

**Goal.** `routeToAgent` Koog node populated; capabilities-mcp client reads agent manifests; all four layers exercised by tests.

**Pre-flight.** Stage 3.2 DONE. capabilities-mcp deployed (from Phase 1) with `pythia` and `golem-erp` fixtures.

**Tasks (8 — slightly bigger; could split to 3.3a + 3.3b if needed).**
0. **Routing-view derivation (2026-06-12: Hebe arc + PD-8)** — before any layer: drop `non_routable` entries → filter by the caller's roles against `visibility_roles` (roles read from the **forwarded bearer**, cohesion review D3 — Themis validates the token itself). Tests: Hebe never scored/prompted/chipped; non-entitled agent invisible; no-survivors → `NO_ENTITLED_AGENT` (emission path in Stage 3.4).
1. **Capabilities client wiring** — Use `shared/libs/kotlin/capabilities-client.CapabilitiesReadClient` per `contracts.md` §4. Bootstrap fail-fast: `require(agents.isNotEmpty())` at App.kt start.
2. **Tests first — `RouteToAgentSpec`** — Per-layer fixture: Layer 0 hint short-circuit; Layer 1 single high-confidence match; Layer 2 rule-tie → LLM call; Layer 3 low confidence → needs_user_pick. Plus `relevant_capabilities` matching test.
3. **Layer 0 — routing_hint** — Short-circuit returning RoutingDecision(chosen=hint, confidence=1.0, rationale="hint honoured", layer_hit=0).
4. **Layer 1 — rule scoring** — Implement scoring per `contracts.md`-aligned weights (+0.5 / +0.4 / +0.3 / +0.2). Hand-tuned; will re-tune from eval-corpus run in Stage 3.5.
5. **Layer 2 — LLM fallback** — CHEAP-tier structured-output via Koog StructureFixingParser; prompt at `prompts/route_to_agent_layer2.md` with top-5 candidates' `description_for_router` + `example_questions` + `counter_examples`. Confidence threshold default 0.7.
6. **Layer 3 — needs_user_pick** — Emit RoutingDecision(needs_user_pick=true, alternates=top-3, layer_hit=3) with per-alternate `why`.
7. **`relevant_capabilities` + profile skip** — Match resolved entities + intent against `search_tags`; skip `routeToAgent` entirely if `profile == INVESTIGATION_DEEP`.

**Stage 3.3 DONE.** All four layers exercised by mocked unit/component tests (Wiremock-backed capabilities-mcp client); capabilities-mcp client cache verified; profile skip path tested.

**Deliverable.** Themis with full routing cascade.

**Dependencies.** Stage 3.2 + Phase 1 (capabilities-mcp deployed).

### Stage 3.4 — Profile + `RefusalWithGaps` + corpus skeleton

**Goal.** `Profile` parameter changes graph traversal behaviour; STRICT-mode `RefusalWithGaps` reachable; routing eval corpus has its bucket structure ready for Bora's content fill.

**Pre-flight.** Stage 3.3 DONE.

**Tasks (6).**
1. **Tests first — `ProfileBehaviourSpec`** — Per-profile snapshot tests: CHAT_QUICK vs INVESTIGATION_DEEP, asserting per-node execution count (fuzzy candidates: 3 vs 10, alt-bindings, max HITL rounds, routeToAgent skip).
2. **Plumb `Profile` through graph context** — Koog graph context carries `Profile`; nodes consume it to choose path. Default `CHAT_QUICK` on `PROFILE_UNSPECIFIED`.
3. **Tests first — `RefusalWithGapsSpec`** — STRICT-mode fixtures: entity-unmapped after fuzzy+LLM, no-agent-matches, capability-unavailable, ambiguous-intent, **no-entitled-agent (PD-8: incl. the explicit-naming case — reveal existence, deny access; the Gap.description names the domain)**. Each maps to a specific `GapKind`.
4. **`RefusalWithGaps` emission path** — In `decideHitlOrEmit`, when `ResolveContext.hitl == STRICT` and any blocker hit, emit `RefusalWithGaps` per `contracts.md` §1.2.
5. **Routing corpus skeleton** — Create `agents/themis/eval/corpus/routing-seed.jsonl` with bucket comment-headers per `contracts.md` §7.2, **plus two buckets added 2026-06-12: entitlement (role-filtered routing + explicit-naming refusals) and KEEP_TOGETHER (comparative cross-domain turns)**. **Bora populates ~30 questions per bucket as a separate task tracked outside this stage — Stage 3.5 has Layer 1 hit-rate ≥ 60% as DONE criterion, which depends on Bora's content.**
6. **`continuation` token extension** (renamed from `themis_prior_context`, cohesion review finding 2.3) — Resume-token shape extended per `contracts.md` §5 (Phase 3 additions: `profileAtIssue`, `continuation`, `alternatesOffered`).

**Stage 3.4 DONE.** Profile changes graph behaviour as designed; STRICT-mode refusal reachable; corpus file structure exists.

**Deliverable.** Profile + RefusalWithGaps complete; eval corpus awaiting Bora content.

**Dependencies.** Stage 3.3.

### Stage 3.5 — Eval harness + CI gates

**Goal.** Eval harness extended for intent_kind + routing checks; CI thresholds enforced.

**Pre-flight.** Stage 3.4 DONE. Bora's routing-corpus content lands during this stage (parallel to harness work).

**Tasks (6).**
1. **Extend Resolver eval harness** — Add `expected.intent_kind` and `expected.chosen_agent_id` checks; per-question Layer-hit capture against `expected.routing_layer_expected`.
2. **Harness CLI** — `just eval-themis` runs the harness against deployed Themis; emits JSON + Markdown summary; PASS/FAIL on thresholds.
3. **Bora's routing-corpus content fill (parallel)** — Bora populates ~30 questions per bucket. Tracked as a Bora-owned task; not a Claude task. Phase 3 close requires content sufficient to baseline thresholds.
4. **Set initial thresholds** — After first eval run on populated corpus: record actual numbers; set CI gates at "current minus 5%" for the three metrics (routing accuracy, Layer 1 hit-rate, intent-kind accuracy).
5. **Wire CI gate** — `.github/workflows/ci.yml` extended to run the eval after build/test. PR fails on threshold drop.
6. **Layer-1-weights tuning pass** — If Layer 1 hit-rate < 60%, adjust the +0.5/+0.4/+0.3/+0.2 weights from `Stage 3.3` task 4 based on eval-corpus disagreements. Re-run; document the tuned weights.

**Stage 3.5 DONE.** Eval harness extended; CI gate live; Layer 1 hit-rate ≥ 60% on the populated corpus.

**Deliverable.** Routing eval gate enforced in kantheon CI.

**Dependencies.** Stage 3.4. **Soft-blocked on Bora's content** (corpus population).

### Stage 3.6 — Iris BFF co-design + observability + cutover

**Goal.** RoutingPickChip wired in `envelope/v1`; Iris BFF stub round-trips chip pick end-to-end against fixture LLM; routing observability surfaces in Grafana; design.md updated.

**Pre-flight.** Stage 3.5 DONE.

**Tasks (6).**
1. **RoutingPickChip + Chip oneof** — Add `RoutingPickChip` to `envelope/v1` per `contracts.md` §1.3; coordinate with the Iris BFF co-design owner (if Iris BFF Stage 1 task doc is being drafted in parallel, share the chip schema there).
2. **Iris BFF stub** — **Superseded by the Iris arc (planned 2026-06-12):** the chip round-trip is implemented in the *real* `agents/iris-bff` as Iris Phase 3 Stage 3.1 — one codebase, no throwaway stub. See [`../iris/plan.md`](../iris/plan.md) §5 and [`../../../architecture/iris/contracts.md`](../../../architecture/iris/contracts.md). If Themis Phase 3 reaches this stage before Iris Phase 2 closes, the two stages are executed jointly.
3. **Chip round-trip component test (mocked)** — Component test in `agents/iris-bff/` against a fixture (mocked) LLM and a mocked/in-process Themis: fixture LLM produces Layer 3 outcome; assert chip events emitted; assert reissue with `routing_hint` causes Layer 0 hit on second pass; assert trace context propagates. (Real cross-service iris-bff ↔ Themis round-trip confirmation deferred to the separate integration-test suite.)
4. **Routing metrics** — Add the seven Phase-3 Themis metrics per `architecture.md` §10.2 (`themis_routing_layer_total`, `themis_routing_chosen_total`, `themis_routing_confidence`, `themis_intent_kind_total`, `themis_intent_kind_llm_fallback_total`, `themis_multi_question_detected_total`, `themis_refusal_total`, `themis_capabilities_cache_*`).
5. **Grafana dashboard** — Create `agents/themis/observability/dashboards/themis-routing.json` Grafana dashboard panel definitions covering per-layer hit-rate, agent-distribution, confidence histogram, intent-kind distribution, refusal breakdown.
6. **Documentation + tag** — Update `themis-design.md` to include routing-layer section (fold-in from `themis-brainstorming.md` Part II); update `agents/themis/README.md`; cross-link `themis-replan-architecture.md`; tag `themis/v0.2.0`.

**Stage 3.6 DONE.** Full chip round-trip works; Grafana dashboard live; design.md reflects routing layer; tag pushed.

**Phase 3 DONE.** Themis with routing live. The first Kantheon arc is shipped.

**Deliverable.** `git tag themis/v0.2.0`.

**Dependencies.** Stage 3.5.

## 6. Cross-cutting work — happens during, not as part of stages

These items aren't part of any one stage but accumulate across the arc.

| Item | Phase | Tracked where |
|---|---|---|
| Update [`themis-design.md`](../../../design/themis/themis-design.md) to fold in routing-layer section | Phase 3 Stage 3.6 task 6 | within the stage |
| Sweep "Wrangler" → "Iris" references inside Resolver Stage 04/05/06 carried-over task docs | Phase 2 Stage 2.2 | during extraction |
| Vocabulary sweep (`named query` → `query`, `stackable pattern` → `stack`) inside Pythia + Golem docs | Out of arc | tracked in [`../next-steps.md`](../_archive/next-steps.md) §6 |
| `architecture/capabilities-mcp/` short companion doc | Phase 1 Stage 1.4 task 6 — folded into README | within the stage |
| Per-area READMEs (`design/`, `architecture/`, `implementation/`) | Already landed 2026-05-15 | done |

## 7. Out of scope for this arc

- **Iris BFF full implementation** — planned 2026-06-12: [`../iris/plan.md`](../iris/plan.md). Stage 3.6's chip round-trip executes inside that arc's Stage 3.1.
- **Iris frontend extraction** — planned 2026-06-12; source corrected to `ai-platform/frontends/agents-fe` (see [`../iris/plan.md`](../iris/plan.md) Phase 2).
- **Pythia v0 implementation** — planned 2026-06-12: [`../pythia/plan.md`](../pythia/plan.md).
- **Golem Kotlin rewrite** — planned 2026-06-12: [`../golem/plan.md`](../golem/plan.md); ShemManifest fixture content fill is that arc's Stage 4.1.
- **`agents/resolver/` retirement from ai-platform** — separate PR after Phase 2 DONE + soak period.
- **Migration of remaining ai-platform tools onto heartbeat** — Phase 1 Stage 1.4 PoCs only `query-mcp`; the rest (metadata-mcp, fuzzy-mcp, llm-gateway, nlp-mcp) migrate in a follow-up phase.
- **Authoring of additional ShemManifests** (Golem-HR, Golem-Sales) — only Golem-ERP at v1.
- **Embedding-based pre-selection** for large agent constellations — v1 lists all agents in Layer 2 prompt; works up to ~20 agents.
- **Multi-agent fan-out per turn** — single-agent dispatch only.
- **Plan-node-level delegation to a Golem** — Pythia design v1.5+.
- **Postgres-backed capabilities registry** — in-memory only at v1.
- **Cross-tenant agent registries** — single-tenant per deployment.
- **Full E2E integration tests** — separate integration-test pass per `planning-conventions.md` §4.

## 8. Open questions / Bora-owned content

Tracked here for visibility; do not block any stage's start but must close before the corresponding stage's DONE.

| Question / content gap | Blocking | Notes |
|---|---|---|
| GitHub org name for Maven URL | Stage 1.1 start | One-line config change. |
| Pythia `AgentManifest` content | Stage 1.4 (loader works against placeholder; content can iterate) | Tracked in `kantheon_state_2026_05.md` memory. |
| Golem-ERP `ShemManifest` content | Stage 1.4 (same) | Tracked in `kantheon_state_2026_05.md` memory. |
| Routing eval corpus content (~30 q/bucket × 6 buckets) | Stage 3.5 DONE | Soft-block — harness runs without it; thresholds set after content lands. |
| Czech/English trigger words for `classifyIntentKind` rules YAML | Stage 3.2 DONE | First-pass seed in `prompts/intent_kind_rules.yaml`; iterates from eval-corpus disagreements. |
| Iris BFF co-design owner (for Stage 3.6 chip flow co-design) | ~~Stage 3.6 start~~ **resolved 2026-06-12** | The Iris arc owns it; Stage 3.6 task 2 executes as Iris Phase 3 Stage 3.1. |
| `agent-base` shared lib decision (now, or after Pythia/Golem also exist) | Out of arc | Pythia design deferred until cross-agent patterns surface. |

## 9. Phase progression checklist

A quick scorecard for picking up where the arc left off:

- [x] **Pre-flight done** (Maven publishing live; G1 closed; nlp-mcp bug closed; PAT set up; GitHub org confirmed).
- [x] **Phase 1 Stage 1.1** — Repo bootstrap.
- [x] **Phase 1 Stage 1.2** — Capabilities proto + service skeleton.
- [x] **Phase 1 Stage 1.3** — MCP + REST surface + heartbeat client.
- [x] **Phase 1 Stage 1.4** — Deployment + ai-platform PoC. **Phase 1 DONE — `capabilities-mcp/v0.1.0`** _(T4 live-K3s deploy + T5 query-mcp PoC + T6 OTel verification deferred-with-evidence; see `architecture/capabilities-mcp/design.md` §7)_.
- [x] **Phase 2 Stage 2.1** — Koog spike (go/no-go). **GO 2026-05-29.**
- [x] **Phase 2 Stage 2.2** — Resolver extraction.
- [x] **Phase 2 Stage 2.3** — Koog graph migration.
- [x] **Phase 2 Stage 2.4** — Deploy + eval gate. **Phase 2 DONE — `themis/v0.1.0` (2026-06-20).** Closed by relocation: corpus eval moved to the integration track (the `themis-routing` nightly context, testing Stage 3.1); themis-mcp builds + deploys against the forked stack (`just deploy-kt themis`). Resolver-parity tasks superseded by the fork._
  - **Inserted: fork switch-over (fork Stage 2.6).** Themis's runtime deps moved off ai-platform onto the in-repo forked stack — proto `cz.dfpartner.nlp.v1` → `org.tatrman.kadmos.v1` + Rule-6 `metadata.ResponseMessage` → `common.v1`; endpoints nlp→Kadmos (`/v1/analyze` 7270), fuzzy→Echo (`/match` 7265), gateway→Prometheus (7280); the last `cz.dfpartner:shared-proto` Maven dep cut. Code parts done 2026-06-14; the **eval no-regression gate runs in the separate integration track** (unit-tests-only policy). Authority: [`../fork/tasks-p2-s2.6-themis-switchover.md`](../fork/tasks-p2-s2.6-themis-switchover.md).
- [ ] **Phase 3 Stage 3.1** — Proto extensions.
- [ ] **Phase 3 Stage 3.2** — `classifyIntentKind` + `detectMultiQuestion`.
- [ ] **Phase 3 Stage 3.3** — `routeToAgent` four-layer cascade.
- [ ] **Phase 3 Stage 3.4** — Profile + `RefusalWithGaps` + corpus skeleton.
- [~] **Phase 3 Stage 3.5** — Eval harness + CI gates. **Harness + gate landed (2026-06-21):** Python routing harness (`run_routing_eval.py` + `selftest.py`), `thresholds.yaml`, `just eval-themis-routing[-selftest]`, `ci.yml` self-test step, `tuning-2026-06.md`, `eval/README.md`. **Two fork-era reconciliations:** harness is Python (not Kotlin `EvalRunner.kt`); the live corpus gate relocates to the nightly `themis-routing` integration context (not a `ci.yml` Wiremock replay — mirrors the Stage 2.4 relocation + unit/component-only-CI policy). **Open (Bora-owned):** corpus content fill (~180 q) → ≥60 % Layer-1 sign-off + threshold retune; olymp `themis-routing` context (testing arc).
- [~] **Phase 3 Stage 3.6** — Iris stub + observability + cutover. **Themis-side landed (2026-06-21):** T4 seven routing metrics in `ResolverOtel` + Grafana dashboard `observability/dashboards/themis-routing.json`; T5 routing-layer fold-in to `themis-design.md`; T6 README + architecture §10.2 cache-deferral note. **T1** (`RoutingPickChip`) pre-existing (Iris arc); **T2/T3** (chip round-trip) superseded by the Iris arc (real iris-bff). **Deferred to Bora:** `themis/v0.2.0` tag + Phase-3-DONE flip; `themis_capabilities_cache_*` (shared-lib hook); live nightly run on the populated corpus. **Phase 3 DONE — `themis/v0.2.0`** pending those.

---

*Plan-doc owner: Bora. First applied to the Themis-in-kantheon arc 2026-05-15. Each stage's per-task list is at `docs/implementation/v1/themis/tasks-p<n>-s<n.m>-*.md` (written after this plan is reviewed).*
