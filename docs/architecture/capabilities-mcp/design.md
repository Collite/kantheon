# capabilities-mcp — Design Companion

> **Companion to** [`tools/capabilities-mcp/README.md`](../../../tools/capabilities-mcp/README.md), [`docs/architecture/themis/architecture.md`](../themis/architecture.md) §7, [`docs/architecture/themis/contracts.md`](../themis/contracts.md) §1.1 / §2 / §3.

This page captures *rationale* — the **why** behind capabilities-mcp's design choices. The **what** lives in the README + contracts.

## 1. One registry for tools AND agents

Earlier drafts proposed two services: `tool-registry` (atomic services) and `agent-registry` (Pythia, Golems). We collapsed to one, with a `Capability` sealed union (`kind: TOOL | AGENT`).

**Why.**

- Both populations need the same operations: register, heartbeat, search by intent / entity / tag, lookup by id.
- Themis routing reads agents; Pythia planning reads both. Two surfaces = two clients in every consumer. One surface = one `CapabilitiesReadClient`.
- The discriminator (`agent_kind`) inside `AgentCapability` already partitions agents into Investigator / Domain-QA / Personal-Assistant. A separate proto package would have duplicated the cross-cutting fields (`service_endpoint`, `health_check_path`, `typical_latency_ms`, …).

## 2. Push-from-tools heartbeat (not registry-pull)

Capability owners (the tool's own service) heartbeat into capabilities-mcp at startup + every 30s. The registry is a **passive consumer of pushed truth**.

**Why not the inverse.**

- Pull semantics would force the registry to know every tool's endpoint a priori — which is exactly what we're trying to discover.
- Pull semantics would also distribute liveness detection across the registry, doubling the failure surface (registry can't reach tool → wrong "down" verdict).
- Push lets each tool's own deployment lifecycle dictate its registry presence. No central choreography.

The trade-off is the warn-and-continue rule (§4) — a tool whose heartbeat is broken still considers itself alive locally. Acceptable: Themis's routing layer treats stale entries as TTL-pruned and won't route to them.

## 3. Source-controlled fixtures + runtime registrations

The registry has two populations:

- **Fixtures**: YAML under `tools/capabilities-mcp/src/main/resources/manifests/`. Loaded at boot. `last_heartbeat_at == null`, exempt from TTL pruning. Bora controls them in git.
- **Runtime registrations**: pushed by services at startup. TTL-pruned if heartbeat stops.

On natural-id collision (`capability_id` for tools, `agent_id` for agents) **runtime wins**. Production beats the source-controlled placeholder.

**Why both.**

- Agents (Pythia, Golems) are large LLM payloads — manifest fields like `description_for_router`, `example_questions`, `counter_examples` need careful Bora-authored content. Source control gives review + diff + history. Heartbeat alone would mean those payloads live only in the agent's own resource bundle, never reviewed alongside platform changes.
- Tools (`query.named:v1`, `model.fit.arima:v1`) belong in the tool's own repo + deployment. Their manifest content (`service_endpoint`, `cost_hints`) follows the deployment lifecycle naturally — fixtures here would invite drift.
- "Runtime wins" lets a Golem-* heartbeat patch its own `service_endpoint` if it gets relocated, without a fixture re-deploy.

## 4. Warn-and-continue on unreachable kantheon

If capabilities-mcp is unreachable at startup, the `CapabilitiesClient.startupRegister` call returns immediately with `registrationId = null` and `lastHeartbeatStatus = NEVER_REGISTERED`. The service starts anyway; a background coroutine keeps retrying with exponential backoff (1s, 2s, 4s, … cap 60s).

**Why.**

- kantheon outage must not cascade into ai-platform outage. The blast radius of "wrong routing decisions for a few minutes" is smaller than "all of ai-platform offline because the agent registry hiccuped".
- Themis itself fail-fasts at boot if `list_agents()` is empty (§4.4 of contracts) — that's a deliberate exception, because Themis with zero agents has no routing function. Everyone else degrades gracefully.

## 5. In-memory at v1

`ConcurrentHashMap<String, RegistryEntry>` keyed by natural id. No persistence at v1.

**Why.**

- All authoritative content is either source-controlled (fixtures, reload from disk on restart) or push-replicated (runtime registrations re-heartbeat on a 30s tick).
- The registry's blast radius if lost on restart is one heartbeat cycle (~30s) of TTL-fresh entries. Source-controlled fixtures repopulate instantly via the loader.
- Postgres-backed persistence and audit history land at v1.5+ once cross-agent patterns (Pythia checkpointer, Golem turn log) demand it.

## 6. Why a separate `RegistryQueryService` between handlers and the registry

`InMemoryRegistry` exposes raw CRUD. `RegistryQueryService` layers the wire-side semantics: filter handling, intent-kind narrowing, capability-tag glob matching, heartbeat outcome typing.

**Why the indirection.**

- Two surfaces (MCP, REST) need the same domain logic. Sharing a service ensures `capabilities.search` over MCP and `POST /v1/capabilities/search` over REST give identical answers for identical inputs.
- Filter semantics (default-on `includeTools` / `includeAgents`, default-off `includePruned`) live in one place rather than duplicated.
- Future surfaces (gRPC, native MCP-over-stdio, batch tooling) drop in by reusing the service.

## 7. Stage 1.4 deferrals

The Phase 1 deliverable is a live local-K3s deployment plus an ai-platform `query-mcp` PoC heartbeat. The following land later:

- **Live K3s deploy + smoke** (T4): deferred because the local cluster wasn't available in the implementation session. Manifests validated via `kubectl kustomize` (idempotent rendering, imagePullPolicy patched, TELEMETRY_ENABLED=false for local), and the in-process startup path is covered by `ModuleStartupSpec` (asserts 503 → 200 on fixture-load completion and the two seed agents resolve).
- **ai-platform `query-mcp` PoC** (T5): cross-repo PR can be authored when T4 is unblocked. Code path is exercised end-to-end by `capabilities-client`'s Wiremock specs (`CapabilitiesClientSpec`).
- **Cross-repo OTel trace verification** (T6): blocked by Tempo/Alloy not running locally per Phase 1 pre-flight. Documented as deferred-with-evidence rather than blocking the phase tag.

`capabilities-mcp/v0.1.0` ships on the basis of green test suite + structurally complete deployment artefacts. Bora can flip the K3s deploy as a separate operation when the cluster is reachable.
