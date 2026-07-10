plugins {
    base
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.jib)
}

application {
    mainClass.set("org.tatrman.pinakes.ApplicationKt")
}

jib {
    from {
        image = "eclipse-temurin:21-jre"
    }
    to {
        image = "pinakes:dev"
    }
    container {
        mainClass = "org.tatrman.pinakes.ApplicationKt"
        // P1 Stage 1.3: HTTP probes (7280) + gRPC PinakesService (7281).
        ports = listOf("7280", "7281")
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

    // Ktor server (HTTP probes) + client (the Kallimachos LoadApi write client).
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.opentelemetry)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.otel.logback.appender)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)

    // gRPC — PinakesService (NettyServerBuilder direct; charon precedent).
    implementation(libs.grpc.stub)
    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.kotlin.stub)
    implementation(libs.grpc.netty.shaded)
    implementation(libs.grpc.services)

    // S3 client — SeaweedAssetStore (data-seaweedfs:8333). Reuse the Seaweed infra
    // Charon uses, not the Charon service (architecture §2).
    implementation(libs.aws.sdk.s3)

    // HOCON
    implementation(libs.typesafe.config)

    // YAML pipeline definitions (per-source binding; contracts §11).
    implementation(libs.kaml)

    testImplementation(libs.bundles.kotest)
    testImplementation(libs.mockk)
    testImplementation(libs.grpc.inprocess)
    // Wiremock — the LLM gateway chat stub for the WikiCompiler spec (S3.2).
    testImplementation(libs.wiremock)
}
