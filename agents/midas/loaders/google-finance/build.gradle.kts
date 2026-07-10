plugins {
    base
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.jib)
}

application {
    mainClass.set("org.tatrman.kantheon.midas.loaders.googlefinance.ApplicationKt")
}

jib {
    from { image = "eclipse-temurin:21-jre" }
    to { image = "midas-google-finance-loader:dev" }
    container {
        mainClass = "org.tatrman.kantheon.midas.loaders.googlefinance.ApplicationKt"
        // HTTP (trigger + run history + probes) on 7316; the pollers run on the in-process scheduler.
        ports = listOf("7316")
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
    implementation(libs.tatrman.ktor.configurator)
    implementation(libs.tatrman.otel.config)
    implementation(libs.tatrman.logging.config)

    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.core)

    // Ktor server (trigger/run REST + probes) + client (→ Midas-core fx-rates upsert).
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)

    implementation(libs.protobuf.java.util)
    implementation(libs.typesafe.config)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)

    testImplementation(libs.bundles.kotest)
    testImplementation(libs.mockk)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
}
