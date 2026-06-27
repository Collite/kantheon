"""
gRPC stubs for MetisService (org.tatrman.metis.v1).

Regenerate with:
  cd <repo_root>
  just proto-py
  python -m grpc_tools.protoc \\
    -I shared/proto/src/main/proto \\
    --grpc_python_out=services/metis/src/metis \\
    shared/proto/src/main/proto/org/tatrman/metis/v1/metis.proto
"""
import grpc

from org.tatrman.metis.v1 import metis_pb2 as _pb2

_SERVICE_NAME = 'org.tatrman.metis.v1.MetisService'


class MetisServiceStub:
    """Client stub for MetisService."""

    def __init__(self, channel: grpc.Channel) -> None:
        self.Fit = channel.unary_unary(
            f'/{_SERVICE_NAME}/Fit',
            request_serializer=_pb2.FitRequest.SerializeToString,
            response_deserializer=_pb2.FitResult.FromString,
        )
        self.Diagnose = channel.unary_unary(
            f'/{_SERVICE_NAME}/Diagnose',
            request_serializer=_pb2.DiagnoseRequest.SerializeToString,
            response_deserializer=_pb2.DiagnoseResult.FromString,
        )
        self.Project = channel.unary_unary(
            f'/{_SERVICE_NAME}/Project',
            request_serializer=_pb2.ProjectRequest.SerializeToString,
            response_deserializer=_pb2.ProjectResult.FromString,
        )
        self.SimulateScenario = channel.unary_unary(
            f'/{_SERVICE_NAME}/SimulateScenario',
            request_serializer=_pb2.SimulateScenarioRequest.SerializeToString,
            response_deserializer=_pb2.ProjectResult.FromString,
        )
        self.ImportDataFrame = channel.stream_unary(
            f'/{_SERVICE_NAME}/ImportDataFrame',
            request_serializer=_pb2.ArrowChunk.SerializeToString,
            response_deserializer=_pb2.ImportResult.FromString,
        )
        self.ExportDataFrame = channel.unary_stream(
            f'/{_SERVICE_NAME}/ExportDataFrame',
            request_serializer=_pb2.ExportRequest.SerializeToString,
            response_deserializer=_pb2.ArrowChunk.FromString,
        )
        self.DropWorkspaceEntry = channel.unary_unary(
            f'/{_SERVICE_NAME}/DropWorkspaceEntry',
            request_serializer=_pb2.DropRequest.SerializeToString,
            response_deserializer=_pb2.DropResult.FromString,
        )
        self.GetStatus = channel.unary_unary(
            f'/{_SERVICE_NAME}/GetStatus',
            request_serializer=_pb2.GetStatusRequest.SerializeToString,
            response_deserializer=_pb2.GetStatusResponse.FromString,
        )


class MetisServiceServicer:
    """Server-side servicer base class for MetisService."""

    def Fit(self, request, context):
        context.set_code(grpc.StatusCode.UNIMPLEMENTED)
        context.set_details('Method not implemented!')
        raise NotImplementedError('Method not implemented!')

    def Diagnose(self, request, context):
        context.set_code(grpc.StatusCode.UNIMPLEMENTED)
        context.set_details('Method not implemented!')
        raise NotImplementedError('Method not implemented!')

    def Project(self, request, context):
        context.set_code(grpc.StatusCode.UNIMPLEMENTED)
        context.set_details('Method not implemented!')
        raise NotImplementedError('Method not implemented!')

    def SimulateScenario(self, request, context):
        context.set_code(grpc.StatusCode.UNIMPLEMENTED)
        context.set_details('Method not implemented!')
        raise NotImplementedError('Method not implemented!')

    def ImportDataFrame(self, request_iterator, context):
        context.set_code(grpc.StatusCode.UNIMPLEMENTED)
        context.set_details('Method not implemented!')
        raise NotImplementedError('Method not implemented!')

    def ExportDataFrame(self, request, context):
        context.set_code(grpc.StatusCode.UNIMPLEMENTED)
        context.set_details('Method not implemented!')
        raise NotImplementedError('Method not implemented!')

    def DropWorkspaceEntry(self, request, context):
        context.set_code(grpc.StatusCode.UNIMPLEMENTED)
        context.set_details('Method not implemented!')
        raise NotImplementedError('Method not implemented!')

    def GetStatus(self, request, context):
        context.set_code(grpc.StatusCode.UNIMPLEMENTED)
        context.set_details('Method not implemented!')
        raise NotImplementedError('Method not implemented!')


class _MetisServiceHandler(grpc.GenericRpcHandler):
    """Generic RPC handler wiring servicer methods to the gRPC server."""

    def __init__(self, servicer):
        self._handlers = {
            f'/{_SERVICE_NAME}/Fit': grpc.unary_unary_rpc_method_handler(
                servicer.Fit,
                request_deserializer=_pb2.FitRequest.FromString,
                response_serializer=_pb2.FitResult.SerializeToString,
            ),
            f'/{_SERVICE_NAME}/Diagnose': grpc.unary_unary_rpc_method_handler(
                servicer.Diagnose,
                request_deserializer=_pb2.DiagnoseRequest.FromString,
                response_serializer=_pb2.DiagnoseResult.SerializeToString,
            ),
            f'/{_SERVICE_NAME}/Project': grpc.unary_unary_rpc_method_handler(
                servicer.Project,
                request_deserializer=_pb2.ProjectRequest.FromString,
                response_serializer=_pb2.ProjectResult.SerializeToString,
            ),
            f'/{_SERVICE_NAME}/SimulateScenario': grpc.unary_unary_rpc_method_handler(
                servicer.SimulateScenario,
                request_deserializer=_pb2.SimulateScenarioRequest.FromString,
                response_serializer=_pb2.ProjectResult.SerializeToString,
            ),
            f'/{_SERVICE_NAME}/ImportDataFrame': grpc.stream_unary_rpc_method_handler(
                servicer.ImportDataFrame,
                request_deserializer=_pb2.ArrowChunk.FromString,
                response_serializer=_pb2.ImportResult.SerializeToString,
            ),
            f'/{_SERVICE_NAME}/ExportDataFrame': grpc.unary_stream_rpc_method_handler(
                servicer.ExportDataFrame,
                request_deserializer=_pb2.ExportRequest.FromString,
                response_serializer=_pb2.ArrowChunk.SerializeToString,
            ),
            f'/{_SERVICE_NAME}/DropWorkspaceEntry': grpc.unary_unary_rpc_method_handler(
                servicer.DropWorkspaceEntry,
                request_deserializer=_pb2.DropRequest.FromString,
                response_serializer=_pb2.DropResult.SerializeToString,
            ),
            f'/{_SERVICE_NAME}/GetStatus': grpc.unary_unary_rpc_method_handler(
                servicer.GetStatus,
                request_deserializer=_pb2.GetStatusRequest.FromString,
                response_serializer=_pb2.GetStatusResponse.SerializeToString,
            ),
        }

    def service_name(self):
        return _SERVICE_NAME

    def service(self, handler_call_details):
        return self._handlers.get(handler_call_details.method)


def add_MetisServiceServicer_to_server(servicer, server):
    """Register a MetisServiceServicer implementation with a gRPC server."""
    server.add_generic_rpc_handlers((_MetisServiceHandler(servicer),))
