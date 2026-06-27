# Intent and Entity Resolver — v1 Design

Companion to [`themis-brief.md`](./themis-brief.md) (the brief — formerly `resolver.md` in ai-platform). This document captures the
load-bearing design decisions made during the brainstorming round, locks the
wire formats, and frames the implementation phasing.

Status: design locked, ready to enter Stage 01.

## Goal

Given a user question (Czech-first; CE languages broadly), return a structured
package containing the linguistic parse, the entities mentioned, and a typed
function-call binding that downstream agents can execute. Probabilistic where
needed, with a HITL escape valve for genuine ambiguity, and an inner LLM-as-arbiter
loop for resolving compound ambiguity (multiple intents × multiple entity
candidates).

The Resolver is consumed by Pythia, Wrangler (the evolved Golem), and any future
agent that needs to map natural-language questions to platform functions.

## Architecture

The capability is split across three layers, following the platform's
service-vs-MCP separation rule (logic in `services/` or `infra/`; MCP servers
are thin wrappers).

```
agents/resolver/                  ─ Kotlin · Koog
   role: orchestrator + HITL loop
   exposes: REST + MCP
   uses: tools/nlp-mcp, tools/fuzzy-mcp, infra/llm-gateway

tools/nlp-mcp/                    ─ Kotlin · Ktor (thin wrapper)
   passes through to infra/nlp

infra/nlp/                        ─ Python · FastAPI
   pluggable engine system
   evolved from Bora's NER PoC at /Users/bora/Dev/ner
   engines: stanza, spacy, nametag (UFAL HTTP), morphodita (Phase 2), langid

services/fuzzy-matcher/           ─ existing
   queried by Resolver per identified domain-entity span
```

The runtime split between Kotlin (Resolver agent) and Python (NLP service)
is deliberate: best-in-class Czech NLP libraries are Python-only (Stanza, spaCy,
UFAL MorphoDiTa/NameTag); Koog is the agent framework standard going forward.
Each piece runs in the language where it is natural. The Kotlin↔Python boundary
crosses MCP/REST/gRPC, not in-process.

The NLP service is positioned as **platform-wide infrastructure**, not as a
Resolver-specific component. Other agents will need parsing capabilities; the
service serves them too.

## Engine plugin architecture

`infra/nlp` supports multiple engines side-by-side from day one, because the
project has an explicit empirical-comparison need: licensing UFAL's
MorphoDiTa+NameTag commercially is justified only if their parse quality
materially exceeds Stanza on representative Czech data.

Engines implement a uniform Protocol:

```python
class NlpEngine(Protocol):
    name: str
    def supported_languages(self) -> set[str]: ...
    def supports(self, lang: str, op: NlpOp) -> bool: ...
    def analyze(self, text: str, lang: str, ops: set[NlpOp]) -> EngineResult: ...
```

Engines are registered via YAML config. A per-op-per-language routing table
selects which engine handles which operation in `NORMAL` mode (e.g., Czech NER
goes to NameTag, English NER stays on Stanza). In `COMPARE` mode, all engines
that support `(language, op)` run in parallel and their per-engine outputs are
returned alongside a fused result. COMPARE mode is the mechanism for empirical
quality comparison.

The op set in v1: `TOKENIZE`, `SENTENCE_SPLIT`, `LEMMATIZE`, `POS_TAG`,
`DEP_PARSE`, `NER`, `DETECT_LANGUAGE`. Stanza, spaCy, and NameTag are wired in
v1; MorphoDiTa via UFAL HTTP API joins in Stage 03 with a path to local
embedding once a license is in place.

The PoC's `DictIndex` (in-process known-term matching) is not carried into
`infra/nlp`. That responsibility belongs to `services/fuzzy-matcher`. Resolver
calls fuzzy-mcp directly per identified span.

## Wire formats

Both protos live in `shared/proto/cz/dfpartner/<service>/v1/`. Both carry the
platform-standard `repeated ResponseMessage messages = 99;` for application-layer
outcomes (per wire-format Rule 6).

### NLP proto (`cz.dfpartner.nlp.v1`)

