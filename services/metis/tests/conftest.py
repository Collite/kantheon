import pytest
import grpc
from concurrent import futures

from metis.workspace import Workspace
from metis.grpc_service import MetisServiceImpl
from metis import metis_pb2_grpc


@pytest.fixture()
def workspace():
    return Workspace(
        idle_ttl_s=60,
        max_dfs_per_session=10,
        max_models_per_session=5,
        max_bytes_total=100 * 1024 * 1024,
    )


@pytest.fixture()
def grpc_server_and_stub(workspace):
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=4))
    metis_pb2_grpc.add_MetisServiceServicer_to_server(MetisServiceImpl(workspace), server)
    port = server.add_insecure_port("[::]:0")
    server.start()
    channel = grpc.insecure_channel(f"localhost:{port}")
    stub = metis_pb2_grpc.MetisServiceStub(channel)
    yield server, stub
    channel.close()
    server.stop(grace=None)
