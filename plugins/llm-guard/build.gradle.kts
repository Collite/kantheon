// The LLM-guard validator plugin (PL-P4.S2.T5) — kantheon shipping a C-5-i plugin on the OPEN
// org.tatrman:ttr-validator-spi (contracts §9). GREENFIELD against the SPI (not the ai-platform LlmGuard
// stage, which is coupled + off-limits). The host (the platform's svarog organ) provides the SPI at
// runtime, so it is `compileOnly` here (not bundled in the plugin jar). DEV: the SPI resolves from Maven
// Local at 0.0.1-LOCAL until Bora cuts the Central publish tag (see settings.gradle.kts interim note).
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
}

tasks.test {
    useJUnitPlatform()
}

dependencies {
    compileOnly(libs.tatrman.ttr.validator.spi)

    testImplementation(libs.bundles.kotest)
    testImplementation(libs.tatrman.ttr.validator.spi)
}