The canonical token-level container is `Token`, holding text, char offsets,
lemma, UD POS, language-specific xpos, morphological features map, and
dependency parent/relation. Embedding dependencies on Token (rather than as an
edge list) reflects the fact that UD parses are trees of parent pointers — one
head per token.

```proto
message AnalyzeRequest {
  string text = 1;
  string language = 2;                  // empty triggers DETECT_LANGUAGE
  repeated NlpOp ops = 3;
  Mode mode = 4;                        // NORMAL or COMPARE
  map<string, string> engineHints = 5;  // op-name → engine override
}

message AnalyzeResponse {
  string language = 1;
  double languageConfidence = 2;
  string engineUsed = 3;                // primary engine in NORMAL
  repeated Token tokens = 4;
  repeated Span sentences = 5;
  repeated Span paragraphs = 6;
  repeated NerEntity entities = 7;
  map<string, EngineResult> byEngine = 8;  // populated only in COMPARE
  string traceId = 9;
  int64 elapsedMs = 10;
  repeated ResponseMessage messages = 99;
}
```

In COMPARE mode, the top-level `tokens` reflects the primary engine; per-engine
tokenizations live in `byEngine[name].tokens`. Different engines tokenize
differently, and the proto does not pretend otherwise.

### Resolver proto (`cz.dfpartner.resolver.v1`)

The Resolver exposes a single RPC. Fresh questions and HITL continuations both
flow through `Resolve()`, distinguished by a `oneof input { FreshQuestion fresh
| ResumeAnswer resume }`. Same endpoint, same caching/tracing/auth path, no
separate "resume" RPC.

```proto
message ResolveRequest {
  string conversationId = 1;
  oneof input {
    FreshQuestion fresh = 2;
    ResumeAnswer resume = 3;
  }
  Registry registry = 4;
  ResolveContext context = 5;
}

message ResolveResponse {
  cz.dfpartner.nlp.v1.AnalyzeResponse parse = 1;  // ALWAYS present
  oneof outcome {
    Resolution resolution = 2;
    AwaitingClarification awaiting = 3;
  }
  string traceId = 4;
  int64 elapsedMs = 5;
  repeated ResponseMessage messages = 99;
}
```

Three further design points worth pinning:

The `parse` field is always present, even when `outcome` is
`AwaitingClarification`. This lets the caller's UI highlight the ambiguous span
in the original sentence using `parse.tokens` plus `awaiting.contextSpan`.

