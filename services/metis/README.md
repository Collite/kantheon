# Metis — Model Estimation Service

Python gRPC service providing SARIMAX (auto-order), Prophet, and linear regression over prepared Arrow series.

## Ports

| Port | Protocol | Use |
|------|----------|-----|
| 7261 | gRPC | MetisService (estimation + workspace) |
| 7260 | HTTP | Probes: /healthz /readyz /metrics |

## Build and run

```bash
# Regenerate proto bindings (Python message classes)
just proto-py

# Sync deps
cd services/metis && uv sync

# Run tests
just test-py services/metis

# Lint
just lint-py services/metis

# Build image
just build-py services/metis

# Deploy to local K3s
just deploy-py services/metis
```

## gRPC stub regeneration

The `src/metis/metis_pb2_grpc.py` file contains the gRPC service stubs. After proto changes:

```bash
just proto-py
python -m grpc_tools.protoc \
  -I shared/proto/src/main/proto \
  --grpc_python_out=services/metis/src/metis \
  shared/proto/src/main/proto/org/tatrman/metis/v1/metis.proto
```

## Workspace

Session workspace keyed `(session_id, name)`. One namespace for DataFrames and fitted models.
TTL: 60 min idle (configurable via `METIS_WORKSPACE_IDLE_TTL_S`).
Caps: 50 DFs / 20 models per session, 4 GiB total.

## Estimation (Phase 2)

Model kinds: `LINEAR` (OLS), `ARIMA` (SARIMAX auto-order), `PROPHET`. See `docs/architecture/metis/contracts.md`.
