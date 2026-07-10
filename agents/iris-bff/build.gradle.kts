// Iris-BFF — dispatch BFF for the Iris SPA (conversation state, Themis dispatch
// in Phase 3, SSE multiplex in Stage 1.3). Phase 1 Stage 1.2: Ktor skeleton +
// session persistence (Postgres via Exposed + Flyway).
plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.jib)
}

application {
    mainClass.set("org.tatrman.kantheon.iris.ApplicationKt")
}

tasks.test {
    useJUnitPlatform()
}

// Offline feedback export (PD-3, Stage 4.3) — `just feedback-export`. Reads
// iris_feedback (+ iris_turns) from the configured Postgres and stages per-agent
// JSONL candidates under eval/candidates/. Pass an output dir via `--args`.
tasks.register<JavaExec>("feedbackExport") {
    group = "application"
    description = "Export iris_feedback rows to per-agent eval/candidates/ JSONL"
    mainClass.set("org.tatrman.kantheon.iris.feedback.FeedbackExportCliKt")
    classpath = sourceSets["main"].runtimeClasspath
}

val osArch = System.getProperty("os.arch").lowercase()
val isArm64 = osArch.contains("aarch64") || osArch.contains("arm64")
val isCi = System.getenv("CI") != null

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
        image = "iris-bff:dev"
    }
    container {
        mainClass = "org.tatrman.kantheon.iris.ApplicationKt"
        ports = listOf("7410")
    }
    dockerClient {
        executable = "docker"
    }
}

dependencies {
    // Shared bootstrap libs (in-repo modules, not Maven).
    implementation(libs.tatrman.ktor.configurator)
    implementation(libs.tatrman.logging.config)
    implementation(libs.tatrman.otel.config)
    implementation(libs.tatrman.db.common)
    // envelope/v1 + iris/v1 proto types (session DTOs map to iris/v1; envelope
    // snapshots persist as JSON — wired further in Stage 1.3).
    implementation(project(":shared:proto"))
    // capabilities-mcp read client — RoutingPickChip display-name labels (Stage 3.1).
    implementation(project(":shared:libs:kotlin:capabilities-client"))

    // Ktor server (Netty engine — robust for the SSE multiplex landing in 1.3).
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.typesafe.config)

    // Ktor client — transitional new-golem /v2 dispatch (GolemV2HttpClient, SSE consume).
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)

    // JsonFormat — IrisStreamEvent / FormatEnvelope proto ↔ JSON on the SSE wire.
    implementation(libs.protobuf.java.util)

    // Static-chip catalog loader (curated suggested topics; Stage 3.2 T3).
    implementation(libs.jackson.dataformat.yaml)

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
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
}
