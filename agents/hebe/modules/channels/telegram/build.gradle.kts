plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

dependencies {
    implementation(project(":agents:hebe:modules:api"))
    implementation(project(":agents:hebe:modules:channels:channel-manager"))
    implementation(project(":agents:hebe:modules:core"))
    implementation(libs.telegrambots.longpolling)
    implementation(libs.telegrambots.client.jetty)
    testImplementation(libs.bundles.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.server.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}
