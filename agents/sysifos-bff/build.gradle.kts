// Sysifos-BFF — dispatch BFF for the Sysifos back-office SPA (auth + tenant
// forwarding, session/draft/stream/dictionary surfaces, Midas-core client).
// Phase 1 Stage 1.1: Ktor skeleton that compiles + serves probes. Behaviour
// (auth, proxies, drafts, stream) lands in Stages 1.2–1.3.
//
// No DB: Sysifos never persists — every write proxies to Midas-core; drafts are
// in-memory (plan.md §5). So no Exposed/Flyway/Postgres here (unlike iris-bff).
plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.jib)
}

application {
    mainClass.set("org.tatrman.kantheon.sysifos.bff.ApplicationKt")
}

tasks.test {
    useJUnitPlatform()
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
        image = "sysifos-bff:dev"
    }
    container {
        mainClass = "org.tatrman.kantheon.sysifos.bff.ApplicationKt"
        ports = listOf("7601")
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
    // bff-base — Keycloak JWT verify + tenant forwarding + health routes (Stage 1.2).
    implementation(project(":shared:libs:kotlin:bff-base"))
    // envelope-render reuse (block rendering lands in Phase 2; dep wired now).
    implementation(project(":shared:libs:kotlin:envelope-render"))
    // proto types (sysifos/v1, midas/v1, envelope/v1) used throughout.
    implementation(project(":shared:proto"))

    // Ktor server (Netty — robust for the SSE stream landing in 1.2).
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

    // Ktor client — Midas-core proxy (MidasCoreClient, Stage 1.2).
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)

    // JsonFormat — sysifos/v1 proto ↔ JSON on the FE↔BFF + SSE wire.
    implementation(libs.protobuf.java.util)

    // Logging.
    api(libs.otel.logback.appender)
    implementation(libs.logback.classic)
    implementation(libs.logstash.logback.encoder)

    // OpenTelemetry.
    implementation(libs.ktor.opentelemetry)
    implementation(libs.micrometer.registry.prometheus)

    testImplementation(libs.bundles.kotest)
    testImplementation(libs.mockk)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.kotlinx.coroutines.test)
    // In-proc Midas-core mock for the CRUD-proxy + draft specs (EXAMPLES §9).
    testImplementation(libs.wiremock)
}
