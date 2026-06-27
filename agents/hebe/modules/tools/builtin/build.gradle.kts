plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

dependencies {
    api(project(":agents:hebe:modules:api"))
    implementation(project(":agents:hebe:modules:memory"))
    implementation(project(":agents:hebe:modules:security"))
    implementation(project(":agents:hebe:modules:observability"))
    // Hebe arc P4 S4.1 — the iris-bff headless client (the "kantheon" tool family),
    // built against the iris/v1 + hebe/v1 protos (wire policy); JsonFormat for the
    // REST/SSE JSON.
    implementation(project(":shared:proto"))
    implementation(libs.protobuf.java.util)
    implementation(libs.jgit)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.mockk)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
}
