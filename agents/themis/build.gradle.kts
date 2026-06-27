// Themis — agent for question understanding and (Phase 3) routing.
//
// Stage 2.2 brings ai-platform/agents/resolver across as a HEAD snapshot. Koog
// migration lands in Stage 2.3; for now the graph runs on plain coroutines and
// the build pulls only the deps the source actually touches (no Spring stack,
// no logstash/micrometer/grpc — see PR for the trim audit).
plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.jib)
}

dependencies {
    // Kotlin / coroutines / serialization
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    // Ktor server
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.serialization.kotlinx.json)

    // Ktor client (NLP / fuzzy / LLM gateway HTTP)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)

    // Proto bindings — all in-repo via :shared:proto. fork Stage 2.6 retargeted
    // Themis off cz.dfpartner:shared-proto: nlp.v1 → kadmos.v1, metadata
    // ResponseMessage/Severity → common.v1 (the last ai-platform Maven coupling).
    implementation(project(":shared:proto"))

    // Forked shared libs (Phase 1.3 — in-repo project deps).
    implementation(project(":shared:libs:kotlin:otel-config"))
    implementation(project(":shared:libs:kotlin:fuzzy-common"))
    implementation(project(":shared:libs:kotlin:logging-config"))
    implementation(project(":shared:libs:kotlin:ktor-configurator"))
    // Shared LLM-gateway client + Koog executor (extracted from themis, Golem Stage 2.3 T1).
    implementation(project(":shared:libs:kotlin:llm-gateway-client"))
    // Capabilities read-client — Themis routeToAgent reads the agent registry (Stage 3.3).
    implementation(project(":shared:libs:kotlin:capabilities-client"))

    // MCP SDK
    implementation(libs.kotlin.mcp.sdk)

    // OpenTelemetry API (SDK pulled by ai-platform.otel-config)
    implementation(libs.opentelemetry.api)

    // Cache
    implementation(libs.caffeine)

    // Config (HOCON)
    implementation(libs.typesafe.config)

    // YAML — intent_kind_rules.yaml loader (Phase 3 Stage 3.2); same Jackson
    // stack as the capabilities-mcp / *-mcp manifest loaders.
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.dataformat.yaml)

    // Logging
    implementation(libs.logback.classic)
    implementation(libs.kotlin.logging)

    // Koog 0.8.x — strategy DSL, prompt-executor, StructureFixingParser.
    // Added in Stage 2.3 T1; ThemisGraph lands in T2 and the cutover removes
    // ResolverGraph in T6. Spike (Stage 2.1) validated this exact pin against
    // kantheon's Ktor 3.2.3 — see agents/_koog-spike/docs/koog-spike-report.md.
    implementation(libs.koog.agents)

    testImplementation(libs.bundles.kotest)
    testImplementation(libs.mockk)
    testImplementation(libs.wiremock)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlinx.coroutines.test)
    // Koog in-process mock executor — `getMockExecutor` + `mockLLMAnswer` DSL.
    // Used by the parallel *KoogSpec tests that exercise LLM-using nodes.
    testImplementation(libs.koog.agents.test)
}

application {
    mainClass.set("org.tatrman.kantheon.themis.MainKt")
}

jib {
    from { image = "eclipse-temurin:21-jre" }
    to { image = "themis-mcp:dev" }
    container {
        mainClass = "org.tatrman.kantheon.themis.MainKt"
        ports = listOf("7901")
    }
}

tasks.test { useJUnitPlatform() }
