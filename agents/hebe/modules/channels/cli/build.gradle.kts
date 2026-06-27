plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

dependencies {
    implementation(project(":agents:hebe:modules:api"))
    implementation(project(":agents:hebe:modules:channels:channel-manager"))
    implementation(project(":agents:hebe:modules:core"))
    implementation(libs.jline)
    testImplementation(libs.bundles.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
