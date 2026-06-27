"""
Metis service bootstrap.

Starts:
  - gRPC server on METIS_GRPC_PORT (default 7261)
  - FastAPI probe server on METIS_HTTP_PORT (default 7260)
"""
import logging
import os
from concurrent import futures

import grpc
import uvicorn
from fastapi import FastAPI, Response

from metis import grpc_service, workspace as ws_module
from metis import metis_pb2_grpc
from metis.telemetry import setup_telemetry

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# FastAPI probe app
# ---------------------------------------------------------------------------
probe_app = FastAPI(title="metis-probes", docs_url=None, redoc_url=None)
_ready = False


@probe_app.get("/healthz")
def healthz():
    return {"status": "ok"}


@probe_app.get("/readyz")
def readyz():
    if not _ready:
        return Response(
            content='{"status":"not_ready"}',
            status_code=503,
            media_type="application/json",
        )
    return {"status": "ready"}


@probe_app.get("/metrics")
def metrics_endpoint():
    # Prometheus exposition of the in-process registry (Stage 3.1 T2). OTLP
    # export layers on top later without changing the RPC call sites.
    from metis.metrics import METRICS

    return Response(content=METRICS.render(), media_type="text/plain; version=0.0.4")


# ---------------------------------------------------------------------------
# gRPC server factory
# ---------------------------------------------------------------------------

def _build_grpc_server(workspace: ws_module.Workspace) -> grpc.Server:
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=10))
    servicer = grpc_service.MetisServiceImpl(workspace)
    metis_pb2_grpc.add_MetisServiceServicer_to_server(servicer, server)
    return server


# ---------------------------------------------------------------------------
# Entrypoint
# ---------------------------------------------------------------------------

def main() -> None:
    global _ready

    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(name)s %(levelname)s %(message)s",
    )

    grpc_port = int(os.environ.get("METIS_GRPC_PORT", "7261"))
    http_port = int(os.environ.get("METIS_HTTP_PORT", "7260"))

    setup_telemetry("metis")

    workspace = ws_module.Workspace(
        idle_ttl_s=int(os.environ.get("METIS_WORKSPACE_IDLE_TTL_S", "3600")),
        max_dfs_per_session=int(os.environ.get("METIS_WORKSPACE_MAX_DFS_PER_SESSION", "50")),
        max_models_per_session=int(os.environ.get("METIS_WORKSPACE_MAX_MODELS_PER_SESSION", "20")),
    )
    workspace.start_sweeper()

    grpc_server = _build_grpc_server(workspace)
    grpc_server.add_insecure_port(f"[::]:{grpc_port}")
    grpc_server.start()
    logger.info("gRPC server listening on :%d", grpc_port)

    _ready = True
    logger.info("Metis ready. Probe server on :%d", http_port)

    try:
        uvicorn.run(probe_app, host="0.0.0.0", port=http_port, log_level="info")
    finally:
        workspace.stop_sweeper()
        grpc_server.stop(grace=5)


if __name__ == "__main__":
    main()
