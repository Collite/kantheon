plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":agents:hebe:modules:api"))
    implementation(project(":agents:hebe:modules:memory"))
    implementation(project(":agents:hebe:modules:observability"))
    implementation(project(":agents:hebe:modules:config"))
    implementation(project(":agents:hebe:modules:tools:dispatch"))
    implementation(libs.bouncycastle)
    // P2 Stage 2.3 — Keycloak token endpoint calls (OBO mint) over the ktor client.
    implementation(libs.bundles.ktor.client)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.mockk)
    testImplementation(libs.bundles.testing)
    testImplementation(libs.kotest.framework.engine)
    testImplementation(libs.kotest.junit.platform.runner)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    testRuntimeOnly(libs.junit.platform.launcher)
}
