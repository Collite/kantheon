plugins {
    base
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.jib)
}

application {
    mainClass.set("org.tatrman.kantheon.report.ApplicationKt")
}

jib {
    from {
        image = "eclipse-temurin:21-jre"
    }
    to {
        image = "report-renderer:dev"
    }
    container {
        mainClass = "org.tatrman.kantheon.report.ApplicationKt"
        // P1 Stage 1.1: HTTP (templates/render/artifacts + probes) on 7320.
        ports = listOf("7320")
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

    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.core)

    // Ktor (probes at Stage 1.1; render routes land Phase 3 Stage 3.4).
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)

    implementation(libs.typesafe.config)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)

    // Proto <-> JSON for the report/v1 contract on the REST wire.
    implementation(libs.protobuf.java.util)

    // Apache POI — the XLSX render engine (Stage 3.4). PPTX (POI slides) + PDF/HTML
    // (Playwright headless Chromium) land alongside; the Playwright dep is added when
    // that engine is wired (integration-deferred — not on the unit gate).
    implementation(libs.apache.poi.ooxml)

    // Jackson YAML — the Midas dashboard-template content (Stage 3.5).
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.dataformat.yaml)

    testImplementation(libs.bundles.kotest)
    testImplementation(libs.mockk)
    testImplementation(libs.ktor.server.test.host)
}
