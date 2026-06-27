plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

dependencies {
    api(project(":agents:hebe:modules:api"))
    api(project(":agents:hebe:modules:config"))
    api(project(":agents:hebe:modules:tools:dispatch"))
    implementation(libs.mcp.kotlin.sdk)
    implementation(libs.kotlinx.io)
    testImplementation(libs.bundles.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.kotest.junit.platform.runner)
}
