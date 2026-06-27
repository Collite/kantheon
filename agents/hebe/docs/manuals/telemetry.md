## Telemetry

### Overview
All services use **OpenTelemetry (OTEL)** for logging, tracing, and metrics with **Grafana Alloy** as the collector (push mode). The shared `otel-config` library (`shared/libs/kotlin/otel-config`) provides the `createOpenTelemetrySdk()` function for consistent setup.

### Components
- **Traces**: OTLP gRPC → Tempo
- **Metrics**: OTLP gRPC → Prometheus
- **Logs**: OTLP gRPC → Grafana Alloy (forwards to Loki-compatible storage)
- **Collector**: Grafana Alloy (receives OTLP, forwards to Tempo/Prometheus/Loki)

### Setting Up a New Service with OTEL

#### 1. Add Dependencies to `build.gradle.kts`
```kotlin
dependencies {
    // ... existing dependencies
    implementation(project(":shared:libs:kotlin:otel-config"))
    api(libs.otel.logback.appender)
    implementation(libs.otel.exporter.otlp)
}
```

#### 2. Add OTEL Appender to `logback.xml`
```xml
<appender name="OTEL" class="io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender">
    <captureExperimentalAttributes>true</captureExperimentalAttributes>
    <captureKeyValuePairAttributes>true</captureKeyValuePairAttributes>
    <captureLoggerContext>true</captureLoggerContext>
    <captureMdcAttributes>*</captureMdcAttributes>
</appender>

<root level="INFO">
    <appender-ref ref="CONSOLE"/> <!-- or JSON_CONSOLE -->
    <appender-ref ref="OTEL"/>
</root>
```

#### 3. Initialize SDK in `Application.kt`
```kotlin
import shared.otel.OtelEndpointConfig
import shared.otel.createOpenTelemetrySdk

fun main() {
    val config = ConfigFactory.load()
    createOpenTelemetrySdk(
        OtelEndpointConfig(
            serviceName = "my-service",
            protocol = System.getenv("MY_SERVICE_OTEL_PROTOCOL") ?: "grpc",
        ),
    )
    // ... rest of main
}
```

### Environment Variables

| Variable | Default | Description |
| :--- | :--- | :--- |
| `OTEL_EXPORTER_OTLP_HOST` | `localhost` | Alloy/collector host |
| `OTEL_EXPORTER_OTLP_GRPC_PORT` | `4317` | gRPC port for OTLP |
| `OTEL_EXPORTER_OTLP_HTTP_PORT` | `4318` | HTTP port for OTLP |
| `<SERVICE>_OTEL_PROTOCOL` | `grpc` | Protocol override per service |

### For Services Using Auto-Configured OpenTelemetry
If a service uses `AutoConfiguredOpenTelemetrySdk`, you **must** also call `OpenTelemetryAppender.install()` to enable log forwarding:
```kotlin
val openTelemetry = io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk
    .builder()
    .build()
    .openTelemetrySdk

io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender.install(openTelemetry)
```

### Accessing Telemetry in K8s
- **Grafana**: `http://grafana.local` (port-forward with `just debug-tunnel`)
- **Tempo**: Traces at `http://tempo.local:3200`
- **Prometheus**: Metrics at `http://prometheus.local:9090`
- **Alloy**: Runs as DaemonSet, receives OTLP on port 4317 (gRPC) and 4318 (HTTP)
