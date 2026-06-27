# charon-mcp — Charon MCP wrapper

> **Status:** `charon-mcp/v0.1.0` — thin MCP wrapper over `org.tatrman.charon.v1.CharonService`. Streamable-HTTP `POST /mcp` on **:7252**; five `move.*` tools; `move.*:v1` ToolCapability manifests heartbeated into capabilities-mcp.
>
> **forked-from:** none — mirrors `tools/metis-mcp` structure (the gRPC-backed MCP-wrapper template).

## What it is

A **zero-logic** pass-through (review-enforced): validate JSON → proto, **one** gRPC call to `CharonService`, proto → JSON. No move logic, no endpoint knowledge — all of that lives in `services/charon`. Pythia calls Charon over **gRPC directly**; the MCP wrapper exists so the `move.*` capabilities are **discoverable in capabilities-mcp** (Pythia's planner searches it for DataFrame-composition options) and so non-Pythia MCP callers can drive moves.

## Tools (contracts §3)

| Tool | → gRPC | Notes |
|---|---|---|
| `move.materialize` | `Materialize` | target = seaweed \| redis \| db_table |
| `move.stage` | `Stage` | target = worker_df (the only way into a worker session) |
| `move.copy` | `Copy` | any legal pair (the generic verb) |
| `move.evict` | `Evict` | blob / redis / worker_df (db_table rejected) |
| `move.describe` | `Describe` | PD-5 liveness: exists + schema fingerprint |

**Locations ride as structured JSON**, never stringified (Rule 7 spirit): each location is a `{ "kind": "seaweed|redis|worker_df|db_table", … }` object. Examples:
- `{"kind":"seaweed","bucket":"pythia-evidence","key":"inv1/h1.arrow","retentionTag":"production"}`
- `{"kind":"worker_df","workerKind":"METIS","sessionId":"s1","dfName":"df1"}`
- `{"kind":"db_table","connectionId":"erp-replica","schema":"dbo","table":"orders"}`

`MoveOptions` is an `options` object: `{"dbWriteMode":"CREATE","expectedSchemaFingerprint":"…","maxBytes":…,"chunkRows":…}`.

The Rule-6 `messages` channel passes through on both the success path (`MoveResult.messages`) and the error path (charon attaches them to the `charon-response-messages-bin` gRPC trailer; the wrapper reads it into the MCP error `extras.messages`).

## Capability manifests

`src/main/resources/manifests/tools/move.*.yaml` (`category: "charon"`, ids `charon.move.<tool>:v1`). Registered via `capabilities-client` `startupRegister` (warn-and-continue if the registry is down — the service always boots). `cost_hints.typical_latency_ms` are seeded; the live per-pair figures come from the bench (`services/charon/bench/`).

## Build / run / config

```bash
just build-kt charon-mcp     # Jib image charon-mcp:latest
just test-kt charon-mcp      # McpToolsSpec (mocked gRPC client)
just deploy-kt charon-mcp    # K3s apply (local overlay)
```

Config (`application.conf`): `server.port` (7252), `charon.host`/`charon.port` (the gRPC backend; blank ⇒ tools report `GRPC_NOT_CONFIGURED`), `capabilities-mcp.url` (blank ⇒ not registered), `telemetry.*`.
