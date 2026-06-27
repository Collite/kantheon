"""Stage 1.1 T4: request-validation specs over in-process gRPC.

Rules (contracts §1.3 / error model):
  INVALID_ARGUMENT   — missing/empty required fields
  NOT_FOUND          — unknown session or DF/model name
  ALREADY_EXISTS     — name collision in workspace
  RESOURCE_EXHAUSTED — cap breach
"""
import pytest
import grpc

from org.tatrman.metis.v1 import metis_pb2


def test_import_missing_session_id_raises_invalid_argument(grpc_server_and_stub):
    _, stub = grpc_server_and_stub
    header = metis_pb2.ArrowChunk(
        header=metis_pb2.ImportHeader(session_id="", df_name="df1")
    )
    with pytest.raises(grpc.RpcError) as exc:
        stub.ImportDataFrame(iter([header]))
    assert exc.value.code() == grpc.StatusCode.INVALID_ARGUMENT


def test_import_missing_df_name_raises_invalid_argument(grpc_server_and_stub):
    _, stub = grpc_server_and_stub
    header = metis_pb2.ArrowChunk(
        header=metis_pb2.ImportHeader(session_id="s1", df_name="")
    )
    with pytest.raises(grpc.RpcError) as exc:
        stub.ImportDataFrame(iter([header]))
    assert exc.value.code() == grpc.StatusCode.INVALID_ARGUMENT


def test_import_no_header_chunk_raises_invalid_argument(grpc_server_and_stub):
    _, stub = grpc_server_and_stub
    # First chunk has no header field set
    chunk = metis_pb2.ArrowChunk(ipc_payload=b"garbage")
    with pytest.raises(grpc.RpcError) as exc:
        stub.ImportDataFrame(iter([chunk]))
    assert exc.value.code() == grpc.StatusCode.INVALID_ARGUMENT


def test_export_missing_session_id_raises_invalid_argument(grpc_server_and_stub):
    _, stub = grpc_server_and_stub
    req = metis_pb2.ExportRequest(session_id="", df_name="df1")
    with pytest.raises(grpc.RpcError) as exc:
        list(stub.ExportDataFrame(req))
    assert exc.value.code() == grpc.StatusCode.INVALID_ARGUMENT


def test_export_unknown_df_raises_not_found(grpc_server_and_stub):
    _, stub = grpc_server_and_stub
    req = metis_pb2.ExportRequest(session_id="s1", df_name="no_such_df")
    with pytest.raises(grpc.RpcError) as exc:
        list(stub.ExportDataFrame(req))
    assert exc.value.code() == grpc.StatusCode.NOT_FOUND


def test_drop_missing_session_id_raises_invalid_argument(grpc_server_and_stub):
    _, stub = grpc_server_and_stub
    req = metis_pb2.DropRequest(session_id="", name="x")
    with pytest.raises(grpc.RpcError) as exc:
        stub.DropWorkspaceEntry(req)
    assert exc.value.code() == grpc.StatusCode.INVALID_ARGUMENT


def test_get_status_returns_empty_workspace(grpc_server_and_stub):
    _, stub = grpc_server_and_stub
    resp = stub.GetStatus(metis_pb2.GetStatusRequest())
    assert resp.sessions == 0
    assert resp.dataframes == 0
    assert resp.models == 0
    assert resp.workspace_bytes == 0
