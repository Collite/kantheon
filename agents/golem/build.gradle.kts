// Golem — per-domain Q&A template (one pod per Shem). Phase 2 Stage 2.1:
// Ktor skeleton + golem_turns persistence (Postgres via Exposed + Flyway). The
// Koog graph, platform clients (theseus-mcp / ariadne-mcp / llm-gateway) and
// Shem/PackageContext land in Stages 2.2–2.4.
plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.jib)
}

application {
    mainClass.set("org.tatrman.kantheon.golem.ApplicationKt")
}

tasks.test {
    useJUnitPlatform()
}

val osArch = System.getProperty("os.arch").lowercase()
val isArm64 = osArch.contains("aarch64") || osArch.contains("arm64")
val isCi = System.getenv("CI") != null

// Image target is parameterized so the same jib config serves local dev and registry publish:
//   local : ./gradlew :agents:golem:jibDockerBuild                    -> golem:dev
//   GHCR  : ./gradlew :agents:golem:jib \
//             -PimageRepo=ghcr.io/boraperusic/golem -PimageTag=0.1.0 \
//             -Djib.to.auth.username=<gh-user> -Djib.to.auth.password=<ghcr-PAT>
val imageRepo = (project.findProperty("imageRepo") as String?) ?: "golem"
val imageTag = (project.findProperty("imageTag") as String?) ?: "dev"

jib {
    from {
        image = "eclipse-temurin:21-jre"
        platforms {
            if (isCi) {
                platform {
                    architecture = "arm64"
                    os = "linux"
                }
                platform {
                    architecture = "amd64"
                    os = "linux"
                }
            } else {
                platform {
                    architecture = if (isArm64) "arm64" else "amd64"
                    os = "linux"
                }
            }
        }
    }
    to {
        image = "$imageRepo:$imageTag"
    }
    container {
        mainClass = "org.tatrman.kantheon.golem.ApplicationKt"
        ports = listOf("7420")
    }
    dockerClient {
        executable = "docker"
    }
}

dependencies {
    // Shared bootstrap libs (in-repo modules, not Maven).
    implementation(project(":shared:libs:kotlin:ktor-configurator"))
    implementation(project(":shared:libs:kotlin:logging-config"))
    implementation(project(":shared:libs:kotlin:otel-config"))
    implementation(project(":shared:libs:kotlin:db-common"))
    // golem/v1 + envelope/v1 + themis/v1 proto types; envelope-render for formatting (Phase 3).
    implementation(project(":shared:proto"))
    implementation(project(":shared:libs:kotlin:envelope-render"))
    // Pattern-parametrization rail — typed {name:{value,type}} map (S2.4 §10 Δ1).
    implementation(project(":shared:libs:kotlin:pattern-params"))

    // Ktor server (Netty engine — robust for the /v1/answer SSE stream landing in Phase 3).
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.typesafe.config)

    // JsonFormat — golem/v1 + envelope/v1 proto ↔ JSON (persistence + SSE wire).
    implementation(libs.protobuf.java.util)

    // Shem YAML → AgentCapability (Stage 2.2). Jackson YAML, mirrors ariadne-mcp's manifest parser.
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.dataformat.yaml)

    // Ariadne model graph client (GetModel) — PackageContext (prompts come from the mounted Shem).
    implementation(project(":shared:libs:kotlin:ariadne-client"))
    // Shared LLM-gateway client + Koog executor — PlanComposer (Stage 2.3). Brings Koog (api).
    implementation(project(":shared:libs:kotlin:llm-gateway-client"))
    // theseus-mcp query edge — MCP streamable-HTTP client (Stage 2.4 T3).
    implementation(libs.kotlin.mcp.sdk)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    // Self-registration into capabilities-mcp at boot (ShemRegistration, Stage 2.2 T4).
    implementation(project(":shared:libs:kotlin:capabilities-client"))

    // Logging.
    api(libs.otel.logback.appender)
    implementation(libs.logback.classic)
    implementation(libs.logstash.logback.encoder)

    // OpenTelemetry.
    implementation(libs.ktor.opentelemetry)
    implementation(libs.micrometer.registry.prometheus)

    // Persistence: Postgres via Exposed + HikariCP, Flyway migrations.
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
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlinx.coroutines.test)

    // Integration tier (testing arc Stage 3.1) — drives the Golem `/v1/answer/sync`
    // REST edge over real HTTP against the live `golem-erp` context. The convention's
    // integrationTest suite already brings kotest + project(); these add the harness
    // (@RequiresContext/ContextHandle), a Ktor HTTP client, and JSON parsing of the
    // ConversationalResponse. Gated by @RequiresContext: compiles + skips with no context.
    "integrationTestImplementation"(project(":shared:libs:kotlin:integration-harness"))
    "integrationTestImplementation"(libs.ktor.client.core)
    "integrationTestImplementation"(libs.ktor.client.cio)
    "integrationTestImplementation"(libs.kotlinx.coroutines.core)
    "integrationTestImplementation"(libs.kotlinx.serialization.json)
}
