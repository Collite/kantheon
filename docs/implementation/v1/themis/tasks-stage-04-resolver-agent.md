# Stage 04 — `agents/resolver` (Koog, Kotlin)

Companion to [docs/v1/resolver-design.md](docs/v1/resolver-design.md).

Goal: implement the Resolver agent itself — the Koog graph that parses,
extracts entities, runs joint inference, and either emits a Resolution or an
AwaitingClarification. Uses everything built in Stages 01–03.

This stage runs in parallel with Stage 03 once Stage 02 closes.

## Entry criteria

- Stage 02 complete: `tools/nlp-mcp` exposes `analyze`/`parse` over MCP and
  trace propagation across Python↔Kotlin is verified.
- `tools/fuzzy-mcp` is callable.
- `infra/llm-gateway` is callable via existing platform patterns.
- Stage 03 in progress (eval harness available for Stage 04 exit criteria);
  Stage 03 does not need to be complete before Stage 04 can start, but its
  eval corpus is needed before Stage 04 can hit its exit criterion on
  resolution quality.
- Bora confirms the directory placement: `agents/` has been Python-only so
  far. Stage 04 introduces the first Kotlin/Koog agent there. **This is the
  first cross-cutting decision of the stage** and must be confirmed before
  scaffolding.

## Exit criteria

- `just deploy-kt resolver` deploys `agents/resolver` to local K3s.
- `POST /v1/resolve` with a Czech question returns either `Resolution` or
  `AwaitingClarification` per the proto.
- HITL resume flow round-trips: an `AwaitingClarification` response can be
  followed by a `ResumeAnswer` and yield a `Resolution`.
- Resolver passes the Stage 03 seed eval corpus with measured precision and
  recall above an agreed threshold (specific numbers set after baseline run).
- Cache hits visible in OTEL metrics (NLP-call-level and resolution-level
  caches both observed).
- OTEL trace propagation verified at the component level (mocked nlp-mcp /
  fuzzy-mcp / llm-gateway via Wiremock): spans are created and context is
  propagated across the resolver's call chain. (Full all-real-hops trace
  confirmation in Tempo — caller → resolver → nlp-mcp → infra/nlp → fuzzy-mcp
  → fuzzy-matcher → llm-gateway — is deferred to the separate integration-test
  suite.)

## Tasks

### 1. Define Resolver proto
- [ ] Create `shared/proto/src/main/proto/cz/dfpartner/resolver/v1/resolver.proto`
      per the design doc §Wire formats. Include `repeated ResponseMessage messages = 99;`.
- [ ] Run `just proto-all` so types are generated for Kotlin, Python, and JS.

### 2. Project scaffolding
- [ ] Confirm directory placement with Bora (entry criterion).
- [ ] Create `agents/resolver/` mirroring an existing Kotlin service layout.
      Apply `id("my.kotlin-ktor")` (or Spring Boot if Koog requires it).
- [ ] Add Koog dependency to `gradle/libs.versions.toml`. Versions are
      centralized; do not hardcode in `build.gradle.kts`.
- [ ] Dependencies: koog-core, generated proto Kotlin types,
      `shared/libs/kotlin/otel-config`, `shared/libs/kotlin/fuzzy-common`,
      `shared/libs/kotlin/mcp-server-base` (for the MCP-server side of the
      agent).

### 3. Koog graph
- [ ] Define the agent graph per design doc §Resolver agent — Koog graph.
- [ ] Implement nodes: `branchOnInput`, `detectLang+parse`,
      `extractUniversal`, `proposeDomainSpans`, `filterRelevantSpans`,
      `fuzzyMatchSpans`, `jointInference`, `decodeToken+applyChoice`,
      `decideHitlOrEmit`, `assembleResp`.
- [ ] Each node is independently testable; share state via the Koog graph's
      context, not module-level singletons.

### 4. Per-node implementation

**`detectLang+parse`** — call `tools/nlp-mcp.analyze` with full op set in
NORMAL mode. Map the response into the local `ParseState` model the
downstream nodes consume.

**`extractUniversal`** — deterministic mapping of `parse.entities` (NER) to
`UniversalEntity` records: DATE → ISO range using the question's own
timestamp as `now`; MONEY → typed amount; LOC/PERSON/ORG → pass-through with
labels.

**`proposeDomainSpans`** — walk `parse.tokens` and `parse.depHead` to
identify nominal phrases (NOUN heads with their nominal modifiers).
Deterministic; no LLM.

**`filterRelevantSpans`** — single CHEAP-tier (Haiku) LLM call. Prompt
includes the candidate spans and `Registry.entityTypes`. Returns a filtered
list of spans, each annotated with the `entityTypeRef` candidates that look
plausible. Structured output via tool-use or JSON-mode.

**`fuzzyMatchSpans`** — for each surviving span, call `fuzzy-mcp` with the
appropriate `fuzzyMatcherNamespace` from the registry. Run calls in parallel
via Kotlin coroutines. Collect `top-N` candidates per span.

**`jointInference`** — single FAST-tier (Sonnet) LLM call. Prompt includes:
parse summary, universal entities, domain candidates per span, registry
function specs (with descriptions and example questions), context (locale,
EntityContext, recent turns). Returns structured: chosen `functionId`, args
as JSON, entity bindings, confidence, alternatives, rationale.

**`decideHitlOrEmit`** — branch on `confidence >= threshold` AND
`roundCounter < 3`. Above threshold → emit Resolution. Below threshold and
rounds remaining → identify the highest-impact ambiguity (the one whose
disambiguation reduces the most uncertainty across other choices) and emit
AwaitingClarification.

