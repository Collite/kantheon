plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

dependencies {
    api(project(":agents:hebe:modules:api"))
    implementation(project(":agents:hebe:modules:memory"))
    implementation(project(":agents:hebe:modules:observability"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.bundles.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    // P2 Stage 2.4 — Kotest runner for PostureSpec.
    testImplementation(libs.kotest.framework.engine)
    testRuntimeOnly(libs.kotest.junit.platform.runner)
    testRuntimeOnly(libs.junit.platform.launcher)
}
