plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

dependencies {
    api(project(":agents:hebe:modules:api"))
    api(project(":agents:hebe:modules:config"))
    api(project(":agents:hebe:modules:memory"))
    api(project(":agents:hebe:modules:security"))
    api(project(":agents:hebe:modules:mcp-server"))
    implementation(libs.bundles.ktor.server)
    testImplementation(libs.bundles.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    testRuntimeOnly(libs.junit.platform.launcher)
}
