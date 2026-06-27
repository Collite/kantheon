// bff-base — the BFF-agnostic foundation shared across kantheon dispatch BFFs
// (Sysifos arc, Phase 1 Stage 1.2). Keycloak JWT verification (decode + RS256/
// JWKS modes), tenant-header forwarding, and the liveness/readiness routes.
//
// Extracted for sysifos-bff; iris-bff carries an equivalent inline copy and
// migrates onto this lib in a follow-up (deferred to avoid disturbing the active
// Iris Stream-A frontier — see the Stage 1.2 audit note in the task list).
//
// Deliberately proto-free and BFF-shape-free: it deals in `CallerIdentity`
// (a plain data class) and generic routes, never in any one BFF's session/draft
// protos. Consumers wire it with their own config keys + logger names.
plugins {
    base
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    `java-library`
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

dependencies {
    // Ktor server primitives (Route, ApplicationCall, respond) for the health
    // routes + `requireCaller`. The JWKS fetch uses the JDK HttpClient (no extra dep).
    api(libs.ktor.server.core)
    api(libs.kotlinx.serialization.json)
    implementation(libs.slf4j.api)

    testImplementation(libs.bundles.kotest)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.wiremock)
}
