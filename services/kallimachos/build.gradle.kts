plugins {
    base
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.jib)
}

application {
    mainClass.set("org.tatrman.kallimachos.ApplicationKt")
}

jib {
    from {
        image = "eclipse-temurin:21-jre"
    }
    to {
        image = "kallimachos:dev"
    }
    container {
        mainClass = "org.tatrman.kallimachos.ApplicationKt"
        // P1 Stage 1.1: probes (7260) + the REST API surface (7261). The
        // `kallimachos-mcp` wrapper (7262) lands in P4 as a separate module.
        ports = listOf("7260", "7261")
    }
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("file.encoding", "UTF-8")
}

dependencies {
    // Shared libs (in-repo; AGENTS.md §5 — every shared dep is a project ref).
    implementation(project(":shared:proto"))
    implementation(libs.tatrman.ktor.configurator)
    implementation(libs.tatrman.otel.config)
    implementation(libs.tatrman.logging.config)

    // Kotlin / coroutines / serialization
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    // Ktor (HTTP probes + the REST corpus surface; routes stubbed at Stage 1.1).
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.opentelemetry)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.otel.logback.appender)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)

    // HOCON
    implementation(libs.typesafe.config)

    // Ingestion parsers — ported from doc-store (Stage 1.1 T4). Framework-agnostic.
    implementation(libs.jsoup) // HTML → DocNode
    implementation(libs.flexmark) // Markdown → DocNode
    implementation(libs.pdfbox) // PDF text extraction

    // Relational + full-text planes (Stage 1.2) on the single Postgres. Exposed
    // 1.0 DSL (not ORM, AGENTS rule) + Flyway migrations + HikariCP pool. The
    // vector plane (pgvector) + AGE plane land in P2.
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.exposed.json)
    implementation(libs.hikaricp)
    implementation(libs.postgresql)
    implementation(libs.flyway.core)
    implementation(libs.flyway.pgsql)

    // Vector plane (P2 Stage 2.1): pgvector helper for the VECTOR plane binding +
    // a Ktor client for the LLM-gateway EmbedText embeddings client.
    implementation(libs.pgvector)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)

    testImplementation(libs.bundles.kotest)
    testImplementation(libs.mockk)
    // Wiremock — the LLM-gateway EmbedText embeddings client spec (EXAMPLES §9).
    testImplementation(libs.wiremock)
    testImplementation(libs.ktor.client.mock)
}