**`decodeToken+applyChoice`** — verify HMAC, decode payload, apply the user's
selected option to the relevant binding, increment round counter, rejoin at
`jointInference`.

**`assembleResp`** — package outcome with parse, trace ID, elapsed ms, empty
`messages` list.

### 5. Stateless resume tokens
- [ ] HMAC-SHA256 signing with a service-internal key (rotatable; loaded
      from secret).
- [ ] Payload: original question, parse hash, per-span domain candidates,
      universal entities, the specific ambiguity asked about, round counter,
      issued-at timestamp.
- [ ] Verify HTTP body and header size headroom; resume tokens may approach
      ~5 KB. If headers are tight, place the token in the request body
      (which is the natural choice given the proto carries it as a string
      field).
- [ ] Rotation strategy: accept tokens signed with the previous key for a
      grace period.

### 6. Caching
- [ ] In-process LRU at the NLP-call level. Key: `hash(text, lang, ops)`.
- [ ] In-process LRU at the resolution level. Key:
      `hash(question, registryVersion, contextHash)`.
- [ ] Configurable sizes (default 1000 each). Metrics:
      `resolver_cache_hit_total{layer}`, `resolver_cache_size{layer}`.

### 7. Determinism
- [ ] All LLM calls (filter, joint inference) use temperature 0 and explicit
      seed where the gateway supports it. Caching keys depend on this
      determinism.

### 8. REST + MCP endpoints
- [ ] `POST /v1/resolve` — REST. Body matches `ResolveRequest`. Response
      matches `ResolveResponse`.
- [ ] MCP tool surface: a single `resolve` tool whose input is the proto
      request (with `oneof input` flattened into the JSON shape) and whose
      output is the proto response.
- [ ] Response always includes `messages: []` per Rule 6.

### 9. Resolver-level eval harness
- [ ] `agents/resolver/eval/run_eval.py` (or Kotlin equivalent if more
      natural) — runs the Stage 03 corpus through full Resolver and measures:
      function-call accuracy (exact match), entity-binding accuracy
      (precision/recall on `(span, entityType, resolvedId)` tuples),
      parse-passthrough fidelity (the `parse` field equals what `infra/nlp`
      returned standalone for the same input).
- [ ] Threshold gate in CI: drop in any of the three metrics below baseline
      fails the build. Baseline is set after the first run.

### 10. Observability
- [ ] OTEL via `shared/libs/kotlin/otel-config`'s `createOpenTelemetrySdk()`.
- [ ] One span per Koog node.
- [ ] Custom metrics: `resolver_request_total{outcome}` (RESOLUTION /
      AWAITING / ERROR), `resolver_confidence_distribution` (histogram),
      `resolver_hitl_round_count`, `resolver_function_resolved_total{functionId}`.

### 11. Tests
- [ ] Unit tests per node (mocked LLM via `llm-gateway` test client).
- [ ] Component tests with Wiremock for `nlp-mcp`, `fuzzy-mcp`, and
      `llm-gateway`. Cover happy path, missing-engine, low-confidence-then-HITL,
      resume flow, malformed registry. (Per the testing policy,
      planning-conventions.md §4: mocked unit tests only; Wiremock is the
      sanctioned mock.)
- [ ] _Deferred to the separate integration-test suite:_ all-real-services
      round-trip in K3s (one Czech + one English question per release).

### 12. Deployment
- [ ] Jib build.
- [ ] K8s manifests under `agents/resolver/k8s/{base,overlays/local}/`.
- [ ] `imagePullPolicy: Never` in local overlay.
- [ ] `just deploy-kt resolver` recipe.
- [ ] HMAC signing key sourced from a Kubernetes Secret (not committed).

### 13. Documentation
- [ ] `agents/resolver/README.md`: API surface, configuration, how to call
      from another agent, how to extend the Koog graph with new nodes.
- [ ] Prompt templates for `filterRelevantSpans` and `jointInference` live in
      `agents/resolver/src/main/resources/prompts/`. Document why each
      template is shaped the way it is — these are the single biggest lever
      on quality.
- [ ] Cross-link from `docs/v1/resolver-design.md`.

## Risks and mitigations

**First Kotlin agent in `agents/`.** Until now `agents/` has been Python-only.
Confirm directory placement with Bora before scaffolding. If preferred,
introduce `agents-kt/` as a sibling layer; do not blend Kotlin and Python in
the same parent without an explicit naming convention.

**Koog learning curve.** Lean on JetBrains' Koog documentation and the
framework-evaluation work from the Pythia conversation. Pythia and Resolver
both target Koog; cross-pollinate code patterns.

**LLM prompt engineering for `jointInference`.** This is the single biggest
quality lever. Budget time for iteration. The Stage 03 eval corpus is the
feedback signal; do not declare Stage 04 done until eval metrics meet the
threshold.

**Resume token size.** Verify HTTP body limits in the deployment path (Ktor,
ingress). At ~5 KB a token is well below most defaults but worth measuring.

**Cache poisoning across registry versions.** Resolution cache keys include
`registryVersion` (a hash provided by the caller). If a caller forgets to
bump the hash when changing the registry, cached results may be wrong.
Document loudly in the API docs; consider a debug header to bypass cache.

## Out of scope for Stage 04

- Streaming progress events from `jointInference` (deferred per design doc).
- Embedding-based function pre-selection for large registries (v1 lists all
  functions in the prompt).
- Stateful HITL backed by DB (v1 is stateless via signed tokens).
- Cross-replica cache via Redis (in-process LRU only).
- Pythia integration (Stage 06; gated by Pythia v0).
- Wrangler integration (Stage 06).