`EntityBinding.binding` is a `oneof { UniversalEntity | DomainEntity }`.
Universal entities (PERSON, LOCATION, DATE, ORG, MONEY, MISC) come from the
NER engines and carry a `normalizedValue` (e.g., ISO date range for "last
week") plus the raw engine label and source engine name. Domain entities
(customer, product, cost-center) come from fuzzy-mcp lookups against the
caller's registry namespaces and carry `resolvedId`, `resolvedLabel`, and a
ranked list of alternatives.

Function-call args are emitted as a JSON string (`string argsJson`), not as
`google.protobuf.Struct`. The caller validates against its own Registry's
`ParamSpec` schema; typing the args proto-side would duplicate validation. The
JSON-string approach is pragmatic across JVM/Python/TS bindings (per
wire-format Rule 7). Documented expectation: camelCase keys.

The `Registry` is passed in on every call; there is no platform-wide
function-catalog service. Each consumer (Pythia, Wrangler, future agents)
publishes its own registry per call. Resolver remains stateless with respect to
caller domain.

## Resolver agent — Koog graph

```
START
  │
  ├── fresh ──► detectLang+parse ─► extractUniversal ─► proposeDomainSpans
  │                                                            │
  │                                                            ▼
  │                                                    filterRelevantSpans
  │                                                            │
  │                                                            ▼
  │                                                     fuzzyMatchSpans
  │                                                            │
  └── resume ──► decodeToken+applyChoice ─────────────────────┐│
                                                              ▼▼
                                                       jointInference
                                                              │
                                                              ▼
                                                      decideHitlOrEmit
                                                          │       │
                                                          ▼       ▼
                                                   RESOLUTION   AWAITING
                                                          │       │
                                                          └───┬───┘
                                                              ▼
                                                        assembleResp
                                                              │
                                                              ▼
                                                            END
```

Per-node responsibilities are summarised below.

`detectLang+parse` makes one call to `tools/nlp-mcp` requesting the full op set
in NORMAL mode. The resulting `AnalyzeResponse` flows through the rest of the
graph and is included in every `ResolveResponse`.

`extractUniversal` reads `parse.entities` and normalises universal types into
typed values: DATE labels become ISO ranges, MONEY becomes typed amounts, etc.
This step is deterministic; no LLM is involved.

`proposeDomainSpans` walks the parse tree (POS + DEP) to identify nominal
phrases that could be domain entities. Deterministic and cheap.

`filterRelevantSpans` is a CHEAP-tier LLM call that cross-references candidate
spans against the caller's `Registry.entityTypes` and emits only spans likely
to refer to known domain entity types. This narrows the search before the
expensive fuzzy lookups.

`fuzzyMatchSpans` calls `tools/fuzzy-mcp` in parallel for each surviving span,
scoped to the appropriate namespace from `EntityTypeSpec.fuzzyMatcherNamespace`.
Each call returns a ranked list of candidates with scores.

`jointInference` is the heart of the agent. It is a single FAST-tier LLM call
(Sonnet-class) that receives parse, universal entities, domain candidates per
span, the caller's registry of functions, and any contextual state, and emits a
structured response containing a chosen `(functionId, argsJson, bindings,
confidence, alternatives, rationale)`. The temptation to break this into a
chain of smaller LLM calls (pick function → bind entities → coerce args) is
resisted: a single prompt with full context lets the LLM reason about
coherence ("Shell-as-customer ↔ sales-order is consistent because the verb is
*prodává*"). Faster, cheaper, more accurate.

`decideHitlOrEmit` branches on `confidence` against a configured threshold and
on the round counter (capped at 3). Above threshold → emit `Resolution`; below
threshold and rounds remaining → emit `AwaitingClarification` with structured
options for the caller to surface. After 3 rounds without confidence, return a
best-effort Resolution flagged with the low-confidence rationale.

`decodeToken+applyChoice` is the resume entry point. The `resumeToken` is a
signed (HMAC) JSON blob containing prior state — original question, parse hash,
per-span candidates, universal entities, the specific ambiguity asked about,
and the round counter. The user's `selectedOptionId` (or `freeTextAnswer`) is
applied to reduce uncertainty, and execution rejoins the graph at
`jointInference`. **No DB, no Redis, no session storage.** Token size is
2–5 KB, well within HTTP-header and request-body limits.

`assembleResp` packages the outcome with the parse and trace metadata.

## HITL contract

When `jointInference` produces below-threshold confidence and rounds remain,
Resolver emits `AwaitingClarification` containing a localised question, a
structured list of options, an opaque resume token, the contextual span being
asked about (so the caller can highlight in UI), and any partial bindings that
were already resolved.

The caller (Wrangler, Pythia, chat-direct) is responsible for surfacing the
question to the user, gathering the answer, and resuming via `Resolve()` with a
`ResumeAnswer`. The contract mirrors Pythia's existing AWAITING_* state pattern.

If multiple ambiguities exist in a single question, Resolver asks one at a
time — picking the ambiguity whose disambiguation has the highest impact on
remaining uncertainty. Batching everything into a multi-question form is
deliberately avoided as poor UX.

## Caching

Three layers, in increasing scope and decreasing hit rate:

| Layer | Key | TTL | Where |
|---|---|---|---|
| Per-node memoization | `(node, deterministic_inputs)` | per-request | in-process |
| Cross-request resolution | `hash(question, registryVersion, contextHash)` | 1h | in-process LRU |
| Engine-level (NLP service) | `hash(text, lang, ops)` | 24h | in-process LRU |

V1 uses in-process LRU caches only. Redis is deferred to v1.1 if multi-replica
deployments make in-process insufficient. Cache keys are deterministic strings
(no LLM-side temperature variance, since `jointInference` runs at temperature 0
for reproducibility).

## Eval corpus — incremental growth strategy

The eval corpus is built incrementally across the lifetime of the platform,
not as a single up-front engineering effort. Stages 03 seeds from existing
material (questions in `docs/v1/ai-ag-vs-erp/`, observable Golem logs, hand-
crafted examples covering known ambiguities — target ~50 questions). The corpus
then grows in two channels:

- **Diff-driven growth (Stage 05).** When Resolver runs alongside Golem's
  in-process logic in parallel deployment, every disagreement is logged as a
  structured event. Each disagreement is a candidate test case: either
  Resolver was wrong (becomes a regression test once fixed) or Golem was wrong
  (becomes a positive test once Golem's behavior is corrected). The corpus
  doubles or triples organically during burn-in.

- **User-driven growth (post Stage 06).** As more users come onto the platform,
  their real questions and the resolved interpretations are sampled and curated
  into new test fixtures. This includes both "Resolver got it right" cases
  (worth pinning) and edge cases that needed clarification (worth understanding).

The format is JSONL, one question per line, with the expected parse, expected
entity bindings (universal + domain), and the expected `(functionId, args)`:

```json
{
  "question": "Které faktury Shell ještě neuhradil?",
  "lang": "cs",
  "expected": {
    "tokens": [...],
    "lemmas": [...],
    "entities": [
      {"span":[3,4],"type":"customer","resolvedLabel":"Shell UK PLC"}
    ],
    "functionId": "listUnpaidInvoices",
    "args": {"customerId":"<placeholder>"}
  }
}
```

The eval harness lives in `infra/nlp/eval/` for parse-quality measurement and
in `agents/resolver/eval/` for end-to-end resolution quality. Both run in CI
nightly against the current corpus.

## Routing layer (Phase 3 — kantheon arc)

> Folded in 2026-06-21 (Stage 3.6). This document's body above is the original
> pre-fork Resolver v1 design (resolution = question → ERP function call). The
> kantheon arc adds a **routing layer** on top: once a question is understood,
> Themis decides *which agent* should answer it. Authoritative shapes live in
> [`docs/architecture/themis/contracts.md`](../../architecture/themis/contracts.md)
> §1.2 (read through its ⚠ shipped-proto reconciliation note) and the cascade in
> [`architecture.md`](../../architecture/themis/architecture.md) §6.2.

The routing layer is four Koog nodes added to the graph (all additive — the
resolution path above is unchanged):

1. **`detectMultiQuestion`** (before `extractUniversal`) — a deterministic
   UD-dependency-graph check with a strong single-question bias. On a genuine
   SPLIT (≥2 disjoint clause topics, no anaphora) it emits
   `AwaitingClarification.MultiQuestionDetected(decomposition=SPLIT)` and Iris
   decomposes into N turns. Relating intents (compare / correlate / explain-by)
   stay `KEEP_TOGETHER` and route as one cross-domain turn (PD-13).
2. **`classifyIntentKind`** (after `extractUniversal`) — rules-first
   (`prompts/intent_kind_rules.yaml`, matched on lemmas), CHEAP-tier LLM
   tie-break only when rules tie. Writes `Resolution.intent_kind`
   (PROCEDURAL / RCA / FORECAST / SIMULATION).
3. **`routeToAgent`** — the **four-layer cascade**, producing a
   `RoutingDecision` (skipped entirely for `profile == INVESTIGATION_DEEP`):
   - **Layer 0** — honour an explicit `routing_hint` (confidence 1.0).
   - **Layer 1** — deterministic rule scoring over agent manifests
     (`+0.5/+0.4/+0.3/+0.2`) read from capabilities-mcp; the routing-view first
     drops `non_routable` agents and any the caller's bearer roles don't entitle.
   - **Layer 2** — CHEAP-tier LLM over the top-5 candidates' router descriptions.
   - **Layer 3** — `needs_user_pick` with the top-3 alternates, rendered by Iris
     as `RoutingPickChip`s; a chip pick re-issues with a `routing_hint` → Layer 0.
4. **Profile + `RefusalWithGaps`** — `Profile` (CHAT_QUICK vs INVESTIGATION_DEEP)
   changes traversal (fuzzy candidate count, HITL rounds, routeToAgent skip). In
   STRICT HITL mode an unrecoverable blocker terminates with `RefusalWithGaps`
   (typed `GapKind`, incl. `NO_ENTITLED_AGENT` — reveal existence, deny access).

**Eval + observability.** The routing corpus
(`eval/corpus/routing-seed.jsonl`, buckets per intent + entitlement +
KEEP_TOGETHER) is scored by `eval/run_routing_eval.py` against
`eval/thresholds.yaml` (routing accuracy, Layer-1 hit-rate ≥ 60 %, intent-kind
accuracy); the live gate runs in the nightly `themis-routing` integration
context (PR CI runs the harness self-test). Seven routing metrics
(`themis_routing_*`, `themis_intent_kind_*`, `themis_multi_question_detected_total`,
`themis_refusal_total`) feed the Grafana dashboard at
`observability/dashboards/themis-routing.json`.

## Phasing

Six stages, each tracked in its own `tasks-resolver-stage-NN-*.md` document at
the project root during work. After each stage closes, the file is hand-moved
to `docs/history/v1/`.

| # | Stage | Goal |
|---|---|---|
| 01 | `infra/nlp` foundation | Clone PoC, add POS/DEP/lang-detect, JSON API, NlpEngine plugin contract, NORMAL mode |
| 02 | `nlp-mcp` thin wrapper | Kotlin/Ktor MCP wrapper, agents can call NLP via MCP |
| 03 | Eval corpus v0 + COMPARE + MorphoDiTa | Seed ~50 Czech questions, COMPARE mode, MorphoDiTa via UFAL API, eval harness |
| 04 | `agents/resolver` (Koog) | Resolver proto, full Koog graph, stateless resume, eval gate |
| 05 | Parallel deployment alongside Golem | Run Resolver in shadow; diff harness; corpus grows from disagreements |
| 06 | Consumer migration | Wrangler primary; Pythia integration when v0 ready; remove Golem in-process logic |

Stages 03 and 04 run in parallel after Stage 02 ships.

## Cross-cutting concerns

**OTEL plumbing.** A single `Resolve()` call spans seven or more service hops
(caller → resolver-mcp → resolver agent → nlp-mcp → infra/nlp → fuzzy-mcp →
fuzzy-matcher → llm-gateway). Trace context propagation must be tested
explicitly in Stage 02 — not bolted on after the fact. Use the existing
`shared/libs/kotlin/otel-config` and `shared/libs/python/otel-config`.

**Localization.** `AwaitingClarification.question` is emitted in the user's
locale, derived from `ResolveContext.locale`. Resolver maintains a small bundle
of clarification templates per language (Czech, English in v1). Free-form text
in `ClarificationOption.label` reflects entity labels as stored in the source
of truth (not translated).

**Authentication.** Caller auth context propagates through the call chain via
the existing platform mechanisms (gRPC metadata / Ktor request headers).
Resolver does not own auth; it forwards what it receives.

## Deferred / future work

The following are explicitly out of scope for v1 and parked for a future
revision:

- NATS-based async invocation of `Resolve()` (v1 is HTTP/gRPC only; per Pythia
  conversation, NATS is deferred to v1.1).
- Cross-replica caching via Redis (v1 in-process only).
- Streaming progress events from `jointInference` (v1 is single-response).
- Embedding-based function pre-selection for large registries (v1 lists all
  functions in the prompt; will not scale beyond ~50 functions per registry).
- Local-licensed MorphoDiTa+NameTag (v1 calls UFAL HTTP API; license decision
  follows Stage 03 quality data).
- Stateful HITL with conversation persistence in DB (v1 is stateless via signed
  resume tokens).

## References

- Brief: [`themis-brief.md`](./themis-brief.md)
- Pythia design: `/Users/bora/Dev/pythia/docs/pythia/` (separate project)
- NLP PoC: `/Users/bora/Dev/ner` (cloned as snapshot into `infra/nlp/` in
  Stage 01; original retained as historical reference)
- Wire-format principles: see ai-platform memory note
