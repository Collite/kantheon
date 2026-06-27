plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":agents:hebe:modules:api"))
    implementation(project(":agents:hebe:modules:observability"))
    implementation(libs.bundles.ktor.client)
    implementation(libs.kotlinx.serialization.json)
    // P2 Stage 2.4 T4 — W3C trace-context (traceparent) propagation on gateway calls.
    implementation(libs.otel.api)
    testImplementation(libs.bundles.testing)
    testImplementation(libs.kotest.framework.engine)
    testImplementation(libs.kotest.junit.platform.runner)
    testImplementation(libs.kotlinx.coroutines.test)
    // P2 Stage 2.2 — ktor MockEngine for the gateway client specs (auth/cost
    // headers/streaming/tool-use/usage) without a live HTTP layer.
    testImplementation(libs.ktor.client.mock)
    testRuntimeOnly(libs.junit.platform.launcher)
}
