# capabilities-mcp

Unified registry of agent + tool capabilities for the Kantheon constellation.

- **What it owns.** A single MCP + REST registry of every `Capability` in the
  constellation — both `ToolCapability` (atomic services like `theseus.query:v1`)
  and `AgentCapability` (Pythia, Golem-* Shems, Hebe, …). Themis reads this for
  routing; Pythia reads it for cross-domain plans; the in-repo forked MCP
  wrappers (theseus-mcp, ariadne-mcp, echo-mcp, kadmos-mcp, …) push themselves in
  via heartbeat.

  > **Fork note (Stage 4.1, 2026-06-17).** The old ai-platform `query-mcp` PoC
  > heartbeat is decommissioned — nothing in kantheon awaits or special-cases it.
  > ai-platform itself is untouched; its heartbeat simply stops being meaningful.
  > The forked toolset (`theseus.{query,compile}:v1`, the six `ariadne.*:v1`
  > tools, `echo.match:v1`, `kadmos.analyze:v1`) now ships as bootstrap fixtures
  > under `manifests/tools/` and is re-registered at runtime by the owning pods.
- **What it does NOT own.** Tool runtime (lives in the tool service itself).
  Agent runtime (lives in `agents/<agent>/`). Persistence — v1 is in-memory.

See also:

- [`docs/architecture/themis/architecture.md`](../../docs/architecture/themis/architecture.md) §7 — placement in the constellation.
- [`docs/architecture/themis/contracts.md`](../../docs/architecture/themis/contracts.md) §1.1 (proto), §2 (MCP + REST surface), §3 (manifest YAML), §4 (heartbeat client).
- [`docs/architecture/capabilities-mcp/design.md`](../../docs/architecture/capabilities-mcp/design.md) — rationale.

## Quickstart — local deploy

```bash
just deploy-kt capabilities-mcp                 # Jib + kubectl apply -k overlays/local
kubectl -n kantheon wait deploy/capabilities-mcp --for=condition=Available --timeout=60s
kubectl -n kantheon port-forward svc/capabilities-mcp 7501:7501 &

curl -sf http://localhost:7501/health                                  # → {"status":"ok"}
curl -sf http://localhost:7501/ready                                   # → {"status":"ready"} after fixture load
curl -sf http://localhost:7501/v1/capabilities/agents | jq '.agents[].agentId'
#   → "pythia"
#   → "golem-erp"
curl -sf http://localhost:7501/v1/capabilities | jq '.capabilities[].tool.capabilityId' | sort -u
#   → "ariadne.get_model:v1" … "echo.match:v1" … "kadmos.analyze:v1" … "theseus.query:v1"
```

## Surfaces

| Path | Purpose |
|---|---|
| `POST /mcp` | MCP Streamable HTTP — six tools (`capabilities.search`, `.list`, `.list_agents`, `.get`, `.register`, `.heartbeat`). |
| `POST /v1/capabilities/search` | REST: filter by intent_kinds / entity_types / capability_tags. |
| `GET /v1/capabilities` | REST: list all (filter by query-string `category`). |
| `GET /v1/capabilities/agents` | REST: AgentCapability-only convenience for Themis Layer 1. |
| `GET /v1/capabilities/{id}` | REST: lookup by `capability_id` or `agent_id`; returns latest version if id is unsuffixed. |
| `POST /v1/capabilities/register` | REST: idempotent register; returns stable `registrationId`. |
| `POST /v1/capabilities/{registrationId}/heartbeat` | REST: refresh `lastHeartbeatAt`; 404 with `unknown_registration_id` if missing. |
| `GET /health` | 200 once Ktor + registry are up. |
| `GET /ready` | 200 once YAML fixtures finish loading. |

Every response carries `messages: []` per ai-platform Rule 6.

## Fixtures

Source-controlled YAML manifests live at `src/main/resources/manifests/`:

```
manifests/
├── agents/
│   ├── pythia.yaml         # AgentManifest — `agent_kind: INVESTIGATOR`
│   └── golem-erp.yaml      # ShemManifest — `agent_kind: DOMAIN_QA`
└── tools/                  # bootstrap fixtures for the forked toolset (theseus x2,
                            #   ariadne x6, echo, kadmos) — runtime heartbeats supersede them
```

Adding a new fixture:

1. Drop a YAML file under `agents/<id>.yaml` or `tools/<id>.yaml` following
   the schema in [`contracts.md`](../../docs/architecture/themis/contracts.md) §3.
2. Restart the pod. The loader registers it with `last_heartbeat_at = null`
   so the TTL pruner ignores it forever.
3. Runtime registrations supersede fixtures on natural-id collision (runtime wins).

## How a service registers itself

Use the [`capabilities-client`](../../shared/libs/kotlin/capabilities-client/) library:

```kotlin
val handle = CapabilitiesClient.startupRegister(
    capability = loadOwnManifest(),
    endpoint = System.getenv("CAPABILITIES_MCP_URL")
        ?: "http://capabilities-mcp.kantheon.svc.cluster.local:7501",
    heartbeatIntervalMs = 30_000,
)
monitor.subscribe(ApplicationStopped) { handle.shutdown() }
```

The client is **warn-and-continue**: if capabilities-mcp is unreachable, the
service still starts; the background coroutine keeps retrying with exponential
backoff (1s → 60s cap).

## TTL pruning + version conventions

- TTL: configurable via `capabilities.ttl-seconds` (default 300s). Runtime
  registrations whose heartbeat is older than the TTL are flagged `pruned = true`
  and hidden from `list`/`list_agents` queries, but still retrievable via
  `get(id)` for audit.
- Fixtures (`last_heartbeat_at == null`) are exempt from pruning.
- A capability id like `model.fit.arima:v1` carries a `:vN` semver suffix.
  Lookups without a suffix return the latest version
  (`v10 > v2`, not lex order).

## Build + run

```bash
just test-kt capabilities-mcp        # unit + module-startup specs
just build-kt capabilities-mcp       # runnable Jar
just deploy-kt capabilities-mcp      # Jib image + kubectl apply -k overlays/local
```

Phase 1 v0.1.0 deployment is purely in-memory; Postgres-backed persistence is
out of scope until v1.5+.
