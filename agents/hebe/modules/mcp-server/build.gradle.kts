plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

dependencies {
    api(project(":agents:hebe:modules:api"))
    api(project(":agents:hebe:modules:config"))
    api(project(":agents:hebe:modules:tools:dispatch"))
    api(project(":agents:hebe:modules:memory"))
    implementation(project(":agents:hebe:modules:tools:builtin"))
    // Hebe arc P3 S3.4 — register the instance (non_routable) into capabilities-mcp.
    implementation(project(":shared:proto"))
    implementation(project(":shared:libs:kotlin:capabilities-client"))
    api(libs.mcp.kotlin.sdk)
    implementation(libs.kotlinx.io)
    implementation(libs.koog.utils)
    testImplementation(libs.bundles.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
