# Themis

Themis is the question-understanding agent in the [Kantheon](../../README.md) constellation. It resolves Czech/English natural-language ERP questions into structured function calls, using a Koog [`AIAgentGraphStrategy`](src/main/kotlin/org/tatrman/kantheon/themis/koog/ThemisGraph.kt) that chains nine deterministic + LLM-backed nodes.

**Phase 2 done — `themis/v0.1.0` (2026-06-20).** Stage 2.3 closed 2026-05-30 with the Koog runtime cutover (PRs #3–#8). Stage 2.4 closed **by relocation**: the fork switch-over (fork Stage 2.6) moved Themis onto the in-repo forked stack (Kadmos/Echo/Prometheus) and relocated the corpus eval no-regression gate into the integration track (the `themis-routing` nightly context, testing Stage 3.1). The original parity-vs-ai-platform-Resolver gate is superseded — see the historical [`eval/STAGE-2.4-DEFERRAL.md`](eval/STAGE-2.4-DEFERRAL.md). Deploy locally with `just deploy-kt themis` (also in `just deploy-fork`). Next: **Phase 3** — the routing layer (`themis/v0.2.0`).

## What it does

1. **`detectLangAndParse`** — state marker. The NLP call (`NlpClient.analyze`) lives in `Main.kt`'s context-builder, not in the graph.
2. **`extractUniversal`** — normalises NER labels (PER / LOC / ORG / DATE / MONEY) to the universal type enum.
3. **`proposeDomainSpans`** — emits one span per NOUN/PROPN head.
4. **`filterRelevantSpans`** — CHEAP-tier LLM call ("haiku") that filters spans against the caller's `EntityTypeSpec` registry.
5. **`fuzzyMatchSpans`** — parallel fuzzy lookups per filtered span via the fuzzy-matching service.
6. **`jointInference`** — FAST-tier LLM call ("sonnet") that selects a function and binds entity arguments.
7. **`decideHitlOrEmit`** — if confidence ≥ threshold OR round-counter ≥ max-rounds, emit `Resolution`; otherwise emit `AwaitingClarification` with an HMAC resume token.
8. **`decodeTokenAndApplyChoice`** — verifies the resume token on round 2+ and re-enters the graph from `extractUniversal`.
9. **`entitiesOnlyAssemble`** — alternative terminal for `RESOLVE_MODE_ENTITIES_ONLY` (skips function-id inference; returns bindings only).

Conditional edges in [`buildThemisGraph`](src/main/kotlin/org/tatrman/kantheon/themis/koog/ThemisGraph.kt) route the `resumeToken != null` and `mode == ENTITIES_ONLY` branches. All node bodies are pure / suspend step functions under [`koog/nodes/`](src/main/kotlin/org/tatrman/kantheon/themis/koog/nodes/) — testable without Koog runtime.

## API surface

### MCP tool — `resolve`

Streamable HTTP MCP at `POST /mcp/v1/tools/resolve`.

| Field | Type | Required | Description |
|---|---|---|---|
| `question` | string | yes | Natural-language user question |
| `conversation_id` | string | no | Trace correlation ID |
| `locale` | string | no | Language code (default `cs`) |
| `resume_token` | string | no | Opaque token from a previous HITL response |

Response is a JSON string in `content[0].text`:

```json
// Resolved:
{"outcome":"resolved","function_id":"listInvoices","confidence":0.92,"rationale":"...","args_json":"{...}","trace_id":"...","elapsed_ms":45}

// Awaiting clarification:
{"outcome":"awaiting_clarification","question":"Which interpretation?","options":[...],"resume_token":"<opaque>"}
```

### REST endpoint — `POST /v1/resolve`

Accepts and returns Protobuf messages (`org.tatrman.kantheon.themis.v1.ResolveRequest` / `ResolveResponse`).

Fresh input:
```protobuf
ResolveRequest {
  fresh { text: "Zobraz faktury pro Novák s.r.o." locale: "cs" }
  conversation_id: "conv-123"
  registry { ... }   // entity types + function specs
}
```

Resume input:
```protobuf
ResolveRequest {
  resume { token: "<opaque resume token>" }
  conversation_id: "conv-123"
}
```

#### `ResolveMode.ENTITIES_ONLY`

When `mode = RESOLVE_MODE_ENTITIES_ONLY`, the graph terminates at `entitiesOnlyAssemble` and skips `jointInference` / `decideHitlOrEmit`. `Registry.function_specs` is ignored; `entity_types` still scopes fuzzy matching. `Resolution.function_id` is empty, `args_json` is `"{}"`, and `Resolution.bindings` carries the resolved entities. Inner entity HITL (ambiguous fuzzy candidates) still fires — the response becomes `AwaitingClarification`. Used by callers (Golem) that own their own intent classification.

### Health

| Path | Returns |
|---|---|
| `GET /health` | `{"status":"ok"}` (process up) |
| `GET /ready` | `{"status":"ready"}` (configured + dependencies reachable) |

## Configuration

Source: [`src/main/resources/application.conf`](src/main/resources/application.conf). HOCON `${?VAR}` substitutions let K8s overlays retarget without rebuilding the image.

| HOCON key | Default | Env override | Notes |
|---|---|---|---|
| `server.port` | `7901` | — | |
| `themis.nlp.host` | `nlp-service` | `NLP_MCP_HOST` | ai-platform `tools/nlp-mcp` |
| `themis.nlp.port` | `8000` | `NLP_MCP_PORT` | |
| `themis.nlp.timeout-ms` | `30000` | `NLP_MCP_TIMEOUT_MS` | |
| `themis.fuzzy.host` | `fuzzy-mcp` | `FUZZY_MCP_HOST` | ai-platform `tools/fuzzy-mcp` |
| `themis.fuzzy.port` | `7143` | `FUZZY_MCP_PORT` | |
| `themis.fuzzy.timeout-ms` | `15000` | `FUZZY_MCP_TIMEOUT_MS` | |
| `themis.llm-gateway.host` | `llm-gateway` | `LLM_GATEWAY_HOST` | ai-platform `infra/llm-gateway` |
| `themis.llm-gateway.port` | `8090` | `LLM_GATEWAY_PORT` | |
| `themis.llm-gateway.timeout-ms` | `60000` | `LLM_GATEWAY_TIMEOUT_MS` | |
| `themis.hmac.secret-key` | (dev fallback) | `HMAC_SECRET_KEY` | required in prod |
| `themis.hitl.confidence-threshold` | `0.75` | — | |
| `themis.hitl.max-rounds` | `3` | — | |

## Build, deploy, smoke

```bash
# Build the Jib image into the local Docker daemon
./gradlew :agents:themis:jibDockerBuild

# Apply the local overlay (creates kantheon namespace + Deployment + Service)
kubectl apply -k agents/themis/k8s/overlays/local

# Wait for ready
kubectl -n kantheon wait deployment/themis-mcp --for=condition=Available --timeout=120s

# Port-forward and smoke
kubectl -n kantheon port-forward svc/themis-mcp 7901:7901 &
curl -sf http://localhost:7901/health
curl -sf http://localhost:7901/ready
```

## Running the eval

Two harnesses — full index in [`eval/README.md`](eval/README.md). The
resolution-quality corpus (50 NORMAL + 12 ENTITIES_ONLY) lives in
[`eval/corpus/`](eval/corpus/), driven by [`eval/run_eval.py`](eval/run_eval.py)
(carried over from ai-platform's Resolver harness with the Themis port/path swap).
The **routing** corpus (Phase 3 Stage 3.5) is driven by
[`eval/run_routing_eval.py`](eval/run_routing_eval.py).

```bash
# Resolution-quality, against a locally-running themis-mcp on port 7901
python3 agents/themis/eval/run_eval.py --host localhost --port 7901 --verbose

# Routing (intent-kind + chosen-agent + layer-hit), against a deployed Themis:
just eval-themis-routing
# Routing harness logic only — no cluster, no LLM:
just eval-themis-routing-selftest
```

**Eval prerequisites** (post-fork — the corpus eval now runs in the `themis-routing` nightly integration context, testing Stage 3.1):
- `kadmos.kantheon` reachable (NLP — `/v1/analyze`, port 7270)
- `echo.kantheon` reachable (fuzzy — `/match`, port 7265)
- `prometheus.kantheon` reachable (LLM gateway, port 7280, real API key configured)

Without those services the call chain bottoms out at the NLP step (`Failed to call NLP service`); the harness reports all 50 questions as errors. (The `ai-platform` nlp/fuzzy/llm-gateway endpoints were the pre-fork targets — replaced by the forked stack in fork Stage 2.6.)

## Tests

```bash
./gradlew :agents:themis:test
```

74 tests across 8 specs as of T6 (PR #8):
- 4 carry-over Resolver integration specs (`ResolverGraphNodeTest`, `ResolverEntitiesOnlySpec`, `ResolverIntegrationTest`, `ResolverModeProjectionSpec`) — exercise the `ThemisGraphDispatch` test fixture which preserves the legacy dispatch loop.
- 4 focused unit specs in [`src/test/.../koog/nodes/`](src/test/kotlin/org/tatrman/kantheon/themis/koog/nodes/) — cover the pure step functions directly.

## Routing layer (Phase 3)

Themis answers **which agent** should handle a turn, on top of resolving *what*
it asks. Four additive Koog nodes: `detectMultiQuestion` → `classifyIntentKind`
→ `routeToAgent` (the four-layer cascade: 0 hint / 1 rule-scoring over agent
manifests / 2 CHEAP-LLM / 3 user-pick chips) → `Profile`/`RefusalWithGaps`. The
`RoutingDecision` rides `Resolution.routing` on REST `/v1/resolve`
(`profile=CHAT_QUICK`); the reduced MCP tool surface disables routing. See
`docs/design/themis/themis-design.md` → *Routing layer* and architecture §6.2.

## Observability

Seven Phase-3 routing metrics in [`ResolverOtel`](src/main/kotlin/org/tatrman/kantheon/themis/ResolverOtel.kt)
— `themis_routing_layer_total` / `_chosen_total` / `themis_routing_confidence`,
`themis_intent_kind_total` / `_llm_fallback_total`,
`themis_multi_question_detected_total`, `themis_refusal_total` — feed the Grafana
dashboard at [`observability/dashboards/themis-routing.json`](observability/dashboards/themis-routing.json)
(per-layer hit-rate, agent distribution, confidence quantiles, intent-kind
distribution, refusal breakdown). The two `themis_capabilities_cache_*` metrics
(architecture §10.2) await a `CapabilitiesReadClient` shared-lib hook.

## Related docs

- Architecture: [`docs/architecture/themis/architecture.md`](../../docs/architecture/themis/architecture.md) §6 (graph) + §10 (observability).
- Contracts: [`docs/architecture/themis/contracts.md`](../../docs/architecture/themis/contracts.md) — proto and HTTP shapes.
- Design: [`docs/design/themis/themis-design.md`](../../docs/design/themis/themis-design.md) — incl. the routing-layer fold-in.
- Eval harnesses: [`eval/README.md`](eval/README.md) — resolution-quality + routing harnesses, thresholds, gate relocation.
- Stage 3.5 (eval harness + gate) plan: [`docs/implementation/v1/themis/tasks-p3-s3.5-eval-ci.md`](../../docs/implementation/v1/themis/tasks-p3-s3.5-eval-ci.md).
- Stage 3.6 (observability + cutover) plan: [`docs/implementation/v1/themis/tasks-p3-s3.6-iris-cutover.md`](../../docs/implementation/v1/themis/tasks-p3-s3.6-iris-cutover.md).
- Stage 2.3 (Koog migration) plan: [`docs/implementation/v1/themis/tasks-p2-s2.3-koog-migration.md`](../../docs/implementation/v1/themis/tasks-p2-s2.3-koog-migration.md).
- Stage 2.4 (eval gate) plan: [`docs/implementation/v1/themis/tasks-p2-s2.4-deploy-eval.md`](../../docs/implementation/v1/themis/tasks-p2-s2.4-deploy-eval.md).
- Stage 2.4 deferral note: [`eval/STAGE-2.4-DEFERRAL.md`](eval/STAGE-2.4-DEFERRAL.md).

## Out of scope (still ahead)

- **Iris BFF chip round-trip** — the `/chat/turn`+`/chat/pick` flow is implemented in the real `agents/iris-bff` (Iris arc), not as a throwaway Themis stub; Stage 3.6's T2/T3 are superseded by it.
- **`themis/v0.2.0` tag** — left to Bora to cut once the arc is signed off (corpus fill + nightly green).
- **Multi-instance HA** — single replica in v1; HMAC resume tokens make Themis stateless, so horizontal scaling is a deployment-time concern.
