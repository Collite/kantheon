plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":agents:hebe:modules:api"))
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlin.logging)
    api(libs.logback.classic)
    api(libs.logstash.logback.encoder)
    implementation(libs.otel.sdk)
    implementation(libs.otel.exporter.otlp)
    implementation(libs.otel.autoconfigure)
    // P2 Stage 2.4 — in-memory SpanExporter to assert span emission / the no-op gate.
    testImplementation(libs.bundles.testing)
    testImplementation(libs.opentelemetry.sdk.testing)
    testRuntimeOnly(libs.junit.platform.launcher)
}
