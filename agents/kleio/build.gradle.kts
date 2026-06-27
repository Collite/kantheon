plugins {
    base
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.jib)
}

application {
    mainClass.set("org.tatrman.kantheon.kleio.ApplicationKt")
}

jib {
    from { image = "eclipse-temurin:21-jre" }
    to { image = "kleio:dev" }
    container {
        mainClass = "org.tatrman.kantheon.kleio.ApplicationKt"
        ports = listOf("7270")
    }
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

dependencies {
    implementation(project(":shared:proto"))
    implementation(project(":shared:libs:kotlin:ktor-configurator"))
    implementation(project(":shared:libs:kotlin:otel-config"))
    implementation(project(":shared:libs:kotlin:logging-config"))
    implementation(project(":shared:libs:kotlin:capabilities-client"))

    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.opentelemetry)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.otel.logback.appender)
    implementation(libs.logback.classic)
    implementation(libs.typesafe.config)

    // kleio/v1 + envelope/v1 ↔ JSON for persistence + the SSE wire.
    implementation(libs.protobuf.java.util)

    // kleio_turns + notebook_artifacts persistence (Postgres via Exposed + Flyway).
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.exposed.json)
    implementation(libs.hikaricp)
    implementation(libs.postgresql)
    implementation(libs.flyway.core)
    implementation(libs.flyway.pgsql)

    testImplementation(libs.bundles.kotest)
    testImplementation(libs.mockk)
    testImplementation(libs.wiremock)
    testImplementation(libs.ktor.client.mock)
}
