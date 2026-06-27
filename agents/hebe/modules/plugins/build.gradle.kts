plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":agents:hebe:modules:plugin-api"))
    implementation(project(":agents:hebe:modules:api"))
    implementation(project(":agents:hebe:modules:observability"))
    implementation(project(":agents:hebe:modules:config"))
    implementation(project(":agents:hebe:modules:security"))
    implementation(libs.pf4j)
    implementation(libs.tomlj)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.bouncycastle)
    testImplementation(libs.bundles.testing)
    testImplementation(libs.kotlinx.coroutines.test)
}
