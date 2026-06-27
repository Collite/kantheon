plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.jib)
}

dependencies {
    implementation(project(":shared:proto"))
    implementation(libs.protobuf.java.util)

    // Forked shared libs (Phase 1.3 — in-repo project deps).
    implementation(project(":shared:libs:kotlin:ktor-configurator"))
    implementation(project(":shared:libs:kotlin:otel-config"))
    implementation(project(":shared:libs:kotlin:logging-config"))

    // Ktor server
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)

    // Kotlin MCP SDK — surface lands in Stage 1.3.
    implementation(libs.kotlin.mcp.sdk)

    // Config + serialization
    implementation(libs.typesafe.config)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    // YAML loader — used by Stage 1.4 ManifestYamlLoader; pulled in early so module resolution
    // is stable.
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.dataformat.yaml)

    // Logging
    implementation(libs.logback.classic)
    implementation(libs.kotlin.logging)

    testImplementation(libs.bundles.kotest)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.mockk)
}

application {
    mainClass.set("org.tatrman.kantheon.capabilities.AppKt")
}

// Image target is parameterized so the same jib config serves both local dev and registry
// publish. Defaults reproduce the old behaviour:
//   local : ./gradlew :tools:capabilities-mcp:jibDockerBuild           -> capabilities-mcp:dev
//   GHCR  : ./gradlew :tools:capabilities-mcp:jib \
//             -PimageRepo=ghcr.io/boraperusic/capabilities-mcp -PimageTag=0.1.0
//           (auth via -Djib.to.auth.username=<gh-user> -Djib.to.auth.password=<ghcr-PAT>)
val imageRepo = (project.findProperty("imageRepo") as String?) ?: "capabilities-mcp"
val imageTag = (project.findProperty("imageTag") as String?) ?: "dev"

jib {
    from { image = "eclipse-temurin:21-jre" }
    to { image = "$imageRepo:$imageTag" }
    container {
        ports = listOf("7501")
        jvmFlags = listOf("-XX:+UseG1GC")
        creationTime.set("USE_CURRENT_TIMESTAMP")
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
