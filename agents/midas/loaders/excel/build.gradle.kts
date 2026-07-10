plugins {
    base
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.jib)
}

application {
    mainClass.set("org.tatrman.kantheon.midas.loaders.excel.ApplicationKt")
}

jib {
    from {
        image = "eclipse-temurin:21-jre"
    }
    to {
        image = "midas-excel-loader:dev"
    }
    container {
        mainClass = "org.tatrman.kantheon.midas.loaders.excel.ApplicationKt"
        // P1 Stage 1.1: HTTP (upload/preview/commit + probes) on 7315.
        ports = listOf("7315")
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

    // Ktor (probes at Stage 1.1; loader REST routes land Stage 1.5).
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    // Ktor HttpClient → Midas-core (Stage 1.5 commit path).
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)

    // Proto <-> JSON on the wire to Midas-core + the loader's own responses.
    implementation(libs.protobuf.java.util)

    implementation(libs.typesafe.config)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)

    // Apache POI — the XLSX parser (Stage 1.5 T2).
    implementation(libs.apache.poi.ooxml)

    // Broker template configs are YAML on the classpath (Jackson; the ariadne-mcp
    // ManifestLoader pattern reused for brokers/*.yaml).
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.dataformat.yaml)

    testImplementation(libs.bundles.kotest)
    testImplementation(libs.mockk)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Regenerate the committed broker fixture XLSX files (Stage 1.5 T1) from the POI
// builders in test sources: `./gradlew :agents:midas:loaders:excel:genFixtures`.
// The fixtures are committed so the deploy smoke (T7) + reviewers have real files;
// this task keeps them reproducible. Replace with real broker exports when in hand.
tasks.register<JavaExec>("genFixtures") {
    group = "build"
    description = "Generate broker fixture XLSX files into src/test/resources/fixtures."
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.tatrman.kantheon.midas.loaders.excel.fixtures.BrokerFixturesKt")
}
