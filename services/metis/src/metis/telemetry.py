import logging
import os

from opentelemetry import metrics, trace
from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry.sdk.metrics.export import PeriodicExportingMetricReader
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor

logger = logging.getLogger(__name__)

_tracer: trace.Tracer | None = None
_meter: metrics.Meter | None = None


def setup_telemetry(service_name: str = "metis") -> None:
    """Initialise OTel tracing + metrics. No-op if OTEL_SDK_DISABLED=true."""
    if os.environ.get("OTEL_SDK_DISABLED", "").lower() == "true":
        logger.info("OTel disabled via OTEL_SDK_DISABLED")
        return

    endpoint = os.environ.get("OTEL_EXPORTER_OTLP_ENDPOINT", "")
    if not endpoint:
        host = os.environ.get("OTEL_EXPORTER_OTLP_HOST", "localhost")
        port = os.environ.get("OTEL_EXPORTER_OTLP_GRPC_PORT", "4317")
        endpoint = f"http://{host}:{port}"

    resource = Resource(attributes={"service.name": service_name})

    try:
        from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import OTLPSpanExporter
        from opentelemetry.exporter.otlp.proto.grpc.metric_exporter import OTLPMetricExporter

        tp = TracerProvider(resource=resource)
        tp.add_span_processor(BatchSpanProcessor(OTLPSpanExporter(endpoint=endpoint, insecure=True)))
        trace.set_tracer_provider(tp)

        mp = MeterProvider(
            resource=resource,
            metric_readers=[PeriodicExportingMetricReader(OTLPMetricExporter(endpoint=endpoint, insecure=True))],
        )
        metrics.set_meter_provider(mp)
        logger.info("OTel initialised: service=%s endpoint=%s", service_name, endpoint)
    except Exception as exc:
        logger.warning("OTel setup failed (non-fatal): %s", exc)


def tracer() -> trace.Tracer:
    return trace.get_tracer("metis")


def meter() -> metrics.Meter:
    return metrics.get_meter("metis")
