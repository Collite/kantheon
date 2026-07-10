// Pythia — autonomous analytical investigator (kantheon arc). Phase 1:
// Ktor skeleton + Postgres state (Exposed + Flyway) + checkpointer + the typed
// event stream + REST control surface. The planner / DAG executor / evaluator /
// reviser / synthesizer land in Phases 2–3; the Charon/Metis data plane in P4.
//
// Persistence is Exposed + Flyway + Postgres (kantheon idiom — there is no jOOQ
// in-repo; the task lists say "jOOQ" but the convention to mirror is Exposed,
// per golem/iris-bff). Unit tests run the in-memory repository fakes
// (planning-conventions §4 — mocked unit tests only; real-PG = integration suite).
plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.jib)
}

application {
    mainClass.set("org.tatrman.kantheon.pythia.ApplicationKt")
}

tasks.test {
    useJUnitPlatform()
}

val osArch = System.getProperty("os.arch").lowercase()
val isArm64 = osArch.contains("aarch64") || osArch.contains("arm64")
val isCi = System.getenv("CI") != null
val imageRepo = (project.findProperty("imageRepo") as String?) ?: "pythia"
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
        mainClass = "org.tatrman.kantheon.pythia.ApplicationKt"
        ports = listOf("7090")
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
    // pythia/v1 + envelope/v1 + themis/v1 + common/v1 proto types.
    implementation(project(":shared:proto"))

    // Ktor server (Netty — the SSE event bridge lands in Stage 1.3).
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

    // JsonFormat — pythia/v1 proto ↔ JSON (persistence JSONB + SSE wire).
    implementation(libs.protobuf.java.util)

    // Phase 2 — resolution + planner + query edge.
    // LLM gateway client + Koog PromptExecutor (planner/synth). Brings Koog (api).
    implementation(libs.tatrman.ttr.llm.client)
    // capabilities-mcp read client — PlanValidator capability-existence checks.
    implementation(project(":shared:libs:kotlin:capabilities-client"))
    // HTTP (ThemisClient REST) + MCP streamable-HTTP (theseus-mcp query edge, Stage 2.3).
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.kotlin.mcp.sdk)

    // Phase 4 — data plane. gRPC-direct clients to Charon (services/charon),
    // the Polars worker (Steropes), and Metis (services/metis). The coroutine
    // stubs come transitively from :shared:proto; the netty transport is added
    // here. Live transports are integration-deferred (planning-conventions §4);
    // the unit gate runs in-process gRPC fixture-servers + fakes.
    implementation(libs.grpc.kotlin.stub)
    implementation(libs.grpc.netty.shaded)
    // Externalised prompt YAML/markdown loading.
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.dataformat.yaml)

    // Logging.
    api(libs.otel.logback.appender)
    implementation(libs.logback.classic)
    implementation(libs.logstash.logback.encoder)

    // OpenTelemetry + metrics.
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
    // SSE client plugin — to consume the event-bridge stream in component tests.
    testImplementation(libs.ktor.client.core)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.client.mock)
    // In-process gRPC server+channel for the Charon / Metis fixture-server specs.
    testImplementation(libs.grpc.inprocess)

    // Integration tier (WS-C2 T5) — drives the live `pythia-rca` context: Pythia's REST control
    // surface (/v1/investigations) over real HTTP against a real Pythia pod. The robust tier
    // exercises PD-8 admission (missing-bearer 403 + cross-user visibility 403) on the async
    // submit→get flow; the gated tier submits + polls to a terminal Status. The root build's
    // integrationTest suite already brings kotest + project(); these add the harness
    // (@RequiresContext/ContextHandle), a Ktor HTTP client, and proto-JSON (JsonFormat) marshalling
    // of Investigation/InvestigationArtifact. Gated by @RequiresContext: compiles + skips w/o context.
    "integrationTestImplementation"(project(":shared:libs:kotlin:integration-harness"))
    // WireMockAdmin — push scripted LLM stubs into the in-cluster WireMock for the gated tier.
    "integrationTestImplementation"(project(":shared:libs:kotlin:component-testkit"))
    "integrationTestImplementation"(project(":shared:proto"))
    "integrationTestImplementation"(libs.protobuf.java.util)
    "integrationTestImplementation"(libs.ktor.client.core)
    "integrationTestImplementation"(libs.ktor.client.cio)
    "integrationTestImplementation"(libs.kotlinx.coroutines.core)
    "integrationTestImplementation"(libs.kotlinx.serialization.json)
}
