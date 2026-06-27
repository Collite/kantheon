plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

dependencies {
    compileOnly(libs.detekt.api)
    testImplementation(libs.detekt.test)
    testImplementation(libs.kotest.framework.engine)
    testImplementation(libs.kotest.assertions)
    testRuntimeOnly(libs.kotest.junit.platform.runner)
}
