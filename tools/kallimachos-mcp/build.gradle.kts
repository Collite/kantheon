plugins {
    base
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.jib)
}

application {
    mainClass.set("org.tatrman.kallimachos.mcp.ApplicationKt")
}

jib {
    from { image = "eclipse-temurin:21-jre" }
    to { image = "kallimachos-mcp:dev" }
    container {
        mainClass = "org.tatrman.kallimachos.mcp.ApplicationKt"
        ports = listOf("7262")
    }
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

dependencies {
    // Shared libs — the shared MCP/Ktor base + capabilities registration client.
    implementation(project(":shared:libs:kotlin:ktor-configurator"))
    implementation(project(":shared:libs:kotlin:otel-config"))
    implementation(project(":shared:libs:kotlin:logging-config"))
    implementation(project(":shared:libs:kotlin:capabilities-client"))
    implementation(project(":shared:proto"))

    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    // MCP server (streamable-HTTP) + the HTTP client that forwards to Kallimachos.
    implementation(libs.kotlin.mcp.sdk)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.opentelemetry)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.otel.logback.appender)
    implementation(libs.logback.classic)
    implementation(libs.typesafe.config)

    // Manifest YAMLs (one ToolCapability per library.* tool).
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.dataformat.yaml)

    testImplementation(libs.bundles.kotest)
    testImplementation(libs.mockk)
    testImplementation(libs.wiremock)
    testImplementation(libs.ktor.client.mock)
}
