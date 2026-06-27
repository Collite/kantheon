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
import { config } from '@/config';

const getAlloyUrl = () => {
    const endpoint = config.otel.host;
    const protocol = config.otel.protocol;

    let port = config.otel.httpPort;
    let urlProtocol = 'http';

    if (protocol === 'HTTPS') {
        port = config.otel.httpsPort;
        urlProtocol = 'https';
    } else if (protocol === 'GRPC') {
        port = config.otel.grpcPort;
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

    const httpRequestDuration = meter.createHistogram('http_request_duration_seconds', {
        description: 'Duration of HTTP requests in seconds',
    });

    const fuzzyMatchCounter = meter.createCounter('fuzzy_match_requests_total', {
        description: 'Total number of fuzzy match requests',
    });

    const fuzzyMatchDuration = meter.createHistogram('fuzzy_match_duration_seconds', {
        description: 'Duration of fuzzy match requests in seconds',
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
        metrics: {
            httpRequestCounter,
            httpRequestDuration,
            fuzzyMatchCounter,
            fuzzyMatchDuration,
        },
    };
}

export type Telemetry = ReturnType<typeof initializeTelemetry>;