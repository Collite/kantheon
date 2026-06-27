import { WebTracerProvider } from '@opentelemetry/sdk-trace-web';
import { BatchSpanProcessor } from '@opentelemetry/sdk-trace-base';
import { OTLPTraceExporter } from '@opentelemetry/exporter-trace-otlp-http';
import { OTLPLogExporter } from '@opentelemetry/exporter-logs-otlp-http';
import { LoggerProvider, BatchLogRecordProcessor } from '@opentelemetry/sdk-logs';
import { MeterProvider, PeriodicExportingMetricReader } from '@opentelemetry/sdk-metrics';
import { OTLPMetricExporter } from '@opentelemetry/exporter-metrics-otlp-http';
import { registerInstrumentations } from '@opentelemetry/instrumentation';
import { FetchInstrumentation } from '@opentelemetry/instrumentation-fetch';
import { XMLHttpRequestInstrumentation } from '@opentelemetry/instrumentation-xml-http-request';
import { ZoneContextManager } from '@opentelemetry/context-zone';
import { resourceFromAttributes } from '@opentelemetry/resources';
import { ATTR_SERVICE_NAME } from '@opentelemetry/semantic-conventions';

const getAlloyUrl = () => {
    const endpoint = import.meta.env.VITE_OTEL_EXPORTER_OTLP_HOST || 'localhost';
    const protocol = import.meta.env.VITE_LANDING_OTEL_PROTOCOL || 'HTTPS';

    let port = import.meta.env.VITE_OTEL_EXPORTER_OTLP_HTTP_PORT || '8089';
    let urlProtocol = 'http';

    if (protocol === 'HTTPS') {
        port = import.meta.env.VITE_OTEL_EXPORTER_OTLP_HTTPS_PORT || '8090';
        urlProtocol = 'https';
    } else if (protocol === 'GRPC') {
        port = import.meta.env.VITE_OTEL_EXPORTER_OTLP_GRPC_PORT || '8091';
        urlProtocol = 'http';
    }

    let baseUrl = `${urlProtocol}://${endpoint}:${port}`;

    if (window.location.protocol === 'https:' && baseUrl.startsWith('http://')) {
        baseUrl = baseUrl.replace('http://', 'https://');
    }
    return baseUrl;
};
const ALLOY_URL = getAlloyUrl();

export function initializeTelemetry(serviceName: string) {
    const traceExporter = new OTLPTraceExporter({
        url: `${ALLOY_URL}/v1/traces`,
    });

    const traceProvider = new WebTracerProvider({
        resource: resourceFromAttributes({
            [ATTR_SERVICE_NAME]: serviceName,
        }),
        spanProcessors: [new BatchSpanProcessor(traceExporter)],
    });

    traceProvider.register({
        contextManager: new ZoneContextManager(),
    });

    const logExporter = new OTLPLogExporter({
        url: `${ALLOY_URL}/v1/logs`,
    });

    const loggerProvider = new LoggerProvider({
        resource: resourceFromAttributes({
            [ATTR_SERVICE_NAME]: serviceName,
        }),
        processors: [new BatchLogRecordProcessor(logExporter)],
    });

    const logger = loggerProvider.getLogger(serviceName);

    const metricExporter = new OTLPMetricExporter({
        url: `${ALLOY_URL}/v1/metrics`,
    });

    const metricReader = new PeriodicExportingMetricReader({
        exporter: metricExporter,
        exportIntervalMillis: 10000,
    });

    const meterProvider = new MeterProvider({
        resource: resourceFromAttributes({
            [ATTR_SERVICE_NAME]: serviceName,
        }),
        readers: [metricReader],
    });

    const meter = meterProvider.getMeter(serviceName);

    const httpRequestCounter = meter.createCounter('http_requests_total', {
        description: 'Total number of HTTP requests',
    });

    registerInstrumentations({
        instrumentations: [
            new FetchInstrumentation({
                applyCustomAttributesOnSpan: (span, request) => {
                    const url = (request as any).url || '';
                    span.setAttribute('http.url', url);
                    httpRequestCounter.add(1, { url, method: (request as any).method || 'GET' });
                },
            }),
            new XMLHttpRequestInstrumentation({
                applyCustomAttributesOnSpan: (span, request) => {
                    const url = (request as any).url || '';
                    span.setAttribute('http.url', url);
                    httpRequestCounter.add(1, { url, method: (request as any).method || 'GET' });
                },
            }),
        ],
        tracerProvider: traceProvider,
    });

    return {
        logger,
        traceProvider,
        meterProvider,
    };
}

export type Telemetry = ReturnType<typeof initializeTelemetry>;
