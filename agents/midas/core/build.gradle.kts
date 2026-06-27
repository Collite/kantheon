plugins {
    base
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.jib)
}

application {
    mainClass.set("org.tatrman.kantheon.midas.core.ApplicationKt")
}

jib {
    from {
        image = "eclipse-temurin:21-jre"
    }
    to {
        image = "midas-core:dev"
    }
    container {
        mainClass = "org.tatrman.kantheon.midas.core.ApplicationKt"
        // 7310 — REST write/read API + probes (Netty). 7311 — MCP tool surface at
        // /mcp (Stage 1.4, CIO), run as a second listener in the same process so
        // the REST error-envelope stack and the MCP base stack don't collide.
        ports = listOf("7310", "7311")
    }
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

dependencies {
    // Shared libs (in-repo; AGENTS.md §5 — every shared dep is a project ref).
    implementation(project(":shared:proto"))
    implementation(project(":shared:libs:kotlin:ktor-configurator"))
    implementation(project(":shared:libs:kotlin:otel-config"))
    implementation(project(":shared:libs:kotlin:logging-config"))
    // Stage 1.4 — MCP tool surface + capabilities registration.
    implementation(project(":shared:libs:kotlin:capabilities-client"))
    // Exposed + HikariCP + Postgres driver (decision #1, 2026-06-21). The
    // operational write/read path is built on db-common in Stage 1.3.
    implementation(project(":shared:libs:kotlin:db-common"))

    // Kotlin / coroutines / serialization
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.core)

    // Ktor (probes at Stage 1.1; REST routes land Stage 1.3, MCP Stage 1.4).
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)

    // MCP tool surface (Stage 1.4) — standalone CIO listener via the shared
    // ktor-configurator MCP base (installMcpKtorBase / safeMcpTool) + the SDK.
    implementation(libs.kotlin.mcp.sdk)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.opentelemetry)

    // Manifest YAML parsing for capabilities registration (Jackson; the
    // ariadne-mcp pattern — wrappers must not depend on :tools:capabilities-mcp).
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.dataformat.yaml)

    // Persistence — Exposed v1 + HikariCP + Postgres + Flyway-on-boot (Stage 1.3;
    // iris-bff precedent). db-common provides the Hikari/Exposed connection.
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.exposed.json)
    implementation(libs.hikaricp)
    implementation(libs.postgresql)
    implementation(libs.flyway.core)
    implementation(libs.flyway.pgsql)

    // Proto ↔ JSON on the REST wire (canonical proto JSON; iris-bff precedent).
    implementation(libs.protobuf.java.util)

    // HOCON + logging
    implementation(libs.typesafe.config)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)

    testImplementation(libs.bundles.kotest)
    testImplementation(libs.mockk)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.kotlinx.coroutines.test)

    // Component tier (real Postgres via Testcontainers; Stage 1.3 T4 — RLS proof).
    "componentTestImplementation"(project(":shared:libs:kotlin:component-testkit"))
    "componentTestImplementation"(libs.postgresql)
    "componentTestImplementation"(libs.flyway.core)
    "componentTestImplementation"(libs.flyway.pgsql)
    "componentTestImplementation"(libs.exposed.core)
    "componentTestImplementation"(libs.exposed.jdbc)
    "componentTestImplementation"(libs.typesafe.config)
}
