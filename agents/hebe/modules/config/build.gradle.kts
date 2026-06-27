plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":agents:hebe:modules:api"))
    api(libs.tomlj)
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    implementation(libs.bouncycastle)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit.jupiter)
    // Kotest runner for the axis-model specs (P2 Stage 2.1) — mirrors the other
    // Hebe modules that mix Kotest specs with the JUnit-Jupiter suites.
    testImplementation(libs.kotest.framework.engine)
    testImplementation(libs.kotest.assertions)
    testRuntimeOnly(libs.kotest.junit.platform.runner)
}
