plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

dependencies {
    api(project(":agents:hebe:modules:api"))
    implementation(project(":agents:hebe:modules:providers:openai-compat"))
    implementation(project(":agents:hebe:modules:config"))
    implementation(project(":agents:hebe:modules:observability"))
    implementation(project(":agents:hebe:modules:memory"))
    implementation(project(":agents:hebe:modules:tools:dispatch"))
    implementation(project(":agents:hebe:modules:security"))
    api(libs.koog.agents)
    api(libs.koog.utils)
    api(libs.koog.utils.common)
    testImplementation(libs.bundles.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    // P2 Stage 2.2 — drive the real gateway provider (OpenAiCompatProvider over a
    // GatewayClient) through the delegates to assert the WIRED cost/X-Turn-Ref path.
    testImplementation(libs.ktor.client.mock)
    testRuntimeOnly(libs.junit.platform.launcher)
}
