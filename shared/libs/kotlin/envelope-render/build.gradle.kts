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
    // envelope/v1 + common/v1 Java protos — the rendering contract this lib emits.
    api(project(":shared:proto"))
    api(libs.kotlinx.serialization.json)
    implementation(libs.protobuf.java.util)
    implementation(libs.kotlinx.coroutines.core)

    // Koog umbrella — the structured-output adapter (FormatCatalog spike). The
    // deterministic core (tables/fallback/charts/chips) does NOT depend on Koog.
    implementation(libs.koog.agents)
    implementation(libs.kotlin.logging)

    testImplementation(libs.bundles.kotest)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.koog.agents.test)
    testImplementation(libs.mockk)
}
