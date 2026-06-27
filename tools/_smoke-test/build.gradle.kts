plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(libs.typesafe.config)
    implementation(libs.logback.classic)
    implementation(libs.kotlin.logging)

    testImplementation(libs.bundles.kotest)
    testImplementation(libs.ktor.server.test.host)
}

application {
    mainClass.set("org.tatrman.kantheon.smoke.AppKt")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
