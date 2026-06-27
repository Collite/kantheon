plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

dependencies {
    api(project(":agents:hebe:modules:api"))
    implementation(project(":agents:hebe:modules:memory"))
    implementation(project(":agents:hebe:modules:core"))
    implementation(project(":agents:hebe:modules:config"))
    implementation(project(":agents:hebe:modules:tools:dispatch"))
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.bundles.testing)
    testImplementation(libs.kotest.junit.platform.runner)
    testRuntimeOnly(libs.junit.platform.launcher)
}
