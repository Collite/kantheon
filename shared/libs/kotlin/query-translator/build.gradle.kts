plugins {
    base
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    `java-library`
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    // Calcite freezes its default literal charset (ISO-8859-1 by default) at class-load. The
    // library promotes it to UTF-8 via CalciteCharset.ensureUtf8() at its entry points, but
    // specs that touch Calcite directly (wire/joiner/optimizer) can load it first in arbitrary
    // order, so pin the charset for the test JVM to keep Unicode-literal coverage deterministic.
    systemProperty("calcite.default.charset", "UTF-8")
    systemProperty("calcite.default.nationalcharset", "UTF-8")
    systemProperty("calcite.default.collation.name", "UTF-8\$en_US")
}

dependencies {
    api(libs.calcite.core)
    api(project(":shared:proto"))
    // `erp-sql-common` was a listed dep in the ai-platform build but its package is not
    // referenced in any `.kt` source (verified via grep at fork time, 2026-06-13). Dropped
    // from the kantheon fork; revisit if a future contributor adds a `shared.erp.*` import.

    implementation(libs.slf4j.api)
    implementation(libs.protobuf.java.util)

    testImplementation(libs.bundles.kotest)
    testImplementation(libs.mockk)
}
