plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

dependencies {
    implementation(project(":agents:hebe:modules:api"))
    implementation(project(":agents:hebe:modules:channels:channel-manager"))
    implementation(project(":agents:hebe:modules:core"))
    implementation(libs.bundles.ktor.server)
    testImplementation(libs.bundles.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.core)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.ktor.serialization.json)
    testRuntimeOnly(libs.junit.platform.launcher)
}
